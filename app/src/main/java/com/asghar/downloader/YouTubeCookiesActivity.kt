package com.asghar.downloader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.asghar.downloader.utils.CookieStore

class YouTubeCookiesActivity : AppCompatActivity() {

    private lateinit var etCookies: EditText
    private lateinit var btnSave: Button
    private lateinit var btnClear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_cookies)

        etCookies = findViewById(R.id.etCookies)
        btnSave = findViewById(R.id.btnSave)
        btnClear = findViewById(R.id.btnClear)

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        val help = findViewById<TextView>(R.id.tvHelp)
        help.text = buildString {
            append("1. Open youtube.com in Chrome/Firefox on desktop\n")
            append("2. Install browser extension: \"EditThisCookie\" or \"Get cookies.txt\"\n")
            append("3. Go to youtube.com, log in, click the extension icon\n")
            append("4. Export as \"Netscape\" format\n")
            append("5. Paste the entire file content in the box and Save\n")
            append("6. Delete cookies after 7 days and re-export (they expire)")
        }

        val existing = CookieStore.getCookies(this)
        if (existing.isNotBlank()) {
            etCookies.setText(existing)
        }

        btnSave.setOnClickListener {
            val text = etCookies.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Paste cookies first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!text.contains("\t") && !text.contains(" #")) {
                Toast.makeText(this, "This doesn't look like Netscape format. Cookies should contain tabs or .youtube.com entries.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            CookieStore.saveCookies(this, text)
            Toast.makeText(this, "Cookies saved! YouTube downloads will use them.", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnClear.setOnClickListener {
            CookieStore.clearCookies(this)
            etCookies.setText("")
            Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show()
        }
    }
}
