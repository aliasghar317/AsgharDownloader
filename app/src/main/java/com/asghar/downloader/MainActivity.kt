package com.asghar.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.asghar.downloader.databinding.ActivityMainBinding
import com.asghar.downloader.databinding.DialogGradientTitleBinding
import com.asghar.downloader.services.DownloadService
import com.asghar.downloader.utils.CookieStore
import com.asghar.downloader.utils.EdgeToEdge
import com.asghar.downloader.utils.LinkParser
import com.asghar.downloader.utils.ProtectedMessage
import com.asghar.downloader.utils.YoutubeDLManager
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var engineReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(binding.root)

        binding.btnDownload.setOnClickListener { startSelectedDownload() }
        binding.btnYoutubeSignIn.setOnClickListener { startActivity(Intent(this, YouTubeLoginActivity::class.java)) }
        binding.btnWhatsapp.setOnClickListener { openUrl("https://wa.me/923093472919") }
        binding.btnTelegram.setOnClickListener { openUrl("https://t.me/FsOfFullMargin") }
        binding.btnDownloads.setOnClickListener { startActivity(Intent(this, MyDownloadsActivity::class.java)) }
        binding.btnMovies.setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }

        showOpeningMessage()
        prepareEngine()
    }

    private fun showOpeningMessage() {
        val titleBinding = DialogGradientTitleBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleBinding.root)
            .setMessage(ProtectedMessage.get())
            .setPositiveButton("OK") { _, _ -> requestRequiredPermissions() }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF3B82F6.toInt())
        }
        dialog.show()
    }

    /** Initialize yt-dlp/FFmpeg once while the home screen is open so the first download does not wait. */
    private fun prepareEngine() {
        engineReady = false
        binding.tvEngineStatus.text = "Preparing YouTube engine…"
        binding.btnDownload.isEnabled = false
        thread(name = "engine-init") {
            // Startup is strictly offline: only initialize the bundled engine.
            val initialized = YoutubeDLManager.init(this@MainActivity)
            val ok = initialized && YoutubeDLManager.ready
            runOnUiThread {
                engineReady = ok
                binding.tvEngineStatus.text = if (ok) "Downloader ready" else "Downloader unavailable"
                binding.btnDownload.isEnabled = ok
            }
        }
    }

    private fun startSelectedDownload() {
        if (!engineReady) {
            Toast.makeText(this, "Downloader is still preparing", Toast.LENGTH_SHORT).show()
            return
        }
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            binding.etUrl.error = "Paste a video link"
            return
        }
        if (LinkParser.detectPlatform(url) == "Unknown") {
            Toast.makeText(this, "Invalid or unsupported URL", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedId = binding.rgQuality.checkedRadioButtonId
        if (selectedId == -1) {
            Toast.makeText(this, "Select a quality first", Toast.LENGTH_SHORT).show()
            return
        }
        val quality = findViewById<RadioButton>(selectedId).tag?.toString() ?: "1080p"
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra("ACTION", "START")
            putExtra("URL", url)
            putExtra("QUALITY", quality)
        }
        startService(intent)
        binding.etUrl.setText("")
        Toast.makeText(this, "Download added to progress list", Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestRequiredPermissions() {
        // Downloads are written through MediaStore.Downloads on Android 10+, so no
        // runtime storage permission is required on those versions. The legacy
        // WRITE_EXTERNAL_STORAGE permission is only needed on Android 9 and below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}
