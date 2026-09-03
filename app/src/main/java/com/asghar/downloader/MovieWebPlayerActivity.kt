package com.asghar.downloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.asghar.downloader.utils.CookieStore

/**
 * WebView-based player used as a fallback when the BFF does not expose
 * a stream URL for a subject (which is the case for every guest-user
 * request). The netfilm.world play page is loaded and the user can log
 * in there. The WebView is configured to persist cookies so once the
 * user signs in on netfilm.world, subsequent visits to play pages
 * automatically carry the session.
 */
class MovieWebPlayerActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_web_player)

        val playUrl = intent.getStringExtra("PLAY_URL").orEmpty()
        val title = intent.getStringExtra("TITLE").orEmpty()
        val webView = findViewById<WebView>(R.id.webPlayer)
        val titleView = findViewById<TextView>(R.id.tvTitle)
        val progress = findViewById<ProgressBar>(R.id.progress)
        titleView.text = title

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        if (playUrl.isBlank()) {
            Toast.makeText(this, "No play URL available", Toast.LENGTH_LONG).show()
            finish(); return
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                // Persist any netfilm cookies that were set by the page so
                // the user only has to log in once.
                runCatching { CookieStore.saveCookies(this@MovieWebPlayerActivity,
                    CookieManager.getInstance().getCookie(playUrl).orEmpty()) }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return null
            }
        }
        webView.loadUrl(playUrl)
    }

    override fun onBackPressed() {
        val webView = findViewById<WebView>(R.id.webPlayer)
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        runCatching { findViewById<WebView>(R.id.webPlayer).destroy() }
        super.onDestroy()
    }
}
