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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.asghar.downloader.utils.MovieBoxApi
import com.asghar.downloader.utils.ThumbnailCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * MovieBox-style movies tab. The screen is laid out like Netflix/MovieBox:
 *   [ Top: "Asghar Downloader" title + back button + cookie key ]
 *   [ Search bar + status line ]
 *   [ Hero banner with Play / Info buttons ]
 *   [ Vertical list: section header, then horizontal rails of posters ]
 *   [ Pull-to-refresh to force reload ]
 *
 * All data is fetched from h5-api.aoneroom.com (MovieBox's public BFF).
 * Stream URLs (subject/play) are empty for unauthenticated callers; the
 * "Play" button on the hero shows a friendly "Sign in to watch" message
 * when the title is locked behind a login.
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
    private lateinit var swipe: SwipeRefreshLayout

    private var currentSearch = ""
    private var inflight = false

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
        swipe = findViewById(R.id.swipeMovies)

        list.layoutManager = LinearLayoutManager(this)
        swipe.setColorSchemeResources(android.R.color.holo_red_light, android.R.color.holo_orange_light)
        swipe.setOnRefreshListener { loadHome(force = true) }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnKey).setOnClickListener { showKeyDialog() }
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                currentSearch = search.text.toString().trim()
                if (currentSearch.isBlank()) loadHome(force = true) else loadSearch(currentSearch, force = true)
                true
            } else false
        }

        // Show cached data immediately so the screen is not blank on
        // every open. The network call below silently refreshes the
        // list in the background.
        renderCache()
        loadHome(force = false)
    }

    private fun showKeyDialog() {
        val edt = EditText(this).apply { hint = "MovieBox API base URL (optional override)" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("MovieBox settings")
            .setMessage("By default the app calls h5-api.aoneroom.com. Leave blank for default.")
            .setView(edt)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val v = edt.text.toString().trim()
                getPreferences(MODE_PRIVATE).edit().putString("moviebox_base", v).apply()
                Toast.makeText(this, "Saved. Reopen Movies to apply.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadHome(force: Boolean) {
        if (inflight) return
        inflight = true
        if (!swipe.isRefreshing) swipe.isRefreshing = true
        status.text = if (force) "Refreshing catalog…" else "Loading movie catalog…"
        executor.execute {
            val rails = MovieBoxApi.homeRails()
            val trending = MovieBoxApi.trending()
            main.post {
                inflight = false
                swipe.isRefreshing = false
                if (rails.isEmpty() && trending.isEmpty()) {
                    status.text = "No movies available. Try search."
                } else {
                    status.text = "Showing ${rails.size} rails"
                    cacheRails(rails, trending)
                    bindRails(rails, trending)
                    if (trending.isNotEmpty()) bindHero(trending.first())
                }
            }
        }
    }

    private fun loadSearch(query: String, force: Boolean) {
        if (inflight) return
        inflight = true
        if (!swipe.isRefreshing) swipe.isRefreshing = true
        status.text = "Searching for \"$query\"…"
        executor.execute {
            val results = MovieBoxApi.search(query)
            main.post {
                inflight = false
                swipe.isRefreshing = false
                val rails = if (results.isEmpty()) emptyList() else listOf(MovieBoxApi.Rail("Search results", results))
                status.text = if (results.isEmpty()) "No results for \"$query\"" else "${results.size} results"
                bindRails(rails, emptyList())
                if (results.isNotEmpty()) bindHero(results.first())
            }
        }
    }

    /** Caches rails + trending under one SharedPreferences key so the
     *  next open can paint instantly. The cache is overwritten on every
     *  successful network load. */
    private fun cacheRails(rails: List<MovieBoxApi.Rail>, trending: List<MovieBoxApi.Movie>) {
        val root = JSONObject()
        root.put("savedAt", System.currentTimeMillis())
        root.put("trending", JSONArray().apply { trending.forEach { put(movieToJson(it)) } })
        root.put("rails", JSONArray().apply {
            rails.forEach { r ->
                val o = JSONObject()
                o.put("title", r.title)
                o.put("items", JSONArray().apply { r.items.forEach { put(movieToJson(it)) } })
                put(o)
            }
        })
        getPreferences(MODE_PRIVATE).edit()
            .putString("moviebox_cache_v1", root.toString())
            .apply()
    }

    private fun renderCache() {
        val raw = getPreferences(MODE_PRIVATE).getString("moviebox_cache_v1", null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val rails = ArrayList<MovieBoxApi.Rail>()
        root.optJSONArray("rails")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val items = ArrayList<MovieBoxApi.Movie>()
                o.optJSONArray("items")?.let { ia ->
                    for (j in 0 until ia.length()) items.add(jsonToMovie(ia.optJSONObject(j)))
                }
                if (items.isNotEmpty()) rails.add(MovieBoxApi.Rail(o.optString("title"), items))
            }
        }
        val trending = ArrayList<MovieBoxApi.Movie>()
        root.optJSONArray("trending")?.let { arr ->
            for (i in 0 until arr.length()) trending.add(jsonToMovie(arr.optJSONObject(i)))
        }
        if (rails.isNotEmpty() || trending.isNotEmpty()) {
            bindRails(rails, trending)
            if (trending.isNotEmpty()) bindHero(trending.first())
            status.text = "Showing cached movies (${rails.size} rails) — refreshing…"
        }
    }

    private fun movieToJson(m: MovieBoxApi.Movie): JSONObject = JSONObject().apply {
        put("id", m.id); put("title", m.title); put("year", m.year)
        put("rating", m.rating); put("poster", m.poster); put("genre", m.genre)
        put("country", m.country); put("type", m.type); put("backdrop", m.backdrop)
    }

    private fun jsonToMovie(o: JSONObject?): MovieBoxApi.Movie {
        if (o == null) return MovieBoxApi.Movie("", "Unknown")
        return MovieBoxApi.Movie(
            id = o.optString("id"),
            title = o.optString("title"),
            year = o.optString("year"),
            rating = o.optString("rating"),
            poster = o.optString("poster"),
            genre = o.optString("genre"),
            country = o.optString("country"),
            type = o.optString("type", "1"),
            backdrop = o.optString("backdrop")
        )
    }

    private fun bindRails(rails: List<MovieBoxApi.Rail>, trending: List<MovieBoxApi.Movie>) {
        val merged = mutableListOf<Any>()
        if (trending.isNotEmpty()) {
            merged.add(HeaderItem("🔥 Trending Now"))
            trending.take(10).forEach { merged.add(it) }
        }
        rails.forEach { r ->
            if (r.items.isNotEmpty()) {
                merged.add(HeaderItem(r.title))
                r.items.take(10).forEach { merged.add(it) }
            }
        }
        list.adapter = MoviesAdapter(merged) { onMovieClick(it) }
    }

    private fun bindHero(m: MovieBoxApi.Movie) {
        heroTitle.text = m.title
        val parts = listOf(m.year, m.rating, m.genre).filter { it.isNotBlank() }
        heroMeta.text = parts.joinToString(" • ")
        val img = if (m.backdrop.isNotBlank()) m.backdrop else m.poster
        if (img.isNotBlank()) ThumbnailCache.loadInto(this, img, heroImage)
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
                if (detail.vipLocked || detail.streams.isEmpty()) {
                    status.text = "\"${m.title}\" requires sign-in to play"
                    Toast.makeText(this, "Sign in to watch \"${m.title}\" (MovieBox stream locked)", Toast.LENGTH_LONG).show()
                    return@post
                }
                val s = detail.streams.first()
                val i = Intent(this, MoviePlayerActivity::class.java).apply {
                    putExtra("STREAM_URL", s.url)
                    putExtra("TITLE", m.title)
                    putExtra("FORMAT", s.format)
                }
                startActivity(i)
            }
        }
    }

    private data class HeaderItem(val title: String)

    private class MoviesAdapter(
        private val items: List<Any>,
        private val onClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_POSTER  = 1

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is HeaderItem -> TYPE_HEADER
            is MovieBoxApi.Movie -> TYPE_POSTER
            else -> TYPE_HEADER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(inf.inflate(R.layout.item_movie_section_header, parent, false))
                else        -> PosterHolder(inf.inflate(R.layout.item_movie_poster, parent, false))
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HeaderItem -> (holder as HeaderHolder).title.text = item.title
                is MovieBoxApi.Movie -> bindPoster(holder as PosterHolder, item)
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

        class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tvSectionTitle)
        }
        class PosterHolder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val rating: TextView = v.findViewById(R.id.tvRating)
        }
    }
}
