package com.asghar.downloader

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.asghar.downloader.adapters.DownloadsAdapter
import com.asghar.downloader.databinding.ActivityMyDownloadsBinding
import com.asghar.downloader.services.DownloadService
import com.asghar.downloader.utils.DownloadStore
import com.asghar.downloader.utils.EdgeToEdge

class MyDownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMyDownloadsBinding
    private lateinit var mainAdapter: DownloadsAdapter
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(binding.root)

        val action: (DownloadStore.Task, String) -> Unit = { task, command ->
            val intent = Intent(this, DownloadService::class.java).apply {
                putExtra("ACTION", command)
                putExtra("TASK_ID", task.id)
            }
            startService(intent)
        }

        mainAdapter = DownloadsAdapter(this, action)
        binding.rvMain.layoutManager = LinearLayoutManager(this)
        binding.rvMain.adapter = mainAdapter
        binding.rvMain.itemAnimator = null
        binding.rvMain.setHasFixedSize(true)
        binding.rvMain.setItemViewCacheSize(6)
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh() {
        val all = DownloadStore.all(this)
        mainAdapter.submitAll(all)
        binding.tvEmpty.visibility =
            if (all.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
