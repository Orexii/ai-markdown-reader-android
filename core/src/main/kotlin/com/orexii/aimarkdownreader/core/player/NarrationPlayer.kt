package com.orexii.aimarkdownreader.core.player

import com.orexii.aimarkdownreader.core.AudioSegment
import com.orexii.aimarkdownreader.core.Navigation
import com.orexii.aimarkdownreader.core.ReaderBlock
import com.orexii.aimarkdownreader.core.Scheduler
import com.orexii.aimarkdownreader.core.SpeechEngine
import com.orexii.aimarkdownreader.core.Speeds
import com.orexii.aimarkdownreader.core.Timeline
import com.orexii.aimarkdownreader.core.Utterance
import com.orexii.aimarkdownreader.core.audio.Stretch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** What the player is doing, for the reader page, the controls and the media session. */
data class PlayerState(
    val revision: Int = 0,
    val loaded: Boolean = false,
    val title: String = "",
    val selection: Boolean = false,
    val count: Int = 0,
    val index: Int = 0,
    /** The word being spoken in passage [index], or -1. */
    val word: Int = -1,
    /** Seconds into passage [index] (at 1×). */
    val offset: Double = 0.0,
    val playing: Boolean = false,
    /** Playing, but the next audio is not ready yet. */
    val waiting: Boolean = false,
    val finished: Boolean = false,
    /** Seconds at 1× from the start of the document, and in total (estimated until synthesized). */
    val elapsed: Double = 0.0,
    val total: Double = 0.0,
    val estimated: Boolean = true,
    val rate: Double = 1.0,
    val section: String = "",
    /** Start of each chapter (headings 1 to 3) in seconds, with its title. */
    val chapters: List<Pair<Double, String>> = emptyList(),
    val engineReady: Boolean = false,
    /** A short message such as "This is the last paragraph"; [noticeId] changes each time one is shown. */
    val notice: String? = null,
    val noticeId: Int = 0,
    val error: String? = null,
    /** Times playback ran out of audio while playing (stalls), since the document was loaded. */
    val stalls: Int = 0,
)

/** Where to start reading a document. */
data class StartAt(val index: Int = 0, val offset: Double = 0.0, val word: Int = -1, val autoplay: Boolean = false)

/**
 * Plays a narration: asks the scheduler for audio ahead of the listener, stretches each passage to the chosen
 * speed and writes the passages back to back into one continuous stream, so there is never a gap between them.
 * Follows the stream to report the passage and word being heard. Everything runs on one audio thread; the
 * public methods only queue commands. Mirrors the extension's media/player.js.
 */
