package com.asghar.downloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.asghar.downloader.utils.CookieStore

/**
 * Tiny WebView that lets the user sign into YouTube once. When the user
 * hits "Use these cookies" the activity exports the WebView cookie jar to
 * a Netscape-format file and finishes. yt-dlp then reads that file via
 * --cookies to bypass the "Sign in to confirm you're not a bot" check.
 */
class YouTubeLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_login)

        web = findViewById(R.id.wvLogin)
        status = findViewById(R.id.tvLoginStatus)
        confirm = findViewById(R.id.btnUseCookies)
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }

        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                status.text = "Loading…"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                status.text = if (url.orEmpty().contains("/feed") || url.orEmpty().contains("youtube.com")) {
                    "Signed in. Tap \"Use these cookies\" to apply."
                } else {
                    "Sign in with your Google account, then tap \"Use these cookies\"."
                }
                confirm.isEnabled = true
            }
        }
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/")
    }

    fun onUseCookies(@Suppress("UNUSED_PARAMETER") view: View) {
        if (!pageLoaded) {
            Toast.makeText(this, "Wait for the page to load first", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = CookieStore.exportFromWebView(this)
        if (ok) {
            Toast.makeText(this, "Cookies saved. Downloads will now use them.", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            status.text = "No YouTube cookies detected. Please sign in fully and try again."
            Toast.makeText(this, "Sign in fully first", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
