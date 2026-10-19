package com.ydeclik.app

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------- Sounds

enum class SoundKind(val label: String) {
    CLICK("Click"), WOOD("Wood block"), BEEP("Beep"), COWBELL("Cowbell"), HIHAT("Hi-hat"), CLAVE("Clave");

    fun render(accent: Boolean, sr: Int): FloatArray = when (this) {
        CLICK -> synth(listOf((if (accent) 1800.0 else 1200.0) to 1.0), 0.05, 90.0, 0.0, sr)
        WOOD -> {
            val f = if (accent) 1100.0 else 800.0
            synth(listOf(f to 1.0, f * 2.4 to 0.4), 0.09, 60.0, 0.0, sr)
        }
        BEEP -> synth(listOf((if (accent) 1320.0 else 880.0) to 1.0), 0.10, 25.0, 0.0, sr)
        COWBELL -> {
            val f = if (accent) 700.0 else 540.0
            synth(listOf(f to 1.0, f * 1.504 to 0.8), 0.20, 22.0, 0.0, sr)
        }
        HIHAT -> synth(emptyList(), if (accent) 0.09 else 0.045, if (accent) 45.0 else 90.0, 1.0, sr)
        CLAVE -> {
            val f = if (accent) 2900.0 else 2300.0
            synth(listOf(f to 1.0, f * 1.6 to 0.3), 0.06, 70.0, 0.0, sr)
        }
    }
}

private fun synth(partials: List<Pair<Double, Double>>, dur: Double, decay: Double, noise: Double, sr: Int): FloatArray {
    val n = (dur * sr).toInt()
    val out = FloatArray(n)
    var prev = 0.0
    var peak = 0f
    for (i in 0 until n) {
        val t = i.toDouble() / sr
        var s = 0.0
        for ((f, a) in partials) s += a * sin(2 * PI * f * t)
        if (noise > 0) {
            val r = Random.nextDouble(-1.0, 1.0)
            s += noise * (r - prev) * 0.5   // crude high-pass
            prev = r
        }
        val env = exp(-t * decay) * min(1.0, t / 0.0008)
        val v = (s * env).toFloat()
        out[i] = v
        peak = max(peak, kotlin.math.abs(v))
    }
    val fade = max(1, min(n / 8, 256))
    val scale = if (peak > 0f) 0.9f / peak else 1f
    for (i in 0 until n) {
        var v = out[i] * scale
        if (i >= n - fade) v *= (n - i).toFloat() / fade
        out[i] = v
    }
    return out
}

// ---------------------------------------------------------------- Engine

data class ClickEvent(val frame: Long, val beat: Int, val sub: Int, val state: NoteState, val muted: Boolean)

class ClickEngine {
    companion object { const val SR = 48000 }

    val cfg = AtomicReference(Config())
    var onTempoChange: ((Int) -> Unit)? = null

    @Volatile var pan = 0f
    @Volatile private var normal = FloatArray(0)
    @Volatile private var accent = FloatArray(0)
    @Volatile private var volume = 0.8f
    @Volatile private var track: AudioTrack? = null
    @Volatile private var device: AudioDeviceInfo? = null
    @Volatile private var running = false
    @Volatile private var gen = 0

    private val events = ConcurrentLinkedQueue<ClickEvent>()
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    init { setSound(SoundKind.CLICK) }

    fun setSound(s: SoundKind) {
        val n = s.render(false, SR)
        for (i in n.indices) n[i] *= 0.7f
        normal = n
        accent = s.render(true, SR)
    }

    fun setVolume(v: Float) { volume = v; track?.setVolume(v) }

    fun setDevice(d: AudioDeviceInfo?) { device = d; track?.setPreferredDevice(d) }

    fun currentFrame(): Long = track?.playbackHeadPosition?.toLong() ?: 0L

    fun drain(upTo: Long): List<ClickEvent> {
        val out = ArrayList<ClickEvent>()
        while (true) {
            val e = events.peek() ?: break
            if (e.frame <= upTo) { events.poll(); out.add(e) } else break
        }
        return out
    }

