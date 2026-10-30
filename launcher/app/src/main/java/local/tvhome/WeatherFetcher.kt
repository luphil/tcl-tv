package local.tvhome

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Current temperature and today's high/low from Open-Meteo: free, no account, no key, no tracking. */
object WeatherFetcher {
    private const val TAG = "TVHome"

    data class Reading(
        val temperature: Int,
        // Line icon for the conditions, or null for unknown codes
        val icon: Int?,
        val description: String,
        val high: Int,
        val low: Int,
    )

    // Blocking; call off the main thread
    fun fetch(weather: Config.Weather): Reading? =
        try {
            val unit = if (weather.fahrenheit) "fahrenheit" else "celsius"
            val url =
                URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=${weather.latitude}&longitude=${weather.longitude}" +
                        "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                        "&forecast_days=1&timezone=auto&temperature_unit=$unit",
                )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val today = json.getJSONObject("daily")
            val code = current.getInt("weather_code")
            Reading(
                temperature = current.getDouble("temperature_2m").roundToInt(),
                icon = iconFor(code, current.optInt("is_day", 1) == 1),
                description = describe(code),
                high = today.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = today.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Weather fetch failed", ex)
            null
        }

    // WMO weather codes: https://open-meteo.com/en/docs
    private fun iconFor(
        code: Int,
        isDay: Boolean,
    ): Int? =
        when (code) {
            0 -> if (isDay) R.drawable.wx_clear_day else R.drawable.wx_clear_night
            1, 2 -> if (isDay) R.drawable.wx_partly_day else R.drawable.wx_partly_night
            3 -> R.drawable.wx_cloudy
            45, 48 -> R.drawable.wx_fog
            in 51..57 -> R.drawable.wx_drizzle
            in 61..67, in 80..82 -> R.drawable.wx_rain
            in 71..77, 85, 86 -> R.drawable.wx_snow
            in 95..99 -> R.drawable.wx_storm
            else -> null
        }

    private fun describe(code: Int): String =
        when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67, in 80..82 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Storms"
            else -> ""
        }
}
