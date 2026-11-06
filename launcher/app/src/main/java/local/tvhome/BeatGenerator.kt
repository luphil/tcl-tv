package local.tvhome

import kotlin.random.Random

/**
 * Makes new beats from each style's conventions plus controlled chance. Each style's lines give
 * every step a weight: 9 always plays, 0 never, and in between more likely the higher the
 * complexity. Later bars repeat the first with small changes, as a drummer would.
 */
object BeatGenerator {
    class Style(
        val name: String,
        val kit: String,
        val stepsPerBeat: Int,
        val swing: Float,
        val time: String,
        val kick: String,
        val snare: String,
        val ghosts: String,
        val timeRole: String = "hat_closed",
        val openHat: String = "",
        val snareRole: String = "snare",
        val percussion: Map<String, String> = emptyMap(),
    )

    /** How loud each group of drums is, 0-10; 0 leaves it out. */
    class Settings(
        val style: Style,
        val complexity: Int,
        val bars: Int,
        val levels: Map<String, Int>,
        val swing: Float,
        val seed: Int,
    )

    val GROUPS =
        linkedMapOf(
            "Kick" to listOf("kick"),
            "Snare" to listOf("snare", "clap", "rimshot", "sidestick"),
            "Hi-hat" to listOf("hat_closed", "hat_loose", "hat_open", "hat_pedal"),
            "Ride" to listOf("ride", "ride_bell"),
            "Crash" to listOf("crash", "crash2"),
            "Toms" to listOf("tom_high", "tom_mid", "tom_low"),
            "Percussion" to listOf("cowbell", "tambourine", "shaker", "claves", "triangle", "agogo_high", "agogo_low", "bongo_high", "bongo_low", "conga_high", "conga_low", "guiro", "cabasa"),
        )

    val STYLES =
        listOf(
            Style("Rock", "big", 4, 0f, "9191919191919191", "9000003090304010", "0000900000009000", "0000002000200010", openHat = "0000000000000020"),
            Style("Pop", "studio", 4, 0f, "9191919191919191", "9000002090300000", "0000900000009000", "0000000200000020", percussion = mapOf("tambourine" to "0000900000009000")),
            Style("Funk", "vintage", 4, 0f, "9595959595959595", "9030304000406020", "0000900000009000", "0300006030600304", openHat = "0020000000200040", percussion = mapOf("tambourine" to "1010101010101010")),
            Style("Soul", "vintage", 4, 0.25f, "9191919191919191", "9000304050400000", "0000900000009000", "0000003000300020", percussion = mapOf("tambourine" to "0000900000009000")),
            Style("Hip-hop", "vintage", 4, 0.4f, "9191919191919191", "9030306000604020", "0000900000009000", "0000003000300000", openHat = "0000000000000030"),
            Style("Trap", "studio", 4, 0f, "9595959595959595", "9040305000503040", "0000000090000000", "0000000000000000"),
            Style("House", "studio", 4, 0.1f, "1090109010901090", "9000900090009000", "0000900000009000", "0000000000000030", openHat = "0090009000900090", percussion = mapOf("shaker" to "3535353535353535", "conga_high" to "0020002000200030")),
            Style("Techno", "big", 4, 0f, "0090009000900090", "9000900090009000", "0000000000009000", "0000000000000000", snareRole = "rimshot", percussion = mapOf("cowbell" to "0020000200200020")),
            Style("Electronic", "808", 4, 0f, "9393939393939393", "9030304000404020", "0000900000009000", "0000000000000000", openHat = "0030003000300030", snareRole = "clap", percussion = mapOf("cowbell" to "0030003000300030")),
            Style("Trap 808", "trap", 4, 0f, "9595959595959595", "9040305000503040", "0000000090000000", "0000000000000000", snareRole = "clap"),
            Style("Techno 909", "909", 4, 0f, "0090009000900090", "9000900090009000", "0000900000009000", "0000000000000000", openHat = "0090009000900090", snareRole = "clap"),
            Style("Drum & Bass", "studio", 4, 0f, "9393939393939393", "9040000000904000", "0000900000009000", "0000006030600304", percussion = mapOf("shaker" to "2222222222222222")),
            Style("Reggae", "vintage", 4, 0.3f, "9191919191919191", "0000000090000000", "0000000090000000", "0000000000000040", snareRole = "rimshot", openHat = "0000000000000020"),
            Style("Jazz", "studio", 3, 0f, "900906900906", "100000100000", "000000000000", "003004003005", timeRole = "ride"),
            Style("Blues", "big", 3, 0f, "906906906906", "900000900300", "000900000900", "000003000003"),
            Style("Latin", "studio", 4, 0f, "9191919191919191", "9000000090030003", "0000000000000000", "0000000000000000", snareRole = "sidestick", percussion = mapOf("cowbell" to "9060906090609060", "claves" to "9009009000909000", "conga_high" to "0000002200000022", "conga_low" to "0000900000009000", "shaker" to "3333333333333333")),
            Style("Afrobeat", "vintage", 4, 0.15f, "9191919191919191", "9000006000603040", "0000900000009000", "0003006003000304", openHat = "0000000000000020", percussion = mapOf("cowbell" to "9090990909099090", "shaker" to "3333333333333333")),
            Style("Metal", "big", 4, 0f, "9090909090909090", "9090909090909090", "0000900000009000", "0000000000000000", timeRole = "ride", percussion = emptyMap()),
        )

