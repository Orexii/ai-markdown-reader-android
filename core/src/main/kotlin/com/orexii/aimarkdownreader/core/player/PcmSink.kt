package com.orexii.aimarkdownreader.core.player

/**
 * Where audio goes: AudioTrack on Android, a simulated device in tests. Used only from the player's audio
 * thread. Positions count frames since the last [flush].
 */
interface PcmSink {
    /** Prepares output for mono 16-bit audio at [sampleRate]. */
    fun open(sampleRate: Int)

    /** Writes up to [length] frames without blocking; returns how many were taken. */
    fun write(pcm: ShortArray, offset: Int, length: Int): Int

    /** Frames the listener has heard since the last flush. */
    fun position(): Long

    fun play()
    fun pause()

    /** Discards everything written but not yet heard, and restarts positions at 0. */
    fun flush()

    fun close()

    /** Times the device ran out of audio while playing, if it can tell (for diagnostics). */
    fun underruns(): Int = 0
}
