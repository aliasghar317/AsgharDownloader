package com.asghar.downloader.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

import com.asghar.downloader.utils.CookieStore
import com.asghar.downloader.utils.DownloadStorage
import com.asghar.downloader.utils.DownloadStore
import com.asghar.downloader.utils.LinkMetadataFetcher
import com.asghar.downloader.utils.LinkParser
import com.asghar.downloader.utils.YoutubeDLManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.concurrent.thread

class DownloadService : Service() {
    private val running = ConcurrentHashMap<String, String>()

    companion object {
        private val PROGRESS_RE = Pattern.compile(
            """(\d+(?:\.\d+)?)%\s+of\s+~?\s*([\d.]+)\s*([KMGT]?i?B).*?(?:at\s+([\d.]+)\s*([KMGT]?i?B)/s)?.*?(?:ETA\s+(\d{1,2}:\d{2}(?::\d{2})?))?""",
            Pattern.CASE_INSENSITIVE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("ACTION")) {
            "PAUSE" -> pause(intent.getStringExtra("TASK_ID").orEmpty())
            "RESUME" -> resume(intent.getStringExtra("TASK_ID").orEmpty())
            else -> startNew(intent)
        }
        return START_NOT_STICKY
    }

    private fun startNew(intent: Intent?) {
        val url = intent?.getStringExtra("URL") ?: return
        val quality = intent.getStringExtra("QUALITY") ?: "720p"
        val id = "task-${System.currentTimeMillis()}"
        val processId = "download-$id"
        val workDir = DownloadStorage.createWorkDirectory(this, id)

        DownloadStore.upsert(
            this,
            DownloadStore.Task(id, url, quality, "Preparing video…", 0, "queued", workDir.absolutePath, processId)
        )
        thread(name = "dl-$id") { executeTask(id, url, quality, workDir, processId, false) }
    }

    private fun resume(id: String) {
        val task = DownloadStore.get(this, id) ?: return
        if (task.status != "paused" && task.status != "failed") return
        val dir = File(task.workDir).apply { mkdirs() }
        DownloadStore.upsert(this, task.copy(status = "downloading"))
        thread(name = "resume-$id") {
            executeTask(id, task.url, task.quality, dir, task.processId, true, task.progress)
        }
    }

    private fun pause(id: String) {
        val task = DownloadStore.get(this, id) ?: return
        val process = running[id] ?: task.processId
        DownloadStore.upsert(this, task.copy(status = "paused"))
        runCatching { YoutubeDL.getInstance().destroyProcessById(process) }
        running.remove(id)
    }

    private fun executeTask(
        id: String,
        url: String,
        quality: String,
        workDir: File,
        processId: String,
        isResume: Boolean,
        previousProgress: Int = 0
    ) {
        var finalTitle = "Downloading video"
        try {
            running[id] = processId
            if (DownloadStore.get(this, id)?.status == "paused") return
            if (LinkParser.detectPlatform(url) == "YouTube") {
                executeYouTubeTask(id, url, quality, workDir, isResume, previousProgress)
                return
            }

            if (!YoutubeDLManager.ready && !YoutubeDLManager.init(this))
                throw IllegalStateException("Downloader engine could not be initialized")

            // Start the real download immediately. Metadata is fetched separately so
            // a slow social-site page cannot block the download for 10-20 seconds.
            updateTask(id, title = "Starting…", progress = previousProgress, status = "downloading")
            thread(name = "meta-$id", isDaemon = true) {
                val meta = LinkMetadataFetcher.fetch(url)
                val cleaned = DownloadStore.cleanTitle(meta.title)
                val generic = cleaned.equals("facebook", true) || cleaned.equals("instagram", true) ||
                    cleaned.equals("youtube", true) || cleaned.equals("video", true)
                if (meta.thumbnailUrl.isNotBlank() || (cleaned.isNotBlank() && !generic)) {
                    updateTask(id, title = cleaned.takeIf { it.isNotBlank() && !generic }, thumbnailUrl = meta.thumbnailUrl)
                }
            }

            finalTitle = DownloadStore.get(this, id)?.title ?: finalTitle
            val request = buildRequest(url, quality, workDir)
            var lastUiUpdate = 0L
            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate < 450L && progress.toInt() < 100) return@execute
                lastUiUpdate = now
                val current = DownloadStore.get(this, id) ?: return@execute
                if (current.status == "paused") return@execute
                val p = progress.toInt().coerceIn(0, 100)
                val safeProgress = maxOf(p, current.progress)
                var downloaded = current.downloadedBytes
                var total = current.totalBytes
                var speedStr = current.speed
                var etaStr = current.eta
                var sizeLabel = current.sizeLabel

                if (!line.isNullOrBlank()) {
                    Regex("Destination: (.+?)(?:\\r?\\n)?$").find(line)?.groupValues?.getOrNull(1)?.let { dest ->
                        val name = File(dest.trim()).nameWithoutExtension
                        DownloadStore.cleanTitle(name).takeIf { it.isNotBlank() }?.let {
                            finalTitle = it
                            updateTask(id, title = it)
                        }
                    }
                    val m = PROGRESS_RE.matcher(line)
                    if (m.find()) {
                        val totalVal = m.group(2)?.toDoubleOrNull()
                        val totalUnit = m.group(3)
                        if (totalVal != null && totalUnit != null) {
                            total = unitToBytes(totalVal, totalUnit)
                            sizeLabel = DownloadStore.formatBytes(total)
                        }
                        val speedVal = m.group(4)?.toDoubleOrNull()
                        val speedUnit = m.group(5)
                        if (speedVal != null && speedUnit != null) speedStr = String.format("%.1f%s/s", speedVal, speedUnit)
                        m.group(6)?.takeIf { it.isNotBlank() }?.let { etaStr = "$it left" }
                        if (total > 0 && safeProgress in 1..99) downloaded = (total * safeProgress / 100.0).toLong()
                    }
                }
                // Never keep an old ETA on screen. A previous ETA (for example
                // "2 min 22 sec left") can otherwise remain frozen even while the
                // download continues. Recalculate it from the latest callback.
                if (total > downloaded && speedStr.isNotBlank()) {
                    val speedBytes = speedToBytes(speedStr)
                    etaStr = if (speedBytes > 0) DownloadStore.formatEta(((total - downloaded) / speedBytes).toInt()) else ""
                } else if (etaInSeconds > 0) {
                    etaStr = DownloadStore.formatEta(etaInSeconds.toInt())
                } else {
                    etaStr = ""
                }
                updateTask(
                    id, progress = safeProgress, status = "downloading",
                    downloadedBytes = downloaded, totalBytes = total,
                    speed = speedStr, eta = etaStr, sizeLabel = sizeLabel
                )
            }

