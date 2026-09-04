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
    const val CDN_IMAGE = "https://pbcdn.aoneroom.com"
    const val PLAY_DOMAIN = "https://netfilm.world"

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val TIMEZONE: String = java.util.TimeZone.getDefault().id
    private var cachedJwt: String? = null
    private var cachedMobileHost: String? = null

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

    /**
     * Search results. The actual /subject/search endpoint on the
     * MovieBox BFF is gated by a JWT (x-user) token. We always pass
     * that token when it's available; if the call fails we fall back
     * to filtering the in-memory catalog.
     */
    fun search(query: String, catalog: List<Movie> = emptyList()): List<Movie> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        // Try the real endpoint first
        val remote = runCatching { searchRemote(q) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) return remote
        // Fall back to local filter on the cached catalog
        return localSearch(q, catalog)
    }

    /**
     * POST /subject/search → list of matching subject objects.
     * Requires the JWT token in the Authorization header.
     */
    fun searchRemote(keyword: String, subjectType: Int = 1, page: Int = 1,
                     perPage: Int = 30): List<Movie> = runCatching {
        val body = JSONObject()
            .put("keyword", keyword)
            .put("page", page)
            .put("perPage", perPage)
            .put("subjectType", subjectType)
        val j = postJson("/subject/search", body)
        parseList(j.optJSONObject("data")?.optJSONArray("items"))
    }.getOrDefault(emptyList())

    /**
     * Local fallback when the BFF search endpoint is unavailable.
     * Filters the in-memory catalog by case-insensitive title match.
     */
    private fun localSearch(query: String, catalog: List<Movie>): List<Movie> {
        val qL = query.lowercase()
        val source = if (catalog.isNotEmpty()) catalog else {
            homeRails().flatMap { it.items } + trending()
        }
        val seen = HashSet<String>()
        val results = ArrayList<Movie>()
        for (m in source) {
            if (!seen.add(m.id)) continue
            val titleL = m.title.lowercase()
            if (titleL == qL ||
                titleL.startsWith(qL) ||
                titleL.contains(" $qL") ||
                titleL.contains("$qL ") ||
                titleL.contains(qL) ||
                m.genre.lowercase().contains(qL) ||
                m.country.lowercase().contains(qL)
            ) results.add(m)
        }
        return results
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
     * Full subject info from /subject/detail-rec. Includes description,
     * subtitles list, ops (rid/esid/a/trace_id), and the netfilm.world
     * detailPath. Use [SubjectInfo] when you want to render a detail page.
     */
    fun subjectInfo(subjectId: String): SubjectInfo? = runCatching {
        val j = getJson("/subject/detail-rec", mapOf("subjectId" to subjectId))
        val items = j.optJSONObject("data")?.optJSONArray("items") ?: return@runCatching null
        val o = items.optJSONObject(0) ?: return@runCatching null
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
        val opsStr = o.optString("ops", "{}")
        val ops = runCatching { JSONObject(opsStr) }.getOrDefault(JSONObject())
        val subtitlesStr = o.optString("subtitles", "")
        val subtitles = subtitlesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val dubsArr = o.optJSONArray("dubs")
        val dubs = dubsArr?.let { arr ->
            (0 until arr.length()).mapNotNull { d -> dubsArr.optJSONObject(d) }
        }.orEmpty()
        SubjectInfo(
            id = o.optString("subjectId"),
            title = o.optString("title"),
            year = o.optString("releaseDate").take(4),
            rating = o.optString("imdbRatingValue"),
            description = o.optString("description"),
            duration = o.optInt("duration"),
            genre = o.optString("genre"),
            country = o.optString("countryName"),
            poster = poster,
            backdrop = backdrop,
            type = o.optString("subjectType"),
            corner = o.optString("corner"),
            subtitles = subtitles,
            detailPath = o.optString("detailPath"),
            hasResource = o.optBoolean("hasResource", false),
            webHighRisk = o.optBoolean("webHighRisk", false),
            accessStrategy = o.optString("accessStrategy"),
            ops = Ops(
                rid = ops.optString("rid"),
                traceId = ops.optString("trace_id"),
                a = ops.optString("a"),
                esid = ops.optString("esid")
            ),
            dubs = dubs.map { Dub(it.optString("dubId", it.optString("id")),
                                   it.optString("dubLang"),
                                   it.optString("dubName"),
                                   it.optString("coverUrl", it.optString("url"))) }
        )
    }.getOrNull()

    /** Related subjects for the "More Like This" rail. */
    fun related(subjectId: String): List<Movie> = runCatching {
        val j = getJson("/subject/detail-rec", mapOf("subjectId" to subjectId))
        val recs = j.optJSONObject("data")?.optJSONArray("recs")
        parseList(recs)
    }.getOrDefault(emptyList())

    /**
     * Returns the list of seasons for a TV series. The BFF exposes them
     * inside /subject/detail-rec. The returned [Season] objects hold the
     * season number, the season title, the episode list and the cover
     * image. For movies this returns an empty list.
     */
    fun seasons(subjectId: String): List<Season> = runCatching {
        val j = getJson("/subject/detail-rec", mapOf("subjectId" to subjectId))
        val items = j.optJSONObject("data")?.optJSONArray("items")
        if (items == null || items.length() == 0) emptyList<Season>() else {
            val o = items.optJSONObject(0)
            val resourceStr = o?.optString("resource", "[]")
            val resources = runCatching { JSONArray(resourceStr) }.getOrNull() ?: return@runCatching emptyList<Season>()
            val out = ArrayList<Season>()
            for (i in 0 until resources.length()) {
                val r = resources.optJSONObject(i) ?: continue
                val eps = r.optJSONArray("episodeList")
                val episodes = ArrayList<Episode>()
                if (eps != null) {
                    for (k in 0 until eps.length()) {
                        val e = eps.optJSONObject(k) ?: continue
                        episodes.add(Episode(
                            id = e.optString("id"),
                            number = e.optInt("e", e.optInt("episode", k + 1)),
                            seasonNumber = r.optInt("s", 1),
                            title = e.optString("title").ifBlank {
                                "Episode ${e.optInt("e", k + 1)}"
                            },
                            duration = e.optInt("duration"),
                            thumbnail = e.optString("img", e.optString("thumbnail"))
                        ))
                    }
                }
                out.add(Season(
                    number = r.optInt("s", i + 1),
                    title = r.optString("title").ifBlank { "Season ${r.optInt("s", i + 1)}" },
                    cover = r.optString("img", r.optString("cover")),
                    episodes = episodes
                ))
            }
            out
        }
    }.getOrDefault(emptyList())

    /**
     * Looks up the available dubbing tracks for a subject. MovieBox's
     * /subject/detail-rec payload sometimes populates a `dubs` array on
     * the subject; otherwise we fall back to scanning the resource
     * groups and surface each one as a separate dub entry.
     */
    fun dubs(subjectId: String): List<Dub> = runCatching {
        val j = getJson("/subject/detail-rec", mapOf("subjectId" to subjectId))
        val items = j.optJSONObject("data")?.optJSONArray("items")
        if (items == null || items.length() == 0) emptyList<Dub>() else {
            val o = items.optJSONObject(0)
            val arr = o?.optJSONArray("dubs")
            if (arr != null && arr.length() > 0) {
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                    Dub(
                        dubId = it.optString("dubId", it.optString("id")),
                        dubLang = it.optString("dubLang"),
                        dubName = it.optString("dubName"),
                        coverUrl = it.optString("coverUrl", it.optString("url"))
                    )
                }
            } else {
                // The BFF might not include a `dubs` array but the
                // resource groups can be parsed as separate audio tracks.
                val res = runCatching { JSONArray(o?.optString("resource", "[]") ?: "[]") }.getOrNull()
                if (res != null && res.length() > 0) {
                    (0 until res.length()).mapNotNull { res.optJSONObject(it) }.map {
                        val lang = it.optString("lang").ifBlank { it.optString("language", "Original") }
                        Dub(
                            dubId = it.optString("id"),
                            dubLang = lang,
                            dubName = it.optString("title").ifBlank { "$lang Audio" },
                            coverUrl = it.optString("img", it.optString("cover"))
                        )
                    }
                } else emptyList()
            }
        }
    }.getOrDefault(emptyList())

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
    data class Stream(
        val quality: String,
        val format: String,
        val url: String,
        val size: Int,
        val signCookie: String = ""
    )
    data class Detail(
        val hasResource: Boolean,
        val streams: List<Stream>,
        val vipLocked: Boolean,
        val maxResolution: Int
    )
    data class Ops(val rid: String, val traceId: String, val a: String, val esid: String)
    data class Dub(val dubId: String, val dubLang: String, val dubName: String, val coverUrl: String)
    data class Season(
        val number: Int,
        val title: String,
        val cover: String = "",
        val episodes: List<Episode> = emptyList()
    )
    data class Episode(
        val id: String,
        val number: Int,
        val seasonNumber: Int = 1,
        val title: String = "",
        val duration: Int = 0,
        val thumbnail: String = ""
    ) {
        val durationFormatted: String
            get() = if (duration <= 0) "" else {
                val h = duration / 3600
                val m = (duration % 3600) / 60
                if (h > 0) "${h}h ${m}m" else "${m}m"
            }
    }

    /**
     * Real stream URL info. MovieBox's mobile BFF
     * (`api6.aoneroom.com/wefeed-mobile-bff/subject-api/play-info`)
     * requires a signed session — we attempt it first and fall back to
     * the web BFF's `/subject/play` if the mobile BFF is unreachable or
     * the request is region-blocked.
     *
     * The fields mirror [VideoDetailStream] in the decompiled MovieBox
     * APK: `format`, `id`, `url`, `resolutions`, `size`, `duration`,
     * `signCookie`, `extCaptions`.
     */
    data class PlayInfo(
        val id: String,
        val subjectId: String,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val streams: List<Stream>
    )

    /**
     * Fetches stream URLs for a single subject (movie) or episode
     * (TV series). `seasonNumber` and `episodeNumber` only matter for
     * series — for movies they are ignored.
     */
    fun playInfo(subjectId: String, seasonNumber: Int = 1, episodeNumber: Int = 1): PlayInfo? {
        // First try the mobile BFF (the real one MovieBox uses)
        runCatching {
            val mobile = mobilePlayInfo(subjectId, seasonNumber, episodeNumber)
            if (mobile != null && mobile.streams.isNotEmpty()) return mobile
        }
        // Then try the web BFF (returns empty for guest sessions but
        // is useful when the user has a paid session)
        return runCatching {
            val d = detail(subjectId) ?: return@runCatching null
            PlayInfo(
                id = "",
                subjectId = subjectId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                streams = d.streams
            )
        }.getOrNull()
    }

    /**
     * Calls the mobile BFF endpoint that the decompiled MovieBox APK
     * uses. The host is `apii.inmoviebox.com` (MovieBox modded uses
     * this one — confirmed by pcapdroid capture showing 289 KB
     * transferred to/from this host with 6+ long-lived connections).
     * The modded APK has `isPremium` always true, so it gets real stream
     * URLs back along with a `signCookie` that must be sent when the
     * CDN URL is hit for actual download/playback.
     */
    private fun mobilePlayInfo(subjectId: String, seasonNumber: Int, episodeNumber: Int): PlayInfo? = runCatching {
        // Try the real MovieBox modded host first (single-i api.inmoviebox.com
        // is internal; the public-facing one in pcapdroid is apii.inmoviebox.com)
        val hosts = listOfNotNull(
            cachedMobileHost(),
            "apii.inmoviebox.com",
            "api.inmoviebox.com",
            "api6.aoneroom.com",
            "api5.aoneroom.com"
        ).distinct()
        for (host in hosts) {
            val r = tryMobileHost(host, subjectId, seasonNumber, episodeNumber) ?: continue
            if (r.streams.isNotEmpty()) return@runCatching r
        }
        null
    }.getOrNull()

    private fun tryMobileHost(host: String, subjectId: String, seasonNumber: Int, episodeNumber: Int): PlayInfo? = runCatching {
        val url = URL("https://$host/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$seasonNumber&ep=$episodeNumber")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-Request-Lang", "en")
        c.setRequestProperty("X-Client-Info", "{\"timezone\":\"$TIMEZONE\"}")
        c.setRequestProperty("Referer", "https://h5.inmoviebox.com/")
        c.setRequestProperty("Origin", "https://h5.inmoviebox.com")
        val jwt = cachedJwt
        if (!jwt.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $jwt")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) {
            Log.w(TAG, "play-info[$host] -> HTTP $code: ${body.take(120)}")
            return@runCatching null
        }
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return@runCatching null
        val arr = data.optJSONArray("streams") ?: data.optJSONArray("videoResourceList")
        val out = ArrayList<Stream>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val urlStr = o.optString("url", o.optString("mainUrl"))
                if (urlStr.isBlank()) continue
                out.add(Stream(
                    quality = o.optString("resolutions", o.optString("quality", "HD")),
                    format = o.optString("format", "hls"),
                    url = urlStr,
                    size = o.optInt("size", 0),
                    signCookie = o.optString("signCookie", o.optString("sign", ""))
                ))
            }
        }
        PlayInfo(
            id = data.optString("id"),
            subjectId = subjectId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            streams = out
        )
    }.getOrNull()

    private fun cachedMobileHost(): String? = null
    data class SubjectInfo(
        val id: String,
        val title: String,
        val year: String = "",
        val rating: String = "",
        val description: String = "",
        val duration: Int = 0,
        val genre: String = "",
        val country: String = "",
        val poster: String = "",
        val backdrop: String = "",
        val type: String = "1",
        val corner: String = "",
        val subtitles: List<String> = emptyList(),
        val detailPath: String = "",
        val hasResource: Boolean = false,
        val webHighRisk: Boolean = false,
        val accessStrategy: String? = null,
        val ops: Ops = Ops("", "", "", ""),
        val dubs: List<Dub> = emptyList()
    ) {
        val typeLabel: String
            get() = when (type) {
                "1" -> "Movie"
                "2" -> "TV Series"
                "3" -> "Short Film"
                "4" -> "Anime"
                "5" -> "Cartoon"
                "6" -> "Music Video"
                "9" -> "Sports"
                else -> "Video"
            }

        val durationFormatted: String
            get() {
                if (duration <= 0) return ""
                val h = duration / 3600
                val m = (duration % 3600) / 60
                val s = duration % 60
                return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
            }
    }
}
