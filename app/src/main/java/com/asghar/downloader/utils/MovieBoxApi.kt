package com.asghar.downloader.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Thin client over MovieBox's public `api.inmoviebox.com` backend.
 *
 * The endpoints and the JSON shapes were derived by reading the
 * `com.community.mbox.in` (MovieBox 3.0.06.0804.03) APK and matching the
 * path prefixes under `/wefeed-mobile-bff/` that the app itself calls. The
 * server returns standard JSON; the data classes we map to are intentionally
 * flat to keep this layer maintenance-friendly.
 */
object MovieBoxApi {
    private const val TAG = "MovieBoxApi"
    private const val BASE = "https://api.inmoviebox.com/wefeed-mobile-bff"
    private const val CDN  = "https://v.inmoviebox.com"

    // Sent on every request so the backend treats us like a real Android
    // install. The combination below matches what the official app uses.
    private const val APP_VERSION = "3.0.06.0804.03"
    private const val CLIENT_TYPE = "android"
    private const val PKG = "com.community.mbox.in"
    private const val LOCALE = "en_US"

    private fun deviceId(): String {
        // MovieBox expects a stable 19-digit device id. We use a random but
        // persistent per-install value stored in shared prefs. The official
        // app does the same dance with OAID.
        val prefs = App.context.getSharedPreferences("moviebox", android.content.Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = (1_000_000_000_000_000_000L + (Math.random() * 8_000_000_000_000_000_000L).toLong()).toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getJson(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val sb = StringBuilder(BASE).append(path)
        if (params.isNotEmpty()) {
            sb.append('?')
            params.entries.forEachIndexed { i, e ->
                if (i > 0) sb.append('&')
                sb.append(URLEncoder.encode(e.key, "UTF-8"))
                  .append('=')
                  .append(URLEncoder.encode(e.value, "UTF-8"))
            }
        }
        val url = URL(sb.toString())
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "MovieBox/$APP_VERSION (Linux; Android 13)")
        c.setRequestProperty("X-Client-Type", CLIENT_TYPE)
        c.setRequestProperty("X-App-Version", APP_VERSION)
        c.setRequestProperty("X-Package", PKG)
        c.setRequestProperty("X-Device-Id", deviceId())
        c.setRequestProperty("X-Locale", LOCALE)
        c.setRequestProperty("X-Platform", "android")
        c.setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "$path -> HTTP $code: ${body.take(120)}")
                throw IllegalStateException("HTTP $code")
            }
            JSONObject(body)
        } finally { c.disconnect() }
    }

    /** Trending feed used for the home grid. */
    fun homeFeed(): List<Movie> {
        return runCatching {
            val j = getJson("/subject-api/trending/v2",
                mapOf("page" to "1", "size" to "30"))
            parseList(j.optJSONArray("data") ?: j.optJSONArray("results"))
        }.getOrElse { emptyList() }
    }

    /** Curated editorial lists ("Top Picks", "Bollywood Hits", …). */
    fun homePlaylists(): List<Playlist> {
        return runCatching {
            val j = getJson("/home/playlist")
            val arr = j.optJSONArray("data")
            if (arr == null) {
                emptyList<Playlist>()
            } else {
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Playlist(
                        id = o.optString("id", o.optString("playlistId")),
                        title = o.optString("title", o.optString("name")),
                        items = parseList(o.optJSONArray("subjects") ?: o.optJSONArray("items"))
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
    fun search(query: String): List<Movie> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val j = getJson("/subject-api/search/v2",
                mapOf("keyword" to query, "page" to "1", "size" to "30"))
            parseList(j.optJSONArray("data") ?: j.optJSONArray("results"))
        }.getOrElse { emptyList() }
    }

    /** Single movie / series detail. */
    fun detail(subjectId: String): MovieDetail? {
        return runCatching {
            val j = getJson("/subject-api/detail-rec", mapOf("subjectId" to subjectId))
            val d = j.optJSONObject("data") ?: j
            val playInfo = runCatching {
                getJson("/subject-api/play-info", mapOf("subjectId" to subjectId)).optJSONObject("data")
            }.getOrNull()
            MovieDetail(
                id = d.optString("subjectId", d.optString("id", subjectId)),
                title = d.optString("title"),
                overview = d.optString("description", d.optString("overview")),
                year = d.optString("year"),
                rating = d.optString("rating"),
                duration = d.optString("duration"),
                poster = poster(d),
                backdrop = backdrop(d),
                genre = (0 until d.optJSONArray("genres")?.length().orZero()).joinToString {
                    d.optJSONArray("genres")?.optString(it).orEmpty()
                }.ifBlank { d.optString("genre") },
                casts = (0 until d.optJSONArray("casts")?.length().orZero()).joinToString {
                    d.optJSONArray("casts")?.optString(it).orEmpty()
                },
                streamUrl = playInfo?.optString("playUrl").orEmpty(),
                captions = parseCaptions(playInfo?.optJSONArray("captions"))
            )
        }.getOrNull()
    }

    /** Fetch external subtitles for a subject (used as a fallback when the
     *  play-info bundle does not include caption tracks). */
    fun captions(subjectId: String): List<Caption> {
        return runCatching {
            val j = getJson("/subject-api/get-ext-captions",
                mapOf("subjectId" to subjectId))
            parseCaptions(j.optJSONArray("data"))
        }.getOrElse { emptyList() }
    }

    private fun parseList(arr: JSONArray?): List<Movie> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Movie(
                id = o.optString("subjectId", o.optString("id")),
                title = o.optString("title", o.optString("name")),
                year = o.optString("year", o.optString("releaseYear")),
                rating = o.optString("rating", o.optString("score")),
                poster = poster(o),
                backdrop = backdrop(o),
                genre = o.optString("genre"),
                type = o.optString("subjectType", o.optString("type"))
            )
        }
    }

    private fun poster(o: JSONObject): String {
        val raw = o.optString("cover", o.optString("poster", o.optString("imageUrl")))
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http")) raw else "$CDN/$raw"
    }

    private fun backdrop(o: JSONObject): String {
        val raw = o.optString("backdrop", o.optString("banner"))
        if (raw.isBlank()) return poster(o)
        return if (raw.startsWith("http")) raw else "$CDN/$raw"
    }

    private fun parseCaptions(arr: JSONArray?): List<Caption> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url", o.optString("captionUrl"))
            if (url.isBlank()) return@mapNotNull null
            Caption(
                language = o.optString("language", o.optString("lang", "en")),
                label = o.optString("label", o.optString("name", "English")),
                url = if (url.startsWith("http")) url else "$CDN/$url"
            )
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    data class Movie(
        val id: String,
        val title: String,
        val year: String = "",
        val rating: String = "",
        val poster: String = "",
        val backdrop: String = "",
        val genre: String = "",
        val type: String = "movie"
    )

    data class MovieDetail(
        val id: String,
        val title: String,
        val overview: String,
        val year: String,
        val rating: String,
        val duration: String,
        val poster: String,
        val backdrop: String,
        val genre: String,
        val casts: String,
        val streamUrl: String,
        val captions: List<Caption>
    )

    data class Playlist(val id: String, val title: String, val items: List<Movie>)

    data class Caption(val language: String, val label: String, val url: String)
}
