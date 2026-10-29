package com.orexii.aimarkdownreader.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.orexii.aimarkdownreader.core.player.PcmSink

/**
 * The player's output: a streaming AudioTrack. A flush replaces the track, so positions always restart at 0
 * (AudioTrack's own counters behave differently after a flush on different devices). The heard position comes
 * from AudioTrack timestamps, which include the output latency (Bluetooth headphones too).
 */
class AudioTrackSink : PcmSink {
    private var track: AudioTrack? = null
    private var rate = 0
    private var written = 0L
    private var playing = false
    private var underrunsBefore = 0
    private val timestamp = AudioTimestamp()

    override fun open(sampleRate: Int) {
        close()
        rate = sampleRate
        create()
    }

    private fun create() {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // About a quarter of a second: enough to ride out scheduling hiccups, small enough to seek quickly.
        val bytes = maxOf(minimum * 2, rate / 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        written = 0
        playing = false
    }

    override fun write(pcm: ShortArray, offset: Int, length: Int): Int {
        val track = track ?: return 0
        val n = track.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) written += n
        return maxOf(0, n)
    }

    override fun position(): Long {
        val track = track ?: return 0
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        var heard = head
        if (track.getTimestamp(timestamp)) {
            val elapsed = if (playing) (System.nanoTime() - timestamp.nanoTime).coerceAtLeast(0) else 0L
            heard = minOf(head, timestamp.framePosition + elapsed * rate / 1_000_000_000L)
        }
        return heard.coerceIn(0, written)
    }

    override fun play() {
        track?.play()
        playing = true
    }

    override fun pause() {
        track?.pause()
        playing = false
    }

    override fun flush() {
        val old = track ?: return
        underrunsBefore += underrunsOf(old)
        old.pause()
        old.flush()
        old.release()
        create()
    }

    override fun close() {
        track?.let {
            underrunsBefore += underrunsOf(it)
            it.release()
        }
        track = null
        playing = false
    }

    private fun underrunsOf(track: AudioTrack): Int = track.underrunCount

    override fun underruns(): Int = underrunsBefore + (track?.let(::underrunsOf) ?: 0)
}
