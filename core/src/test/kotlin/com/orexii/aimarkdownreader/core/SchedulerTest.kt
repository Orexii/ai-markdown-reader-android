package com.orexii.aimarkdownreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ports of the extension's test/scheduler.test.ts. */
class SchedulerTest {
    private class FakeEngine(override val batchChars: Int = 170, private val hold: Boolean = false) : SpeechEngine {
        override val kind = EngineKind.NATURAL
        val calls: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf())
        @Volatile var failNext = false
        val release = CountDownLatch(1)

        override fun synthesize(utterances: List<SpeechInput>, voice: String, dictionary: Map<String, String>): List<AudioSegment> {
            calls += utterances.map { it.words.size }
            if (hold) release.await(5, TimeUnit.SECONDS)
            Thread.sleep(1)
            if (failNext) {
                failNext = false
                throw IllegalStateException("boom")
            }
            return utterances.map { AudioSegment(ShortArray(24000), 24000, DoubleArray(0)) }
        }

        override fun close() {}
    }

    private val short = Goldens.documents.getValue("scheduler-short").utterances
    private val long = Goldens.documents.getValue("scheduler-long").utterances

    private class Setup(engine: SpeechEngine, utterances: List<Utterance>, log: (String) -> Unit = {}) {
        val delivered: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val failed: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())
        val scheduler = Scheduler(engine, utterances, "af_heart", emptyMap(), { index, _ -> delivered += index }, { index, error -> failed += index to error.message!! }, log)
    }

    private fun until(timeout: Long = 5000, check: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!check()) {
            check(System.currentTimeMillis() - started < timeout) { "Timed out waiting for the scheduler." }
            Thread.sleep(5)
        }
    }

    @Test
    fun urgentPassageFirstCombiningAShortHeadingWithWhatFollows() {
        val engine = FakeEngine()
        val setup = Setup(engine, short)
        setup.scheduler.need(listOf(0, 1, 2, 3, 4))
        until { setup.delivered.size == 5 }
        assertEquals(listOf(0, 1, 2, 3, 4), setup.delivered.toList())
        assertEquals("short heading merged with the next passage", 2, engine.calls[0].size)
        assertTrue(short[0].spokenLength() + short[1].spokenLength() < 250)
        assertTrue("later passages are batched", engine.calls.size < 5)
        setup.scheduler.dispose()
    }

    @Test
    fun cachedAudioIsDeliveredAgainAndInFlightWorkIsNotDuplicated() {
        val engine = FakeEngine(170, hold = true)
        val setup = Setup(engine, short)
        setup.scheduler.need(listOf(3))
        setup.scheduler.need(listOf(3))
        Thread.sleep(20)
        assertEquals(1, engine.calls.size)
        engine.release.countDown()
        until { setup.delivered.size == 1 }
        assertEquals(listOf(3), setup.delivered.toList())
        setup.scheduler.need(listOf(3))
        assertEquals("cached audio is delivered at once", listOf(3, 3), setup.delivered.toList())
        assertEquals(1, engine.calls.size)
        setup.scheduler.dispose()
    }

    @Test
    fun aJumpReprioritizesAndFailuresDoNotStopTheQueue() {
        val engine = FakeEngine(0, hold = true)
        val setup = Setup(engine, short)
        engine.failNext = true
        setup.scheduler.need(listOf(0, 1, 2))
        // In TypeScript the first passage starts inside need(); here it starts on the worker thread.
        until { engine.calls.isNotEmpty() }
        setup.scheduler.need(listOf(4, 3))
        engine.release.countDown()
        until { setup.failed.size == 1 && setup.delivered.size == 2 }
        assertEquals(listOf(0 to "boom"), setup.failed.toList())
        assertEquals(listOf(4, 3), setup.delivered.toList())
        assertTrue("engines without batching get one passage per request", engine.calls.all { it.size == 1 })
        setup.scheduler.dispose()
    }

    @Test
    fun disposingStopsDelivery() {
        val engine = FakeEngine(170, hold = true)
        val setup = Setup(engine, short)
        setup.scheduler.need(listOf(1, 2))
        Thread.sleep(20)
        setup.scheduler.dispose()
        engine.release.countDown()
        Thread.sleep(50)
        assertEquals(emptyList<Int>(), setup.delivered.toList())
    }

    @Test
    fun batchesStaySmallWhileLittleIsBufferedAndGrowOnceAhead() {
        fun run(ahead: Double): List<List<Int>> {
            val engine = FakeEngine()
            val setup = Setup(engine, long)
            setup.scheduler.need((0 until 4).toList(), ahead, 2.0)
            until { setup.delivered.size == 4 }
            setup.scheduler.dispose()
            return engine.calls.toList()
        }
        val chars = long[0].spokenLength()
        assertTrue("each sentence is long enough to stand alone", chars > 40 && chars * 2 > 90)
        assertEquals("nothing buffered: one sentence at a time", listOf(1, 1, 1, 1), run(0.0).map { it.size })
        assertTrue("a minute buffered: full batches for better prosody", run(60.0)[0].size > 1)
    }
}

class NavigationTest {
    private val doc = Goldens.documents.getValue("wagyu-rows-announce")
    private val utterances = doc.utterances
    private val blocks = doc.blocks

    @Test
    fun nextParagraphMovesThroughATableOneRowAtATime() {
        val tableBlock = blocks.indexOfFirst { it.kind == "table" }
        val intro = blocks[tableBlock].first
        val firstRow = (Navigation.nextParagraph(utterances, intro) as Navigation.Move.To).index
        assertEquals("from the introduction to the first row", tableBlock, utterances[firstRow].block)
        assertEquals(1, utterances[firstRow].part)
        val secondRow = (Navigation.nextParagraph(utterances, firstRow) as Navigation.Move.To).index
        assertEquals(2, utterances[secondRow].part)
        assertEquals(Navigation.Move.To(firstRow), Navigation.previousParagraph(utterances, secondRow, 0.0))
        assertEquals(Navigation.Move.End("This is the last paragraph"), Navigation.nextParagraph(utterances, utterances.size - 1))
    }

