package com.asghar.downloader.utils

import android.net.Uri

object LinkParser {
    /**
     * yt-dlp supports many more sites than a fixed platform allow-list.
     * Accept any well-formed HTTP(S) URL and let yt-dlp decide whether the
     * specific site/extractor is supported.
     */
    fun detectPlatform(url: String): String {
        val parsed = try { Uri.parse(url.trim()) } catch (_: Exception) { null }
        val scheme = parsed?.scheme?.lowercase()
        val host = parsed?.host?.lowercase().orEmpty()

        if (scheme != "http" && scheme != "https") return "Unknown"
        if (host.isBlank()) return "Unknown"

        return when {
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> "YouTube"
            host == "fb.watch" || host == "facebook.com" || host.endsWith(".facebook.com") -> "Facebook"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") -> "Twitter"
            host == "soundcloud.com" || host.endsWith(".soundcloud.com") -> "SoundCloud"
            else -> "Supported URL"
        }
    }
}
