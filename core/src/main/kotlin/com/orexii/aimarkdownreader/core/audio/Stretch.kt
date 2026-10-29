package com.orexii.aimarkdownreader.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Pitch-preserving speed changes for one passage at a time (Sonic, as ExoPlayer uses). */
object Stretch {
    /** [pcm] from frame [from] played [speed] times faster, pitch unchanged. Speed 1 returns the frames as they are. */
    fun pcm16(pcm: ShortArray, from: Int, sampleRate: Int, speed: Double): ShortArray {
        val start = from.coerceIn(0, pcm.size)
        if (abs(speed - 1.0) < 1e-6 || start == pcm.size) return pcm.copyOfRange(start, pcm.size)
        val sonic = Sonic(sampleRate, 1, speed.toFloat(), 1f, sampleRate, false)
        val input = ByteBuffer.allocateDirect((pcm.size - start) * 2).order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(pcm, start, pcm.size - start)
        sonic.queueInput(input)
        sonic.queueEndOfStream()
        val output = ByteBuffer.allocateDirect(sonic.outputSize).order(ByteOrder.nativeOrder())
        sonic.getOutput(output)
        output.flip()
        return ShortArray(output.remaining() / 2).also { output.asShortBuffer().get(it) }
    }
}
