package com.asghar.downloader

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asghar.downloader.utils.DownloadStore
import com.asghar.downloader.utils.DownloadStorage
import com.asghar.downloader.utils.ThumbnailCache
import java.util.concurrent.Executors

class PlatformVideosActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_platform_videos)
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val platform = intent.getStringExtra("PLATFORM") ?: "Videos"
        findViewById<TextView>(R.id.tvTitle).text = platform
        val ids = intent.getStringArrayListExtra("TASK_IDS") ?: arrayListOf()
        val recycler = findViewById<RecyclerView>(R.id.rvVideos)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator = null
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(6)
        recycler.adapter = VideoListAdapter(ids)
    }

    override fun onResume() {
        super.onResume()
        (findViewById<RecyclerView>(R.id.rvVideos).adapter as? VideoListAdapter)?.refreshTasks()
    }

    private inner class VideoListAdapter(private val ids: List<String>) : RecyclerView.Adapter<VideoListAdapter.Holder>() {
        private var tasks: List<DownloadStore.Task> = ids.mapNotNull { DownloadStore.get(this@PlatformVideosActivity, it) }
        private val executor = Executors.newFixedThreadPool(3)
        private val main = Handler(Looper.getMainLooper())
        private val cache = android.util.LruCache<String, Bitmap>(16)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

        override fun getItemCount() = tasks.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val task = tasks[position]
            holder.title.text = DownloadStore.cleanTitle(task.title).ifBlank { "Video ${position + 1}" }
            holder.size.text = when {
                task.totalBytes > 0 -> DownloadStore.formatBytes(task.totalBytes)
                task.sizeLabel.isNotBlank() -> task.sizeLabel
                else -> ""
            }
            holder.status.text = if (task.watchedPercent > 0) "${task.watchedPercent}% watched" else "Finished"
            holder.watchProgress.visibility = if (task.watchedPercent > 0) View.VISIBLE else View.GONE
            if (task.watchedPercent > 0) holder.watchProgress.progress = task.watchedPercent
            loadThumb(holder.thumb, task.thumbnailUrl, task.outputPath)
            holder.play.setOnClickListener { play(task) }
            holder.itemView.setOnClickListener { play(task) }
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@PlatformVideosActivity)
                    .setTitle("Delete video?")
                    .setMessage(task.title)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        DownloadStorage.deletePublishedFile(this@PlatformVideosActivity, task.outputPath)
                        java.io.File(task.workDir).deleteRecursively()
                        DownloadStore.remove(this@PlatformVideosActivity, task.id)
                        refreshTasks()
                    }.show()
                true
            }
        }

        fun refreshTasks() {
            tasks = ids.mapNotNull { DownloadStore.get(this@PlatformVideosActivity, it) }
            notifyDataSetChanged()
        }

        private fun play(task: DownloadStore.Task) {
            if (task.outputPath.isBlank()) return
            startActivity(Intent(this@PlatformVideosActivity, VideoPlayerActivity::class.java).apply {
                putExtra("URI", task.outputPath)
                putExtra("TITLE", task.title)
                putExtra("TASK_ID", task.id)
            })
        }

        private fun loadThumb(view: ImageView, remote: String, local: String) {
            val key = remote.ifBlank { local }
            view.tag = key
            if (key.isBlank()) return
            val cached = cache.get(key) ?: ThumbnailCache.get(this@PlatformVideosActivity, key)
            if (cached != null) {
                cache.put(key, cached)
                view.setImageBitmap(cached)
                return
            }
            executor.execute {
                val bmp = if (remote.isNotBlank()) ThumbnailCache.loadRemote(this@PlatformVideosActivity, remote) else null
                    ?: ThumbnailCache.loadLocal(this@PlatformVideosActivity, local)
                if (bmp != null) {
                    ThumbnailCache.put(this@PlatformVideosActivity, key, bmp)
                    cache.put(key, bmp)
                    main.post { if (view.isAttachedToWindow && view.tag == key) view.setImageBitmap(bmp) }
                }
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.ivThumb)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val size: TextView = v.findViewById(R.id.tvSize)
            val status: TextView = v.findViewById(R.id.tvStatus)
            val play: Button = v.findViewById(R.id.btnPlay)
            val watchProgress: android.widget.ProgressBar = v.findViewById(R.id.watchProgress)
        }
    }
}
