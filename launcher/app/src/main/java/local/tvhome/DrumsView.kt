package local.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/** The drums page: settings down the left, the beat drawn as a step grid on the right. */
class DrumsView(
    context: Context,
    private val typeface: Typeface,
) : View(context) {
    class Row(
        val label: String,
        val value: () -> String,
        val change: ((Int) -> Unit)? = null,
        val press: (() -> Unit)? = null,
        val arrows: Boolean = true,
    )

    var title = "Drums"
    var tabs: List<String> = emptyList()
    var tab = 0
    var rows: List<Row> = emptyList()
    var selected = 0
    var beat: Beat? = null
    var names: List<String> = emptyList() // The beats in this style, under the settings
    var chosen = 0
    var status = ""
    var hint = ""
    var playing = false
    var playingStep: () -> Int = { -1 }
    var rowLevel: (String) -> Float = { 1f } // How far each drum is turned up, to dim quiet rows

    private val density = resources.displayMetrics.density
    private val scaled = density * resources.configuration.fontScale
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@DrumsView.typeface }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(value: Float) = value * density

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        text.textSize = size * scaled
        text.color = color
        text.textAlign = align
        canvas.drawText(value, x, y, text)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        val edge = dp(EDGE_DP)
        val right = width - edge
        label(canvas, title, edge, dp(44f), 26f, Color.WHITE)
        label(canvas, status, right, dp(44f), 14f, Color.WHITE, Paint.Align.RIGHT)

        val panelRight = width * PANEL_SHARE
        var y = dp(80f)
        val rowHeight = minOf(dp(25f), (height - dp(120f)) / maxOf(rows.size, 1))
        rows.forEachIndexed { i, row ->
            val current = i == selected
            if (current) {
                fill.color = HIGHLIGHT
                canvas.drawRoundRect(edge - dp(6f), y - rowHeight * 0.72f, panelRight, y + rowHeight * 0.28f, dp(5f), dp(5f), fill)
            }
            label(canvas, row.label, edge, y, 12f, if (current) Color.WHITE else DIM)
            val value = row.value()
            val shown = if (current && row.change != null && row.arrows) "‹  $value  ›" else value
            label(canvas, shown, panelRight - dp(6f), y, 12f, if (current) Color.WHITE else DIM, Paint.Align.RIGHT)
            y += rowHeight
        }

        // The beats in this style, around the chosen one
        if (names.isNotEmpty()) {
            y += dp(12f)
            val fit = ((height - dp(44f) - y) / dp(19f)).toInt().coerceAtLeast(1)
            val first = (chosen - fit / 2).coerceIn(0, maxOf(0, names.size - fit))
            names.drop(first).take(fit).forEachIndexed { i, name ->
                label(canvas, name, edge, y + i * dp(19f), 12f, if (first + i == chosen) ACCENT else DIM)
            }
        }

        beat?.let { drawGrid(canvas, it, panelRight + dp(24f), dp(70f), right, height - dp(44f)) }
        label(canvas, hint, edge, height - dp(EDGE_DP), 11f, DIM)
        if (playing) postInvalidateOnAnimation()
    }

    private fun drawGrid(
        canvas: Canvas,
        beat: Beat,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val roles = ORDER.filter { role -> beat.tracks[role]?.any { it > 0 } == true }
        if (roles.isEmpty()) return
        val nameRoom = dp(78f)
        val steps = beat.steps
        val cell = (right - left - nameRoom) / steps
        val height = minOf(dp(30f), (bottom - top) / roles.size)
        val now = playingStep()
        val startY = top + ((bottom - top) - height * roles.size) / 2
        if (now in 0 until steps) {
            fill.color = PLAYHEAD
            canvas.drawRect(left + nameRoom + now * cell, startY, left + nameRoom + (now + 1) * cell, startY + height * roles.size, fill)
        }
        roles.forEachIndexed { r, role ->
            val y = startY + r * height
            label(canvas, NAMES[role] ?: role, left, y + height * 0.66f, 11f, DIM)
            val velocities = beat.tracks.getValue(role)
            val loudness = rowLevel(role)
            for (s in 0 until steps) {
                val x = left + nameRoom + s * cell
                val v = velocities[s]
                fill.color = if (s / beat.stepsPerBeat % 2 == 0) CELL_A else CELL_B
                canvas.drawRoundRect(x + dp(1f), y + dp(1.5f), x + cell - dp(1f), y + height - dp(1.5f), dp(2f), dp(2f), fill)
                if (v > 0) {
                    fill.color = if (s == now) ACCENT else Color.WHITE
                    fill.alpha = ((70 + 185 * v) * (MIN_ROW_BRIGHTNESS + (1 - MIN_ROW_BRIGHTNESS) * loudness)).toInt()
                    canvas.drawRoundRect(x + dp(1f), y + dp(1.5f), x + cell - dp(1f), y + height - dp(1.5f), dp(2f), dp(2f), fill)
                }
            }
        }
    }

    companion object {
        private const val EDGE_DP = 21f
        private const val PANEL_SHARE = 0.34f
        private const val MIN_ROW_BRIGHTNESS = 0.18f
        private val BACKGROUND = Color.rgb(12, 12, 14)
        private val DIM = Color.argb(170, 255, 255, 255)
        private val HIGHLIGHT = Color.argb(45, 255, 255, 255)
        private val CELL_A = Color.argb(22, 255, 255, 255)
        private val CELL_B = Color.argb(38, 255, 255, 255)
        private val PLAYHEAD = Color.argb(60, 255, 180, 84)
        private val ACCENT = Color.rgb(255, 180, 84)
        private val NAMES =
            linkedMapOf(
                "crash" to "Crash", "crash2" to "China", "ride" to "Ride", "ride_bell" to "Ride bell", "hat_open" to "Open hat",
                "hat_loose" to "Loose hat", "hat_closed" to "Hi-hat", "hat_pedal" to "Hat pedal", "tom_high" to "High tom",
                "tom_mid" to "Mid tom", "tom_low" to "Low tom", "snare" to "Snare", "clap" to "Clap", "rimshot" to "Rimshot", "sidestick" to "Side stick",
                "kick" to "Kick", "cowbell" to "Cowbell", "tambourine" to "Tambourine", "shaker" to "Shaker", "claves" to "Claves",
                "triangle" to "Triangle", "agogo_high" to "Agogo high", "agogo_low" to "Agogo low", "bongo_high" to "Bongo high",
                "bongo_low" to "Bongo low", "conga_high" to "Conga high", "conga_low" to "Conga low", "guiro" to "Guiro", "cabasa" to "Cabasa",
            )
        private val ORDER = NAMES.keys.toList()
    }
}
