package local.tvhome

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A metronome, opened with a code typed on the homescreen. OK starts and stops it, up/down
 * choose tempo, beats per bar or sound, left/right change the chosen one, the number buttons
 * type a tempo at any time, and Back leaves.
 */
class MetronomeActivity : Activity() {
    private val engine = ClickEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("metronome", MODE_PRIVATE) }

    private lateinit var tiles: Tiles
    private lateinit var tempoText: TextView
    private lateinit var tempoLabel: TextView
    private lateinit var barText: TextView
    private lateinit var soundText: TextView
    private lateinit var stateText: TextView
    private lateinit var dots: LinearLayout

    private var bpm = 120
    private var beatsPerBar = 4
    private var sound = ClickSound.WOOD

    // The row left/right change: ROW_TEMPO, ROW_BEATS or ROW_SOUND
    private var row = ROW_TEMPO
    private val endPreview = Runnable { engine.stop() }
    private val typed = StringBuilder()
    private val applyTyped = Runnable { commitTyped() }

    // Lights the dots as each beat reaches the speakers, not when it's generated
    private val watchBeats =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                engine.heardBeat()?.let { flash(it) }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bpm = prefs.getInt(KEY_BPM, bpm).coerceIn(MIN_BPM, MAX_BPM)
        beatsPerBar = prefs.getInt(KEY_BEATS, beatsPerBar).coerceIn(1, MAX_BEATS)
        sound = ClickSound.entries.firstOrNull { it.name == prefs.getString(KEY_SOUND, null) } ?: sound

        val config = Config.load()
        tiles = Tiles(this, config.typeface(config.fontWeight))
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
        tempoText = tiles.text("", 120f, Color.WHITE).apply { typeface = config.typeface(config.clockWeight) }
        column.addView(tempoText)
        tempoLabel = tiles.text("", 14f, DIM_TEXT)
        column.addView(tempoLabel)
        dots =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
            }
        column.addView(
            dots,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tiles.dp(DOT_ROW_DP)).apply { setMargins(0, tiles.dp(28), 0, tiles.dp(10)) },
        )
        barText = tiles.text("", 14f, DIM_TEXT)
        column.addView(barText)
        soundText = tiles.text("", 14f, DIM_TEXT).apply { setPadding(0, tiles.dp(12), 0, 0) }
        column.addView(soundText)
        stateText = tiles.text("", 16f, Color.WHITE).apply { setPadding(0, tiles.dp(24), 0, 0) }
        column.addView(stateText)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 12, 14)) }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(
            tiles.text("OK  start / stop     Up / Down  choose     Left / Right  change     0-9  type a tempo     Back  exit", 11f, DIM_TEXT),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = tiles.dp(21) },
        )
        setContentView(root)
        showTempo()
        showDots()
        showRows()
        showState()
    }

    // Never keeps ticking once it's off screen
    override fun onPause() {
        super.onPause()
        stop()
        prefs.edit().putInt(KEY_BPM, bpm).putInt(KEY_BEATS, beatsPerBar).putString(KEY_SOUND, sound.name).apply()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.repeatCount == 0) typeDigit('0' + (keyCode - KeyEvent.KEYCODE_0))
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                commitTyped()
                if (event.repeatCount == 0) toggle()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                commitTyped()
                change(-1, event.repeatCount > 0)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                commitTyped()
                change(1, event.repeatCount > 0)
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                commitTyped()
                if (event.repeatCount == 0) selectRow(row - 1)
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                commitTyped()
                if (event.repeatCount == 0) selectRow(row + 1)
            }

            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun selectRow(value: Int) {
        row = value.coerceIn(ROW_TEMPO, ROW_SOUND)
        showRows()
    }

    // Holding the button only runs the tempo along
    private fun change(
        step: Int,
        held: Boolean,
    ) {
        when {
            row == ROW_TEMPO -> setTempo(bpm + step)
            held -> return
            row == ROW_BEATS -> setBeats(beatsPerBar + step)
            else -> setSound(step)
        }
    }

    private fun toggle() {
        if (playing()) stop() else start()
    }

    private fun playing() = stateText.tag == true

    private fun start() {
        handler.removeCallbacks(endPreview)
        engine.stop()
        engine.bpm = bpm
        engine.beatsPerBar = beatsPerBar
        engine.sound = sound
        engine.start()
        stateText.tag = true
        showState()
        Choreographer.getInstance().postFrameCallback(watchBeats)
    }

    private fun stop() {
        handler.removeCallbacks(endPreview)
        Choreographer.getInstance().removeFrameCallback(watchBeats)
        engine.stop()
        stateText.tag = false
        showState()
        handler.removeCallbacks(applyTyped)
        typed.clear()
        showTempo()
    }

    private fun setTempo(value: Int) {
        bpm = value.coerceIn(MIN_BPM, MAX_BPM)
        engine.bpm = bpm
        showTempo()
    }

    private fun setBeats(value: Int) {
        beatsPerBar = value.coerceIn(1, MAX_BEATS)
        engine.beatsPerBar = beatsPerBar
        showDots()
        showRows()
    }

    private fun setSound(step: Int) {
        val sounds = ClickSound.entries
        sound = sounds[(sound.ordinal + step + sounds.size) % sounds.size]
        engine.sound = sound
        showRows()
        if (!playing()) preview()
    }

    // One click of the sound, so it can be chosen by ear while stopped
    private fun preview() {
        handler.removeCallbacks(endPreview)
        engine.stop()
        engine.bpm = bpm
        engine.beatsPerBar = beatsPerBar
        engine.sound = sound
        engine.start()
        handler.postDelayed(endPreview, PREVIEW_MS)
    }

    // A tempo is complete at three digits, or after a short pause
    private fun typeDigit(digit: Char) {
        handler.removeCallbacks(applyTyped)
        typed.append(digit)
        tempoText.text = typed
        tempoText.alpha = TYPING_ALPHA
        if (typed.length == 3) commitTyped() else handler.postDelayed(applyTyped, TYPING_PAUSE_MS)
    }

    private fun commitTyped() {
        handler.removeCallbacks(applyTyped)
        if (typed.isEmpty()) return
        val value = typed.toString().toInt()
        typed.clear()
        setTempo(value)
    }

    private fun showTempo() {
        tempoText.text = bpm.toString()
        tempoText.alpha = 1f
    }

    // The chosen row is bright and shows it can be changed
    private fun showRows() {
        show(tempoLabel, "BPM", ROW_TEMPO)
        show(barText, if (beatsPerBar == 1) "1 beat per bar" else "$beatsPerBar beats per bar", ROW_BEATS)
        show(soundText, sound.label, ROW_SOUND)
    }

    private fun show(
        view: TextView,
        value: String,
        thisRow: Int,
    ) {
        view.text = if (row == thisRow) "‹   $value   ›" else value
        view.setTextColor(if (row == thisRow) Color.WHITE else DIM_TEXT)
    }

    private fun showDots() {
        dots.removeAllViews()
        for (i in 0 until beatsPerBar) {
            val size = tiles.dp(if (i == 0 && beatsPerBar > 1) ACCENT_DOT_DP else DOT_DP)
            dots.addView(
                View(this).apply {
                    alpha = DOT_REST_ALPHA
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.WHITE)
                        }
                },
                LinearLayout.LayoutParams(size, size).apply { setMargins(tiles.dp(9), 0, tiles.dp(9), 0) },
            )
        }
    }

    private fun showState() {
        stateText.text = if (playing()) "Playing" else "Press OK to start"
    }

    private fun flash(index: Int) {
        val dot = dots.getChildAt(index) ?: return
        val fade = (60_000L / bpm * 0.8f).toLong().coerceAtMost(MAX_FLASH_MS)
        dot.animate().cancel()
        dot.alpha = 1f
        dot.scaleX = FLASH_SCALE
        dot.scaleY = FLASH_SCALE
        dot
            .animate()
            .alpha(DOT_REST_ALPHA)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(fade)
            .start()
    }

    companion object {
        private const val KEY_BPM = "bpm"
        private const val KEY_BEATS = "beats"
        private const val KEY_SOUND = "sound"
        private const val ROW_TEMPO = 0
        private const val ROW_BEATS = 1
        private const val ROW_SOUND = 2
        private const val PREVIEW_MS = 450L
        private const val MIN_BPM = 30
        private const val MAX_BPM = 300
        private const val MAX_BEATS = 12
        private const val TYPING_PAUSE_MS = 1200L
        private const val TYPING_ALPHA = 0.6f
        private const val DOT_DP = 16
        private const val ACCENT_DOT_DP = 24
        private const val DOT_ROW_DP = 36
        private const val DOT_REST_ALPHA = 0.25f
        private const val FLASH_SCALE = 1.35f
        private const val MAX_FLASH_MS = 350L
        private val DIM_TEXT = Color.argb(170, 255, 255, 255)
    }
}
