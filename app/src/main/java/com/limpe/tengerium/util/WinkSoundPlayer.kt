package com.limpe.tengerium.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Plays the sound that belongs to a classic wink.
 *
 * The effects are synthesized on a worker thread and played through a static
 * [AudioTrack], so the app ships no extra audio files and each wink sounds
 * different — a guitar riff, a wet smack, a cackle, a knock, and so on.
 * Only one wink sound plays at a time; starting another stops the previous one.
 */
object WinkSoundPlayer {

    private const val SAMPLE_RATE = 22050
    private const val TAG = "WinkSoundPlayer"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wink-sound").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var active: AudioTrack? = null

    /** Bumped on every play/stop so queued work for an older wink can bail out. */
    private var generation = 0

    /**
     * Plays [winkId]'s effect when it has one.
     *
     * @return false when this wink has no synthesized sound (retired winks), so
     *         the caller can fall back to the generic wink chime.
     */
    fun play(context: Context, winkId: String): Boolean {
        if (!WinkSynth.supports(winkId)) return false
        stop()
        val token = synchronized(lock) { generation }
        executor.execute {
            if (synchronized(lock) { generation != token }) return@execute
            val samples = try {
                WinkSynth.build(winkId)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not synthesize wink sound $winkId", t)
                return@execute
            }
            if (synchronized(lock) { generation != token }) return@execute
            val track = try {
                createTrack(samples)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not create AudioTrack for $winkId", t)
                null
            } ?: return@execute

            synchronized(lock) {
                if (generation != token) {
                    releaseQuietly(track)
                    return@execute
                }
                active = track
            }
            main.postDelayed(
                { releaseQuietly(track) },
                samples.size * 1000L / SAMPLE_RATE + 450L
            )
            try {
                track.play()
                val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    releaseQuietly(track)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not play wink sound $winkId", t)
                releaseQuietly(track)
            }
        }
        return true
    }

    /** Stops any wink sound that is still playing. */
    fun stop() {
        val track = synchronized(lock) {
            generation++
            val current = active
            active = null
            current
        }
        track?.let { releaseQuietly(it) }
    }

    private fun createTrack(samples: ShortArray): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuffer * 2, 64 * 1024, 0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            releaseQuietly(track)
            throw IllegalStateException("AudioTrack did not initialize (state=${track.state})")
        }
        track.setVolume(1f)
        return track
    }

    private fun releaseQuietly(track: AudioTrack) {
        synchronized(lock) {
            if (active === track) active = null
        }
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: Throwable) {
        }
        try {
            track.flush()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }
}

// ---------------------------------------------------------------------------- synth

private enum class Wave {
    SINE, SAW, SQUARE, TRIANGLE;

    fun at(phase: Double): Float {
        val p = phase - floor(phase)
        return when (this) {
            SINE -> sin(p * TWO_PI).toFloat()
            SAW -> (2.0 * p - 1.0).toFloat()
            SQUARE -> if (p < 0.5) 1f else -1f
            TRIANGLE -> (4.0 * abs(p - 0.5) - 1.0).toFloat()
        }
    }
}

private const val SR = 22050
private val TWO_PI = 2.0 * PI

private class WinBuf(seconds: Float) {
    val samples = FloatArray((seconds * SR).toInt().coerceAtLeast(1))

