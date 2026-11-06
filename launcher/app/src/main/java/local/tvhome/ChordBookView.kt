package local.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Draws a page of the chord book: chord boxes by note or by type, or whole-neck scales by note. */
class ChordBookView(
    context: Context,
    private val book: ChordBook,
    private val typeface: Typeface,
) : View(context) {
    enum class Mode(
        val label: String,
    ) {
        BY_NOTE("By note"),
        BY_TYPE("By chord"),
        SCALES("Scales"),
    }

    var mode = Mode.BY_NOTE
    var note = 0
    var type = 0
    var position = ChordBook.Position.COMMON
    var scalePage = 0

    val scalePages get() = (book.scales.size + SCALES_PER_PAGE - 1) / SCALES_PER_PAGE

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@ChordBookView.typeface }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(value: Float) = value * density

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        text.textSize = sizeSp * scaledDensity
        text.color = color
        text.textAlign = align
        canvas.drawText(value, x, y, text)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        val edge = dp(EDGE_DP)
        val right = width - edge

        val title =
            when (mode) {
                Mode.BY_NOTE -> "${book.notes[note]} chords"
                Mode.BY_TYPE -> "${book.chords[type].name} chords"
                Mode.SCALES -> "${book.notes[note]} scales"
            }
        label(canvas, title, edge, dp(44f), 26f, Color.WHITE)

        // The three views, the current one bright
        var x = right
        Mode.entries.reversed().forEach { entry ->
            label(canvas, entry.label, x, dp(40f), 13f, if (entry == mode) Color.WHITE else DIM, Paint.Align.RIGHT)
            x -= text.measureText(entry.label) + dp(22f)
        }

        drawPicker(canvas, edge, right, dp(72f))

        val top = dp(92f)
        val bottom = height - dp(44f)
        when (mode) {
            Mode.BY_NOTE -> drawChords(canvas, edge, top, right, bottom, book.chords.map { it to note }, CHORD_COLUMNS)
            Mode.BY_TYPE -> drawChords(canvas, edge, top, right, bottom, book.notes.indices.map { book.chords[type] to it }, CHORD_COLUMNS)
            Mode.SCALES -> drawScales(canvas, edge, top, right, bottom)
        }

        val hint =
            if (mode == Mode.SCALES) {
                "Left / Right  note     Up / Down  view     OK  page ${scalePage + 1} of $scalePages     Back  exit"
            } else {
                "Left / Right  ${if (mode == Mode.BY_NOTE) "note" else "chord"}     Up / Down  view     OK  change position     Back  exit"
            }
        label(canvas, hint, edge, height - dp(EDGE_DP), 11f, DIM)
        if (mode != Mode.SCALES) label(canvas, position.label, right, height - dp(EDGE_DP), 11f, Color.WHITE, Paint.Align.RIGHT)
    }

    // The row of notes or chord types being stepped through
    private fun drawPicker(
        canvas: Canvas,
        left: Float,
        right: Float,
        y: Float,
    ) {
        val items = if (mode == Mode.BY_TYPE) book.chords.map { it.suffix.ifEmpty { "maj" } } else book.notes
        val selected = if (mode == Mode.BY_TYPE) type else note
        val step = (right - left) / items.size
        items.forEachIndexed { i, item ->
            val centre = left + step * (i + 0.5f)
            if (i == selected) {
                fill.color = HIGHLIGHT
                canvas.drawRoundRect(centre - step / 2 + dp(2f), y - dp(13f), centre + step / 2 - dp(2f), y + dp(6f), dp(5f), dp(5f), fill)
            }
            label(canvas, item, centre, y, if (mode == Mode.BY_TYPE) 10f else 12f, if (i == selected) Color.WHITE else DIM, Paint.Align.CENTER)
        }
    }

    private fun drawChords(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        chords: List<Pair<ChordBook.Chord, Int>>,
        columns: Int,
    ) {
        val rows = (chords.size + columns - 1) / columns
        val cellW = (right - left) / columns
        val cellH = (bottom - top) / rows
        chords.forEachIndexed { i, (chord, root) ->
            val cell = RectF(left + cellW * (i % columns), top + cellH * (i / columns), 0f, 0f)
            cell.right = cell.left + cellW
            cell.bottom = cell.top + cellH
            drawChord(canvas, cell, book.notes[root] + chord.suffix, book.shape(chord, root, position), root)
        }
    }

    // The classic chord box: strings upright, low E on the left, nut or fret number at the top
    private fun drawChord(
        canvas: Canvas,
        cell: RectF,
        name: String,
        frets: List<Int?>,
        root: Int,
    ) {
        val nameSize = (cell.height() / density * 0.13f).coerceIn(12f, 20f)
        label(canvas, name, cell.centerX(), cell.top + nameSize * scaledDensity, nameSize, Color.WHITE, Paint.Align.CENTER)

        val markRoom = dp(11f)
        val gridTop = cell.top + nameSize * scaledDensity + dp(6f) + markRoom
        val gridHeight = cell.bottom - gridTop - dp(9f)
        val fretGap = gridHeight / SHOWN_FRETS
        val stringGap = minOf(fretGap * 0.95f, cell.width() * 0.6f / 5)
        val gridLeft = cell.centerX() - stringGap * 2.5f

        val dot = minOf(stringGap, fretGap) * 0.4f
        val pressed = frets.filterNotNull().filter { it > 0 }
        val start = if (pressed.isEmpty() || pressed.max() <= SHOWN_FRETS) 1 else pressed.min()

        line.color = GRID
        line.strokeWidth = dp(1f)
        for (s in 0..5) canvas.drawLine(gridLeft + stringGap * s, gridTop, gridLeft + stringGap * s, gridTop + fretGap * SHOWN_FRETS, line)
        for (f in 0..SHOWN_FRETS) canvas.drawLine(gridLeft, gridTop + fretGap * f, gridLeft + stringGap * 5, gridTop + fretGap * f, line)
        if (start == 1) {
            line.color = Color.WHITE
            line.strokeWidth = dp(3f)
            canvas.drawLine(gridLeft - dp(0.5f), gridTop, gridLeft + stringGap * 5 + dp(0.5f), gridTop, line)
        } else {
            label(canvas, "${start}fr", gridLeft - dot - dp(5f), gridTop + fretGap * 0.68f, 10f, DIM, Paint.Align.RIGHT)
        }

        drawBarre(canvas, frets, start, gridLeft, gridTop, stringGap, fretGap, dot)
        frets.forEachIndexed { s, fret ->
            val x = gridLeft + stringGap * s
            when (fret) {
                null -> label(canvas, "×", x, gridTop - dp(4f), 11f, DIM, Paint.Align.CENTER)
                0 -> {
                    line.color = if ((ChordBook.TUNING[s] - root).mod(12) == 0) ROOT else DIM
                    line.strokeWidth = dp(1.2f)
                    canvas.drawCircle(x, gridTop - dp(7.5f), dp(3f), line)
                }
                else -> {
                    fill.color = if ((ChordBook.TUNING[s] + fret - root).mod(12) == 0) ROOT else Color.WHITE
                    canvas.drawCircle(x, gridTop + fretGap * (fret - start + 0.5f), dot, fill)
                }
            }
        }
    }

    // One finger across the lowest fret, for shapes with more notes than fingers
    // (and when nothing between its ends is open or muted)
    private fun drawBarre(
        canvas: Canvas,
        frets: List<Int?>,
        start: Int,
        gridLeft: Float,
        gridTop: Float,
        stringGap: Float,
        fretGap: Float,
        dot: Float,
    ) {
        val pressed = frets.filterNotNull().filter { it > 0 }
        if (pressed.size <= FINGERS) return
        val low = pressed.min()
        val first = frets.indexOf(low)
        val last = frets.lastIndexOf(low)
        if (last == first || (first..last).any { (frets[it] ?: 0) < low }) return
        val y = gridTop + fretGap * (low - start + 0.5f)
        fill.color = Color.WHITE
        canvas.drawRoundRect(gridLeft + stringGap * first - dot, y - dot, gridLeft + stringGap * last + dot, y + dot, dot, dot, fill)
    }

    private fun drawScales(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val cellW = (right - left) / SCALE_COLUMNS
        val cellH = (bottom - top) / (SCALES_PER_PAGE / SCALE_COLUMNS)
        val gap = dp(18f)
        book.scales.drop(scalePage * SCALES_PER_PAGE).take(SCALES_PER_PAGE).forEachIndexed { i, scale ->
            val x = left + cellW * (i % SCALE_COLUMNS)
            val y = top + cellH * (i / SCALE_COLUMNS)
            drawScale(canvas, RectF(x, y, x + cellW - gap, y + cellH), scale)
        }
    }

    // The whole neck to the 12th fret, high E at the top as in tab; open strings left of the nut
    private fun drawScale(
        canvas: Canvas,
        cell: RectF,
        scale: ChordBook.Scale,
    ) {
        label(canvas, scale.name, cell.left, cell.top + dp(11f), 12f, Color.WHITE)
        label(canvas, book.spell(scale, note).joinToString(" "), cell.right, cell.top + dp(11f), 10f, DIM, Paint.Align.RIGHT)

        val boardTop = cell.top + dp(20f)
        val stringGap = (cell.bottom - dp(FRET_NUMBER_ROOM_DP) - boardTop) / 5
        val openRoom = dp(12f)
        val nut = cell.left + openRoom
        val fretGap = (cell.right - nut) / NECK_FRETS
        val dot = minOf(stringGap, fretGap) * 0.33f

        line.color = GRID
        line.strokeWidth = dp(1f)
        for (s in 0..5) canvas.drawLine(nut, boardTop + stringGap * s, cell.right, boardTop + stringGap * s, line)
        for (f in 1..NECK_FRETS) canvas.drawLine(nut + fretGap * f, boardTop, nut + fretGap * f, boardTop + stringGap * 5, line)
        line.color = Color.WHITE
        line.strokeWidth = dp(2.5f)
        canvas.drawLine(nut, boardTop, nut, boardTop + stringGap * 5, line)
        // Fret numbers sit clear of the dots on the bottom string
        val numberBaseline = boardTop + stringGap * 5 + dot + dp(9f)
        for (f in FRET_MARKERS) {
            label(canvas, f.toString(), nut + fretGap * (f - 0.5f), numberBaseline, 8f, DIM, Paint.Align.CENTER)
        }

        val inScale = scale.semitones.map { (it + note).mod(12) }.toSet()
        for (s in 0..5) {
            val y = boardTop + stringGap * (5 - s)
            for (f in 0..NECK_FRETS) {
                val pitch = (ChordBook.TUNING[s] + f).mod(12)
                if (pitch !in inScale) continue
                fill.color = if (pitch == note) ROOT else Color.WHITE
                canvas.drawCircle(if (f == 0) nut - openRoom / 2 else nut + fretGap * (f - 0.5f), y, dot, fill)
            }
        }
    }

    companion object {
        private const val EDGE_DP = 21f
        private const val CHORD_COLUMNS = 6
        private const val SHOWN_FRETS = 5
        private const val FINGERS = 4
        private const val SCALE_COLUMNS = 3
        private const val SCALES_PER_PAGE = 15
        private const val NECK_FRETS = 12
        private const val FRET_NUMBER_ROOM_DP = 17f
        private val FRET_MARKERS = listOf(3, 5, 7, 9, 12)
        private val BACKGROUND = Color.rgb(12, 12, 14)
        private val DIM = Color.argb(170, 255, 255, 255)
        private val GRID = Color.argb(95, 255, 255, 255)
        private val HIGHLIGHT = Color.argb(45, 255, 255, 255)
        private val ROOT = Color.rgb(255, 180, 84)
    }
}