            val files = workDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.orEmpty()
            if (files.isEmpty()) throw IllegalStateException("No downloaded file was produced")

            var firstUri: String? = null
            var finalSize = 0L
            files.forEach { file ->
                finalSize += file.length()
                val pub = DownloadStorage.publishDownloadedFile(this, file)
                if (firstUri == null && pub != null) firstUri = pub.toString()
            }
            finalTitle = DownloadStore.cleanTitle(DownloadStore.get(this, id)?.title.orEmpty()).ifBlank {
                DownloadStore.cleanTitle(finalTitle).ifBlank { DownloadStore.cleanTitle(files.first().nameWithoutExtension).ifBlank { "Downloaded video" } }
            }
            updateTask(
                id, title = finalTitle, progress = 100, status = "completed", outputPath = firstUri ?: "",
                downloadedBytes = finalSize, totalBytes = finalSize, sizeLabel = DownloadStore.formatBytes(finalSize),
                speed = "", eta = ""
            )
        } catch (e: Exception) {
            val msg = e.message?.replace('\n', ' ')?.take(140) ?: "Download failed"
            val cur = DownloadStore.get(this, id)
            if (cur?.status != "paused") updateTask(id, status = "failed", title = if (finalTitle == "Downloading video") msg else finalTitle)
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        } finally {
            running.remove(id)
            // Keep the work directory on failure so the user can retry without
            // losing partial downloads. It is deleted on successful completion
            // (after the file has been copied to MediaStore) or when the task
            // is removed from the download list.
            if (DownloadStore.get(this, id)?.status == "completed") {
                DownloadStorage.deleteWorkDirectory(workDir)
            }
            if (running.isEmpty()) stopSelf()
        }
    }

    private fun executeYouTubeTask(
        id: String, url: String, quality: String, workDir: File,
        isResume: Boolean, previousProgress: Int
    ) {
        // YouTube uses exactly one engine: yt-dlp.
        if (DownloadStore.get(this, id)?.status == "paused") return
        try {
            executeYouTubeWithYtDlp(id, url, quality, workDir, isResume, previousProgress)
        } catch (e: Exception) {
            val detail = e.message?.replace('\n', ' ')?.take(180) ?: "YouTube download failed"
            if (DownloadStore.get(this, id)?.status != "paused") {
                updateTask(id, status = "failed", title = detail)
            }
            runCatching { YoutubeDL.getInstance().destroyProcessById("youtube-$id") }
        } finally {
        }
    }

    private fun executeYouTubeWithYtDlp(
        id: String, url: String, quality: String, workDir: File,
        isResume: Boolean, previousProgress: Int
    ) {
        if (!YoutubeDLManager.ready && !YoutubeDLManager.init(this)) {
            throw IllegalStateException("yt-dlp engine could not be initialized")
        }

        updateTask(id, title = "Starting…", progress = if (isResume) previousProgress else 0, status = "downloading")

        val request = buildRequest(url, quality, workDir)
        var lastUiUpdate = 0L
        YoutubeDL.getInstance().execute(request, "youtube-$id") { progress, etaInSeconds, line ->
            val now = System.currentTimeMillis()
            if (now - lastUiUpdate < 250L && progress.toInt() < 100) return@execute
            lastUiUpdate = now
            val current = DownloadStore.get(this, id) ?: return@execute
            if (current.status == "paused") return@execute
            val p = progress.toInt().coerceIn(0, 100)
            val safe = maxOf(current.progress, p)
            var title: String? = null
            var total = current.totalBytes
            var downloaded = current.downloadedBytes
            var speed = current.speed
            var eta = if (etaInSeconds > 0) DownloadStore.formatEta(etaInSeconds.toInt()) else ""
            if (!line.isNullOrBlank()) {
                // yt-dlp prints the actual destination title before transfer starts.
                Regex("Destination: (.+?)(?:\\r?\\n)?$").find(line)?.groupValues?.getOrNull(1)?.let { dest ->
                    val name = File(dest.trim()).nameWithoutExtension
                    DownloadStore.cleanTitle(name).takeIf { it.isNotBlank() }?.let { title = it }
                }
                // Also parse bytes/speed from yt-dlp's live progress line so the UI
                // never shows a stale/duplicated size value.
                PROGRESS_RE.matcher(line).takeIf { it.find() }?.let { m ->
                    m.group(2)?.toDoubleOrNull()?.let { value ->
                        m.group(3)?.let { unit ->
                            total = unitToBytes(value, unit)
                        }
                    }
                    m.group(4)?.toDoubleOrNull()?.let { value ->
                        m.group(5)?.let { unit -> speed = String.format("%.1f%s/s", value, unit) }
                    }
                    m.group(6)?.takeIf { it.isNotBlank() }?.let { eta = "$it left" }
                }
            }
            if (total > 0 && safe in 1..99) downloaded = total * safe / 100L
            updateTask(
                id, title = title, progress = safe, status = "downloading",
                downloadedBytes = downloaded, totalBytes = total, speed = speed,
                eta = eta, sizeLabel = if (total > 0) DownloadStore.formatBytes(total) else ""
            )
        }
        val files = workDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".jpg", true) && !it.name.endsWith(".webp", true) && !it.name.endsWith(".png", true) }.orEmpty()
        val output = files.maxByOrNull { it.length() } ?: throw IllegalStateException("yt-dlp produced no output file")
        val storedTitle = DownloadStore.get(this, id)?.title.orEmpty()
        val title = DownloadStore.cleanTitle(storedTitle).ifBlank { DownloadStore.cleanTitle(output.nameWithoutExtension).ifBlank { "YouTube video" } }
        publishAndCompleteYoutube(id, title, output)
    }

    private fun publishAndCompleteYoutube(id: String, title: String, output: File) {
        val published = runCatching { DownloadStorage.publishDownloadedFile(this, output) }.getOrNull()
        val finalSize = output.length()
        updateTask(
            id,
            title = title,
            progress = 100,
            status = "completed",
            outputPath = published?.toString() ?: "",
            downloadedBytes = finalSize,
            totalBytes = finalSize,
            sizeLabel = DownloadStore.formatBytes(finalSize),
            speed = "",
            eta = ""
        )
    }

    private fun buildRequest(url: String, quality: String, workDir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        val h = when (quality) {
            "4k" -> 2160; "1080p" -> 1080; "720p" -> 720;
            "480p" -> 480; "360p" -> 360; "240p" -> 240; else -> 720
        }
        when (quality) {
            "mp3" -> {
                request.addOption("-f", "ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }
            else -> request.addOption("-f", "bv*[ext=mp4][vcodec^=avc1][height<=$h]+ba[ext=m4a]/bv*[ext=mp4][height<=$h]+ba[ext=m4a]/b[ext=mp4][height<=$h]/bv*[height<=$h]+ba/b[height<=$h]/b")
        }
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("--continue")
        request.addOption("--no-overwrites")
        request.addOption("--no-mtime")
        request.addOption("--no-warnings")
        request.addOption("--socket-timeout", "30")
        request.addOption("--geo-bypass")
        request.addOption("--retries", "20")
        request.addOption("--fragment-retries", "20")
        request.addOption("--concurrent-fragments", "4")
        if (quality != "mp3") request.addOption("--merge-output-format", "mp4")
        // YouTube's bot detection rotates weekly. We use the freshest yt-dlp
        // (nightly build) combined with multiple client signatures. If the user
        // has imported cookies from a browser session, we pass them so the
        // server treats us as a signed-in user.
        if (LinkParser.detectPlatform(url) == "YouTube") {
            // The mweb/android clients are no longer consistently served by YouTube.
            // `web_safari` + `ios` together cover the freshest desktop and mobile
            // signatures. We pass them in two separate --extractor-args calls so
            // yt-dlp can pick the one that works without re-parsing a long string.
            request.addOption(
                "--extractor-args",
                "youtube:player_client=default,web_safari,ios,android"
            )
            request.addOption(
                "--extractor-args",
                "youtube:player_skip=webpage,configs"
            )
            // A Chrome Windows UA is harder for YouTube's bot filter to flag
            // during the signature-decrypt phase than a default yt-dlp UA.
            request.addOption(
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            request.addOption(
                "--add-header",
                "Accept-Language:en-US,en;q=0.9"
            )
            // Extra headers that YouTube checks before returning 403.
            request.addOption("--add-header", "sec-ch-ua:\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not-A.Brand\";v=\"99\"")
            request.addOption("--add-header", "sec-ch-ua-mobile:?0")
            request.addOption("--add-header", "sec-ch-ua-platform:\"Windows\"")
            request.addOption("--add-header", "sec-fetch-dest:document")
            request.addOption("--add-header", "sec-fetch-mode:navigate")
            request.addOption("--add-header", "sec-fetch-site:none")
            request.addOption("--add-header", "sec-fetch-user:?1")
            request.addOption("--add-header", "upgrade-insecure-requests:1")
            // Force IPv4 to avoid the rare "Errno 7" on dual-stack networks.
            request.addOption("--force-ipv4")
            // Wider retries help with intermittent TLS / DNS timeouts.
            request.addOption("--retries", "30")
            request.addOption("--fragment-retries", "30")
            request.addOption("--socket-timeout", "45")
            // Cookies are the single biggest fix for 403 — if the user has
            // imported them, ship them along; if not, we still attempt the
            // public client.
            if (CookieStore.hasCookies(this)) {
                request.addOption("--cookies", CookieStore.cookiesFile(this).absolutePath)
            } else {
                // No cookies imported — try to use the embedded PO token
                // from the bundled extractor. The new yt-dlp nightly reads
                // `youtube_pot_builtin=1` so the signature step does not
                // require a manual visitorData cookie.
                request.addOption("--extractor-args", "youtube:youtube_pot_builtin=1")
            }
        }
        request.addOption("-o", "${workDir.absolutePath}/%(title).200B.%(ext)s")
        return request
    }

    private fun speedToBytes(value: String): Long {
        val m = Regex("([0-9.]+)\\s*([KMGT]?i?B)/s", RegexOption.IGNORE_CASE).find(value) ?: return 0L
        val number = m.groupValues[1].toDoubleOrNull() ?: return 0L
        return unitToBytes(number, m.groupValues[2])
    }

    private fun unitToBytes(value: Double, unit: String): Long = when (val u = unit.uppercase()) {
        "GB" -> (value * 1024 * 1024 * 1024).toLong()
        "MB" -> (value * 1024 * 1024).toLong()
        "KB" -> (value * 1024).toLong()
        "TB" -> (value * 1024L * 1024 * 1024 * 1024).toLong()
        else -> if (u.startsWith("GI")) (value * 1024 * 1024 * 1024).toLong()
        else if (u.startsWith("MI")) (value * 1024 * 1024).toLong()
        else if (u.startsWith("KI")) (value * 1024).toLong()
        else if (u.startsWith("TI")) (value * 1024L * 1024 * 1024 * 1024).toLong()
        else value.toLong()
    }

    private fun updateTask(
        id: String, title: String? = null, progress: Int? = null, status: String? = null,
        outputPath: String? = null, downloadedBytes: Long? = null, totalBytes: Long? = null,
        speed: String? = null, eta: String? = null, sizeLabel: String? = null,
        thumbnailUrl: String? = null
    ) {
        val old = DownloadStore.get(this, id) ?: return
        DownloadStore.upsert(this, old.copy(
            title = title ?: old.title,
            progress = progress ?: old.progress,
            status = status ?: old.status,
            outputPath = outputPath ?: old.outputPath,
            downloadedBytes = downloadedBytes ?: old.downloadedBytes,
            totalBytes = totalBytes ?: old.totalBytes,
            speed = speed ?: old.speed,
            eta = eta ?: old.eta,
            sizeLabel = sizeLabel ?: old.sizeLabel,
            thumbnailUrl = thumbnailUrl ?: old.thumbnailUrl
        ))
    }
}
