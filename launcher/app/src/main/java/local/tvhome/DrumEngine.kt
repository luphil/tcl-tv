package local.tvhome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

/** One beat: for each drum, a velocity (0 = silent) per step. */
class Beat(
    val name: String,
    val beats: Int,
    val stepsPerBeat: Int,
    val swing: Float,
    val tracks: Map<String, FloatArray>,
) {
    val steps get() = tracks.values.firstOrNull()?.size ?: (beats * stepsPerBeat)
}

/**
 * Plays beats from sampled kits, placing every hit by counting samples. Tempo, beat and kit
 * changes apply cleanly as they come round: a new beat starts on the next bar.
 */
class DrumEngine {
    @Volatile var bpm = 100

    /** Timing and level variation, 0 = machine-tight, 1 = loose. */
    @Volatile var humanise = 0.25f

    /** Per-drum volume, 1 = as recorded. Drums not listed play at 1. */
    @Volatile var volumes: Map<String, Float> = emptyMap()

    @Volatile var kit: DrumKit? = null

    @Volatile var percussion: DrumKit? = null

    @Volatile private var nextBeat: Beat? = null

    /** The step reaching the speakers now, or -1 when stopped. */
    @Volatile var playingStep = -1
        private set

    /** Whether the audio can't keep up (for checking on the TV). */
    @Volatile var underruns = 0
        private set

    @Volatile private var running = false

    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val random = Random(System.nanoTime())

    private class Voice(
        val kit: DrumKit,
        val hit: DrumKit.Hit,
        val role: String,
        val startFrame: Long,
        val gain: Float,
    ) {
        var position = 0
        var fadeLeft = -1
    }

    private class Scheduled(
        val frame: Long,
        val role: String,
        val velocity: Float,
    )

    fun play(beat: Beat) {
        nextBeat = beat
        if (running) return
        running = true
        worker = thread(name = "drums", priority = Thread.MAX_PRIORITY) { render() }
    }

    /** Swaps to a beat at the next bar, if playing. */
    fun change(beat: Beat) {
        nextBeat = beat
    }

    fun stop() {
        running = false
        worker?.join(STOP_WAIT_MS)
        worker = null
        playingStep = -1
    }

    private fun render() {
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

        val mix = FloatArray(CHUNK * 2)
        val out = ShortArray(CHUNK * 2)
        val scratch = ShortArray(CHUNK * 2)
        val voices = ArrayList<Voice>()
        val pending = ArrayList<Scheduled>()
        val steps = ArrayDeque<Pair<Long, Int>>()
        var beat = nextBeat
        var step = 0
        var frame = 0L
        var nextStep = LEAD_IN_FRAMES.toDouble()

        while (running) {
            val current = beat
            val end = frame + CHUNK
            // Work a little ahead, so swing and human timing can land either side of the grid
            while (current != null && nextStep < end + LOOKAHEAD_FRAMES) {
                if (step == 0) nextBeat?.let { beat = it }
                val active = beat ?: break
                schedule(active, step, nextStep, pending)
                steps.addLast(nextStep.toLong() to step)
                nextStep += SAMPLE_RATE * 60.0 / bpm / active.stepsPerBeat
                step = (step + 1) % active.steps
            }

            val due = pending.filter { it.frame < end }
            pending.removeAll(due)
            due.forEach { start(it, voices) }

            mix.fill(0f)
            val iterator = voices.iterator()
            while (iterator.hasNext()) {
                val voice = iterator.next()
                if (!mixVoice(voice, frame, mix, scratch)) iterator.remove()
            }
            for (i in out.indices) {
                val sample = mix[i] * MASTER
                // Gentle limiter: clean until close to full level, then rounds off
                val limited = if (abs(sample) < KNEE) sample else (if (sample > 0) 1f else -1f) * (KNEE + (1 - KNEE) * (1 - 1 / (1 + (abs(sample) - KNEE) * 4)))
                out[i] = (limited * Short.MAX_VALUE).toInt().toShort()
            }
            audio.write(out, 0, out.size)
            frame = end

            val heard = audio.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            while (steps.isNotEmpty() && steps.first().first <= heard) playingStep = steps.removeFirst().second
            underruns = audio.underrunCount
        }
        track = null
        audio.pause()
        audio.flush()
        audio.release()
    }

