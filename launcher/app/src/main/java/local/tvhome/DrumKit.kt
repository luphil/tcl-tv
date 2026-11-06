package local.tvhome

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * A sampled drum kit on the TV's storage, built by tools/build-drumkits.py. The audio stays in
 * the file and is read as it plays, so a kit can be far bigger than the TV's memory.
 */
class DrumKit private constructor(
    val id: String,
    val label: String,
    private val audio: ShortBuffer,
    private val roles: Map<String, List<List<Hit>>>,
    private val levels: Map<String, List<Float>>,
) {
    class Hit(
        val start: Int,
        val frames: Int,
    )

    fun has(role: String) = role in roles

    /**
     * A hit of this drum, from the recording closest in loudness to the velocity, so a soft
     * note is equally soft on every kit. Null if the kit hasn't this drum.
     */
    fun hit(
        role: String,
        velocity: Float,
        pick: (Int) -> Int,
    ): Hit? {
        val layers = roles[role] ?: return null
        val target = (velocity.coerceIn(0f, 1f) - 1) * FULL_RANGE_DB
        val layer = levels.getValue(role).withIndex().minBy { abs(it.value - target) }.index
        val takes = layers[layer]
        return takes[pick(takes.size)]
    }

    /** Copies a stretch of a hit's stereo audio out of the kit. */
    fun read(
        at: Int,
        into: ShortArray,
        count: Int,
    ) {
        audio.duplicate().apply { position(at * 2) }.get(into, 0, count * 2)
    }

    /** Pulls every recording this drum uses from storage, so the first hits don't stutter. */
    fun warm(role: String) {
        val scratch = ShortArray(WARM_FRAMES * 2)
        roles[role]?.flatten()?.forEach { hit -> read(hit.start, scratch, minOf(WARM_FRAMES, hit.frames)) }
    }

    companion object {
        const val FOLDER = "/sdcard/TVHome/drums"
        private const val WARM_FRAMES = 12000

        // From the hardest hit (velocity 1) down to silence (0)
        private const val FULL_RANGE_DB = 26f

        /** Which kits are on the TV, in the order to offer them (percussion is added to every kit). */
        fun available(): List<String> =
            listOf("big", "studio", "vintage", "brushes", "808", "909", "trap").filter { File("$FOLDER/$it/pack.bin").canRead() }

        fun load(id: String): DrumKit? {
            val folder = File(FOLDER, id)
            val index = File(folder, "index.json")
            if (!index.canRead()) return null
            val json = JSONObject(index.readText())
            val rolesJson = json.getJSONObject("roles")
            val roles =
                rolesJson.keys().asSequence().associateWith { role ->
                    val layers = rolesJson.getJSONArray(role)
                    (0 until layers.length()).map { l ->
                        val takes = layers.getJSONArray(l)
                        (0 until takes.length()).map { t ->
                            val hit = takes.getJSONArray(t)
                            Hit(hit.getInt(0), hit.getInt(1))
                        }
                    }
                }
            val levelsJson = json.getJSONObject("levels")
            val levels =
                roles.keys.associateWith { role ->
                    val list = levelsJson.getJSONArray(role)
                    (0 until list.length()).map { list.getDouble(it).toFloat() }
                }
            val audio =
                RandomAccessFile(File(folder, "pack.bin"), "r").use { file ->
                    file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                }
            return DrumKit(id, json.getString("label"), audio, roles, levels)
        }
    }
}
