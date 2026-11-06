package local.tvhome

import kotlin.random.Random

/**
 * Makes new beats from each style's conventions plus controlled chance. Each style's lines give
 * every step a weight: 9 always plays, 0 never, and in between more likely the higher the
 * complexity. Later bars repeat the first with small changes, as a drummer would. Every drum
 * has its own randomness and its own busyness, so changing one leaves the others as they were.
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

    /** How busy each group of drums is, 0-10: 0 leaves it out, 5 is the style's natural amount. */
    class Settings(
        val style: Style,
        val complexity: Int,
        val bars: Int,
        val busy: Map<String, Int>,
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
    private const val NATURAL = 5

    /** Where each drum's busyness starts for a style. */
    fun defaultBusy(style: Style): Map<String, Int> =
        mapOf(
            "Kick" to NATURAL,
            "Snare" to NATURAL,
            "Hi-hat" to NATURAL,
            "Ride" to if (style.timeRole == "ride") NATURAL else 0,
            "Crash" to 1,
            "Toms" to 3,
            "Percussion" to if (style.percussion.isEmpty()) 0 else NATURAL,
        )

    fun generate(settings: Settings): Beat {
        val style = settings.style
        val sub = style.stepsPerBeat
        val barSteps = BEATS * sub
        val steps = barSteps * settings.bars
        val chance = 0.15f + settings.complexity / 10f * 1.1f
        val busy = { group: String -> settings.busy[group] ?: 0 }
        val tracks = LinkedHashMap<String, FloatArray>()

        // Each drum draws from its own randomness, so one drum changing leaves the rest alone
        fun randomFor(role: String) = Random(settings.seed * 31 + role.hashCode())

        fun lay(
            role: String,
            line: String,
            level: Float,
            group: String,
            stickiness: Float = 0.75f,
            exact: Boolean = false,
            extras: Boolean = false,
        ) {
            if (line.isEmpty()) return
            val random = randomFor(role)
            val amount = busy(group)
            val scale = (amount / NATURAL.toFloat()).let { it * it }
            val track = tracks.getOrPut(role) { FloatArray(steps) }
            val first = BooleanArray(barSteps)
            for (bar in 0 until settings.bars) {
                for (i in 0 until barSteps) {
                    val weight = line[i].digitToInt()
                    val odds =
                        when {
                            weight == 9 -> 1f
                            exact -> weight / 9f
                            weight == 0 -> if (extras) maxOf(0, amount - NATURAL) / NATURAL.toFloat() * 0.3f else 0f
                            else -> weight / 9f * chance * scale
                        }
                    val fresh = random.nextFloat() < odds
                    // Later bars mostly repeat the first, with the odd change
                    val play = if (bar == 0 || weight == 9 || random.nextFloat() > stickiness) fresh else first[i]
                    if (bar == 0) first[i] = play
                    if (play) track[bar * barSteps + i] = maxOf(track[bar * barSteps + i], level)
                }
            }
        }

        fun accent(role: String) {
            val track = tracks[role] ?: return
            for (i in 0 until steps) {
                if (track[i] > 0 && i % sub == 0) track[i] = 0.8f
                if (track[i] > 0 && i % (sub * 2) == 0) track[i] = 0.9f
            }
        }

        val rideKeepsTime = style.timeRole == "ride"
        if (busy("Hi-hat") > 0) {
            if (rideKeepsTime) {
                // With the ride keeping time, the hat is played with the foot on 2 and 4
                lay("hat_pedal", timeLine(busy("Hi-hat"), sub, backbeat(sub)), 0.7f, "Hi-hat", stickiness = 0.9f, exact = true)
            } else {
                lay("hat_closed", timeLine(busy("Hi-hat"), sub, style.time), 0.6f, "Hi-hat", stickiness = 0.9f, exact = true)
                accent("hat_closed")
                lay("hat_open", style.openHat, 0.7f, "Hi-hat")
            }
        }
        if (busy("Ride") > 0) {
            lay("ride", timeLine(busy("Ride"), sub, if (rideKeepsTime) style.time else eighths(sub)), 0.6f, "Ride", stickiness = 0.9f, exact = true)
            accent("ride")
        }
        if (busy("Kick") > 0) lay("kick", style.kick, 0.9f, "Kick", extras = true)
        if (busy("Snare") > 0) {
            lay(style.snareRole, style.snare, 1f, "Snare", stickiness = 1f)
            lay("snare", style.ghosts, 0.22f, "Snare", extras = true)
        }
        if (busy("Percussion") > 0) {
            val parts = style.percussion.ifEmpty { if (sub == 3) TRIPLET_PERCUSSION else STRAIGHT_PERCUSSION }
            parts.forEach { (role, line) -> lay(role, line, 0.7f, "Percussion", extras = false) }
        }
        if (busy("Toms") > NATURAL) lay("tom_low", "0".repeat(barSteps), 0.7f, "Toms", extras = true)

        // A fill to turn the phrase round, longer with complexity and busier toms
        if (settings.complexity >= 4 && settings.bars > 1) fill(tracks, settings, sub, steps, randomFor("fill"))
        crashes(tracks, busy("Crash"), steps, barSteps, sub)

        return Beat("${style.name} ${settings.seed % 1000}", BEATS, sub, settings.swing, tracks)
    }

    // Beats 2 and 4
    private fun backbeat(sub: Int) = line(sub) { i -> if (i % (sub * 2) == sub) 9 else 0 }

    private fun eighths(sub: Int) = line(sub) { i -> if (i % sub == 0 || (sub == 4 && i % 2 == 0) || (sub == 3 && i % 3 == 2)) 9 else 0 }

    private fun line(
        sub: Int,
        weight: (Int) -> Int,
    ) = (0 until BEATS * sub).joinToString("") { weight(it).toString() }

    /** A time-keeping cymbal at a busyness: from half notes, through the natural line, to every step. */
    private fun timeLine(
        busy: Int,
        sub: Int,
        natural: String,
    ): String =
        when {
            busy == NATURAL -> natural
            busy == 1 -> line(sub) { i -> if (i % (sub * 2) == 0) 9 else 0 }
            busy == 2 -> line(sub) { i -> if (i % sub == 0) 9 else 0 }
            busy == 3 -> line(sub) { i -> if (i % sub == 0) 9 else if (eighths(sub)[i] == '9') 5 else 0 }
            busy == 4 -> eighths(sub)
            // Past natural, the steps between the eighths fill in
            else -> eighths(sub).let { base -> line(sub) { i -> if (base[i] == '9') 9 else listOf(3, 5, 7, 9, 9)[busy - 6].coerceAtMost(if (busy == 10) 9 else 8) } }
        }

    // One at the top, then at each bar, half bar or beat as it gets busier
    private fun crashes(
        tracks: MutableMap<String, FloatArray>,
        busy: Int,
        steps: Int,
        barSteps: Int,
        sub: Int,
    ) {
        if (busy == 0) return
        val every =
            when {
                busy <= 2 -> steps
                busy <= 5 -> barSteps
                busy <= 8 -> barSteps / 2
                else -> sub
            }
        val track = tracks.getOrPut("crash") { FloatArray(steps) }
        for (i in 0 until steps step every) track[i] = 0.9f
    }

    private fun fill(
        tracks: MutableMap<String, FloatArray>,
        settings: Settings,
        sub: Int,
        steps: Int,
        random: Random,
    ) {
        val toms = settings.busy["Toms"] ?: 0
        val length = sub * (1 + (if (settings.complexity >= 8) 1 else 0) + (if (toms >= 7) 1 else 0))
        val drums =
            buildList {
                if ((settings.busy["Snare"] ?: 0) > 0) add("snare")
                if (toms > 0) addAll(listOf("tom_high", "tom_mid", "tom_low"))
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

    // For styles with no percussion of their own
    private val STRAIGHT_PERCUSSION = mapOf("tambourine" to "0000600000006000", "shaker" to "3030303030303030")
    private val TRIPLET_PERCUSSION = mapOf("tambourine" to "000600000600", "shaker" to "303303303303")
}