    fun toPcm(gain: Float): ShortArray {
        var peak = 0f
        for (s in samples) {
            val v = abs(s)
            if (v > peak) peak = v
        }
        if (peak < 0.0001f) peak = 1f
        val g = gain / peak
        return ShortArray(samples.size) {
            (samples[it] * g * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
        }
    }
}

private fun WinBuf.tone(
    start: Float,
    dur: Float,
    f0: Float,
    f1: Float = f0,
    amp: Float = 0.4f,
    wave: Wave = Wave.SINE,
    attack: Float = 0.004f,
    release: Float = 0.06f,
    vibratoHz: Float = 0f,
    vibratoDepth: Float = 0f,
    curve: Float = 1f,
    decay: Float = 0f,
    drive: Float = 0f,
    amHz: Float = 0f,
    amDepth: Float = 0f
) {
    val i0 = (start * SR).toInt()
    val n = (dur * SR).toInt().coerceAtLeast(1)
    val atk = (attack * SR).coerceAtLeast(1f)
    val rel = (release * SR).coerceAtLeast(1f)
    var phase = 0.0
    for (k in 0 until n) {
        val idx = i0 + k
        if (idx < 0) continue
        if (idx >= samples.size) break
        val x = k.toFloat() / n
        val f = f0 + (f1 - f0) * x.pow(curve)
        var vib = 1f
        if (vibratoDepth != 0f) {
            vib += vibratoDepth * sin(TWO_PI * vibratoHz * k / SR).toFloat()
        }
        phase += f * vib / SR
        var v = wave.at(phase)
        if (drive > 0f) v = tanh(v.toDouble() * drive).toFloat()
        var env = min(1f, k / atk) * min(1f, (n - k) / rel)
        if (decay > 0f) env *= exp(-decay * x * 4f).toFloat()
        if (amDepth > 0f) {
            env *= 1f - amDepth * 0.5f * (1f + sin(TWO_PI * amHz * k / SR).toFloat())
        }
        samples[idx] += v * amp * env
    }
}

private fun WinBuf.noise(
    start: Float,
    dur: Float,
    amp: Float,
    cutoff0: Float,
    cutoff1: Float = cutoff0,
    attack: Float = 0.003f,
    release: Float = 0.04f,
    decay: Float = 0f,
    amHz: Float = 0f,
    amDepth: Float = 0f,
    seed: Int = 17
) {
    val i0 = (start * SR).toInt()
    val n = (dur * SR).toInt().coerceAtLeast(1)
    val atk = (attack * SR).coerceAtLeast(1f)
    val rel = (release * SR).coerceAtLeast(1f)
    val random = Random(seed)
    var low = 0f
    for (k in 0 until n) {
        val idx = i0 + k
        if (idx < 0) continue
        if (idx >= samples.size) break
        val x = k.toFloat() / n
        val cutoff = cutoff0 + (cutoff1 - cutoff0) * x
        val a = 1f - exp(-TWO_PI * cutoff / SR).toFloat()
        low += (random.nextFloat() * 2f - 1f - low) * a
        var env = min(1f, k / atk) * min(1f, (n - k) / rel)
        if (decay > 0f) env *= exp(-decay * x * 4f).toFloat()
        if (amDepth > 0f) {
            env *= 1f - amDepth * 0.5f * (1f + sin(TWO_PI * amHz * k / SR).toFloat())
        }
        samples[idx] += low * amp * env * 2.5f
    }
}

internal object WinkSynth {

    private val supported = setOf(
        "guitar", "kiss", "laugh", "knock", "slap", "peek", "icecream",
        "balloon", "pig", "dance", "ufo", "frog", "heart", "idea"
    )

    fun supports(winkId: String): Boolean = winkId in supported

    fun build(winkId: String): ShortArray = when (winkId) {
        "guitar" -> guitar()
        "kiss" -> kiss()
        "laugh" -> laugh()
        "knock" -> knock()
        "slap" -> slap()
        "peek" -> peek()
        "icecream" -> iceCream()
        "balloon" -> balloon()
        "pig" -> pig()
        "dance" -> dance()
        "ufo" -> ufo()
        "frog" -> frog()
        "heart" -> heart()
        "idea" -> idea()
        else -> WinBuf(0.1f)
    }.toPcm(0.88f)

    /** Distorted power chords, a rising bend, then the guitar smashes into the amp. */
    private fun guitar(): WinBuf {
        val b = WinBuf(2.7f)
        for (at in floatArrayOf(0.12f, 0.46f, 0.8f)) {
            for (n in floatArrayOf(110f, 164.8f, 220f, 329.6f)) {
                b.tone(
                    at, 0.24f, n, n * 0.995f, amp = 0.22f, wave = Wave.SAW,
                    attack = 0.004f, release = 0.06f, drive = 3.2f
                )
            }
            b.noise(at, 0.05f, 0.16f, 4200f, 1200f)
        }
        // bend up to the smash
        b.tone(
            1.12f, 0.62f, 220f, 520f, amp = 0.28f, wave = Wave.SAW,
            attack = 0.02f, release = 0.1f, vibratoHz = 6f, vibratoDepth = 0.03f, drive = 3f
        )
        b.tone(1.2f, 0.5f, 330f, 780f, amp = 0.16f, wave = Wave.SAW, drive = 2.5f)
        // the smash into the amplifier
        b.noise(1.94f, 0.5f, 0.75f, 5000f, 500f, decay = 0.7f)
        b.tone(1.94f, 0.3f, 110f, 44f, amp = 0.6f, wave = Wave.SINE, release = 0.25f)
        b.tone(1.96f, 0.6f, 780f, 500f, amp = 0.18f, wave = Wave.SQUARE, decay = 0.9f)
        // feedback dying away
        b.tone(
            2.05f, 0.55f, 220f, 180f, amp = 0.07f, wave = Wave.SAW,
            attack = 0.05f, release = 0.4f, vibratoHz = 5f, vibratoDepth = 0.02f, drive = 1.5f
        )
        return b
    }

