package com.ironlog.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The level-up chime for a timed set that passes last time's hold: a quick rising bell run (G, C, E, G) that lands on a
 * ringing high C, about a second long, with a short buzz. It's made here (no sound file) and played on the alarm sound
 * channel, so it sounds even with the phone on silent or vibrate, at the alarm volume.
 */
object Chime {
    private const val RATE = 44100
    private val pcm: ShortArray by lazy { build() }

    private fun build(): ShortArray {
        // (frequency, start s, length s, loudness)
        val notes = listOf(
            Triple(783.99, 0.00, 0.30) to 0.55, Triple(1046.50, 0.08, 0.30) to 0.6,
            Triple(1318.51, 0.16, 0.30) to 0.65, Triple(1567.98, 0.24, 0.35) to 0.7,
            Triple(2093.00, 0.34, 0.75) to 0.8,
        )
        val total = (RATE * 1.1).toInt()
        val mix = DoubleArray(total)
        for ((n, amp) in notes) {
            val (f, start, len) = n
            val s0 = (start * RATE).toInt()
            val count = (len * RATE).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / RATE
                if (s0 + i >= total) break
                // A bell: the note plus softer overtones, a 5 ms fade-in and a ringing fade-out.
                val env = minOf(1.0, t / 0.005) * exp(-t * 5.5)
                val v = sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2.0 * t) + 0.12 * sin(2 * PI * f * 3.01 * t)
                mix[s0 + i] += amp * env * v
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6)
        // A short fade at the very end so it never clicks.
        return ShortArray(total) { i ->
            val tail = ((total - i) / (RATE * 0.02)).coerceAtMost(1.0)
            (mix[i] / peak * 0.9 * tail * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun play(c: Context) {
        Thread {
            runCatching {
                val data = pcm
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(data.size * 2).build()
                track.write(data, 0, data.size)
                track.play()
                Thread.sleep(data.size * 1000L / RATE + 200)
                track.release()
            }
        }.start()
        runCatching {
            val v = c.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 60, 60, 60, 180), intArrayOf(0, 255, 0, 255, 0, 255), -1))
        }
    }
}
