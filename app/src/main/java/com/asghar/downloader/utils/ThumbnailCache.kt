package com.asghar.downloader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Persistent, small thumbnail cache. It survives activity/app restarts so thumbnails are not re-downloaded. */
object ThumbnailCache {
    private const val DIR = "thumbnail_cache"
    private const val MAX_FILES = 300
    private const val MAX_BYTES = 50L * 1024L * 1024L

    private fun file(context: Context, key: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    fun get(context: Context, key: String): Bitmap? {
        if (key.isBlank()) return null
        val f = file(context, key)
        if (!f.isFile || f.length() == 0L) return null
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    fun put(context: Context, key: String, bitmap: Bitmap) {
        if (key.isBlank()) return
        val f = file(context, key)
        runCatching {
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            f.setLastModified(System.currentTimeMillis())
            trim(context)
        }
    }

    fun loadRemote(context: Context, url: String): Bitmap? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
        }
        try {
            c.inputStream.use { input ->
                val bytes = input.readBytes()
                decodeSampled(bytes)
            }
        } finally { c.disconnect() }
    }.getOrNull()

    fun loadLocal(context: Context, path: String): Bitmap? = runCatching {
        if (path.isBlank()) return@runCatching null
        val r = MediaMetadataRetriever()
        // Always use the (context, Uri) overload: it works for both content://
        // URIs (MediaStore) and file:// paths on every supported Android version.
        // The (String) overload has historically failed with content URIs and
        // with some file:// paths on Android 11+.
        val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
            Uri.parse(path)
        } else {
            Uri.fromFile(File(path))
        }
        r.setDataSource(context, uri)
        val times = longArrayOf(1_000_000L, 2_500_000L, 5_000_000L, 8_000_000L)
        var best: Bitmap? = null
        for (time in times) {
            val b = r.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
            best = b
            if (!isMostlyBlack(b)) break
        }
        r.release()
        best?.let { scale(it) }
    }.getOrNull()

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val maxW = 480
        if (bitmap.width <= maxW) return bitmap
        val h = (bitmap.height.toFloat() * maxW / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxW, h, true)
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        while (w / s > 480 || h / s > 480) s *= 2
        return s
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width.coerceAtMost(32)
        val h = bitmap.height.coerceAtMost(32)
        if (w <= 0 || h <= 0) return true
        var sum = 0L
        var count = 0
        for (x in 0 until w) for (y in 0 until h) {
            val px = bitmap.getPixel(x, y)
            sum += ((px shr 16) and 255) + ((px shr 8) and 255) + (px and 255)
            count++
        }
        return count > 0 && sum / count < 35
    }

    private fun trim(context: Context) {
        val files = File(context.filesDir, DIR).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (f in files) {
            if (count <= MAX_FILES && total <= MAX_BYTES) break
            val size = f.length()
            if (f.delete()) { total -= size; count-- }
        }
    }
}
