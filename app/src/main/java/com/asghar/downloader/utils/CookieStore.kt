package com.asghar.downloader.utils

import android.content.Context
import java.io.File

/**
 * Minimal cookie store that lets the user paste YouTube cookies in
 * Netscape format. The raw cookie text is saved to an internal file
 * and passed to yt-dlp via --cookies.
 *
 * Usage:
 *   1. Open youtube.com in a browser, log in.
 *   2. Use a browser extension (e.g. "Get cookies.txt") to export
 *      cookies for youtube.com as Netscape format.
 *   3. In the app, go to Settings > YouTube Cookies, paste the
 *      entire cookie file content, and save.
 *   4. yt-dlp will use --cookies with this file for YouTube downloads.
 */
object CookieStore {
    private const val COOKIES_FILE = "youtube_cookies.txt"

    fun cookiesFile(ctx: Context): File {
        val f = File(ctx.filesDir, COOKIES_FILE)
        if (!f.exists()) f.writeText("")
        return f
    }

    fun hasCookies(ctx: Context): Boolean {
        val f = cookiesFile(ctx)
        return f.exists() && f.length() > 50
    }

    fun getCookies(ctx: Context): String {
        return cookiesFile(ctx).takeIf { it.exists() }?.readText().orEmpty()
    }

    fun saveCookies(ctx: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.contains("\t") && !trimmed.contains(" #")) {
            return
        }
        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# This is a generated file. Do not edit.\n")
        sb.append(trimmed)
        if (!trimmed.endsWith("\n")) sb.append("\n")
        cookiesFile(ctx).writeText(sb.toString())
    }

    fun clearCookies(ctx: Context) {
        cookiesFile(ctx).writeText("")
    }
}