    /** An exaggerated wet "mwah!" */
    private fun kiss(): WinBuf {
        val b = WinBuf(2.2f)
        b.noise(0.1f, 0.45f, 0.07f, 500f, 2200f)

        fun smack(at: Float, amp: Float) {
            b.tone(at, 0.14f, 220f, 110f, amp = 0.5f * amp, wave = Wave.SINE, attack = 0.005f, release = 0.08f)
            b.tone(
                at, 0.1f, 760f, 220f, amp = 0.4f * amp, wave = Wave.SAW,
                attack = 0.003f, release = 0.05f, drive = 1.6f
            )
            b.noise(at, 0.09f, 0.55f * amp, 3200f, 500f, attack = 0.002f, release = 0.05f, seed = 23)
            b.noise(at + 0.02f, 0.12f, 0.2f * amp, 900f, 300f, seed = 24)
            b.tone(at + 0.08f, 0.09f, 900f, 300f, amp = 0.2f * amp, wave = Wave.SINE, release = 0.06f)
        }

        smack(1.12f, 1f)
        smack(1.75f, 0.45f)
        return b
    }

    /** A screeching, breathless cackle that runs out of air. */
    private fun laugh(): WinBuf {
        val b = WinBuf(6.8f)
        for (i in 0 until 22) {
            val at = 0.28f + i * 0.26f
            val f = 780f - i * 24f
            b.tone(
                at, 0.17f, f, f * 0.78f, amp = 0.3f, wave = Wave.SAW,
                attack = 0.012f, release = 0.06f,
                vibratoHz = 22f + i, vibratoDepth = 0.07f, drive = 1.8f
            )
            b.tone(
                at, 0.14f, f * 2.1f, f * 1.7f, amp = 0.1f, wave = Wave.TRIANGLE,
                attack = 0.01f, release = 0.06f
            )
            b.noise(at, 0.12f, 0.12f, 4200f, 1800f, attack = 0.005f, seed = 30 + i)
        }
        b.noise(
            6.0f, 0.65f, 0.16f, 1800f, 700f, attack = 0.08f, release = 0.3f,
            amHz = 9f, amDepth = 0.6f, seed = 44
        )
        return b
    }

    /** Four heavy thumps, as if someone is pounding on the monitor. */
    private fun knock(): WinBuf {
        val b = WinBuf(2.3f)
        for ((i, at) in floatArrayOf(0.15f, 0.91f, 1.67f).withIndex()) {
            b.tone(
                at, 0.28f, 95f, 48f + i * 2f, amp = 0.8f, wave = Wave.SINE,
                attack = 0.002f, release = 0.2f, decay = 0.5f
            )
            b.tone(
                at, 0.12f, 170f, 120f, amp = 0.3f, wave = Wave.TRIANGLE,
                attack = 0.002f, release = 0.08f
            )
            b.noise(at, 0.07f, 0.6f, 2200f, 500f, attack = 0.001f, release = 0.06f, seed = 50 + i)
            b.noise(
                at + 0.01f, 0.25f, 0.1f, 400f, 200f, attack = 0.005f, release = 0.2f,
                decay = 0.5f, seed = 60 + i
            )
        }
        return b
    }

    /** Bright applause-like pulses for the cheering smiley conducting a crowd. */
    private fun slap(): WinBuf {
        val b = WinBuf(5.8f)
        for (i in 0 until 14) {
            val at = 0.25f + i * 0.36f
            if (i % 2 == 0) {
                b.noise(at, 0.06f, 0.65f, 3800f, 1400f, attack = 0.001f, release = 0.04f, seed = 70 + i)
                b.tone(
                    at, 0.05f, 900f, 500f, amp = 0.2f, wave = Wave.TRIANGLE,
                    attack = 0.001f, release = 0.03f
                )
            } else {
                b.tone(at, 0.1f, 220f, 120f, amp = 0.45f, wave = Wave.SINE, attack = 0.002f, release = 0.07f)
                b.noise(at, 0.05f, 0.4f, 1400f, 500f, attack = 0.001f, release = 0.04f, seed = 80 + i)
            }
            b.tone(
                at + 0.14f, 0.12f, 520f, 980f, amp = 0.13f, wave = Wave.SQUARE,
                attack = 0.01f, release = 0.05f, vibratoHz = 30f, vibratoDepth = 0.05f
            )
        }
        b.tone(5.15f, 0.32f, 1200f, 240f, amp = 0.3f, wave = Wave.SQUARE, attack = 0.002f, release = 0.12f)
        b.noise(5.15f, 0.26f, 0.45f, 5000f, 1000f, seed = 90)
        return b
    }

