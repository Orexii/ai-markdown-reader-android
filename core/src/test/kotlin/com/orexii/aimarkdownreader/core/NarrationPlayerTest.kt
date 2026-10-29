package com.orexii.aimarkdownreader.core

import com.orexii.aimarkdownreader.core.audio.Stretch
import com.orexii.aimarkdownreader.core.espeak.EspeakPhonemizer
import com.orexii.aimarkdownreader.core.player.NarrationPlayer
import com.orexii.aimarkdownreader.core.player.PcmSink
import com.orexii.aimarkdownreader.core.player.StartAt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A device that plays in real time with a small buffer, like AudioTrack: the heard position never passes what
 * was written, and playback continues from there when more arrives (an underrun).
 */
class SimulatedSink(private val bufferSeconds: Double = 0.25) : PcmSink {
    private var rate = 0
    private var written = 0L
    private var heard = 0.0
    private var playing = false
    private var last = System.nanoTime()
    private var starved = false
    @Volatile var underrunCount = 0
    val received = ArrayList<Short>()

    @Synchronized private fun update() {
        val now = System.nanoTime()
        if (playing && rate > 0) {
            heard = minOf(written.toDouble(), heard + (now - last) / 1e9 * rate)
            if (heard >= written && written > 0) starved = true
        }
        last = now
    }

    @Synchronized override fun open(sampleRate: Int) { rate = sampleRate; flush() }

    @Synchronized override fun write(pcm: ShortArray, offset: Int, length: Int): Int {
        update()
        val free = (bufferSeconds * rate).toLong() - (written - heard.toLong())
        val n = minOf(length.toLong(), maxOf(0L, free)).toInt()
        if (n > 0 && starved && playing) {
            underrunCount++
            starved = false
        }
        for (i in offset until offset + n) received.add(pcm[i])
        written += n
        return n
    }

    @Synchronized override fun position(): Long { update(); return heard.toLong() }
    @Synchronized override fun play() { update(); playing = true }
    @Synchronized override fun pause() { update(); playing = false }
    @Synchronized override fun flush() { written = 0; heard = 0.0; starved = false; last = System.nanoTime() }
    override fun close() {}
    override fun underruns() = underrunCount
}

/** Speech stand-in: a tone per word, about 16 characters a second, made quickly. */
class ToneEngine(private val delayMs: Long = 3) : SpeechEngine {
    override val kind = EngineKind.NATURAL
    override val batchChars = 170
    val requests = java.util.concurrent.atomic.AtomicInteger()

    override fun synthesize(utterances: List<SpeechInput>, voice: String, dictionary: Map<String, String>): List<AudioSegment> {
        requests.incrementAndGet()
        Thread.sleep(delayMs)
        return utterances.map { u ->
            val speech = u.words.sumOf { it.text.length + it.sep.length } * 0.06
            val frames = ((speech + u.pause) * 24000).toInt()
            val pcm = ShortArray(frames) { i -> if (i < speech * 24000) (sin(2 * PI * 220 * i / 24000) * 8000).toInt().toShort() else 0 }
            val times = DoubleArray(u.words.size * 2)
            var at = 0.0
            u.words.forEachIndexed { k, word ->
                val length = (word.text.length + word.sep.length) * 0.06
                times[2 * k] = at
                times[2 * k + 1] = at + length
                at += length
            }
            AudioSegment(pcm, 24000, times)
        }
    }

    override fun close() {}
}

class StretchTest {
    @Test
    fun stretchingChangesLengthNotPitch() {
        val pcm = ShortArray(48000) { i -> (sin(2 * PI * 200 * i / 24000) * 10000).toInt().toShort() }
        fun crossings(samples: ShortArray) = (1 until samples.size).count { samples[it - 1] < 0 && samples[it] >= 0 }
        for (speed in listOf(0.5, 2.0, 3.0, 5.0)) {
            val out = Stretch.pcm16(pcm, 0, 24000, speed)
            assertEquals("length at $speed×", 48000 / speed, out.size.toDouble(), 48000 / speed * 0.03)
            val pitch = crossings(out) / (out.size / 24000.0)
            assertEquals("pitch at $speed×", 200.0, pitch, 12.0)
        }
        assertEquals(24000, Stretch.pcm16(pcm, 24000, 24000, 1.0).size)
    }
}

class NarrationPlayerTest {
    private val doc = Goldens.documents.getValue("wagyu-rows-announce")
    private val sink = SimulatedSink()
    private val player = NarrationPlayer(sink)

    @After
    fun release() = player.release()

    private fun waitFor(timeoutMs: Long = 10_000, check: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!check()) {
            check(System.currentTimeMillis() - started < timeoutMs) { "Timed out; state ${player.state.value}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun playsGaplesslyAtThreeTimesAndFollowsTheWords() {
        player.setEngine(ToneEngine(), "af_heart", emptyMap())
        player.setRate(3.0)
        player.load(doc.utterances, doc.blocks, "wagyu.md", false, StartAt(autoplay = true))
        val words = mutableSetOf<Pair<Int, Int>>()
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 4000) {
            val state = player.state.value
            if (state.word >= 0) words += state.index to state.word
            Thread.sleep(15)
        }
        val state = player.state.value
        assertTrue("about 12 s of text read in 4 s at 3×: ${state.elapsed}", state.elapsed > 9)
        assertTrue("words were followed: ${words.size}", words.size > 15)
        assertEquals("no stalls", 0, state.stalls)
        assertEquals("no underruns after the start", 0, sink.underrunCount)
        assertTrue(state.playing && !state.waiting)
        assertEquals(3.0, state.rate, 0.0)
    }

