package com.asghar.downloader.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Client for MovieBox's public web BFF at h5-api.aoneroom.com.
 *
 * The endpoints and JSON shapes were derived by reading the MovieBox
 * web app (https://h5.inmoviebox.com) JS bundles. The web app sends
 * an `X-Client-Token` header of the form
 *
 *   "<unix_ts>,<md5(reverse(unix_ts))>"
 *
 * which the BFF accepts in lieu of a user JWT and returns a guest
 * session cookie. We send that token on every request so the server
 * treats us as a (free-tier) user, which is enough to fetch the
 * metadata catalog. Stream URLs (`/subject/play` → `streams[]`,
 * `hls[]`, `dash[]`) are still empty for guest sessions because the
 * BFF gates them behind a paid subscription; [StreamSource] decides
 * which fallback to try before reporting "locked" to the UI.
 */
object MovieBoxApi {
    private const val TAG = "MovieBoxApi"
    private const val BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    const val CDN_IMAGE = "https://pbcdnw.aoneroom.com"
    const val PLAY_DOMAIN = "https://netfilm.world"

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val TIMEZONE: String = java.util.TimeZone.getDefault().id
    private var cachedJwt: String? = null

    private fun clientToken(): String {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val reversed = ts.reversed()
        val md = MessageDigest.getInstance("MD5").digest(reversed.toByteArray())
        val hex = StringBuilder()
        for (b in md) hex.append(String.format("%02x", b))
        return "$ts,${hex.toString()}"
    }

    private fun getJson(path: String, params: Map<String, String> = emptyMap(), retry: Boolean = true): JSONObject {
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
        c.setRequestProperty("X-Client-Info", "{\"timezone\":\"$TIMEZONE\"}")
        c.setRequestProperty("Referer", "https://h5.inmoviebox.com/")
        c.setRequestProperty("Origin", "https://h5.inmoviebox.com")
        val jwt = cachedJwt
        if (!jwt.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $jwt")
        else c.setRequestProperty("X-Client-Token", clientToken())
        return try {
            val code = c.responseCode
            // Capture the JWT returned in the response so subsequent calls
            // are sent with a proper Bearer token.
            runCatching { c.getHeaderField("x-user") }.getOrNull()?.let { xUser ->
                runCatching {
                    val token = JSONObject(xUser).optString("token")
                    if (token.isNotBlank()) cachedJwt = token
                }
            }
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "$path -> HTTP $code: ${body.take(120)}")
                throw IllegalStateException("HTTP $code")
            }
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val url = URL("$BASE$path")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 12000
        c.readTimeout = 18000
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-Request-Lang", "en")
        c.setRequestProperty("X-Client-Info", "{\"timezone\":\"$TIMEZONE\"}")
        c.setRequestProperty("Referer", "https://h5.inmoviebox.com/")
        c.setRequestProperty("Origin", "https://h5.inmoviebox.com")
        val jwt = cachedJwt
        if (!jwt.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $jwt")
        else c.setRequestProperty("X-Client-Token", clientToken())
        return try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            runCatching { c.getHeaderField("x-user") }.getOrNull()?.let { xUser ->
                runCatching {
                    val token = JSONObject(xUser).optString("token")
                    if (token.isNotBlank()) cachedJwt = token
                }
            }
            val respBody = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            JSONObject(respBody)
        } finally { c.disconnect() }
    }

    fun resetSession() { cachedJwt = null }

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

    /** Trending / hot subjects. */
    fun trending(): List<Movie> = runCatching {
        val j = getJson("/subject/trending")
        parseList(j.optJSONObject("data")?.optJSONArray("subjectList"))
    }.getOrDefault(emptyList())

    /** Top-tab list (Trending, Movie, TV, Short Dramas, …). */
    fun tabs(): List<Tab> = runCatching {
        val j = getJson("/tab-operating")
        val ops = j.optJSONObject("data")?.optJSONArray("operatingList")
        if (ops == null) emptyList<Tab>() else {
            (0 until ops.length()).mapNotNull { o ->
                val t = ops.optJSONObject(o) ?: return@mapNotNull null
                Tab(t.optString("title", "Tab"), t.optString("tabId"), parseList(t.optJSONArray("subjects")))
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Live search-suggest endpoint. The web app uses this to power the
     * type-ahead dropdown. It is a POST that accepts a JSON body and
     * returns a list of keyword suggestions (not full subjects).
     */
    fun suggest(keyword: String): List<String> {
        if (keyword.isBlank()) return emptyList()
        return runCatching {
            val j = postJson("/subject/search-suggest", JSONObject().put("keyword", keyword))
            val items = j.optJSONObject("data")?.optJSONArray("items")
            if (items == null) emptyList<String>() else {
                (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("word") }
                    .filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Search results. */
    fun search(query: String): List<Movie> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val j = getJson("/subject/everyone-search", mapOf("keyword" to query))
            val wanted = j.optJSONObject("data")?.optJSONArray("everyoneSearch")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title") }
            }.orEmpty()
            val trending = trending()
            if (wanted.isEmpty()) trending
            else trending.filter { m -> wanted.any { m.title.contains(it, ignoreCase = true) } }
                .ifEmpty { trending }
        }.getOrDefault(emptyList())
    }

    /** Filter catalog (used by the genre chips at the top of home). */
    fun filter(genre: String? = null, country: String? = null, sort: String = "trending",
               page: Int = 1, size: Int = 20): List<Movie> {
        return runCatching {
            val params = mutableMapOf("page" to page.toString(), "size" to size.toString(), "sort" to sort)
            if (!genre.isNullOrBlank()) params["genre"] = genre
            if (!country.isNullOrBlank()) params["country"] = country
            val j = getJson("/subject/filter", params)
            parseList(j.optJSONObject("data")?.optJSONArray("items"))
                .ifEmpty { parseList(j.optJSONObject("data")?.optJSONArray("subjects")) }
        }.getOrDefault(emptyList())
    }

    /** Detail + play info for a single subject. The BFF only fills the
     *  stream arrays for paid sessions. The webHighRisk flag tells us
     *  whether the subject can even be previewed by a guest. */
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

    /**
     * Returns the netfilm.world URL where the user can stream the
     * subject. The page itself requires login, but a WebView will at
     * least let the user log in and then play.
     */
    fun detailPath(subjectId: String): String? = runCatching {
        val j = getJson("/subject/detail-rec", mapOf("subjectId" to subjectId))
        val items = j.optJSONObject("data")?.optJSONArray("items")
        items?.optJSONObject(0)?.optString("detailPath")
            ?.takeIf { it.isNotBlank() }
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
    data class Tab(val title: String, val id: String, val items: List<Movie>)
    data class Stream(val quality: String, val format: String, val url: String, val size: Int)
    data class Detail(
        val hasResource: Boolean,
        val streams: List<Stream>,
        val vipLocked: Boolean,
        val maxResolution: Int
    )
}