    private const val BEATS = 4

    fun generate(settings: Settings): Beat {
        val style = settings.style
        val random = Random(settings.seed)
        val barSteps = BEATS * style.stepsPerBeat
        val steps = barSteps * settings.bars
        val chance = 0.15f + settings.complexity / 10f * 1.1f
        val on = { group: String -> (settings.levels[group] ?: 0) > 0 }
        val tracks = LinkedHashMap<String, FloatArray>()

        fun lay(
            role: String,
            line: String,
            level: Float,
            stickiness: Float = 0.75f,
        ) {
            if (line.isEmpty()) return
            val track = tracks.getOrPut(role) { FloatArray(steps) }
            val first = BooleanArray(barSteps)
            for (bar in 0 until settings.bars) {
                for (i in 0 until barSteps) {
                    val weight = line[i].digitToInt()
                    val fresh = weight == 9 || random.nextFloat() < weight / 9f * chance
                    // Later bars mostly repeat the first, with the odd change
                    val play = if (bar == 0 || weight == 9 || random.nextFloat() > stickiness) fresh else first[i]
                    if (bar == 0) first[i] = play
                    if (play) track[bar * barSteps + i] = maxOf(track[bar * barSteps + i], level)
                }
            }
        }

        // The time-keeper falls back to whichever cymbal is switched on
        val time =
            when {
                style.timeRole == "ride" && on("Ride") -> "ride"
                on("Hi-hat") -> "hat_closed"
                on("Ride") -> "ride"
                else -> null
            }
        if (time != null) {
            lay(time, style.time, 0.6f, stickiness = 0.9f)
            val track = tracks.getValue(time)
            for (i in 0 until steps) {
                if (track[i] > 0 && i % style.stepsPerBeat == 0) track[i] = 0.8f
                if (track[i] > 0 && i % barSteps % (style.stepsPerBeat * 2) == 0) track[i] = 0.9f
            }
        }
        if (on("Hi-hat") && style.openHat.isNotEmpty()) lay("hat_open", style.openHat, 0.7f)
        if (on("Hi-hat") && time == "ride" && style.stepsPerBeat == 3) lay("hat_pedal", "000900000900", 0.7f)
        if (on("Kick")) lay("kick", style.kick, 0.9f)
        if (on("Snare")) {
            lay(style.snareRole, style.snare, 1f, stickiness = 1f)
            lay("snare", style.ghosts, 0.22f)
        }
        if (on("Percussion")) style.percussion.forEach { (role, line) -> lay(role, line, 0.7f) }

        // A fill to turn the phrase round, busier with complexity
        if (settings.complexity >= 4 && settings.bars > 1) fill(tracks, style, steps, settings, random, on)
        if (on("Crash")) tracks.getOrPut("crash") { FloatArray(steps) }[0] = 0.9f

        return Beat("${style.name} ${settings.seed % 1000}", BEATS, style.stepsPerBeat, settings.swing, tracks)
    }

    private fun fill(
        tracks: MutableMap<String, FloatArray>,
        style: Style,
        steps: Int,
        settings: Settings,
        random: Random,
        on: (String) -> Boolean,
    ) {
        val length = style.stepsPerBeat * if (settings.complexity >= 8) 2 else 1
        val drums = buildList {
            if (on("Snare")) add("snare")
            if (on("Toms")) addAll(listOf("tom_high", "tom_mid", "tom_low"))
        }
        if (drums.isEmpty()) return
        val start = steps - length
        tracks.values.forEach { track -> for (i in start until steps) track[i] = 0f }
        for (i in 0 until length) {
            if (random.nextFloat() > 0.45f + settings.complexity * 0.05f) continue
            // Down the kit from high to low through the fill
            val drum = drums[i * drums.size / length]
            tracks.getOrPut(drum) { FloatArray(steps) }[start + i] = 0.6f + 0.35f * i / length
        }
    }
}
