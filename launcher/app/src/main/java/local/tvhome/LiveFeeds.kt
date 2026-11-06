package local.tvhome

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/**
 * The live feeds, from assets/livefeeds.json. YouTube's live video IDs change whenever a stream
 * restarts, so each feed also says how to find itself again; see [findLiveId].
 */
class LiveFeed(
    val group: String,
    val name: String,
    val place: String,
    val zone: TimeZone,
    val channel: String?,
    val search: String?,
    val match: String?,
    val id: String?,
    val stream: String?,
    val everyHours: Int,
    val minutes: Int,
) {
    /** Feeds like Ocean Networks Canada's only run for a few minutes on a schedule. */
    val scheduled get() = stream != null

    /** Milliseconds until the camera comes on; 0 if it's on now. */
    fun untilOn(now: Long = System.currentTimeMillis()): Long {
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        hour.timeInMillis = now
        hour.set(Calendar.MINUTE, 0)
        hour.set(Calendar.SECOND, 0)
        hour.set(Calendar.MILLISECOND, 0)
        hour.add(Calendar.HOUR_OF_DAY, -(hour.get(Calendar.HOUR_OF_DAY) % everyHours))
        val start = hour.timeInMillis
        if (now < start + minutes * MINUTE) return 0
        return start + everyHours * HOUR - now
    }

    /** Milliseconds until the camera goes off again, when it's on. */
    fun untilOff(now: Long = System.currentTimeMillis()): Long = minutes * MINUTE - now % (everyHours * HOUR)

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}

object LiveFeeds {
    private const val TAG = "TVHome"
    private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val LIVE_ONLY = "&sp=EgJAAQ%253D%253D"

    fun load(context: Context): List<LiveFeed> {
        val list = JSONArray(context.assets.open("livefeeds.json").bufferedReader().use { it.readText() })
        return (0 until list.length()).map { i ->
            val feed = list.getJSONObject(i)
            LiveFeed(
                feed.getString("group"),
                feed.getString("name"),
                feed.getString("place"),
                TimeZone.getTimeZone(feed.getString("zone")),
                feed.optString("channel").ifEmpty { null },
                feed.optString("search").ifEmpty { null },
                feed.optString("match").ifEmpty { null },
                feed.optString("id").ifEmpty { null },
                feed.optString("stream").ifEmpty { null },
                feed.optInt("everyHours", 1),
                feed.optInt("minutes", 0),
            )
        }
    }

    /** Looks up the video a feed is live on right now. Blocking; call off the main thread. */
    fun findLiveId(feed: LiveFeed): String? {
        val search = feed.search ?: return null
        return try {
            val url = URL("https://www.youtube.com/results?search_query=${URLEncoder.encode(search, "UTF-8")}$LIVE_ONLY")
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Language", "en")
                }
            val page = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val start = page.indexOf("var ytInitialData = ")
            if (start < 0) return null
            val from = start + "var ytInitialData = ".length
            val json = JSONObject(page.substring(from, page.indexOf(";</script>", from)))
            val results = ArrayList<JSONObject>()
            collect(json, results)
            results.firstOrNull { matches(it, feed) }?.getString("videoId")
        } catch (ex: Exception) {
            Log.w(TAG, "Couldn't look up ${feed.name}", ex)
            null
        }
    }

    private fun matches(
        video: JSONObject,
        feed: LiveFeed,
    ): Boolean {
        val title = text(video.optJSONObject("title"))
        val owner = video.optJSONObject("ownerText")?.toString().orEmpty()
        val rightChannel = feed.channel == null || owner.contains("\"/${feed.channel}\"", ignoreCase = true)
        return rightChannel && title.contains(feed.match.orEmpty(), ignoreCase = true)
    }

    private fun text(value: JSONObject?): String {
        val runs = value?.optJSONArray("runs") ?: return value?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }
    }

    private fun collect(
        node: Any?,
        out: MutableList<JSONObject>,
    ) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { out.add(it) }
                node.keys().forEach { collect(node.get(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.get(i), out)
        }
    }
}