    @Test
    fun aLongTableRowReadInPartsIsOneStop() {
        // The extension's test/document.test.ts: a paragraph, the table introduction, each row, the next paragraph.
        val doc = Goldens.documents.getValue("table-stops").utterances
        assertEquals(listOf("0:0", "1:0", "1:1", "1:2", "2:0"), doc.map { "${it.block}:${it.part}" }.distinct())
        val longRow = doc.indices.filter { doc[it].block == 1 && doc[it].part == 1 }
        assertTrue("the long row is read in parts", longRow.size > 1)
        val next = (Navigation.nextParagraph(doc, longRow.first()) as Navigation.Move.To).index
        assertEquals("→ skips the rest of the long row", 2, doc[next].part)
        assertEquals("← from inside the row goes to its start", Navigation.Move.To(longRow.first()), Navigation.previousParagraph(doc, longRow.last(), 0.0))
    }

    @Test
    fun previousParagraphGoesToTheStartOfThisOneFirst() {
        val multi = utterances.indices.first { it > 0 && utterances[it].block == utterances[it - 1].block }
        val start = (multi downTo 0).last { utterances[it].block == utterances[multi].block }
        assertEquals(Navigation.Move.To(start), Navigation.previousParagraph(utterances, multi, 0.0))
        // At the very start of a paragraph, previous goes to the start of the one before.
        val previousStart = (start - 1 downTo 0).last { utterances[it].block == utterances[start - 1].block }
        assertEquals(Navigation.Move.To(previousStart), Navigation.previousParagraph(utterances, start, 0.0))
        // Two seconds in, it restarts the paragraph instead.
        assertEquals(Navigation.Move.To(start), Navigation.previousParagraph(utterances, start, 2.0))
    }

    @Test
    fun sentencesAndSections() {
        assertEquals(Navigation.Move.To(5), Navigation.previousSentence(5, 2.0))
        assertEquals(Navigation.Move.To(4), Navigation.previousSentence(5, 0.5))
        assertEquals(Navigation.Move.End("This is the last sentence"), Navigation.nextSentence(utterances, utterances.size - 1))
        val headings = blocks.filter { it.kind == "heading" && it.first >= 0 }.map { it.first }
        assertEquals(Navigation.Move.To(headings[1]), Navigation.nextSection(blocks, headings[0]))
        assertEquals(Navigation.Move.To(headings[1]), Navigation.previousSection(blocks, headings[1] + 1, 0.0))
        assertEquals(Navigation.Move.To(headings[0]), Navigation.previousSection(blocks, headings[1], 0.2))
        assertEquals(blocks.first { it.first == headings[1] }.heading!!.text, Navigation.section(blocks, headings[1] + 1))
        assertTrue(Navigation.chapters(blocks).all { it.heading!!.level <= 3 })
    }

    @Test
    fun locateFindsThePassageAndWordAtASourcePosition() {
        val target = utterances.indexOfFirst { u -> u.range != null && u.words.size > 4 && u.words[3].range != null }
        val word = utterances[target].words[3].range!!
        assertEquals(target to 3, Navigation.locate(utterances, word.line, word.character))
        val start = utterances[target].range!!
        assertEquals(target to -1, Navigation.locate(utterances, start.line, 0).let { (i, w) -> i to (if (w <= 0) -1 else w) })
    }

    @Test
    fun timelineEstimatesThenLearns() {
        val timeline = Timeline(utterances)
        val estimate = timeline.total
        assertTrue(estimate > 60)
        assertTrue(timeline.estimatedFrom(0))
        timeline.learn(0, 2.0)
        assertEquals(2.0, timeline.duration(0), 0.0)
        assertEquals(2.0, timeline.start(1), 0.0)
        assertEquals(0, timeline.locate(1.9))
        assertEquals(1, timeline.locate(2.0))
        assertEquals(utterances.size - 1, timeline.locate(1e9))
    }

    @Test
    fun speedsMatchTheExtension() {
        assertEquals(20, Speeds.ALL.size)
        assertEquals(0.25, Speeds.ALL.first(), 0.0)
        assertEquals(5.0, Speeds.ALL.last(), 0.0)
        assertEquals(listOf(0.5, 1.0, 1.5, 2.0), Speeds.choices(false, 1.0))
        assertEquals(listOf(0.5, 1.0, 1.25, 1.5, 2.0), Speeds.choices(false, 1.25))
        assertEquals(1.5, Speeds.faster(1.0, false), 0.0)
        assertEquals(1.25, Speeds.faster(1.0, true), 0.0)
        assertEquals(0.5, Speeds.slower(1.0, false), 0.0)
        assertEquals("1×", Speeds.format(1.0))
        assertEquals("1.25×", Speeds.format(1.25))
        assertEquals("0.5×", Speeds.format(0.5))
        assertEquals(1.0, Speeds.nearest(Double.NaN), 0.0)
        assertEquals(5.0, Speeds.nearest(9.0), 0.0)
        assertEquals(1.25, Speeds.nearest(1.3), 0.0)
    }

    @Test
    fun wordAtFollowsTheWordTimes() {
        val segment = AudioSegment(ShortArray(24000), 24000, doubleArrayOf(0.0, 0.2, -1.0, -1.0, 0.25, 0.5, 0.6, 0.9))
        assertEquals(0, segment.wordAt(0.0))
        assertEquals(2, segment.wordAt(0.23))
        assertEquals(3, segment.wordAt(0.7))
    }
}
