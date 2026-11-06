package local.tvhome

import android.content.Context
import org.json.JSONObject

/**
 * Chord shapes and scales, from assets/chordbook.json. Shapes are stored once per chord type
 * and moved up the neck for each root; tools/check-chordbook.py checks all of them.
 */
class ChordBook(
    context: Context,
) {
    class Chord(
        val suffix: String,
        val name: String,
        private val eShape: List<Int?>,
        private val aShape: List<Int?>,
        val open: Map<String, List<Int?>>,
    ) {
        fun moved(
            root: Int,
            onAString: Boolean,
        ): List<Int?> {
            val shape = if (onAString) aShape else eShape
            val fret = (root - if (onAString) PC_A else PC_E).mod(12)
            val lowest = shape.filterNotNull().min()
            val start = if (fret + lowest < 0) fret + 12 else fret
            return shape.map { it?.plus(start) }
        }
    }

    class Scale(
        val name: String,
        val degrees: List<String>,
    ) {
        val semitones = degrees.map { semitone(it) }
    }

    val notes: List<String>
    val chords: List<Chord>
    val scales: List<Scale>

    init {
        val json = JSONObject(context.assets.open("chordbook.json").bufferedReader().use { it.readText() })
        val noteList = json.getJSONArray("notes")
        notes = (0 until noteList.length()).map { noteList.getString(it) }
        val chordList = json.getJSONArray("chords")
        chords =
            (0 until chordList.length()).map { i ->
                val chord = chordList.getJSONObject(i)
                val open = chord.optJSONObject("open")
                Chord(
                    suffix = chord.getString("suffix"),
                    name = chord.getString("name"),
                    eShape = chord.getString("e").split(" ").map { it.toIntOrNull() },
                    aShape = chord.getString("a").split(" ").map { it.toIntOrNull() },
                    open = open?.keys()?.asSequence()?.associateWith { key -> open.getString(key).map { it.digitToIntOrNull() } } ?: emptyMap(),
                )
            }
        val scaleList = json.getJSONArray("scales")
        scales =
            (0 until scaleList.length()).map { i ->
                val scale = scaleList.getJSONObject(i)
                Scale(scale.getString("name"), scale.getString("degrees").split(" "))
            }
    }

    /** The shape for a chord: frets low E to high E, null = not played. */
    fun shape(
        chord: Chord,
        root: Int,
        position: Position,
    ): List<Int?> =
        when (position) {
            Position.E_SHAPE -> chord.moved(root, onAString = false)
            Position.A_SHAPE -> chord.moved(root, onAString = true)
            Position.COMMON ->
                chord.open[notes[root]] ?: run {
                    val e = chord.moved(root, onAString = false)
                    val a = chord.moved(root, onAString = true)
                    if (e.first()!! <= a[1]!!) e else a
                }
        }

    /** A scale's notes on a root, spelled with the right letters (Eb Dorian has Gb, not F#). */
    fun spell(
        scale: Scale,
        root: Int,
    ): List<String> {
        val rootLetter = LETTERS.indexOf(notes[root][0])
        return scale.degrees.mapIndexed { i, degree ->
            val letter = (rootLetter + degree.last().digitToInt() - 1) % 7
            val shift = (root + scale.semitones[i] - NATURALS[letter] + 6).mod(12) - 6
            LETTERS[letter] + if (shift >= 0) "#".repeat(shift) else "b".repeat(-shift)
        }
    }

    enum class Position(
        val label: String,
    ) {
        COMMON("Common shapes"),
        E_SHAPE("Root on 6th string"),
        A_SHAPE("Root on 5th string"),
    }

    companion object {
        // Pitch classes of the open strings, low E to high E
        val TUNING = listOf(4, 9, 2, 7, 11, 4)
        private const val PC_E = 4
        private const val PC_A = 9
        private const val LETTERS = "CDEFGAB"
        private val NATURALS = listOf(0, 2, 4, 5, 7, 9, 11)

        private fun semitone(degree: String): Int =
            NATURALS[degree.last().digitToInt() - 1] + degree.count { it == '#' } - degree.count { it == 'b' }
    }
}
