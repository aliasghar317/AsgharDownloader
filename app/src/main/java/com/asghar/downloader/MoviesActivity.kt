package com.asghar.downloader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asghar.downloader.utils.ThumbnailCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MoviesActivity : AppCompatActivity() {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var adapter: MovieAdapter
    private var token = ""
    private var selectedLanguage = ""

    private val languages = linkedMapOf("All" to "", "English" to "en", "Hindi" to "hi", "Urdu" to "ur", "Tamil" to "ta", "Telugu" to "te", "Arabic" to "ar", "Korean" to "ko", "Spanish" to "es", "French" to "fr")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)
        search = findViewById(R.id.etSearch)
        status = findViewById(R.id.tvStatus)
        adapter = MovieAdapter(this) { movie -> showMovie(movie) }
        findViewById<RecyclerView>(R.id.rvMovies).apply {
            layoutManager = GridLayoutManager(this@MoviesActivity, 3)
            itemAnimator = null
            adapter = this@MoviesActivity.adapter
            setHasFixedSize(true)
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnKey).setOnClickListener { askToken(true) }
        buildLanguageRow()
        search.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { loadMovies(search.text.toString()); true } else false }
        token = getPreferences(Context.MODE_PRIVATE).getString("tmdb_token", "").orEmpty()
        if (token.isBlank()) askToken(false) else loadMovies("")
    }

    private fun buildLanguageRow() {
        val row = findViewById<LinearLayout>(R.id.languageRow)
        languages.forEach { (name, code) ->
            val b = Button(this).apply {
                text = name
                textSize = 11f
                isAllCaps = false
                setOnClickListener { selectedLanguage = code; loadMovies(search.text.toString()) }
            }
            row.addView(b, LinearLayout.LayoutParams(WRAP, 38).apply { setMargins(3, 0, 3, 0) })
        }
    }

    private fun askToken(force: Boolean) {
        val input = EditText(this).apply { hint = "TMDB API Read Access Token"; setText(token) }
        AlertDialog.Builder(this).setTitle("Movie catalog setup")
            .setMessage("The movie catalog uses TMDB metadata. Paste your TMDB API Read Access Token here. It is stored only on this device.")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                token = input.text.toString().trim()
                getPreferences(Context.MODE_PRIVATE).edit().putString("tmdb_token", token).apply()
                if (token.isBlank() && !force) status.text = "TMDB token required for the movie catalog"
                else loadMovies(search.text.toString())
            }.show()
    }

    private fun loadMovies(query: String) {
        if (token.isBlank()) { status.text = "Add your TMDB API token first"; return }
        status.text = "Loading movies…"
        executor.execute {
            runCatching {
                val endpoint = if (query.isBlank()) {
                    val languagePart = if (selectedLanguage.isBlank()) "" else "&with_original_language=${URLEncoder.encode(selectedLanguage, "UTF-8")}"
                    "https://api.themoviedb.org/3/discover/movie?include_adult=false&sort_by=popularity.desc$languagePart&page=1"
                } else {
                    "https://api.themoviedb.org/3/search/movie?include_adult=false&query=${URLEncoder.encode(query, "UTF-8")}&page=1"
                }
                val root = getJson(endpoint)
                val results = root.optJSONArray("results") ?: org.json.JSONArray()
                val list = ArrayList<Movie>()
                for (i in 0 until results.length()) {
                    val o = results.optJSONObject(i) ?: continue
                    list.add(Movie(o.optInt("id"), o.optString("title"), o.optString("release_date").take(4), o.optString("overview"), o.optString("poster_path"), o.optString("original_language")))
                }
                main.post { adapter.submit(list); status.text = "${list.size} movies" }
            }.onFailure { e -> main.post { status.text = "Movie catalog error: ${e.message?.take(120) ?: "request failed"}" } }
        }
    }

    private fun getJson(endpoint: String): JSONObject {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun showMovie(movie: Movie) {
        executor.execute {
            val providers = runCatching { getJson("https://api.themoviedb.org/3/movie/${movie.id}/watch/providers") }.getOrNull()
            val region = providers?.optJSONObject("results")?.optJSONObject("US") ?: providers?.optJSONObject("results")?.optJSONObject("PK")
            val link = region?.optString("link").orEmpty()
            val names = ArrayList<String>()
            region?.optJSONArray("flatrate")?.let { arr -> for (i in 0 until arr.length()) names.add(arr.optJSONObject(i)?.optString("provider_name").orEmpty()) }
            main.post {
                val message = buildString {
                    append(if (movie.year.isBlank()) "" else movie.year + "\n")
                    append(if (movie.language.isBlank()) "" else "Language: ${movie.language.uppercase()}\n\n")
                    append(movie.overview.ifBlank { "No overview available." })
                    if (names.isNotEmpty()) append("\n\nStreaming providers: ${names.joinToString()}")
                    else append("\n\nNo provider was returned for the selected region.")
                }
                val builder = AlertDialog.Builder(this).setTitle(movie.title).setMessage(message).setNegativeButton("Close", null)
                if (link.isNotBlank()) builder.setPositiveButton("Watch / Provider") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
                builder.show()
            }
        }
    }

    data class Movie(val id: Int, val title: String, val year: String, val overview: String, val poster: String, val language: String)

    class MovieAdapter(private val context: Context, private val click: (Movie) -> Unit) : RecyclerView.Adapter<MovieAdapter.Holder>() {
        private var items = emptyList<Movie>()
        private val executor = Executors.newFixedThreadPool(3)
        private val main = Handler(Looper.getMainLooper())
        private val cache = android.util.LruCache<String, Bitmap>(24)
        fun submit(newItems: List<Movie>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: Holder, p: Int) {
            val m = items[p]; h.title.text = m.title; h.meta.text = listOf(m.year, m.language.uppercase()).filter { it.isNotBlank() }.joinToString(" • ")
            val url = if (m.poster.isNotBlank()) "https://image.tmdb.org/t/p/w342${m.poster}" else ""
            h.poster.tag = url
            if (url.isBlank()) return
            val cached = cache.get(url) ?: ThumbnailCache.get(context, url)
            if (cached != null) { cache.put(url, cached); h.poster.setImageBitmap(cached) }
            else executor.execute { val b = ThumbnailCache.loadRemote(context, url); if (b != null) { ThumbnailCache.put(context, url, b); cache.put(url, b); main.post { if (h.poster.tag == url) h.poster.setImageBitmap(b) } } }
            h.itemView.setOnClickListener { click(m) }
        }
        class Holder(v: View) : RecyclerView.ViewHolder(v) { val poster: ImageView = v.findViewById(R.id.ivPoster); val title: TextView = v.findViewById(R.id.tvMovieTitle); val meta: TextView = v.findViewById(R.id.tvMovieMeta) }
    }
    private companion object { const val WRAP = -2 }
}
