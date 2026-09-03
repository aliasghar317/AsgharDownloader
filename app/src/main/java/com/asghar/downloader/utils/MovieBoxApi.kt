package com.asghar.downloader.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for MovieBox's public web BFF at h5-api.aoneroom.com.
 *
 * The endpoints and JSON shapes below were derived by reading the MovieBox
 * web app (https://h5.inmoviebox.com) JS bundles. The home endpoint returns
 * a list of "operating" sections under data.operatingList; each section
 * has a `subjects` array with the full subject metadata.
 *
 * Stream URLs (subject/play → streams/dash/hls) are only filled for
 * authenticated users; the public endpoint always returns empty arrays,
 * so the UI must surface a "Sign in to watch" affordance instead of
 * trying to play a missing URL.
 */
object MovieBoxApi {
    private const val TAG = "MovieBoxApi"
    private const val BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    const val CDN_IMAGE = "https://pbcdnw.aoneroom.com"
    const val PLAY_DOMAIN = "https://netfilm.world"

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

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
        c.connectTimeout = 12000
        c.readTimeout = 18000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-Request-Lang", "en")
        c.setRequestProperty("X-Client-Info", "android")
        c.setRequestProperty("Referer", "https://h5.inmoviebox.com/")
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

    /**
     * Returns the ordered list of rails the MovieBox home page would
     * show. The endpoint returns data.operatingList — a list of section
     * objects. Only the sections that carry a `subjects` array are
     * turned into rails; ad-only or banner-only sections are skipped.
     */
    fun homeRails(): List<Rail> {
        return runCatching {
            val j = getJson("/home")
            val ops = j.optJSONObject("data")?.optJSONArray("operatingList")
            if (ops == null) emptyList<Rail>() else {
                val rails = ArrayList<Rail>()
                (0 until ops.length()).forEach { i ->
                    val op = ops.optJSONObject(i) ?: return@forEach
                    val subs = op.optJSONArray("subjects")
                    if (subs != null && subs.length() > 0) {
                        val items = parseList(subs)
                        if (items.isNotEmpty()) rails.add(Rail(op.optString("title", "More"), items))
                    }
                }
                rails
            }
        }.getOrDefault(emptyList())
    }

    /** Free-text search via the suggestions endpoint + trending filter. */
    fun search(query: String): List<Movie> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val suggest = getJson("/subject/everyone-search", mapOf("keyword" to query))
            val wanted = suggest.optJSONObject("data")
                ?.optJSONArray("everyoneSearch")
                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title") } }
                .orEmpty()
            val trending = trending()
            if (wanted.isEmpty()) trending
            else trending.filter { m -> wanted.any { m.title.contains(it, ignoreCase = true) } }
                .ifEmpty { trending }
        }.getOrDefault(emptyList())
    }

    /** Trending / hot subjects — used as the search fallback corpus. */
    fun trending(): List<Movie> = runCatching {
        val j = getJson("/subject/trending")
        parseList(j.optJSONObject("data")?.optJSONArray("subjectList"))
    }.getOrDefault(emptyList())

    /**
     * Detail + play info for a single subject. The public BFF always
     * returns empty streams unless the caller is authenticated; the
     * UI should check [Detail.hasResource] + [Detail.streams] before
     * trying to play.
     */
    fun detail(subjectId: String): Detail? = runCatching {
        val j = getJson("/subject/play", mapOf("subjectId" to subjectId))
        val d = j.optJSONObject("data") ?: return@runCatching null
        val streams = ArrayList<Stream>()
        d.optJSONArray("streams")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url", s.optString("mainUrl"))
                if (url.isNotBlank()) streams.add(
                    Stream(s.optString("quality", s.optString("label", "HD")),
                           s.optString("format", "hls"), url, s.optInt("size", 0))
                )
            }
        }
        d.optJSONArray("hls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url")
                if (url.isNotBlank()) streams.add(Stream("HLS", "hls", url, 0))
            }
        }
        d.optJSONArray("dash")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optString("url")
                if (url.isNotBlank()) streams.add(Stream("DASH", "dash", url, 0))
            }
        }
        Detail(
            hasResource = d.optBoolean("hasResource", false),
            streams = streams,
            vipLocked = d.optBoolean("vipLocked", false),
            maxResolution = d.optJSONObject("playConfig")?.optInt("maxResolution", 1080) ?: 1080
        )
    }.getOrNull()

    private fun parseList(arr: JSONArray?): List<Movie> {
        if (arr == null) return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cover = o.optJSONObject("cover")
            val rawCover = cover?.optString("url").orEmpty()
            val poster = if (rawCover.startsWith("http")) rawCover
                         else if (rawCover.isNotBlank()) "$CDN_IMAGE/$rawCover"
                         else ""
            val rawBackdrop = cover?.optString("backdrop")?.takeIf { it.isNotBlank() }
                ?: o.optString("backdrop")
            val backdrop = if (rawBackdrop.startsWith("http")) rawBackdrop
                           else if (rawBackdrop.isNotBlank()) "$CDN_IMAGE/$rawBackdrop"
                           else poster
            out.add(Movie(
                id = o.optString("subjectId"),
                title = o.optString("title"),
                year = o.optString("releaseDate").take(4),
                rating = o.optString("imdbRatingValue"),
                poster = poster,
                genre = o.optString("genre"),
                country = o.optString("countryName"),
                type = o.optString("subjectType"),
                backdrop = backdrop
            ))
        }
        return out
    }

    data class Movie(
        val id: String,
        val title: String,
        val year: String = "",
        val rating: String = "",
        val poster: String = "",
        val genre: String = "",
        val country: String = "",
        val type: String = "1",
        val backdrop: String = ""
    )
    data class Rail(val title: String, val items: List<Movie>)
    data class Stream(val quality: String, val format: String, val url: String, val size: Int)
    data class Detail(
        val hasResource: Boolean,
        val streams: List<Stream>,
        val vipLocked: Boolean,
        val maxResolution: Int
    )
}