    /** A cheeky raspberry. */
    private fun peek(): WinBuf {
        val b = WinBuf(2.6f)
        b.tone(
            0.72f, 0.22f, 300f, 640f, amp = 0.3f, wave = Wave.SINE,
            attack = 0.01f, release = 0.1f, vibratoHz = 12f, vibratoDepth = 0.06f
        )
        b.tone(0.74f, 0.16f, 600f, 900f, amp = 0.1f, wave = Wave.TRIANGLE, attack = 0.01f, release = 0.08f)
        b.tone(
            1.5f, 0.75f, 105f, 80f, amp = 0.45f, wave = Wave.SAW,
            attack = 0.02f, release = 0.12f, amHz = 26f, amDepth = 0.95f
        )
        b.tone(
            1.5f, 0.7f, 210f, 160f, amp = 0.12f, wave = Wave.SQUARE,
            attack = 0.02f, release = 0.12f, amHz = 26f, amDepth = 0.8f
        )
        b.noise(
            1.5f, 0.75f, 0.35f, 2200f, 900f, attack = 0.02f, release = 0.15f,
            amHz = 26f, amDepth = 0.9f, seed = 41
        )
        return b
    }

    /** A slide whistle down, a splat, then dramatic sobbing. */
    private fun iceCream(): WinBuf {
        val b = WinBuf(3.4f)
        b.tone(
            0.12f, 0.3f, 880f, 940f, amp = 0.14f, wave = Wave.SINE,
            attack = 0.02f, release = 0.08f, vibratoHz = 9f, vibratoDepth = 0.05f
        )
        b.tone(
            1.02f, 0.62f, 1500f, 320f, amp = 0.32f, wave = Wave.SINE,
            attack = 0.02f, release = 0.06f,
            vibratoHz = 10f, vibratoDepth = 0.03f, curve = 0.35f
        )
        b.noise(1.64f, 0.16f, 0.7f, 2600f, 400f, attack = 0.001f, release = 0.08f, seed = 100)
        b.tone(1.64f, 0.14f, 150f, 70f, amp = 0.4f, wave = Wave.SINE, attack = 0.002f, release = 0.1f)
        for (i in 0 until 4) {
            val at = 1.95f + i * 0.34f
            b.tone(
                at, 0.3f, 520f - i * 20f, 300f, amp = 0.26f, wave = Wave.TRIANGLE,
                attack = 0.02f, release = 0.12f, vibratoHz = 8f, vibratoDepth = 0.12f
            )
            b.noise(
                at + 0.02f, 0.28f, 0.1f, 1200f, 500f, attack = 0.02f, release = 0.15f,
                amHz = 7f, amDepth = 0.7f, seed = 110 + i
            )
        }
        return b
    }

    /** Rising breath, a sharp balloon pop, and a few soft falling fragments. */
    private fun balloon(): WinBuf {
        val b = WinBuf(5.4f)
        b.tone(
            0.2f, 3.9f, 420f, 980f, amp = 0.13f, wave = Wave.SINE,
            attack = 0.08f, release = 0.12f, vibratoHz = 8f, vibratoDepth = 0.05f
        )
        b.noise(0.2f, 3.9f, 0.06f, 500f, 2200f, attack = 0.08f, release = 0.1f, seed = 120)
        val popAt = 4.12f
        b.noise(popAt, 0.42f, 0.9f, 5200f, 550f, attack = 0.001f, release = 0.12f, decay = 0.6f, seed = 121)
        b.tone(popAt, 0.28f, 230f, 65f, amp = 0.5f, wave = Wave.SINE, attack = 0.001f, release = 0.15f)
        b.noise(popAt + 0.02f, 0.2f, 0.45f, 6000f, 2500f, attack = 0.001f, release = 0.1f, seed = 122)
        for (i in 0 until 7) {
            val at = popAt + 0.08f + i * 0.12f
            val f = 900f + (i * 137) % 500
            b.tone(at, 0.09f, f, f * 0.6f, amp = 0.16f, wave = Wave.SINE, attack = 0.002f, release = 0.06f)
        }
        return b
    }

