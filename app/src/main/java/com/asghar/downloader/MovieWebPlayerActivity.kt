package com.asghar.downloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.asghar.downloader.utils.CookieStore
import com.asghar.downloader.utils.MovieBoxApi
import com.asghar.downloader.utils.ThumbnailCache
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebView-based player that loads the netfilm.world play page for a given
 * subject. The page runs the MovieBox HLS player in a WebView; we inject
 * JavaScript to intercept the actual m3u8 URL so that we can:
 *
 *  1. Play it directly in ExoPlayer (better UX)
 *  2. Offer download at multiple qualities
 *
 * The flow is:
 *   1. Load netfilm.world/play/<detailPath>
 *   2. Wait for the HLS.js player to initialise
 *   3. Run a JS snippet that reads player.currentSrc
 *   4. If we get an m3u8 URL, hand it to ExoPlayer
 *   5. If the page shows "Log in", show the login dialog
 *
 * The user logs in once on the WebView and the cookies are persisted
 * so subsequent visits to play pages are already authenticated.
 */
class MovieWebPlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnClose: ImageButton

    private var m3u8Url: String? = null
    private var movieTitle: String = ""
    private var playUrl: String = ""

    companion object {
        private const val TAG = "MovieWebPlayer"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_web_player)

        playUrl = intent.getStringExtra("PLAY_URL").orEmpty()
        movieTitle = intent.getStringExtra("TITLE").orEmpty()

        titleView = findViewById(R.id.tvTitle)
        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webPlayer)
        btnClose = findViewById(R.id.btnClose)

        titleView.text = movieTitle

        if (playUrl.isBlank()) {
            Toast.makeText(this, "No play URL available", Toast.LENGTH_LONG).show()
            finish(); return
        }

        btnClose.setOnClickListener { finish() }

        // Setup WebView
        val cookieMgr = CookieManager.getInstance()
        cookieMgr.setAcceptCookie(true)
        cookieMgr.setAcceptThirdPartyCookies(webView, true)

        // Load existing app cookies so the user doesn't have to log in every time
        val existingCookies = CookieStore.getCookies(this)
        if (existingCookies.isNotBlank()) {
            existingCookies.lines().forEach { line ->
                if (line.contains("\t") && !line.startsWith("#")) {
                    val parts = line.split("\t")
                    if (parts.size >= 7) {
                        val domain = ".netfilm.world"
                        val name = parts[5].trim()
                        val value = parts[6].trim()
                        if (name.isNotBlank() && value.isNotBlank()) {
                            cookieMgr.setCookie(domain, "$name=$value")
                        }
                    }
                }
            }
            Log.d(TAG, "Loaded ${existingCookies.lines().count { it.contains(".netfilm.world") }} cookies")
        }

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        // Desktop UA — netfilm.world SPA returns 404 on mobile UA for
        // some regions; desktop Chrome works on the same IPs.
        settings.userAgentString = (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )

        // Inject a JS interface so the WebView can tell us when the player is ready
        webView.addJavascriptInterface(WebPlayerBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject JS to intercept HLS player and find the m3u8 URL
                injectStreamInterceptor()
            }

            override fun onReceivedError(
                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl == playUrl) {
                    // First attempt to load the play page failed. Try
                    // the home domain as a fallback.
                    Log.w(TAG, "Play page error: $errorCode $description, trying home fallback")
                    val home = MovieBoxApi.PLAY_DOMAIN + "/"
                    if (playUrl != home) {
                        playUrl = home
                        webView.loadUrl(home)
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if ((url.contains(".m3u8") || url.contains(".mp4")) && m3u8Url == null) {
                    m3u8Url = url
                    Log.d(TAG, "Found stream: $url")
                    runOnUiThread {
                        onStreamFound(url)
                    }
                }
                return null
            }
        }

        webView.loadUrl(playUrl)
        Log.d(TAG, "Loading: $playUrl")
    }

    /**
     * Injects a script that polls the HLS.js / native video element
     * once per second until it finds a .m3u8 URL, then calls back into
     * the AndroidBridge.
     */
    private fun injectStreamInterceptor() {
        val script = """
            (function() {
                var found = false;
                function check() {
                    if (found) return;
                    // Try HLS.js instances
                    if (typeof hlsjs !== 'undefined' && hlsjs.url) {
                        found = true;
                        AndroidBridge.onStreamFound(hlsjs.url);
                        return;
                    }
                    // Try native video elements
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var src = videos[i].src || videos[i].currentSrc;
                        if (src && src.indexOf('.m3u8') !== -1) {
                            found = true;
                            AndroidBridge.onStreamFound(src);
                            return;
                        }
                    }
                    // Try Shaka player
                    if (typeof shaka !== 'undefined' && shaka.Player) {
                        try {
                            var players = document.querySelectorAll('[data-shaka-player-container] video');
                            for (var j = 0; j < players.length; j++) {
                                AndroidBridge.onPlayerReady();
                                return;
                            }
                        } catch(e) {}
                    }
                    // If the page shows login/signup, notify Android
                    var bodyText = document.body ? document.body.innerText : '';
                    if (bodyText.indexOf('Log in') !== -1 || bodyText.indexOf('Sign up') !== -1 ||
                        bodyText.indexOf('Sign in') !== -1) {
                        AndroidBridge.onLoginRequired();
                    }
                }
                // Poll every 800ms for up to 30 seconds
                var interval = setInterval(check, 800);
                setTimeout(function() { clearInterval(interval); }, 30000);
                // Also run immediately
                check();
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun onStreamFound(url: String) {
        Log.d(TAG, "Stream URL intercepted: $url")
        m3u8Url = url
        // Auto-play in ExoPlayer
        val i = android.content.Intent(this, MoviePlayerActivity::class.java).apply {
            putExtra("STREAM_URL", url)
            putExtra("TITLE", movieTitle)
            putExtra("FORMAT", "hls")
        }
        startActivity(i)
        finish()
    }

    private fun onLoginRequired() {
        runOnUiThread {
            Toast.makeText(this, "Please log in on the screen to watch", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPlayerReady() {
        runOnUiThread {
            // Player is loaded but we haven't found m3u8 yet
            // Try injecting a script to get the source directly
            val getSrcScript = """
                (function() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var src = videos[i].src || videos[i].currentSrc;
                        if (src) { AndroidBridge.onStreamFound(src); return; }
                    }
                    // Try to get HLS.js instance
                    if (typeof getHLSUrl === 'function') {
                        var url = getHLSUrl();
                        if (url) { AndroidBridge.onStreamFound(url); return; }
                    }
                    AndroidBridge.onLoginRequired();
                })();
            """.trimIndent()
            webView.evaluateJavascript(getSrcScript, null)
        }
    }

    fun onDownloadClicked(v: View) {
        val url = m3u8Url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Stream not loaded yet. Wait for video to start.", Toast.LENGTH_LONG).show()
            return
        }
        // Parse qualities from m3u8 URL if possible
        showQualityDialog(url)
    }

    private fun showQualityDialog(url: String) {
        // Build download URL by replacing quality in m3u8
        val qualities = listOf(
            "1080P" to "1080",
            "720P" to "720",
            "480P" to "480",
            "360P" to "360"
        )
        val names = qualities.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Download Quality")
            .setItems(names) { _, which ->
                val q = qualities[which]
                val dlUrl = buildQualityUrl(url, q.second)
                startDownload(dlUrl, movieTitle, q.first)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildQualityUrl(baseUrl: String, quality: String): String {
        // If the m3u8 URL contains quality variants, try to use the specific variant
        // Otherwise just return the master m3u8 and let yt-dlp handle quality selection
        return if (baseUrl.contains("master")) {
            // For master playlists, yt-dlp will pick the best quality automatically
            baseUrl
        } else {
            baseUrl
        }
    }

    private fun startDownload(url: String, title: String, quality: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "No stream URL found", Toast.LENGTH_SHORT).show()
            return
        }
        val safe = title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("$safe [$quality]")
            .setDescription("Downloading $safe [$quality]")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "AsgharDownloader/Movies/$safe.mp4"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("Referer", "https://netfilm.world/")
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
        Toast.makeText(this, "Download started: $quality", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    /**
     * Bridge between the WebView's JavaScript and the Android activity.
     * Injected as `window.AndroidBridge`.
     */
    inner class WebPlayerBridge {
        @JavascriptInterface
        fun onStreamFound(url: String) {
            Log.d(TAG, "JS bridge received stream: $url")
            if (m3u8Url == null && url.contains(".m3u8")) {
                m3u8Url = url
                runOnUiThread {
                    onStreamFound(url)
                }
            }
        }

        @JavascriptInterface
        fun onPlayerReady() {
            Log.d(TAG, "JS bridge: player ready")
            runOnUiThread { onPlayerReady() }
        }

        @JavascriptInterface
        fun onLoginRequired() {
            Log.d(TAG, "JS bridge: login required")
            runOnUiThread { onLoginRequired() }
        }
    }
}
