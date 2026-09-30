package com.appsalad.recorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Start/stop chimes, synthesised once: a rising two-note chime when a recording starts
 * and a falling one when it stops, so the two are told apart without looking.
 * Played as media, so they follow the media volume (usually up, unlike notification volume).
 */
object Chime {
    private const val RATE = 44100
    private val start by lazy { notes(659.25, 987.77) } // E5 → B5
    private val stop by lazy { notes(987.77, 659.25, 493.88) } // B5 → E5 → B4

    private fun notes(vararg hz: Double): ShortArray {
        val noteMs = 110; val gapMs = 25
        val per = RATE * noteMs / 1000; val gap = RATE * gapMs / 1000
        val tail = RATE * 180 / 1000 // let the last note ring out
        val out = ShortArray(hz.size * (per + gap) + tail)
        hz.forEachIndexed { n, f ->
            val len = if (n == hz.lastIndex) per + tail else per
            val off = n * (per + gap)
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                // quick attack, exponential decay; a soft overtone makes it bell-like
                val env = min(1.0, t / 0.004) * exp(-t * if (n == hz.lastIndex) 9.0 else 14.0)
                val v = sin(2 * PI * f * t) + 0.25 * sin(2 * PI * 2 * f * t)
                val s = (v / 1.25 * env * 0.8 * Short.MAX_VALUE).toInt()
                out[off + i] = (out[off + i] + s).coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    fun play(starting: Boolean) {
        val pcm = if (starting) start else stop
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) { t.release() }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
    }
}
