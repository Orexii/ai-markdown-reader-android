package com.orexii.aimarkdownreader.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * Port of the extension's src/scheduler.ts: synthesizes the passages the player needs, most urgent first,
 * combining neighbours for natural prosody. Synthesis runs on its own thread; [need] can be called from
 * any thread and delivers cached audio at once.
 */
class Scheduler(
    private val engine: SpeechEngine,
    private val utterances: List<Utterance>,
    private val voice: String,
    private val dictionary: Map<String, String>,
    private val deliver: (Int, AudioSegment) -> Unit,
    private val fail: (Int, Throwable) -> Unit,
    private val log: (String) -> Unit = {},
    private val cacheBytes: Long = 96L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var wanted: List<Int> = emptyList()
    private val cache = LinkedHashMap<Int, AudioSegment>()
    private var cachedBytes = 0L
    private val inflight = HashSet<Int>()
    @Volatile private var disposed = false

    /** Buffered playback the player reported, when it reported it, and audio delivered since. */
    private var ahead = Ahead(0.0, 0L, 1.0, 0.0)
    private class Ahead(val seconds: Double, val at: Long, val rate: Double, var delivered: Double)

    /** Measured synthesis time per spoken character, used to size batches. */
    private var secondsPerChar = 0.02

    private val worker = Thread(::run, "speech-scheduler").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    /**
     * Replaces the wanted list. The first index is needed now; the rest are prefetched in order. [aheadSeconds]
     * is how many seconds the player can play from what it already has, at [rate].
     */
    fun need(indices: List<Int>, aheadSeconds: Double = 0.0, rate: Double = 1.0) {
        val ready = ArrayList<Pair<Int, AudioSegment>>()
        lock.withLock {
            if (disposed) return
            val valid = indices.filter { it >= 0 && it < utterances.size }.distinct()
            for (index in valid) {
                val cached = cache.remove(index) ?: continue
                cache[index] = cached
                ready.add(index to cached)
            }
            wanted = valid.filter { !cache.containsKey(it) }
            ahead = Ahead(if (aheadSeconds.isFinite()) max(0.0, aheadSeconds) else 0.0, clock(), if (rate > 0) rate else 1.0, 0.0)
            changed.signalAll()
        }
        for ((index, segment) in ready) deliver(index, segment)
    }

    /** Audio already made for a passage, if it is still cached. */
    fun cached(index: Int): AudioSegment? = lock.withLock { cache[index] }

    /** Seconds of playback the player has buffered now, including audio delivered since it last asked. */
    private fun buffered(): Double {
        val a = ahead
        return max(0.0, a.seconds - (clock() - a.at) / 1000.0) + a.delivered / a.rate
    }

    private fun input(index: Int): SpeechInput {
        val utterance = utterances[index]
        return SpeechInput(utterance.words.map { SynthesisWord(it.text, it.sep) }, utterance.pauseAfter)
    }

    private fun batch(start: Int, buffered: Double): List<Int> {
        val batch = mutableListOf(start)
        if (engine.batchChars == 0) return batch
        // Small batches arrive sooner; full batches sound better. Use full ones once a full batch can be made in time.
        val fullBatchSeconds = engine.batchChars * secondsPerChar
        val limit = if (buffered < max(LOW_WATER, fullBatchSeconds * 2)) min(URGENT_CHARS, engine.batchChars) else engine.batchChars
        var chars = utterances[start].spokenLength()
        for (next in start + 1 until utterances.size) {
            if (cache.containsKey(next) || next in inflight) break
            val size = utterances[next].spokenLength()
            if (chars + size > MAX_CHARS) break
            if (chars >= SHORT_CHARS && (chars + size > limit || next !in wanted)) break
            batch.add(next)
            chars += size
        }
        return batch
    }

    private fun store(index: Int, segment: AudioSegment) {
        cache[index] = segment
        cachedBytes += segment.bytes
        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && cachedBytes > cacheBytes) {
            val (key, value) = iterator.next()
            if (key in wanted) continue
            iterator.remove()
            cachedBytes -= value.bytes
        }
    }

    private fun run() {
        while (true) {
            val batch: List<Int>
            val buffered: Double
            lock.withLock {
                var start: Int? = null
                while (!disposed) {
                    start = wanted.firstOrNull { !cache.containsKey(it) && it !in inflight }
                    if (start != null) break
                    changed.await()
                }
                if (disposed) return
                buffered = buffered()
                batch = batch(start!!, buffered)
                inflight.addAll(batch)
            }
            val chars = batch.sumOf { utterances[it].spokenLength() }
            val started = clock()
            try {
                val segments = engine.synthesize(batch.map(::input), voice, dictionary)
                if (disposed) return
                check(segments.size == batch.size) { "The speech engine returned an unexpected number of passages." }
                val elapsed = (clock() - started) / 1000.0
                val delivered = ArrayList<Pair<Int, AudioSegment>>()
                lock.withLock {
                    if (chars > 0) secondsPerChar = secondsPerChar * 0.7 + (elapsed / chars) * 0.3
                    if (buffered > 0 && elapsed > buffered) {
                        log("Speech generation fell behind playback: $chars characters took ${"%.1f".format(elapsed)} s with ${"%.1f".format(buffered)} s of audio buffered.")
                    }
                    segments.forEachIndexed { k, segment ->
                        store(batch[k], segment)
                        ahead.delivered += segment.duration
                        delivered.add(batch[k] to segment)
                    }
                }
                for ((index, segment) in delivered) deliver(index, segment)
            } catch (error: Throwable) {
                if (disposed) return
                fail(batch[0], error)
            } finally {
                lock.withLock {
                    inflight.removeAll(batch.toSet())
                    wanted = wanted.filter { it !in batch }
                }
            }
        }
    }

    fun dispose() {
        lock.withLock {
            disposed = true
            cache.clear()
            cachedBytes = 0
            wanted = emptyList()
            changed.signalAll()
        }
    }

    private companion object {
        const val URGENT_CHARS = 90
        /** Kokoro is weaker on very short inputs, so tiny passages are always combined with what follows. */
        const val SHORT_CHARS = 40
        const val MAX_CHARS = 250
        /** Below this much buffered playback (seconds), batches stay small so audio arrives before it is needed. */
        const val LOW_WATER = 5.0
    }
}
