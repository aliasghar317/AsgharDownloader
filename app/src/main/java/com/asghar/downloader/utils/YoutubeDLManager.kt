package com.asghar.downloader.utils

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import java.io.File

object YoutubeDLManager {
    private const val TAG = "YoutubeDLManager"

    @Volatile var ready: Boolean = false
        private set

    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            val app = context.applicationContext
            try {
                YoutubeDL.getInstance().init(app)
                Log.d(TAG, "YoutubeDL.init succeeded")
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "YoutubeDL.init failed", e)
                throw e
            }
            runCatching { FFmpeg.getInstance().init(app) }
            overwriteBundledYtDlp(app)
            Log.d(TAG, "yt-dlp overwrite step finished, engine ready")
            ready = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Engine initialization failed", e)
            e.printStackTrace()
            false
        }
    }

    private fun overwriteBundledYtDlp(context: Context) {
        val ytdlpDir = File(context.noBackupFilesDir, "youtubedl-android")
        val target = File(ytdlpDir, "yt-dlp")

        // Step 1: read bundled size from assets (the freshest yt-dlp we shipped)
        val bundledBytes = runCatching {
            context.assets.open("yt-dlp/yt-dlp").use { it.readBytes() }
        }.getOrNull()
        if (bundledBytes == null || bundledBytes.isEmpty()) {
            Log.w(TAG, "yt-dlp asset missing or empty, leaving engine as-is")
            return
        }
        val bundledSize = bundledBytes.size.toLong()
        Log.d(TAG, "Bundled yt-dlp size = $bundledSize bytes")

        // Step 2: if target already matches, nothing to do
        if (target.exists() && target.length() == bundledSize) {
            Log.d(TAG, "yt-dlp already up-to-date (${target.length()} bytes)")
            target.setExecutable(true, false)
            return
        }

        // Step 3: write atomically. Always remove the existing file first so the
        // Python interpreter cannot pick up a half-written new copy while the
        // library still has its ytdlpPath pointing here.
        if (!ytdlpDir.exists() && !ytdlpDir.mkdirs()) {
            Log.w(TAG, "Cannot create $ytdlpDir, skipping overwrite")
            return
        }
        if (target.exists()) {
            if (!target.delete()) {
                Log.w(TAG, "Could not delete old yt-dlp at $target, skipping overwrite")
                return
            }
        }
        try {
            target.writeBytes(bundledBytes)
            target.setExecutable(true, false)
            Log.d(TAG, "yt-dlp overwritten: ${target.length()} bytes (was ${if (target.exists()) "different" else "missing"})")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to write new yt-dlp", e)
            return
        }
    }
}