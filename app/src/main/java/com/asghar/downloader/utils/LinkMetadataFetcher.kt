package com.asghar.downloader.utils

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** Lightweight OpenGraph metadata reader used for title/thumbnail previews. */
object LinkMetadataFetcher {
    data class Metadata(val title: String = "", val thumbnailUrl: String = "")

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"

    fun fetch(url: String): Metadata {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            Metadata(
                title = findMeta(html, "og:title")
                    .ifBlank { findMeta(html, "twitter:title") }
                    .ifBlank { findHtmlTitle(html) },
                thumbnailUrl = findMeta(html, "og:image")
                    .ifBlank { findMeta(html, "twitter:image") }
            ).let { it.copy(title = clean(it.title)) }
        } catch (_: Exception) {
            Metadata()
        } finally {
            connection.disconnect()
        }
    }

    private fun findMeta(html: String, property: String): String {
        val escaped = Pattern.quote(property)
        val patterns = listOf(
            Pattern.compile("<meta[^>]+(?:property|name)=[\\\"']$escaped[\\\"'][^>]+content=[\\\"']([^\\\"']*)[\\\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<meta[^>]+content=[\\\"']([^\\\"']*)[\\\"'][^>]+(?:property|name)=[\\\"']$escaped[\\\"']", Pattern.CASE_INSENSITIVE)
        )
        for (pattern in patterns) {
            val match = pattern.matcher(html)
            if (match.find()) return Html.fromHtml(match.group(1), Html.FROM_HTML_MODE_LEGACY).toString()
        }
        return ""
    }

    private fun findHtmlTitle(html: String): String {
        val match = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        return if (match.find()) Html.fromHtml(match.group(1), Html.FROM_HTML_MODE_LEGACY).toString() else ""
    }

    private fun clean(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(240)
}
