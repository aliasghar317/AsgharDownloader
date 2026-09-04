package com.asghar.downloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.asghar.downloader.utils.MovieBoxApi
import java.util.concurrent.Executors

@UnstableApi
class MoviePlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var titleView: TextView
    private var resolvedUrl: String = ""

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_player)

        val subjectId = intent.getStringExtra("SUBJECT_ID").orEmpty()
        val season = intent.getIntExtra("SEASON", 1)
        val episode = intent.getIntExtra("EPISODE", 1)
        var streamUrl = intent.getStringExtra("STREAM_URL").orEmpty()
        val title = intent.getStringExtra("TITLE").orEmpty()
        titleView = findViewById(R.id.tvTitle)
        titleView.text = title

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener {
            if (resolvedUrl.isBlank()) {
                Toast.makeText(this, "No stream URL available", Toast.LENGTH_SHORT).show()
            } else {
                startDownload(resolvedUrl, title)
            }
        }

        if (streamUrl.isNotBlank()) {
            resolvedUrl = streamUrl
            startPlayback(streamUrl)
        } else if (subjectId.isNotBlank()) {
            // We were given an episode, not a stream URL — resolve it.
            titleView.text = "$title  •  Resolving…"
            executor.execute {
                val info = MovieBoxApi.playInfo(subjectId, season, episode)
                val first = info?.streams?.firstOrNull { it.url.isNotBlank() }
                runOnUiThread {
                    if (first == null) {
                        Toast.makeText(this, "Stream locked. Sign in to watch.", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        resolvedUrl = first.url
                        startPlayback(first.url)
                    }
                }
            }
        } else {
            Toast.makeText(this, "Stream URL missing", Toast.LENGTH_LONG).show()
            finish()
            return
        }
    }

    private fun startPlayback(streamUrl: String) {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        val playerView = findViewById<androidx.media3.ui.PlayerView>(R.id.playerView)
        playerView.player = exo

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MovieBox/3.0 (Linux; Android 13)")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://h5.inmoviebox.com/",
                    "Origin" to "https://h5.inmoviebox.com"
                )
            )

        val isHls = streamUrl.contains(".m3u8", ignoreCase = true) ||
            streamUrl.contains("/hls/", ignoreCase = true)
        val source: MediaSource = if (isHls) {
            HlsMediaSource.Factory(httpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(streamUrl))
        } else {
            ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.Builder()
                    .setUri(streamUrl)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build())
        }
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun startDownload(url: String, title: String) {
        val safe = title.ifBlank { "movie" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setDescription("Downloading $safe")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "AsgharDownloader/Movies/$safe.mp4"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("Referer", "https://h5.inmoviebox.com/")
            .addRequestHeader("User-Agent", "MovieBox/3.0 (Linux; Android 13)")
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