    @Synchronized fun start() {
        if (running) return
        running = true
        val myGen = ++gen
        events.clear()
        thread = Thread({ runLoop(myGen) }, "ydeclik-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized fun stop() {
        running = false
        thread?.join(400)
        thread = null
        events.clear()
    }

    private fun alive(g: Int) = running && gen == g

    private fun bump(c: Config): Config {
        val nb = (c.bpm + c.addBpm).coerceIn(20, 300)
        if (nb == c.bpm) return c
        cfg.updateAndGet { it.copy(bpm = nb) }
        main.post { onTempoChange?.invoke(nb) }
        return c.copy(bpm = nb)
    }

    private fun runLoop(g: Int) {
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = max(minBuf, SR / 10 * 2 * 4)   // ~100 ms
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        device?.let { t.setPreferredDevice(it) }
        t.setVolume(volume)
        track = t

        var scheduledUntil = 0L
        var exactPos = 0.0
        var beatIndex = 0
        var barsPlayed = 0
        var cycleBar = 0
        var barsSinceAdd = 0
        var lastAddFrame = 0L
        var mutedBar = false

        t.play()

        while (alive(g)) {
            var c = cfg.get()
            if (beatIndex >= c.sig.beats) beatIndex = 0

            // Bar-level logic (auto mode)
            if (beatIndex == 0) {
                if (c.autoOn) {
                    when (c.autoMode) {
                        AutoMode.MUTE -> {
                            val total = max(1, c.playBars + c.muteBars)
                            mutedBar = (cycleBar % total) >= c.playBars
                            cycleBar++
                        }
                        AutoMode.ADD -> {
                            mutedBar = false
                            if (c.addUnit == AddUnit.BAR) {
                                if (barsPlayed > 0) barsSinceAdd++
                                if (barsSinceAdd >= max(1, c.addEvery)) {
                                    barsSinceAdd = 0
                                    c = bump(c)
                                }
                            }
                        }
                    }
                } else {
                    mutedBar = false; cycleBar = 0; barsSinceAdd = 0
                }
                barsPlayed++
            }
            if (c.autoOn && c.autoMode == AutoMode.ADD && c.addUnit == AddUnit.SEC) {
                if ((scheduledUntil - lastAddFrame).toDouble() >= max(1, c.addEvery).toDouble() * SR) {
                    lastAddFrame = scheduledUntil
                    c = bump(c)
                }
            } else {
                lastAddFrame = scheduledUntil
            }

            // Beat length (fractional accumulation avoids drift)
            exactPos += 60.0 / max(1, c.bpm) * SR
            val endFrame = exactPos.roundToLong()
            val n = max(1, (endFrame - scheduledUntil).toInt())

            val out = FloatArray(n * 2)
            val subs = max(1, c.subs.getOrElse(beatIndex) { 1 })
            val p = pan
            val gl = min(1f, 1f - p)
            val gr = min(1f, 1f + p)
            val nrm = normal
            val acc = accent

            for (k in 0 until subs) {
                val st = c.state(beatIndex, k)
                val off = (k.toDouble() / subs * n).toInt()
                events.add(ClickEvent(scheduledUntil + off, beatIndex, k, st, mutedBar))
                if (st == NoteState.REST || mutedBar) continue
                val src = if (st == NoteState.ACCENT) acc else nrm
                val gain = if (k == 0) 1f else 0.8f
                val len = min(src.size, n - off)
                for (i in 0 until len) {
                    val s = src[i] * gain
                    out[(off + i) * 2] += s * gl
                    out[(off + i) * 2 + 1] += s * gr
                }
            }

            // Non-blocking write so stop() is always responsive
            var o = 0
            while (o < out.size && alive(g)) {
                val w = t.write(out, o, out.size - o, AudioTrack.WRITE_NON_BLOCKING)
                if (w < 0) { running = false; break }
                if (w == 0) Thread.sleep(2) else o += w
            }
            scheduledUntil = endFrame
            beatIndex++
        }

        try { t.pause(); t.flush() } catch (_: Exception) {}
        t.release()
        track = null
    }
}
