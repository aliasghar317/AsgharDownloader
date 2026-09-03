package com.asghar.downloader.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.asghar.downloader.PlatformVideosActivity
import com.asghar.downloader.R
import com.asghar.downloader.VideoPlayerActivity
import com.asghar.downloader.views.AnimatedProgressBar
import com.asghar.downloader.databinding.ItemDownloadBinding
import com.asghar.downloader.utils.DownloadStore
import com.asghar.downloader.utils.ThumbnailCache
import com.asghar.downloader.utils.DownloadStorage
import java.util.concurrent.Executors

class DownloadsAdapter(
    private val context: Context,
    private val action: (DownloadStore.Task, String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_SECTION_HEADER = 0
        const val TYPE_DOWNLOAD_ITEM = 1
        const val TYPE_FOLDER = 2
    }

    sealed class ListItem {
        data class SectionHeader(val title: String, val actionText: String = "") : ListItem()
        data class DownloadItem(val task: DownloadStore.Task) : ListItem()
        data class FolderItem(val platform: String, val tasks: List<DownloadStore.Task>) : ListItem()
    }

    private var items = emptyList<ListItem>()
    private val thumbExecutor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapCache = android.util.LruCache<String, Bitmap>(24)

    fun submitAll(all: List<DownloadStore.Task>) {
        val result = mutableListOf<ListItem>()
        val active = all.filter { it.status != "completed" }
        val completed = all.filter { it.status == "completed" }

        if (active.isNotEmpty()) {
            result += ListItem.SectionHeader("Downloading", active.size.toString())
            active.forEach { result += ListItem.DownloadItem(it) }
        }

        val free = runCatching { android.os.Environment.getExternalStorageDirectory().freeSpace }.getOrDefault(0L)
        result += ListItem.SectionHeader("Downloaded", if (free > 0) "${DownloadStore.formatBytes(free)} available" else "")

        val order = listOf(
            "Facebook Videos" to listOf("facebook", "fb.com", "fb.watch"),
            "YouTube Videos" to listOf("youtube", "youtu.be"),
            "TikTok Videos" to listOf("tiktok"),
            "Instagram Videos" to listOf("instagram", "instagr.am"),
            "Twitter Videos" to listOf("twitter", "x.com"),
            "SoundCloud Videos" to listOf("soundcloud")
        )
        val groups = linkedMapOf<String, MutableList<DownloadStore.Task>>()
        order.forEach { groups[it.first] = mutableListOf() }
        groups["Other Videos"] = mutableListOf()

        completed.forEach { task ->
            val u = task.url.lowercase()
            val match = order.firstOrNull { (_, keys) -> keys.any(u::contains) }?.first
            groups[match ?: "Other Videos"]?.add(task)
        }
        groups.forEach { (name, tasks) ->
            if (tasks.isNotEmpty()) result += ListItem.FolderItem(name, tasks.sortedByDescending { it.id })
        }

        val oldItems = items
        val newItems = result.toList()
        if (oldItems == newItems) return
        items = newItems
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return itemKey(oldItems[oldItemPosition]) == itemKey(newItems[newItemPosition])
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        }).dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.SectionHeader -> TYPE_SECTION_HEADER
        is ListItem.DownloadItem -> TYPE_DOWNLOAD_ITEM
        is ListItem.FolderItem -> TYPE_FOLDER
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_FOLDER -> FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
            else -> ItemHolder(ItemDownloadBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.SectionHeader -> bindHeader(holder as HeaderHolder, item)
            is ListItem.DownloadItem -> bindDownload(holder as ItemHolder, item.task)
            is ListItem.FolderItem -> bindFolder(holder as FolderHolder, item)
        }
    }

    private fun bindHeader(holder: HeaderHolder, item: ListItem.SectionHeader) {
        holder.title.text = item.title
        holder.action.text = item.actionText
        holder.action.visibility = if (item.actionText.isBlank()) View.GONE else View.VISIBLE
    }

    private fun bindFolder(holder: FolderHolder, item: ListItem.FolderItem) {
        holder.name.text = item.platform
        val total = item.tasks.sumOf { it.totalBytes.coerceAtLeast(0L) }
        holder.meta.text = "${item.tasks.size} videos${if (total > 0) " · ${DownloadStore.formatBytes(total)}" else ""}"
        val watched = item.tasks.map { it.watchedPercent }.average().toInt().coerceIn(0, 100)
        holder.status.text = if (watched > 0) "$watched% watched" else "Not open"
        val thumbTask = item.tasks.firstOrNull { it.thumbnailUrl.isNotBlank() } ?: item.tasks.firstOrNull()
        loadThumbnail(holder.thumb, thumbTask?.thumbnailUrl, thumbTask?.outputPath)

        val open = View.OnClickListener {
            context.startActivity(Intent(context, PlatformVideosActivity::class.java).apply {
                putExtra("PLATFORM", item.platform)
                putStringArrayListExtra("TASK_IDS", ArrayList(item.tasks.map { it.id }))
            })
        }
        holder.itemView.setOnClickListener(open)
        holder.open.setOnClickListener(open)
    }

    private fun bindDownload(holder: ItemHolder, task: DownloadStore.Task) {
        val b = holder.binding
        val thumbKey = task.thumbnailUrl.ifBlank { task.outputPath }
        if (b.ivThumb.tag != thumbKey) {
            b.ivThumb.tag = thumbKey
            b.ivThumb.setImageResource(R.drawable.thumb_placeholder)
        }
        b.watchProgress.visibility = if (task.watchedPercent > 0) View.VISIBLE else View.GONE
        if (task.watchedPercent > 0) b.watchProgress.progress = task.watchedPercent
        loadThumbnail(b.ivThumb, task.thumbnailUrl, task.outputPath)
        b.tvQualityBadge.text = when (task.quality) {
            "4k" -> "4K"; "1080p" -> "1080P"; "720p" -> "720P"; "480p" -> "480P"
            "360p" -> "360P"; "240p" -> "240P"; "mp3" -> "MP3"; else -> task.quality.uppercase()
        }
        b.tvFileName.text = DownloadStore.cleanTitle(task.title).ifBlank { "Downloading video" }
        holder.itemView.setOnLongClickListener { showDeleteDialog(task); true }

        b.tvMeta.text = when {
            task.status == "completed" && task.totalBytes > 0 -> DownloadStore.formatBytes(task.totalBytes)
            task.totalBytes > 0 -> "${DownloadStore.formatBytes(task.downloadedBytes)} / ${DownloadStore.formatBytes(task.totalBytes)}"
            task.downloadedBytes > 0 -> DownloadStore.formatBytes(task.downloadedBytes)
            task.sizeLabel.isNotBlank() -> task.sizeLabel
            else -> ""
        }

        when (task.status) {
            "paused" -> {
                setProgress(b.progress, task.progress)
                b.tvStatus.text = "Paused · ${task.progress}%"
                b.tvStatus.setTextColor(0xFFFFB020.toInt())
                b.btnPause.text = "Resume"
                b.btnPause.setBackgroundResource(R.drawable.play_button_bg)
                b.btnPause.isEnabled = true
                b.btnPause.setOnClickListener { action(task, "RESUME") }
            }
            "failed" -> {
                setProgress(b.progress, task.progress)
                b.tvStatus.text = "Failed"
                b.tvStatus.setTextColor(0xFFFF5B5B.toInt())
                b.btnPause.text = "Retry"
                b.btnPause.setBackgroundResource(R.drawable.play_button_bg)
                b.btnPause.isEnabled = true
                b.btnPause.setOnClickListener { action(task, "RESUME") }
            }
            "completed" -> {
                setProgress(b.progress, 100)
                val watched = task.watchedPercent
                b.tvStatus.text = if (watched > 0) "$watched% watched" else "Finished"
                b.tvStatus.setTextColor(0xFF20E6A1.toInt())
                b.btnPause.text = "Play"
                b.btnPause.setBackgroundResource(R.drawable.play_button_bg)
                b.btnPause.isEnabled = true
                b.btnPause.setOnClickListener { openFile(task) }
            }
            else -> {
                setProgress(b.progress, task.progress)
                val live = mutableListOf<String>()
                if (task.speed.isNotBlank()) live += task.speed
                if (task.eta.isNotBlank()) live += task.eta
                if (task.progress > 0) live += "${task.progress}%"
                b.tvStatus.text = live.joinToString(" · ").ifBlank { "Starting…" }
                b.tvStatus.setTextColor(0xFFAAAAAA.toInt())
                b.btnPause.text = "Pause"
                b.btnPause.setBackgroundResource(R.drawable.pause_button_bg)
                b.btnPause.isEnabled = true
                b.btnPause.setOnClickListener { action(task, "PAUSE") }
            }
        }
    }

    private fun setProgress(progress: AnimatedProgressBar, value: Int) {
        progress.setProgress(value, true)
    }

    private fun showDeleteDialog(task: DownloadStore.Task) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Delete download?")
            .setMessage(task.title.ifBlank { "This download" })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                DownloadStorage.deletePublishedFile(context, task.outputPath)
                java.io.File(task.workDir).deleteRecursively()
                DownloadStore.remove(context, task.id)
                submitAll(DownloadStore.all(context))
            }
            .show()
    }

    private fun itemKey(item: ListItem): String = when (item) {
        is ListItem.SectionHeader -> "header:${item.title}"
        is ListItem.DownloadItem -> "task:${item.task.id}"
        is ListItem.FolderItem -> "folder:${item.platform}"
    }

    private fun loadThumbnail(view: ImageView, remoteUrl: String?, localPath: String?) {
        val remote = remoteUrl.orEmpty()
        val local = localPath.orEmpty()
        val key = remote.ifBlank { local }
        view.tag = key
        if (key.isBlank()) return
        val cached = bitmapCache.get(key) ?: ThumbnailCache.get(context, key)
        if (cached != null) {
            bitmapCache.put(key, cached)
            view.setImageBitmap(cached)
            return
        }
        thumbExecutor.execute {
            val bitmap = if (remote.isNotBlank()) ThumbnailCache.loadRemote(context, remote) else null
                ?: ThumbnailCache.loadLocal(context, local)
            if (bitmap != null) {
                ThumbnailCache.put(context, key, bitmap)
                bitmapCache.put(key, bitmap)
                mainHandler.post {
                    if (view.isAttachedToWindow && view.tag == key) view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun openFile(task: DownloadStore.Task) {
        if (task.outputPath.isBlank()) {
            Toast.makeText(context, "File path not available", Toast.LENGTH_SHORT).show(); return
        }
        context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
            putExtra("URI", task.outputPath); putExtra("TITLE", task.title); putExtra("TASK_ID", task.id)
        })
    }

    class ItemHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvHeaderTitle)
        val action: TextView = v.findViewById(R.id.tvHeaderAction)
    }
    class FolderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.ivFolderThumb)
        val name: TextView = v.findViewById(R.id.tvFolderName)
        val meta: TextView = v.findViewById(R.id.tvFolderMeta)
        val status: TextView = v.findViewById(R.id.tvFolderStatus)
        val open: Button = v.findViewById(R.id.btnFolderOpen)
    }
}
