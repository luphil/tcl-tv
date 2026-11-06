package local.tvhome

import android.content.Context

/** The drum patterns from assets/drumbeats.txt, checked by tools/check-drumbeats.py. */
class BeatLibrary(
    context: Context,
) {
    class Entry(
        val style: String,
        val kit: String,
        val beat: Beat,
    )

    val entries: List<Entry>
    val styles: List<String>

    init {
        val found = mutableListOf<Entry>()
        var header: List<String>? = null
        var tracks = LinkedHashMap<String, FloatArray>()

        fun finish() {
            val h = header ?: return
            val (beats, sub) = h[2].split(" ").map { it.toInt() }
            found += Entry(h[0], h[4], Beat(h[1], beats, sub, h[3].toFloat(), tracks))
            tracks = LinkedHashMap()
        }
        context.assets
            .open("drumbeats.txt")
            .bufferedReader()
            .forEachLine { line ->
                when {
                    line.isBlank() || line.startsWith("#") -> Unit
                    line.startsWith("@") -> {
                        finish()
                        header = line.drop(1).split("|").map { it.trim() }
                    }
                    else -> {
                        val (key, steps) = line.split(" ", limit = 2)
                        tracks[ROLES.getValue(key[0])] = FloatArray(steps.length) { velocity(steps[it]) }
                    }
                }
            }
        finish()
        entries = found
        styles = found.map { it.style }.distinct()
    }

    companion object {
        val ROLES =
            mapOf(
                'k' to "kick", 's' to "snare", 'C' to "clap", 'r' to "rimshot", 't' to "sidestick", 'h' to "hat_closed", 'l' to "hat_loose",
                'o' to "hat_open", 'p' to "hat_pedal", 'y' to "ride", 'b' to "ride_bell", 'c' to "crash", 'd' to "crash2",
                '1' to "tom_high", '2' to "tom_mid", '3' to "tom_low", 'w' to "cowbell", 'm' to "tambourine", 'e' to "shaker",
                'v' to "claves", 'n' to "triangle", 'a' to "agogo_high", 'A' to "agogo_low", 'g' to "bongo_high", 'G' to "bongo_low",
                'q' to "conga_high", 'Q' to "conga_low", 'u' to "guiro", 'f' to "cabasa",
            )

        private fun velocity(step: Char) =
            when (step) {
                'X' -> 1f
                'x' -> 0.75f
                'o' -> 0.5f
                'g' -> 0.22f
                else -> 0f
            }
    }
}
