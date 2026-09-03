package com.asghar.downloader

import android.content.Intent
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asghar.downloader.utils.MovieBoxApi
import com.asghar.downloader.utils.ThumbnailCache
import java.util.concurrent.Executors

/**
 * MovieBox-style movies tab. The screen is laid out like Netflix/MovieBox:
 *   [ Top: animated "Asghar Downloader" title + search bar ]
 *   [ Hero banner with Play / Info buttons ]
 *   [ Horizontal rails: Trending, Top Picks, … ]
 *   [ Grid rail (search results) ]
 *
 * All data is fetched from api.inmoviebox.com (MovieBox's public BFF).
 * Region-blocked requests return empty rails instead of an error toast,
 * so the user can still see a partially populated catalogue.
 */
class MoviesActivity : AppCompatActivity() {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroImage: ImageView
    private lateinit var heroPlay: Button
    private lateinit var heroInfo: Button
    private lateinit var list: RecyclerView

    private val adapters = mutableListOf<Any>()
    private val railData = mutableListOf<Rail>()
    private var currentSearch = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)
        search = findViewById(R.id.etSearch)
        status = findViewById(R.id.tvStatus)
        heroTitle = findViewById(R.id.tvHeroTitle)
        heroMeta = findViewById(R.id.tvHeroMeta)
        heroImage = findViewById(R.id.ivHero)
        heroPlay = findViewById(R.id.btnHeroPlay)
        heroInfo = findViewById(R.id.btnHeroInfo)
        list = findViewById(R.id.rvMovies)
        list.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnKey).setOnClickListener { showKeyDialog() }
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                currentSearch = search.text.toString().trim()
                loadSearch(currentSearch); true
            } else false
        }

        loadHome()
    }

    private fun showKeyDialog() {
        val edt = EditText(this).apply { hint = "MovieBox API base URL (optional override)" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("MovieBox settings")
            .setMessage("By default the app calls api.inmoviebox.com. Leave blank for default.")
            .setView(edt)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val v = edt.text.toString().trim()
                getPreferences(MODE_PRIVATE).edit().putString("moviebox_base", v).apply()
                Toast.makeText(this, "Saved. Reopen Movies to apply.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadHome() {
        status.text = "Loading movie catalog…"
        executor.execute {
            val trending = MovieBoxApi.homeFeed()
            val playlists = MovieBoxApi.homePlaylists()
            main.post {
                railData.clear()
                if (trending.isNotEmpty()) {
                    railData.add(Rail("🔥 Trending Now", trending))
                }
                playlists.forEach { p ->
                    if (p.items.isNotEmpty()) railData.add(Rail(p.title, p.items))
                }
                if (railData.isEmpty()) {
                    status.text = "No movies available in this region. Try search."
                } else {
                    status.text = "Showing ${railData.size} rails"
                }
                bindRails()
                if (trending.isNotEmpty()) bindHero(trending.first())
            }
        }
    }

    private fun loadSearch(query: String) {
        if (query.isBlank()) { loadHome(); return }
        status.text = "Searching for \"$query\"…"
        executor.execute {
            val results = MovieBoxApi.search(query)
            main.post {
                railData.clear()
                railData.add(Rail("Search results", results))
                status.text = if (results.isEmpty()) "No results for \"$query\""
                              else "${results.size} results"
                bindRails()
                if (results.isNotEmpty()) bindHero(results.first())
            }
        }
    }

    private fun bindRails() {
        val merged = mutableListOf<Any>()
        railData.forEach { r ->
            merged.add(HeaderItem(r.title))
            r.items.forEach { merged.add(it) }
        }
        adapters.clear()
        adapters.addAll(merged)
        list.adapter = MoviesAdapter(merged) { onMovieClick(it) }
    }

    private fun bindHero(m: MovieBoxApi.Movie) {
        heroTitle.text = m.title
        val parts = listOf(m.year, m.rating, m.genre).filter { it.isNotBlank() }
        heroMeta.text = parts.joinToString(" • ")
        if (m.backdrop.isNotBlank()) {
            ThumbnailCache.loadInto(this, m.backdrop, heroImage)
        } else if (m.poster.isNotBlank()) {
            ThumbnailCache.loadInto(this, m.poster, heroImage)
        }
        heroPlay.setOnClickListener { onMovieClick(m) }
        heroInfo.setOnClickListener { onMovieClick(m) }
    }

    private fun onMovieClick(m: MovieBoxApi.Movie) {
        status.text = "Loading ${m.title}…"
        executor.execute {
            val detail = MovieBoxApi.detail(m.id)
            main.post {
                if (detail == null) {
                    status.text = "Could not load this title"
                    Toast.makeText(this, "Could not load detail", Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (detail.streamUrl.isBlank()) {
                    status.text = "No playable stream for \"${detail.title}\""
                    Toast.makeText(this, "No playable stream found", Toast.LENGTH_LONG).show()
                    return@post
                }
                val i = Intent(this, MoviePlayerActivity::class.java).apply {
                    putExtra("STREAM_URL", detail.streamUrl)
                    putExtra("TITLE", detail.title)
                }
                startActivity(i)
            }
        }
    }

    private data class Rail(val title: String, val items: List<MovieBoxApi.Movie>)
    private data class HeaderItem(val title: String)

    private class MoviesAdapter(
        private val items: List<Any>,
        private val onClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_POSTER = 1
        private val TYPE_ROW    = 2

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is HeaderItem -> TYPE_HEADER
            is MovieBoxApi.Movie -> TYPE_POSTER
            else -> TYPE_ROW
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(inf.inflate(R.layout.item_movie_section_header, parent, false))
                TYPE_POSTER -> PosterHolder(inf.inflate(R.layout.item_movie_poster, parent, false))
                else        -> RowHolder(inf.inflate(R.layout.item_movie, parent, false))
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HeaderItem -> (holder as HeaderHolder).title.text = item.title
                is MovieBoxApi.Movie -> when (holder) {
                    is PosterHolder -> bindPoster(holder, item)
                    is RowHolder    -> bindRow(holder, item)
                }
            }
        }

        private fun bindPoster(h: PosterHolder, m: MovieBoxApi.Movie) {
            h.title.text = m.title
            h.rating.text = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
            h.rating.visibility = if (m.rating.isNotBlank()) View.VISIBLE else View.GONE
            h.poster.tag = m.poster
            if (m.poster.isNotBlank()) {
                ThumbnailCache.loadInto(h.itemView.context, m.poster, h.poster)
            }
            h.itemView.setOnClickListener { onClick(m) }
        }

        private fun bindRow(h: RowHolder, m: MovieBoxApi.Movie) {
            h.title.text = m.title
            val parts = listOfNotNull(
                m.year.takeIf { it.isNotBlank() },
                m.rating.takeIf { it.isNotBlank() }?.let { "★ $it" },
                m.genre.takeIf { it.isNotBlank() }
            )
            h.meta.text = parts.joinToString(" • ")
            h.overview.text = ""
            h.poster.tag = m.poster
            if (m.poster.isNotBlank()) {
                ThumbnailCache.loadInto(h.itemView.context, m.poster, h.poster)
            }
            h.itemView.setOnClickListener { onClick(m) }
        }

        class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvSectionTitle)
        }
        class PosterHolder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val rating: TextView = v.findViewById(R.id.tvRating)
        }
        class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val meta: TextView = v.findViewById(R.id.tvMeta)
            val overview: TextView = v.findViewById(R.id.tvOverview)
        }
    }
}