    @Test
    fun pauseKeepsThePlaceAndPlayContinuesFromIt() {
        player.setEngine(ToneEngine(), "af_heart", emptyMap())
        player.load(doc.utterances, doc.blocks, "wagyu.md", false, StartAt(autoplay = true))
        waitFor { player.state.value.elapsed > 1.5 }
        player.pause()
        val paused = player.await { player.state.value }
        Thread.sleep(400)
        val later = player.state.value
        assertEquals(paused.index, later.index)
        assertEquals(paused.offset, later.offset, 0.05)
        assertTrue(!later.playing)
        player.play()
        waitFor { player.state.value.elapsed > later.elapsed + 0.3 }
        assertTrue("resumed from the same place", player.state.value.elapsed < later.elapsed + 1.5)
    }

    @Test
    fun navigationMovesByParagraphSentenceAndSection() {
        player.setEngine(ToneEngine(), "af_heart", emptyMap())
        player.load(doc.utterances, doc.blocks, "wagyu.md", false, StartAt())
        player.nextParagraph()
        val first = player.await { player.state.value }
        assertTrue(doc.utterances[first.index].block != doc.utterances[0].block)
        player.nextSentence()
        assertEquals(first.index + 1, player.await { player.state.value }.index)
        player.nextSection()
        val section = player.await { player.state.value }
        assertEquals("heading", doc.blocks[doc.utterances[section.index].block].kind)
        player.seek(doc.utterances.size - 1)
        player.nextParagraph()
        val end = player.await { player.state.value }
        assertEquals("This is the last paragraph", end.notice)
        player.seekWord(5, 2)
        assertEquals(5, player.await { player.state.value }.index)
    }

    @Test
    fun skipsBySecondsAndChangesSpeedWhilePlaying() {
        player.setEngine(ToneEngine(), "af_heart", emptyMap())
        player.load(doc.utterances, doc.blocks, "wagyu.md", false, StartAt(index = 10, autoplay = true))
        waitFor { player.state.value.playing && player.state.value.word >= 0 }
        val before = player.state.value.elapsed
        player.skip(30.0)
        waitFor { player.state.value.elapsed > before + 25 }
        player.skip(-10.0)
        waitFor { abs(player.state.value.elapsed - (before + 20)) < 3 }
        player.setRate(2.0)
        waitFor { player.state.value.rate == 2.0 && player.state.value.playing }
        val at = player.state.value.elapsed
        Thread.sleep(1000)
        val moved = player.state.value.elapsed - at
        assertEquals("two seconds of text per second at 2×", 2.0, moved, 0.5)
    }

    @Test
    fun finishesAtTheEnd() {
        val short = Goldens.documents.getValue("scheduler-short")
        player.setEngine(ToneEngine(), "af_heart", emptyMap())
        player.setRate(5.0)
        player.load(short.utterances, short.blocks, "short.md", false, StartAt(autoplay = true))
        waitFor { player.state.value.finished }
        val state = player.state.value
        assertTrue(!state.playing)
        assertEquals(short.utterances.size - 1, state.index)
        player.play()
        waitFor { player.state.value.playing }
        assertEquals("play after the end starts again", 0, player.state.value.index)
    }

    @Test
    fun theRealModelKeepsUpAtThreeTimes() {
        val directory = File(System.getProperty("amr.modelDir") ?: ".model")
        val model = listOf("model_quantized.onnx", "model.onnx").map { File(directory, "onnx/$it") }.firstOrNull { it.isFile }
        assumeTrue("Run node tools/fetch-model.mjs .model compact to test the real model.", model != null && Goldens.espeakLibrary != null)
        val style = parseVoice(File(directory, "voices/af_heart.bin").readBytes())
        val inference = OrtInference(model!!.absolutePath, 4)
        val engine = NaturalEngine(Kokoro(Goldens::lexicon, EspeakPhonemizer(Goldens.espeakData.absolutePath, Goldens.espeakLibrary!!.absolutePath), inference) { style }) { inference.close() }
        player.setEngine(engine, "af_heart", emptyMap())
        player.setRate(3.0)
        player.load(doc.utterances, doc.blocks, "wagyu.md", false, StartAt(autoplay = true))
        waitFor(30_000) { player.state.value.playing && !player.state.value.waiting && player.state.value.word >= 0 }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 20_000) Thread.sleep(50)
        val state = player.state.value
        println("Real model at 3×: passage ${state.index} of ${state.count}, ${"%.0f".format(state.elapsed)} s read, stalls ${state.stalls}, underruns ${sink.underrunCount}")
        assertTrue("at most one stall at 3× (the extension's E2E bar)", state.stalls <= 1)
        assertTrue(state.elapsed > 40)
        player.pause()
        engine.close()
    }
}
