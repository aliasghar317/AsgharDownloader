package com.asghar.downloader.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DownloadStore {
    private const val PREFS = "download_store"
    private const val KEY_TASKS = "tasks"

    data class Task(
        val id: String,
        val url: String,
        val quality: String,
        val title: String,
        val progress: Int,
        val status: String,
        val workDir: String,
        val processId: String,
        val outputPath: String = "",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speed: String = "",
        val eta: String = "",
        val sizeLabel: String = "",
        val thumbnailUrl: String = "",
        val watchedPercent: Int = 0,
        val playbackPositionMs: Long = 0L,
        val durationMs: Long = 0L
    )

    fun all(context: Context): List<Task> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TASKS, "[]") ?: "[]"
        val result = mutableListOf<Task>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result += Task(
                    id = o.optString("id"),
                    url = o.optString("url"),
                    quality = o.optString("quality"),
                    title = o.optString("title", "Downloading video"),
                    progress = o.optInt("progress", 0),
                    status = o.optString("status", "queued"),
                    workDir = o.optString("workDir"),
                    processId = o.optString("processId"),
                    outputPath = o.optString("outputPath"),
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    totalBytes = o.optLong("totalBytes", 0L),
                    speed = o.optString("speed", ""),
                    eta = o.optString("eta", ""),
                    sizeLabel = o.optString("sizeLabel", ""),
                    thumbnailUrl = o.optString("thumbnailUrl", ""),
                    watchedPercent = o.optInt("watchedPercent", 0),
                    playbackPositionMs = o.optLong("playbackPositionMs", 0L),
                    durationMs = o.optLong("durationMs", 0L)
                )
            }
        }
        return result.sortedByDescending { it.id }
    }

    fun get(context: Context, id: String): Task? = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(context: Context, task: Task) {
        val current = all(context).filterNot { it.id == task.id }.toMutableList()
        current += task
        save(context, current)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    private fun save(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("url", t.url)
                put("quality", t.quality)
                put("title", t.title)
                put("progress", t.progress)
                put("status", t.status)
                put("workDir", t.workDir)
                put("processId", t.processId)
                put("outputPath", t.outputPath)
                put("downloadedBytes", t.downloadedBytes)
                put("totalBytes", t.totalBytes)
                put("speed", t.speed)
                put("eta", t.eta)
                put("sizeLabel", t.sizeLabel)
                put("thumbnailUrl", t.thumbnailUrl)
                put("watchedPercent", t.watchedPercent)
                put("playbackPositionMs", t.playbackPositionMs)
                put("durationMs", t.durationMs)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TASKS, arr.toString()).apply()
    }


    fun cleanTitle(title: String): String {
        var t = title.replace(Regex("\\s+"), " ").trim()
        if (t.isBlank()) return ""
        val statLabel = "(?:views?|reactions?|react(?:ions?)?|likes?|comments?|followers?|shares?)"
        // Strip a stats block at the beginning. Covers "15K reactions |", "2.3M views -",
        // "Reactions: 15K |", "15K likes ·" etc. The label and number can be in any order
        // and use any of | · • - as the separator.
        val statsStart = Regex(
            "(?i)^[\\s0-9.,KMBT+]*[:.]?\\s*$statLabel(?:\\s*[:.]\\s*[\\s0-9.,KMBT+]+)?(?:\\s*[·•|\\-:]+\\s*)+"
        )
        t = statsStart.replace(t, "").trim()
        // Also strip "<number> <label> |" at start when label comes after number.
        val statsStartAlt = Regex(
            "(?i)^[0-9.,KMBT+ ]+$statLabel(?:\\s*[·•|\\-:]+\\s*)+"
        )
        t = statsStartAlt.replace(t, "").trim()
        // Strip a stats block anywhere it appears as a standalone "<num> <label>" chunk.
        val statsAnywhere = Regex(
            "(?i)\\b[0-9]+(?:[.,][0-9]+)?\\s*[KMBTkmbt]?\\s*$statLabel\\b"
        )
        t = statsAnywhere.replace(t, "").trim()
        // Drop leading/trailing separator garbage left behind (|, ·, -, :, …).
        t = t.replace(Regex("^[\\s·•|\\-:,…]+"), "").trim()
        t = t.replace(Regex("[\\s·•|\\-:,…]+$"), "").trim()
        if (t.contains("|")) {
            val parts = t.split("|").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size > 1 && parts.first().matches(Regex("(?i).*[0-9].*(views?|reactions?|likes?|comments?|shares?).*"))) {
                t = parts.drop(1).joinToString(" | ")
            }
        }
        val onlyStats = Regex(
            "(?i)^[0-9.,KMBT+ ]+$statLabel(?:\\s*[·•|\\-]+\\s*[0-9.,KMBT+ ]+$statLabel)*$"
        )
        return if (onlyStats.matches(t)) "" else t.take(240)
    }
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.1fGB", bytes / gb)
            bytes >= mb -> String.format("%.1fMB", bytes / mb)
            bytes >= kb -> String.format("%.0fKB", bytes / kb)
            else -> "${bytes}B"
        }
    }

    fun formatEta(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format("%dh %dm", h, m)
            m > 0 -> String.format("%d min %d sec left", m, s)
            else -> String.format("%d sec left", s)
        }
    }
}
