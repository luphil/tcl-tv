package local.tvhome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The metronome's sounds, each with a higher or stronger version for the first beat of the bar. */
enum class ClickSound(
    val label: String,
) {
    BEEP("Beep"),
    WOOD("Wood block"),
    TICK("Tick"),
    COWBELL("Cowbell"),
    ;

    val accent by lazy { render(accented = true) }
    val normal by lazy { render(accented = false) }

    private fun render(accented: Boolean): ShortArray =
        when (this) {
            BEEP -> tones(0.04, if (accented) 0.8 else 0.65, Partial(if (accented) 1760.0 else 1100.0, 0.008, 1.0))
            WOOD -> {
                val hz = if (accented) 1250.0 else 950.0
                tones(0.06, if (accented) 0.9 else 0.7, Partial(hz, 0.012, 1.0), Partial(hz * 2.45, 0.005, 0.5))
            }
            TICK -> noise(0.02, if (accented) 0.004 else 0.0025, if (accented) 0.95 else 0.6)
            COWBELL -> {
                val pitch = if (accented) 1.2 else 1.0
                tones(0.14, if (accented) 0.8 else 0.55, Partial(845.0 * pitch, 0.045, 0.6), Partial(587.0 * pitch, 0.045, 0.4))
            }
        }

    private class Partial(
        val hz: Double,
        val decay: Double,
        val gain: Double,
    )

    // Tones that die away quickly
    private fun tones(
        seconds: Double,
        level: Double,
        vararg partials: Partial,
    ) = normalised(
        DoubleArray((SAMPLE_RATE * seconds).toInt()) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            partials.sumOf { sin(2 * PI * it.hz * t) * exp(-t / it.decay) * it.gain }
        },
        level,
    )

    // A burst of bright noise (each sample minus the last keeps the high frequencies)
    private fun noise(
        seconds: Double,
        decay: Double,
        level: Double,
    ): ShortArray {
        val random = Random(7)
        var last = 0.0
        return normalised(
            DoubleArray((SAMPLE_RATE * seconds).toInt()) { i ->
                val sample = random.nextDouble(-1.0, 1.0)
                val bright = sample - last
                last = sample
                bright * exp(-i.toDouble() / SAMPLE_RATE / decay)
            },
            level,
        )
    }

    private fun normalised(
        samples: DoubleArray,
        level: Double,
    ): ShortArray {
        val peak = samples.maxOf { abs(it) }
        return ShortArray(samples.size) { (samples[it] / peak * level * Short.MAX_VALUE).toInt().toShort() }
    }

    companion object {
        const val SAMPLE_RATE = 48000
    }
}

/**
 * Metronome audio. Clicks are placed by counting samples rather than with timers, so the
 * beat stays steady however busy the TV is. Tempo, bar length and sound apply from the next beat.
 */
class ClickEngine {
    @Volatile var bpm = 120

    @Volatile var beatsPerBar = 4

    @Volatile var sound = ClickSound.BEEP

    @Volatile private var running = false

    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val beats = ConcurrentLinkedQueue<Beat>()

    private class Beat(
        val frame: Long,
        val index: Int,
    )

    fun start() {
        if (running) return
        running = true
        beats.clear()
        worker = thread(name = "metronome", priority = Thread.MAX_PRIORITY) { play() }
    }

    fun stop() {
        running = false
        worker?.join(STOP_WAIT_MS)
        worker = null
    }

    /** The newest beat that has reached the speakers since the last call (its place in the bar), if any. */
    fun heardBeat(): Int? {
        val played =
            try {
                track?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: return null
            } catch (ex: IllegalStateException) {
                return null // just released
            }
        var heard: Int? = null
        while (true) {
            val beat = beats.peek() ?: break
            if (beat.frame > played) break
            beats.poll()
            heard = beat.index
        }
        return heard
    }

    private fun play() {
        val audio =
            try {
                newTrack()
            } catch (ex: RuntimeException) {
                Log.e(TAG, "Can't open audio", ex)
                running = false
                return
            }
        track = audio
        audio.play()

        val chunk = ShortArray(CHUNK_FRAMES)
        var sounding: ShortArray? = null
        var soundingAt = 0
        var frame = 0L
        var index = 0
        // A moment's lead-in, as HDMI audio can swallow the very start of a sound
        var nextBeat = SAMPLE_RATE * LEAD_IN_SECONDS

        while (running) {
            for (i in chunk.indices) {
                if (frame >= nextBeat) {
                    val bar = beatsPerBar
                    if (index >= bar) index = 0
                    beats.add(Beat(frame, index))
                    sounding = sound.let { if (index == 0 && bar > 1) it.accent else it.normal }
                    soundingAt = 0
                    index++
                    nextBeat += SAMPLE_RATE * 60.0 / bpm
                }
                val click = sounding
                chunk[i] = if (click != null && soundingAt < click.size) click[soundingAt++] else 0
                frame++
            }
            audio.write(chunk, 0, chunk.size)
        }
        track = null
        audio.pause()
        audio.flush()
        audio.release()
    }

    private fun newTrack(): AudioTrack {
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).setAudioFormat(format)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "TVHome"
        private const val SAMPLE_RATE = ClickSound.SAMPLE_RATE
        private const val CHUNK_FRAMES = 240
        private const val LEAD_IN_SECONDS = 0.15
        private const val STOP_WAIT_MS = 300L
    }
}