    /** A bright, original retro-electronic dance loop for the beanie-wearing boy. */
    private fun dance(): WinBuf {
        val b = WinBuf(5.9f)
        val melody = floatArrayOf(392f, 523f, 659f, 523f, 440f, 587f, 698f, 587f)
        for (step in 0 until 24) {
            val at = 0.08f + step * 0.23f
            if (step % 4 == 0) {
                b.tone(at, 0.18f, 110f, 48f, amp = 0.5f, wave = Wave.SINE,
                    attack = 0.002f, release = 0.12f)
                b.tone(at, 0.14f, if (step % 8 == 0) 110f else 82f,
                    if (step % 8 == 0) 110f else 82f, amp = 0.18f, wave = Wave.SQUARE,
                    attack = 0.004f, release = 0.08f)
            }
            if (step % 2 == 1) {
                b.noise(at, 0.045f, 0.16f, 8200f, 4600f, attack = 0.001f,
                    release = 0.03f, seed = 170 + step)
            }
            val f = melody[step % melody.size]
            b.tone(at, 0.16f, f, f * 1.01f, amp = 0.11f, wave = Wave.SQUARE,
                attack = 0.004f, release = 0.07f)
        }
        return b
    }

    /** A fast, high-pitched synth-pop loop for the dancing pig. */
    private fun pig(): WinBuf {
        val b = WinBuf(3.3f)
        val lead = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f, 783.99f, 659.25f)
        for (step in 0 until 16) {
            val at = 0.08f + step * 0.2f
            if (step % 4 == 0) {
                b.tone(at, 0.16f, 140f, 45f, amp = 0.6f, wave = Wave.SINE, attack = 0.002f, release = 0.1f)
                b.tone(
                    at, 0.22f, if ((step / 4) % 2 == 0) 110f else 82.4f,
                    if ((step / 4) % 2 == 0) 110f else 82.4f,
                    amp = 0.26f, wave = Wave.SQUARE, attack = 0.006f, release = 0.08f
                )
            }
            if (step % 2 == 1) {
                b.noise(at, 0.04f, 0.18f, 8000f, 5000f, attack = 0.001f, release = 0.03f, seed = 130 + step)
            }
            b.tone(
                at, 0.14f, lead[step % lead.size], lead[step % lead.size], amp = 0.1f,
                wave = Wave.SQUARE, attack = 0.004f, release = 0.06f
            )
        }
        b.tone(3.1f, 0.2f, 600f, 1600f, amp = 0.2f, wave = Wave.SQUARE, attack = 0.005f, release = 0.08f)
        return b
    }

    /** Retro oscillating space synth plus a tractor-beam hum. */
    private fun ufo(): WinBuf {
        val b = WinBuf(4f)
        b.noise(0f, 0.7f, 0.2f, 300f, 2600f, attack = 0.05f, release = 0.2f, seed = 140)
        for (i in 0 until 3) {
            val at = 0.6f + i * 0.85f
            b.tone(
                at, 0.42f, 300f, 900f, amp = 0.2f, wave = Wave.SQUARE,
                attack = 0.03f, release = 0.08f, vibratoHz = 16f, vibratoDepth = 0.12f
            )
            b.tone(
                at + 0.42f, 0.42f, 900f, 300f, amp = 0.2f, wave = Wave.SQUARE,
                attack = 0.03f, release = 0.08f, vibratoHz = 16f, vibratoDepth = 0.12f
            )
        }
        b.tone(0.5f, 2.7f, 62f, 58f, amp = 0.14f, wave = Wave.SINE, attack = 0.2f, release = 0.4f)
        b.tone(0.6f, 2.4f, 124f, 118f, amp = 0.05f, wave = Wave.TRIANGLE, attack = 0.3f, release = 0.4f)
        b.noise(3.25f, 0.65f, 0.2f, 800f, 5200f, attack = 0.02f, release = 0.3f, seed = 141)
        return b
    }