    private fun schedule(
        beat: Beat,
        step: Int,
        at: Double,
        pending: MutableList<Scheduled>,
    ) {
        val sway = humanise
        val stepFrames = SAMPLE_RATE * 60.0 / bpm / beat.stepsPerBeat
        // Swing pushes every second step later
        val swung = if (beat.stepsPerBeat % 2 == 0 && step % 2 == 1) stepFrames * beat.swing * MAX_SWING else 0.0
        beat.tracks.forEach { (role, velocities) ->
            val velocity = velocities[step]
            if (velocity <= 0f) return@forEach
            val push = (random.nextFloat() - 0.5f) * 2 * sway * MAX_DRIFT_FRAMES
            val level = (velocity + (random.nextFloat() - 0.5f) * sway * MAX_VELOCITY_DRIFT).coerceIn(0.05f, 1f)
            pending.add(Scheduled((at + swung + push).toLong() + LOOKAHEAD_FRAMES, role, level))
        }
    }

    private fun start(
        note: Scheduled,
        voices: MutableList<Voice>,
    ) {
        // Acoustic kits have no clap, so it falls back to their snare
        val role = if (kit?.has(note.role) == false && percussion?.has(note.role) != true) FALLBACKS[note.role] ?: note.role else note.role
        val source = (if (kit?.has(role) == true) kit else percussion?.takeIf { it.has(role) }) ?: return
        var last = -1
        val hit =
            source.hit(role, note.velocity) { count ->
                var pick = random.nextInt(count)
                if (count > 1 && pick == last) pick = (pick + 1) % count
                last = pick
                pick
            } ?: return
        // A closing hi-hat stops an open one ringing, as on a real hat; the same drum hit again damps itself
        val hats = role.startsWith("hat_")
        voices.forEach { voice ->
            val choked = (hats && voice.role.startsWith("hat_")) || (voice.role == role && voice.role !in LET_RING)
            if (choked && voice.fadeLeft < 0) voice.fadeLeft = FADE_FRAMES
        }
        if (voices.size >= MAX_VOICES) voices.removeAt(0)
        val gain = (volumes[role] ?: 1f) * (MIN_GAIN + (1 - MIN_GAIN) * note.velocity)
        voices.add(Voice(source, hit, role, note.frame, gain))
    }

    // Adds a voice into the mix; false once it has finished
    private fun mixVoice(
        voice: Voice,
        chunkStart: Long,
        mix: FloatArray,
        scratch: ShortArray,
    ): Boolean {
        val offset = maxOf(0L, voice.startFrame - chunkStart).toInt()
        val count = minOf(CHUNK - offset, voice.hit.frames - voice.position)
        if (count <= 0) return offset >= CHUNK
        voice.kit.read(voice.hit.start + voice.position, scratch, count)
        for (i in 0 until count) {
            var gain = voice.gain / Short.MAX_VALUE
            if (voice.fadeLeft >= 0) {
                gain *= voice.fadeLeft.toFloat() / FADE_FRAMES
                if (voice.fadeLeft > 0) voice.fadeLeft--
            }
            mix[(offset + i) * 2] += scratch[i * 2] * gain
            mix[(offset + i) * 2 + 1] += scratch[i * 2 + 1] * gain
        }
        voice.position += count
        return voice.position < voice.hit.frames && voice.fadeLeft != 0
    }

    private fun newTrack(): AudioTrack {
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setAudioFormat(format)
            .setBufferSizeInBytes(SAMPLE_RATE * 4 / 5) // 200 ms, cover for slow storage reads
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "TVHome"
        private const val SAMPLE_RATE = 48000
        private const val CHUNK = 256
        private const val LEAD_IN_FRAMES = 7200
        private const val LOOKAHEAD_FRAMES = 1440
        private const val MAX_SWING = 0.34
        private const val MAX_DRIFT_FRAMES = 480
        private const val MAX_VELOCITY_DRIFT = 0.16f
        private const val MAX_VOICES = 32
        private const val FADE_FRAMES = 480
        private const val MIN_GAIN = 0.55f
        private const val MASTER = 0.6f
        private const val KNEE = 0.8f
        private const val STOP_WAIT_MS = 400L
        private val LET_RING = setOf("crash", "crash2", "ride", "ride_bell")
        private val FALLBACKS = mapOf("clap" to "snare")
    }
}
