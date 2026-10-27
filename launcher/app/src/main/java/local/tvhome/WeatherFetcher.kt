package local.tvhome

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Current temperature from Open-Meteo: free, no account, no key, no tracking. */
object WeatherFetcher {
    private const val TAG = "TVHome"

    data class Reading(
        val temperature: Int,
        val symbol: String,
    )

    // Blocking; call off the main thread
    fun fetch(weather: Config.Weather): Reading? =
        try {
            val unit = if (weather.fahrenheit) "fahrenheit" else "celsius"
            val url =
                URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=${weather.latitude}&longitude=${weather.longitude}" +
                        "&current=temperature_2m,weather_code,is_day&temperature_unit=$unit",
                )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val current = JSONObject(body).getJSONObject("current")
            Reading(
                temperature = current.getDouble("temperature_2m").roundToInt(),
                symbol = symbolFor(current.getInt("weather_code"), current.optInt("is_day", 1) == 1),
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Weather fetch failed", ex)
            null
        }

    // WMO weather codes: https://open-meteo.com/en/docs
    private fun symbolFor(
        code: Int,
        isDay: Boolean,
    ): String =
        when (code) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "🌤️" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..67, in 80..82 -> "🌧️"
            in 71..77, 85, 86 -> "❄️"
            in 95..99 -> "⛈️"
            else -> ""
        }
}
