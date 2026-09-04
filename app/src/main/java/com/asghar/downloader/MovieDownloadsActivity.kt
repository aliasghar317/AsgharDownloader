package com.asghar.downloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asghar.downloader.services.DownloadService
import com.asghar.downloader.utils.DownloadStore
import com.asghar.downloader.utils.EdgeToEdge
import com.asghar.downloader.utils.MovieBoxApi
import com.asghar.downloader.utils.ThumbnailCache
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MovieBox-style downloads tab for the Movies feature.
 *
 * Layout:
 *   - Top bar (back + "Downloads" + "Transfer" badge)
 *   - "Downloading" section (in-flight movies + series with a progress bar)
 *   - "Downloaded" section (every finished movie, with the MovieBox-style
 *     poster, title, size, "Play" button, etc.)
 *
 * The data source is [DownloadStore] which already tracks every download
 * initiated from the Movie detail sheet or the WebView player.
 */
class MovieDownloadsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvDownloadingCount: TextView
    private lateinit var tvDownloadedSpace: TextView
    private lateinit var containerDownloading: LinearLayout
    private lateinit var containerDownloaded: LinearLayout
    private lateinit var emptyState: LinearLayout

    private val lastProgressUpdate = ConcurrentHashMap<String, Long>()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            window.decorView.postDelayed(this, 600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_downloads)
        EdgeToEdge.apply(window.decorView)

        btnBack = findViewById(R.id.btnBack)
        tvDownloadingCount = findViewById(R.id.tvDownloadingCount)
        tvDownloadedSpace = findViewById(R.id.tvDownloadedSpace)
        containerDownloading = findViewById(R.id.containerDownloading)
        containerDownloaded = findViewById(R.id.containerDownloaded)
        emptyState = findViewById(R.id.emptyState)

        btnBack.setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnTransfer).setOnClickListener {
            Toast.makeText(this, "Transfer manager (coming soon)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        window.decorView.removeCallbacks(refreshRunnable)
        window.decorView.post(refreshRunnable)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        window.decorView.handler?.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh() {
        val all = DownloadStore.all(this)
        val downloading = all.filter { it.status == "downloading" || it.status == "queued" || it.status == "preparing" || it.status == "starting" }
        val downloaded = all.filter { it.status == "completed" }
        val failed = all.filter { it.status == "failed" }

        // Bind counts
        val totalDownloading = downloading.size + failed.size
        tvDownloadingCount.text = totalDownloading.toString()
        tvDownloadedSpace.text = formatSpace(remainingBytes())

        // Downloading section
        containerDownloading.removeAllViews()
        if (downloading.isEmpty() && failed.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No active downloads"
                setTextColor(0xFF7C8AA5.toInt())
                textSize = 13f
                setPadding(20, 12, 20, 12)
            }
            containerDownloading.addView(empty)
        } else {
            (downloading + failed).forEach { task ->
                containerDownloading.addView(buildDownloadingRow(task))
            }
        }

        // Downloaded section
        containerDownloaded.removeAllViews()
        if (downloaded.isEmpty()) {
            emptyState.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
            downloaded.forEach { task ->
                containerDownloaded.addView(buildDownloadedRow(task))
            }
        }
    }

    private fun buildDownloadingRow(task: DownloadStore.Task): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_movie_download_progress, containerDownloading, false)
        val tvTitle = row.findViewById<TextView>(R.id.tvTitle)
        val tvSub = row.findViewById<TextView>(R.id.tvSub)
        val progress = row.findViewById<ProgressBar>(R.id.progress)
        val btnPause = row.findViewById<Button>(R.id.btnPause)
        val btnDelete = row.findViewById<ImageButton>(R.id.btnDelete)
        val quality = row.findViewById<TextView>(R.id.tvQuality)
        val thumb = row.findViewById<ImageView>(R.id.ivThumb)

        tvTitle.text = task.title.ifBlank { "Downloading…" }
        val total = task.totalBytes.coerceAtLeast(0L)
        val downloaded = task.downloadedBytes.coerceAtLeast(0L)
        val speed = task.speed
        val size = if (task.sizeLabel.isNotBlank()) task.sizeLabel else DownloadStore.formatBytes(total)
        tvSub.text = if (task.status == "failed") {
            "Failed - tap to retry"
        } else {
            "${DownloadStore.formatBytes(downloaded)} / $size" +
                if (speed.isNotBlank()) " · $speed" else ""
        }
        progress.progress = task.progress.coerceIn(0, 100)
        progress.isIndeterminate = task.status == "queued" || task.status == "preparing"
        quality.text = task.quality
        val meta = metaFor(task)
        if (meta?.poster?.isNotBlank() == true) {
            ThumbnailCache.loadInto(this, meta.poster, thumb)
        }
        btnPause.text = when (task.status) {
            "paused" -> "Resume"
            "failed" -> "Retry"
            else -> "Pause"
        }
        btnPause.setOnClickListener {
            when (task.status) {
                "paused", "failed" -> startService(Intent(this, DownloadService::class.java).apply {
                    putExtra("ACTION", "RESUME")
                    putExtra("TASK_ID", task.id)
                })
                else -> startService(Intent(this, DownloadService::class.java).apply {
                    putExtra("ACTION", "PAUSE")
                    putExtra("TASK_ID", task.id)
                })
            }
        }
        btnDelete.setOnClickListener {
            startService(Intent(this, DownloadService::class.java).apply {
                putExtra("ACTION", "PAUSE")
                putExtra("TASK_ID", task.id)
            })
            DownloadStore.remove(this, task.id)
            Toast.makeText(this, "Removed from list", Toast.LENGTH_SHORT).show()
        }
        row.setOnClickListener {
            if (task.status == "failed") {
                btnPause.performClick()
            }
        }
        return row
    }

    private fun buildDownloadedRow(task: DownloadStore.Task): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_movie_downloaded, containerDownloaded, false)
        val tvTitle = row.findViewById<TextView>(R.id.tvTitle)
        val tvSub = row.findViewById<TextView>(R.id.tvSub)
        val btnPlay = row.findViewById<Button>(R.id.btnPlay)
        val btnMore = row.findViewById<ImageButton>(R.id.btnMore)
        val thumb = row.findViewById<ImageView>(R.id.ivThumb)

        tvTitle.text = task.title.ifBlank { "Movie" }
        val size = task.sizeLabel.ifBlank { DownloadStore.formatBytes(task.downloadedBytes) }
        val parts = listOfNotNull(
            if (size.isNotBlank()) size else null,
            if (task.quality.isNotBlank()) task.quality else null
        )
        tvSub.text = parts.joinToString(" · ")
        val meta = metaFor(task)
        if (meta?.poster?.isNotBlank() == true) {
            ThumbnailCache.loadInto(this, meta.poster, thumb)
        }
        btnPlay.setOnClickListener { playTask(task) }
        row.setOnClickListener { playTask(task) }
        btnMore.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(task.title.ifBlank { "Options" })
                .setItems(arrayOf("Play", "Delete")) { _, which ->
                    when (which) {
                        0 -> playTask(task)
                        1 -> {
                            DownloadStore.remove(this, task.id)
                            Toast.makeText(this, "Removed from list", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
        return row
    }

    private fun playTask(task: DownloadStore.Task) {
        val path = task.outputPath
        if (path.isBlank()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = Uri.parse(path)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (_: Exception) {
            // Fallback to a raw file path
            val f = File(path.replace("file://", ""))
            if (f.exists()) {
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(f), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(i)
            } else {
                Toast.makeText(this, "Local file missing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun metaFor(task: DownloadStore.Task): MovieBoxApi.SubjectInfo? {
        val sid = task.subjectId
        if (sid.isBlank()) return null
        val raw = getPreferences(MODE_PRIVATE).getString("subject_meta_$sid", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            MovieBoxApi.SubjectInfo(
                id = o.optString("id"),
                title = o.optString("title"),
                poster = o.optString("poster"),
                backdrop = o.optString("backdrop")
            )
        }.getOrNull()
    }

    private fun remainingBytes(): Long {
        val stat = android.os.StatFs(Environment.getDataDirectory().path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    private fun formatSpace(bytes: Long): String {
        if (bytes <= 0) return "0 MB available"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0
        return if (gb >= 1) String.format("%.1fGB available", gb)
               else String.format("%.1fMB available", mb)
    }
}