    /** A buzzing fly, a rubbery thwip, then a satisfied croak. */
    private fun frog(): WinBuf {
        val b = WinBuf(3.3f)
        b.tone(
            0.25f, 1.9f, 250f, 300f, amp = 0.13f, wave = Wave.SQUARE,
            attack = 0.05f, release = 0.02f,
            vibratoHz = 6f, vibratoDepth = 0.06f, amHz = 12f, amDepth = 0.5f
        )
        b.noise(0.25f, 1.9f, 0.04f, 2200f, 1600f, attack = 0.05f, release = 0.02f, seed = 150)
        b.tone(2.16f, 0.1f, 2200f, 240f, amp = 0.4f, wave = Wave.SINE, attack = 0.001f, release = 0.04f)
        b.noise(2.16f, 0.05f, 0.35f, 5000f, 1200f, attack = 0.001f, release = 0.03f, seed = 151)
        b.tone(2.3f, 0.16f, 220f, 90f, amp = 0.4f, wave = Wave.SINE, attack = 0.005f, release = 0.1f)
        for (i in 0 until 2) {
            val at = 2.42f + i * 0.4f
            b.tone(
                at, 0.32f, 70f, 62f, amp = 0.45f, wave = Wave.SAW,
                attack = 0.01f, release = 0.1f, amHz = 32f, amDepth = 0.95f
            )
            b.tone(
                at, 0.3f, 140f, 120f, amp = 0.16f, wave = Wave.SQUARE,
                attack = 0.01f, release = 0.1f, amHz = 32f, amDepth = 0.9f
            )
        }
        return b
    }

    /** Mystical twinkling chimes as the heart is drawn in fairy dust. */
    private fun heart(): WinBuf {
        val b = WinBuf(3.2f)
        val random = Random(7)
        for (i in 0 until 18) {
            val at = 0.15f + i * 0.155f + random.nextFloat() * 0.04f
            val f = 1500f + random.nextFloat() * 2200f
            b.tone(
                at, 0.55f, f, f * 0.995f, amp = 0.15f, wave = Wave.SINE,
                attack = 0.004f, release = 0.05f, decay = 0.8f
            )
            b.tone(
                at, 0.3f, f * 2.01f, f * 2f, amp = 0.05f, wave = Wave.SINE,
                attack = 0.004f, release = 0.05f, decay = 1.2f
            )
        }
        b.tone(0.2f, 2.6f, 220f, 220f, amp = 0.06f, wave = Wave.SINE, attack = 0.4f, release = 0.6f)
        b.tone(0.2f, 2.6f, 329.6f, 330f, amp = 0.045f, wave = Wave.SINE, attack = 0.5f, release = 0.6f)
        b.tone(0.2f, 2.6f, 440f, 442f, amp = 0.035f, wave = Wave.SINE, attack = 0.6f, release = 0.6f)
        b.tone(
            2.7f, 0.5f, 2400f, 3000f, amp = 0.12f, wave = Wave.SINE,
            attack = 0.02f, release = 0.3f, vibratoHz = 12f, vibratoDepth = 0.02f
        )
        return b
    }

    /** A digital chime, a switch click, then the electrical ping of a lightbulb. */
    private fun idea(): WinBuf {
        val b = WinBuf(2.9f)
        b.tone(0.16f, 0.16f, 1200f, 1200f, amp = 0.25f, wave = Wave.SQUARE, attack = 0.002f, release = 0.08f)
        b.tone(0.3f, 0.2f, 1800f, 1800f, amp = 0.22f, wave = Wave.SQUARE, attack = 0.002f, release = 0.1f)
        b.noise(1.32f, 0.03f, 0.5f, 6000f, 3000f, attack = 0.001f, release = 0.02f, seed = 160)
        b.tone(1.34f, 0.9f, 2400f, 2350f, amp = 0.3f, wave = Wave.SINE, attack = 0.003f, release = 0.1f, decay = 0.7f)
        b.tone(1.34f, 0.6f, 3600f, 3550f, amp = 0.12f, wave = Wave.SINE, attack = 0.003f, release = 0.1f, decay = 1f)
        b.tone(
            1.33f, 0.18f, 60f, 62f, amp = 0.16f, wave = Wave.SAW,
            attack = 0.005f, release = 0.05f, amHz = 50f, amDepth = 0.6f
        )
        b.tone(1.45f, 1.3f, 120f, 122f, amp = 0.07f, wave = Wave.SINE, attack = 0.2f, release = 0.5f)
        return b
    }
}
