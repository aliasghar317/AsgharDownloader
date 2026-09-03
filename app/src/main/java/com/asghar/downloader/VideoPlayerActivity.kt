package com.asghar.downloader

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.asghar.downloader.utils.DownloadStore

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var video: VideoView
    private val handler = Handler(Looper.getMainLooper())
    private var taskId: String? = null
    private var duration = 0

    private val progressSaver = object : Runnable {
        override fun run() {
            savePlayback()
            if (::video.isInitialized && video.isPlaying) handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        video = findViewById(R.id.videoView)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        taskId = intent.getStringExtra("TASK_ID")
        val raw = intent.getStringExtra("URI") ?: run { finish(); return }

        video.setMediaController(MediaController(this))
        video.setVideoURI(Uri.parse(raw))
        video.setOnPreparedListener {
            duration = it.duration
            val task = taskId?.let { id -> DownloadStore.get(this, id) }
            if (task != null && task.playbackPositionMs > 0 && task.playbackPositionMs < duration) {
                video.seekTo(task.playbackPositionMs.toInt())
            }
            it.start()
            handler.removeCallbacks(progressSaver)
            handler.post(progressSaver)
        }
        video.setOnCompletionListener {
            taskId?.let { id ->
                DownloadStore.get(this, id)?.let { task ->
                    DownloadStore.upsert(this, task.copy(watchedPercent = 100, playbackPositionMs = duration.toLong(), durationMs = duration.toLong()))
                }
            }
        }
    }

    override fun onPause() {
        savePlayback()
        handler.removeCallbacks(progressSaver)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun savePlayback() {
        val id = taskId ?: return
        if (!::video.isInitialized) return
        val pos = video.currentPosition.toLong()
        val dur = if (duration > 0) duration.toLong() else video.duration.toLong()
        if (dur <= 0) return
        val percent = ((pos * 100L) / dur).toInt().coerceIn(0, 100)
        DownloadStore.get(this, id)?.let { task ->
            DownloadStore.upsert(this, task.copy(watchedPercent = percent, playbackPositionMs = pos, durationMs = dur))
        }
    }
}