class NarrationPlayer(
    private val sink: PcmSink,
    private val log: (String) -> Unit = {},
) {
    private val commands = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var running = true
    private val stateFlow = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = stateFlow.asStateFlow()

    // Document and engine.
    private var revision = 0
    private var title = ""
    private var selection = false
    private var utterances: List<Utterance> = emptyList()
    private var blocks: List<ReaderBlock> = emptyList()
    private var timeline = Timeline(emptyList())
    private var chapters: List<Pair<Double, String>> = emptyList()
    private var engine: SpeechEngine? = null
    private var voice = ""
    private var dictionary: Map<String, String> = emptyMap()
    private var scheduler: Scheduler? = null
    private val segments = HashMap<Int, AudioSegment>()
    private var segmentBytes = 0L
    private var lastNeed = ""

    // Listening position.
    private var index = 0
    private var offset = 0.0
    private var word = -1
    private var pendingWord: Pair<Int, Int>? = null
    private var playing = false
    private var waiting = false
    private var finished = false
    private var rate = 1.0
    private var notice: String? = null
    private var noticeId = 0
    private var error: String? = null
    private var stalls = 0

    // Output stream: what has been written to the sink since the last flush.
    private class Mark(val index: Int, val sinkStart: Long, val frames: Int, val inputStart: Int, val speed: Double)
    private class Output(val index: Int, val pcm: ShortArray, var position: Int)
    private val marks = ArrayDeque<Mark>()
    private var written = 0L
    private var nextToWrite = 0
    private var startOffset = 0.0
    private var current: Output? = null
    private var endWritten = false
    private var sinkRate = 0
    private var sinkPlaying = false

    private val thread = Thread(::loop, "narration-audio").apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true
        start()
    }

    // Commands, callable from any thread.

    /** Loads a narration. Keeps the engine; starts at [start]. */
    fun load(utterances: List<Utterance>, blocks: List<ReaderBlock>, title: String, selection: Boolean, start: StartAt) = post {
        stopOutput()
        revision++
        this.utterances = utterances
        this.blocks = blocks
        this.title = title
        this.selection = selection
        timeline = Timeline(utterances)
        chapters = emptyList()
        resetAudio()
        index = start.index.coerceIn(0, max(0, utterances.size - 1))
        offset = max(0.0, start.offset)
        word = -1
        pendingWord = if (start.word >= 0) index to start.word else null
        finished = false
        waiting = false
        error = null
        stalls = 0
        playing = start.autoplay && utterances.isNotEmpty()
        renewScheduler()
        rebuildChapters()
        if (playing && engine != null) startOutput() else requestNeeded()
    }

    /** Unloads the document and stops. */
    fun unload() = post {
        stopOutput()
        revision++
        utterances = emptyList()
        blocks = emptyList()
        timeline = Timeline(emptyList())
        chapters = emptyList()
        resetAudio()
        scheduler?.dispose()
        scheduler = null
        index = 0
        offset = 0.0
        word = -1
        playing = false
        waiting = false
        finished = false
        error = null
    }

    /** Uses a speech engine (null while none is ready). Keeps the place but none of the old audio. */
    fun setEngine(engine: SpeechEngine?, voice: String, dictionary: Map<String, String>) = post {
        val resume = playing
        if (playing) offset = currentOffset()
        stopOutput()
        this.engine = engine
        this.voice = voice
        this.dictionary = dictionary
        resetAudio()
        timeline.reset()
        rebuildChapters()
        renewScheduler()
        playing = resume && engine != null
        if (playing) startOutput() else requestNeeded()
    }

    fun play() = post { playNow() }

    fun pause() = post { pauseNow() }

    fun togglePlay() = post { if (playing) pauseNow() else playNow() }

    private fun pauseNow() {
        if (playing) offset = currentOffset()
        playing = false
        waiting = false
        // Keep what was written so resuming continues seamlessly.
        if (sinkPlaying) {
            sink.pause()
            sinkPlaying = false
        }
    }

    private fun playNow() {
        if (utterances.isEmpty()) return
        error = null
        if (finished) return go(0, 0.0, true)
        if (playing) return
        playing = true
        if (engine == null) return
        if (resumeSink()) return
        startOutput()
    }

    /** Moves to passage [i], [seconds] into it. */
    fun seek(i: Int, seconds: Double = 0.0, play: Boolean? = null) = post { go(i, seconds, play ?: playing) }

    /** Moves to word [k] of passage [i] (or the start of the passage for -1). */
    fun seekWord(i: Int, k: Int, play: Boolean? = null) = post { goWord(i, k, play ?: playing) }

    /** Moves to [seconds] from the start of the document (at 1×). */
    fun seekTo(seconds: Double) = post { seekToNow(seconds) }

    fun skip(seconds: Double) = post { seekToNow(elapsedNow() + seconds) }

    fun previousSentence() = post { move(Navigation.previousSentence(index, currentOffset())) }
    fun nextSentence() = post { move(Navigation.nextSentence(utterances, index)) }
    fun previousParagraph() = post { move(Navigation.previousParagraph(utterances, index, currentOffset())) }
    fun nextParagraph() = post { move(Navigation.nextParagraph(utterances, index)) }
    fun previousSection() = post { move(Navigation.previousSection(blocks, index, currentOffset())) }
    fun nextSection() = post { move(Navigation.nextSection(blocks, index)) }
    fun top() = post { go(0, 0.0, true) }

    /** Sets the speed (quarter steps from 0.25× to 5×); the pitch stays the same. */
    fun setRate(value: Double) = post {
        val next = Speeds.nearest(value)
        if (next == rate) return@post
        val resume = playing
        val at = currentOffset()
        rate = next
        if (resume) {
            go(index, at, true)
        } else {
            // What is waiting in the paused stream was made for the old speed.
            offset = at
            stopOutput()
            requestNeeded()
        }
    }

    fun release() {
        post {
            stopOutput()
            scheduler?.dispose()
            scheduler = null
            sink.close()
            running = false
        }
        thread.join(2000)
    }

    /** Runs [block] on the audio thread after the commands queued before it (for tests and snapshots). */
    fun <T> await(timeoutMs: Long = 5000, block: () -> T): T {
        val result = LinkedBlockingQueue<Result<T>>()
        post {
            publish()
            result.add(runCatching(block))
        }
        return result.poll(timeoutMs, TimeUnit.MILLISECONDS)?.getOrThrow() ?: error("The audio thread did not answer.")
    }

    private fun post(command: () -> Unit) {
        if (running) commands.add(command)
    }

    // Audio thread.

    private fun loop() {
        while (running) {
            val wait = if (playing && !waiting) 8L else 100L
            val command = try {
                commands.poll(wait, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            try {
                command?.invoke()
                while (true) (commands.poll() ?: break).invoke()
                if (running && playing && engine != null) pump()
                publish()
            } catch (failure: Throwable) {
                log("Player error: ${failure.message}")
                error = "Something went wrong while reading. Press Play to try again."
                pauseNow()
                publish()
            }
        }
    }

    private fun move(target: Navigation.Move) {
        when (target) {
            is Navigation.Move.To -> go(target.index, 0.0, playing)
            is Navigation.Move.End -> flash(target.message)
        }
    }

    private fun flash(text: String) {
        notice = text
        noticeId++
    }

    private fun go(i: Int, seconds: Double, play: Boolean) {
        if (utterances.isEmpty()) return
        stopOutput()
        index = i.coerceIn(0, utterances.size - 1)
        offset = max(0.0, seconds)
        word = -1
        if (pendingWord?.first != index) pendingWord = null
        finished = false
        waiting = false
        error = null
        playing = play
        if (playing && engine != null) startOutput() else {
            nextToWrite = index
            startOffset = offset
            requestNeeded()
        }
    }

    private fun goWord(i: Int, k: Int, play: Boolean) {
        val segment = segments[i]
        val start = if (segment != null && k >= 0 && 2 * k < segment.wordTimes.size) segment.wordTimes[2 * k] else -1.0
        val target = i.coerceIn(0, max(0, utterances.size - 1))
        go(target, if (start > 0) max(0.0, start - 0.05) else 0.0, play)
        pendingWord = if (k >= 0 && segment == null) target to k else null
    }

    private fun seekToNow(seconds: Double) {
        if (utterances.isEmpty()) return
        val target = seconds.coerceIn(0.0, max(0.0, timeline.total - 0.05))
        val i = timeline.locate(target)
        go(i, target - timeline.start(i), playing)
    }

    private fun elapsedNow(): Double = timeline.start(index) + currentOffset()

    /** Seconds into the passage being heard. */
    private fun currentOffset(): Double {
        if (!playing || marks.isEmpty()) return offset
        val heard = locateHeard() ?: return offset
        return if (heard.first == index) heard.second else offset
    }

    /** The passage and seconds into it that the listener hears now, from the marks of the written stream. */
    private fun locateHeard(): Pair<Int, Double>? {
        if (marks.isEmpty() || sinkRate == 0) return null
        val position = sink.position()
        var mark = marks.first()
        for (candidate in marks) {
            if (candidate.sinkStart > position) break
            mark = candidate
        }
        val within = (position - mark.sinkStart).coerceIn(0L, mark.frames.toLong())
        val input = mark.inputStart + within * mark.speed
        return mark.index to input / sinkRate
    }

    private fun resetAudio() {
        segments.clear()
        segmentBytes = 0
        lastNeed = ""
    }

    private fun renewScheduler() {
        scheduler?.dispose()
        scheduler = null
        val engine = engine ?: return
        if (utterances.isEmpty()) return
        val token = revision
        val generation = Any()
        currentGeneration = generation
        scheduler = Scheduler(
            engine, utterances, voice, dictionary,
            deliver = { i, segment -> post { if (revision == token && currentGeneration === generation) onSegment(i, segment) } },
            fail = { i, failure -> post { if (revision == token && currentGeneration === generation) onFailure(i, failure) } },
            log = log,
        )
    }

    private var currentGeneration: Any? = null

    private fun onSegment(i: Int, segment: AudioSegment) {
        val old = segments.put(i, segment)
        if (old != null) segmentBytes -= old.bytes
        segmentBytes += segment.bytes
        val wasKnown = timeline.isKnown(i)
        timeline.learn(i, segment.duration)
        if (!wasKnown) rebuildChapters()
        val pending = pendingWord
        if (pending != null && pending.first == i) {
            val start = segment.wordTimes.getOrNull(2 * pending.second) ?: -1.0
            if (start >= 0 && i == index && current == null && marks.isEmpty()) {
                offset = max(0.0, start - 0.05)
                if (nextToWrite == i) startOffset = offset
            }
            pendingWord = null
        }
        evict()
        requestNeeded()
    }

    private fun onFailure(i: Int, failure: Throwable) {
        log("Speech generation failed for passage $i: ${failure.message}")
        if (i == nextToWrite || i == index) {
            error = "This passage could not be read (${failure.message ?: "unknown error"}). Press Play to try again."
            pauseNow()
            lastNeed = ""
        }
    }

    private fun evict() {
        if (segmentBytes < 160L * 1024 * 1024) return
        val iterator = segments.entries.iterator()
        while (iterator.hasNext() && segmentBytes > 120L * 1024 * 1024) {
            val (i, segment) = iterator.next()
            if (kotlin.math.abs(i - index) < 80) continue
            iterator.remove()
            segmentBytes -= segment.bytes
        }
    }

    /** Seconds of playback, at the current speed, ready from the current position on. */
    private fun bufferedAhead(): Double {
        var seconds = -currentOffset()
        var i = index
        while (i < utterances.size && segments.containsKey(i)) seconds += segments.getValue(i).duration.also { i++ }
        return max(0.0, seconds) / rate
    }

    private fun requestNeeded() {
        val scheduler = scheduler ?: return
        if (utterances.isEmpty()) return
        val wanted = ArrayList<Int>()
        var ahead = 0.0
        var i = index
        while (i < utterances.size && wanted.size < 40 && ahead < 150) {
            if (!segments.containsKey(i)) wanted.add(i)
            ahead += timeline.duration(i)
            i++
        }
        val key = "$revision:${wanted.joinToString(",")}"
        if (key == lastNeed) return
        lastNeed = key
        if (wanted.isNotEmpty()) scheduler.need(wanted, bufferedAhead(), rate)
    }

    private fun stopOutput() {
        if (sinkRate != 0) {
            if (sinkPlaying) sink.pause()
            sink.flush()
        }
        sinkPlaying = false
        marks.clear()
        written = 0
        current = null
        endWritten = false
    }

    private fun startOutput() {
        stopOutput()
        nextToWrite = index
        startOffset = offset
        waiting = false
        requestNeeded()
        if (engine != null) pump()
    }

    /** Resumes a paused stream; false when there is nothing to resume. */
    private fun resumeSink(): Boolean {
        if (marks.isEmpty() && current == null) return false
        if (!sinkPlaying && sinkRate != 0) {
            sink.play()
            sinkPlaying = true
        }
        return true
    }

    private fun openOutput(i: Int): Output? {
        val segment = segments[i] ?: return null
        if (sinkRate != segment.sampleRate) {
            if (sinkRate != 0) sink.close()
            sink.open(segment.sampleRate)
            sinkRate = segment.sampleRate
            sinkPlaying = false
        }
        val from = if (i == nextToWrite && marks.isEmpty() && current == null) (startOffset * segment.sampleRate).roundToLong().toInt() else 0
        val pcm = Stretch.pcm16(segment.pcm, from.coerceIn(0, segment.pcm.size), segment.sampleRate, rate)
        marks.addLast(Mark(i, written, pcm.size, from.coerceIn(0, segment.pcm.size), rate))
        // Marks for passages already heard are no longer needed.
        val heard = sink.position()
        while (marks.size > 2 && marks[1].sinkStart + marks[1].frames <= heard) marks.removeFirst()
        return Output(i, pcm, 0)
    }

    /** Writes what the sink can take, and follows what is being heard. */
    private fun pump() {
        while (!endWritten) {
            val output = current ?: openOutput(nextToWrite)?.also { current = it } ?: break
            if (output.position < output.pcm.size) {
                val taken = sink.write(output.pcm, output.position, output.pcm.size - output.position)
                output.position += taken
                written += taken
                if (!sinkPlaying && taken > 0) {
                    sink.play()
                    sinkPlaying = true
                }
                if (output.position < output.pcm.size) break
            }
            current = null
            nextToWrite = output.index + 1
            if (nextToWrite >= utterances.size) endWritten = true
        }
        val heard = sink.position()
        locateHeard()?.let { (i, seconds) ->
            if (i != index) {
                index = i
                word = -1
                requestNeeded()
            }
            offset = seconds
            word = segments[i]?.wordAt(seconds) ?: -1
        }
        if (heard >= written) {
            if (endWritten) {
                finish()
            } else if (!waiting && current == null) {
                waiting = true
                // Running dry after audio has started is a stall; waiting for the first audio is not.
                if (written > 0) {
                    stalls++
                    log("Playback stalled waiting for passage $nextToWrite.")
                }
                requestNeeded()
            }
        } else if (waiting) {
            waiting = false
        }
        if (waiting && current != null) waiting = false
    }

    private fun finish() {
        offset = currentOffset()
        playing = false
        waiting = false
        finished = true
        word = -1
        stopOutput()
    }

    private fun rebuildChapters() {
        chapters = Navigation.chapters(blocks).map { timeline.start(it.first) to (it.heading?.text ?: "") }
    }

    private fun publish() {
        val heardOffset = if (playing) currentOffset() else offset
        val next = PlayerState(
            revision = revision,
            loaded = utterances.isNotEmpty(),
            title = title,
            selection = selection,
            count = utterances.size,
            index = index,
            word = word,
            offset = heardOffset,
            playing = playing,
            waiting = waiting,
            finished = finished,
            elapsed = min(timeline.start(index) + heardOffset, timeline.total),
            total = timeline.total,
            estimated = timeline.estimatedFrom(index),
            rate = rate,
            section = Navigation.section(blocks, index),
            chapters = chapters,
            engineReady = engine != null,
            notice = notice,
            noticeId = noticeId,
            error = error,
            stalls = stalls,
        )
        if (next != stateFlow.value) stateFlow.value = next
    }
}
