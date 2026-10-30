package local.tvhome

import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Everything on the homescreen comes from /sdcard/TVHome/config.json (pushed from the Mac). */
data class Config(
    val wallpaperFolder: String = "/sdcard/Pictures/Wallpapers",
    val wallpaperSeconds: Int = 45,
    val wallpaperShuffle: Boolean = true,
    val wallpaperZoom: Boolean = true,
    val clock: Boolean = true,
    val clockFormat: String = "HH:mm",
    // Shown under the clock; blank hides it
    val dateFormat: String = "EEE d MMM",
    // Seconds without a button press before the row fades away, leaving photo, clock and weather; 0 = never
    val ambientSeconds: Int = 20,
    // Corner for the small settings button ("bottom-left", ...), or "none"
    val settingsPosition: String = "bottom-left",
    // Path to a .ttf/.otf on the TV (e.g. /sdcard/TVHome/fonts/Inter.ttf); null = system font
    val font: String? = null,
    // "top" or "bottom"
    val rowPosition: String = "bottom",
    // "top-right", "bottom-right", "top-left" or "bottom-left"
    val clockPosition: String = "top-right",
    // Remote key code -> "settings", "quickPanel" (TCL picture/sound panel) or an app package
    val remoteButtons: Map<Int, String> = mapOf(KEYCODE_GEAR to "settings"),
    val fontWeight: Int = 400,
    val clockWeight: Int = 300,
    val weather: Weather? = null,
    val items: List<Item> = emptyList(),
) {
    /** The configured font at a weight (variable fonts honour it; others ignore it). */
    fun typeface(weight: Int): Typeface {
        val path = font
        if (path != null && File(path).canRead()) {
            try {
                return Typeface
                    .Builder(File(path))
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .build()
            } catch (ex: Exception) {
                Log.e(TAG, "Bad font $path", ex)
            }
        }
        return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
    }

    data class Weather(
        val latitude: Double,
        val longitude: Double,
        val fahrenheit: Boolean,
    )

    sealed class Item {
        abstract val label: String?

        data class App(
            val packageName: String,
            override val label: String?,
        ) : Item()

        data class Input(
            val inputId: String,
            override val label: String?,
            val subtitle: String?,
            // Built-in logo ("apple" or "playstation") and background colours (one, or a gradient)
            val logo: String?,
            val colors: List<Int>,
        ) : Item()

        data class AllApps(
            override val label: String?,
        ) : Item()

        data class Settings(
            override val label: String?,
        ) : Item()
    }

    companion object {
        const val PATH = "/sdcard/TVHome/config.json"

        // The TCL remote's gear button sends KEYCODE_NOTIFICATION
        private const val KEYCODE_GEAR = 83
        private const val TAG = "TVHome"

        fun lastModified(): Long = File(PATH).lastModified()

        fun readable(): Boolean = File(PATH).canRead()

        fun load(): Config {
            val file = File(PATH)
            if (!file.canRead()) {
                Log.w(TAG, "No readable config at $PATH, using defaults")
                return Config(items = listOf(Item.AllApps(null), Item.Settings(null)))
            }
            return try {
                parse(JSONObject(file.readText()))
            } catch (ex: Exception) {
                Log.e(TAG, "Bad config, using defaults", ex)
                Config(items = listOf(Item.AllApps(null), Item.Settings(null)))
            }
        }

        // "colors": ["#1c1c1e", "#636366"] or "color": "#003791"; bad values are skipped
        private fun colors(item: JSONObject): List<Int> {
            val values =
                item.optJSONArray("colors")?.let { list -> (0 until list.length()).map { list.optString(it) } }
                    ?: listOfNotNull(item.optString("color").ifBlank { null })
            return values.mapNotNull {
                try {
                    Color.parseColor(it)
                } catch (ex: IllegalArgumentException) {
                    Log.w(TAG, "Bad colour $it")
                    null
                }
            }
        }

        private fun parse(json: JSONObject): Config {
            val defaults = Config()
            val wallpaper = json.optJSONObject("wallpaper") ?: JSONObject()
            val clock = json.optJSONObject("clock") ?: JSONObject()
            val layout = json.optJSONObject("layout") ?: JSONObject()
            val weather =
                json.optJSONObject("weather")?.takeIf { it.optBoolean("enabled", true) }?.let {
                    Weather(
                        latitude = it.getDouble("latitude"),
                        longitude = it.getDouble("longitude"),
                        fahrenheit = it.optString("unit", "C").equals("F", ignoreCase = true),
                    )
                }
            val itemsJson = json.optJSONArray("items")
            val items =
                (0 until (itemsJson?.length() ?: 0)).mapNotNull { i ->
                    val item = itemsJson!!.getJSONObject(i)
                    val label = item.optString("label").ifBlank { null }
                    when (item.optString("type")) {
                        "app" -> Item.App(item.getString("package"), label)
                        "input" ->
                            Item.Input(
                                item.getString("input"),
                                label,
                                item.optString("subtitle").ifBlank { null },
                                item.optString("logo").ifBlank { null },
                                colors(item),
                            )
                        "allApps" -> Item.AllApps(label)
                        "settings" -> Item.Settings(label)
                        else -> null
                    }
                }

            return Config(
                wallpaperFolder = wallpaper.optString("folder", defaults.wallpaperFolder),
                wallpaperSeconds = wallpaper.optInt("seconds", defaults.wallpaperSeconds).coerceAtLeast(5),
                wallpaperShuffle = wallpaper.optBoolean("shuffle", defaults.wallpaperShuffle),
                wallpaperZoom = wallpaper.optBoolean("zoom", defaults.wallpaperZoom),
                clock = clock.optBoolean("enabled", defaults.clock),
                clockFormat = clock.optString("format", defaults.clockFormat),
                dateFormat = clock.optString("dateFormat", defaults.dateFormat),
                ambientSeconds =
                    json.optJSONObject("ambient")?.let {
                        if (it.optBoolean("enabled", true)) it.optInt("seconds", defaults.ambientSeconds).coerceAtLeast(0) else 0
                    } ?: defaults.ambientSeconds,
                settingsPosition = layout.optString("settings", defaults.settingsPosition),
                font = json.optString("font").ifBlank { null },
                rowPosition = layout.optString("row", defaults.rowPosition),
                clockPosition = layout.optString("clock", defaults.clockPosition),
                remoteButtons =
                    json.optJSONObject("remoteButtons")?.let { buttons ->
                        buttons.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to buttons.getString(key) } }.toMap()
                    } ?: defaults.remoteButtons,
                fontWeight = json.optInt("fontWeight", defaults.fontWeight),
                clockWeight = json.optInt("clockWeight", defaults.clockWeight),
                weather = weather,
                items = items,
            )
        }
    }
}
