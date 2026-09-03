package com.asghar.downloader.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object DownloadStorage {
    private const val ROOT = "AsgharDownloader"
    private const val VIDEO = "Video"
    private const val AUDIO = "Audio"
    private const val MOVIES = "Movies"

    fun createWorkDirectory(context: Context, taskId: String): File {
        val base = File(context.filesDir, "asghar-downloads/$taskId")
        return base.apply { mkdirs() }
    }

    fun deleteWorkDirectory(directory: File) {
        directory.deleteRecursively()
    }

    fun publishDownloadedFile(context: Context, file: File): Uri? {
        if (!file.exists()) return null
        val isAudio = file.extension.lowercase() in setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")
        val subDir = if (isAudio) AUDIO else VIDEO

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())) {
            val root = File(Environment.getExternalStorageDirectory(), ROOT)
            val targetDir = File(root, subDir).apply { mkdirs() }
            val target = uniqueTarget(File(targetDir, file.name))
            file.copyTo(target, overwrite = false)
            file.delete()
            return Uri.fromFile(target)
        }

        val mime = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"; "opus" -> "audio/ogg"; "aac" -> "audio/aac"
            "flac" -> "audio/flac"; "mp4" -> "video/mp4"; "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$subDir")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                ?: throw IllegalStateException("Unable to open destination")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun deletePublishedFile(context: Context, uriString: String): Boolean {
        if (uriString.isBlank()) return false
        return runCatching {
            if (uriString.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(uriString), null, null) > 0
            } else {
                val raw = uriString.removePrefix("file://")
                File(raw).delete()
            }
        }.getOrDefault(false)
    }

    fun publicRoot(): File = File(Environment.getExternalStorageDirectory(), ROOT)
    fun videoDir(): File = File(publicRoot(), VIDEO)
    fun audioDir(): File = File(publicRoot(), AUDIO)
    fun moviesDir(): File = File(publicRoot(), MOVIES)

    private fun uniqueTarget(original: File): File {
        if (!original.exists()) return original
        val base = original.nameWithoutExtension
        val ext = original.extension
        var i = 2
        var candidate: File
        do { candidate = File(original.parentFile, "$base ($i).$ext"); i++ } while (candidate.exists())
        return candidate
    }
}
