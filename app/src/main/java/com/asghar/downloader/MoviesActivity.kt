package com.asghar.downloader

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.asghar.downloader.utils.MovieBoxApi
import com.asghar.downloader.utils.ThumbnailCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * MovieBox-style movies tab.
 *
 * Layout (top to bottom):
 *   1. App title + back/settings
 *   2. Live search bar (TextWatcher with 300ms debounce → /search-suggest)
 *   3. Status line
 *   4. Horizontal chip rail: Trending, Movie, TV, Short Dramas
 *   5. Hero banner carousel (ViewAnimator with 3-5 featured posters)
 *   6. Mixed-rail RecyclerView:
 *        - "2025 Must-Watch" (2-col grid)
 *        - "Trending Now" (horizontal rail)
 *        - "Movies in Minutes" (2-col grid)
 *        - "Keep Watching" (horizontal rail, when cache has a watched list)
 *        - …all other operatingList sections as horizontal rails
 *   7. Pull-to-refresh to force-reload
 *
 * All catalog data is fetched from h5-api.aoneroom.com. The first
 * load paints instantly from SharedPreferences (if any) and silently
 * refreshes in the background. Stream URLs are NOT exposed by the BFF
 * for guest sessions; tapping a movie opens a web player fallback
 * (netfilm.world) so the user can still watch the title.
 */
class MoviesActivity : AppCompatActivity() {

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rvChips: RecyclerView
    private lateinit var rvSuggestions: RecyclerView
    private lateinit var suggestionsContainer: FrameLayout
    private lateinit var heroAnimator: ViewAnimator
    private lateinit var heroDots: LinearLayout

    private val chips = mutableListOf<Chip>()
    private var currentChip = 0
    private var inflight = false
    private var searchDebounce: Runnable? = null
    private val searchHandler = Handler(Looper.getMainLooper())

    private var cachedRails: List<MovieBoxApi.Rail> = emptyList()
    private var cachedTrending: List<MovieBoxApi.Movie> = emptyList()

    private val heroTimer = Handler(Looper.getMainLooper())
    private val heroRunnable = object : Runnable {
        override fun run() {
            if (heroAnimator.childCount > 1) {
                val next = (heroAnimator.displayedChild + 1) % heroAnimator.childCount
                heroAnimator.displayedChild = next
                updateDots(next)
            }
            heroTimer.postDelayed(this, 4500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)

        search = findViewById(R.id.etSearch)
        status = findViewById(R.id.tvStatus)
        list = findViewById(R.id.rvMovies)
        swipe = findViewById(R.id.swipeMovies)
        rvChips = findViewById(R.id.rvChips)
        rvSuggestions = findViewById(R.id.rvSuggestions)
        suggestionsContainer = findViewById(R.id.suggestionsContainer)
        heroAnimator = findViewById(R.id.heroAnimator)
        heroDots = findViewById(R.id.heroDots)

        list.layoutManager = LinearLayoutManager(this)
        rvChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSuggestions.layoutManager = LinearLayoutManager(this)
        swipe.setColorSchemeResources(android.R.color.holo_red_light, android.R.color.holo_orange_light)
        swipe.setOnRefreshListener { loadHome(force = true) }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnKey).setOnClickListener { openMovieDownloads() }

        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                commitSearch(search.text.toString().trim())
                hideSuggestions()
                true
            } else false
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                searchDebounce?.let { searchHandler.removeCallbacks(it) }
                if (q.length < 2) { hideSuggestions(); return }
                searchDebounce = Runnable { fetchSuggestions(q) }
                searchHandler.postDelayed(searchDebounce!!, 300L)
            }
        })

        renderCache()
        loadHome(force = false)
    }

    override fun onResume() {
        super.onResume()
        heroTimer.postDelayed(heroRunnable, 4500L)
    }

    override fun onPause() {
        super.onPause()
        heroTimer.removeCallbacks(heroRunnable)
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

    /**
     * The download arrow in the toolbar now opens the Movies downloads
     * list, mirroring the social-media downloads tab in MovieBox
     * (the icon previously opened a settings dialog).
     */
    private fun openMovieDownloads() {
        startActivity(Intent(this, MovieDownloadsActivity::class.java))
    }

    private fun loadHome(force: Boolean) {
        if (inflight) return
        inflight = true
        if (!swipe.isRefreshing) swipe.isRefreshing = true
        status.text = if (force) "Refreshing catalog…" else "Loading movie catalog…"
        executor.execute {
            val rails = MovieBoxApi.homeRails()
            val trending = MovieBoxApi.trending()
            val tabs = MovieBoxApi.tabs()
            val gridRails = listOf("2025 Must-Watch", "Movies in Minutes", "Now Playing",
                "Top Picks", "Featured", "New Releases").mapNotNull { name ->
                rails.firstOrNull { it.title.equals(name, true) } ?: rails.getOrNull(rails.indexOfFirst { it.title.contains("Must", true) || it.title.contains("Top", true) || it.title.contains("Popular", true) })
            }
            main.post {
                inflight = false
                swipe.isRefreshing = false
                cachedRails = rails
                cachedTrending = trending
                if (rails.isEmpty() && trending.isEmpty()) {
                    status.text = "No movies available. Try search."
                } else {
                    status.text = "Showing ${rails.size} rails • ${trending.size} trending"
                    cacheRails(rails, trending)
                    bindChips(tabs)
                    bindHero(trending.take(5).ifEmpty { rails.flatMap { it.items } }.take(5))
                    bindRails(rails, trending)
                }
            }
        }
    }

    private fun fetchSuggestions(q: String) {
        executor.execute {
            val list = MovieBoxApi.suggest(q)
            main.post { showSuggestions(list, q) }
        }
    }

    private fun showSuggestions(items: List<String>, q: String) {
        if (items.isEmpty() || q != search.text.toString().trim()) { hideSuggestions(); return }
        suggestionsContainer.visibility = View.VISIBLE
        rvSuggestions.adapter = SuggestionAdapter(items) { suggestion ->
            search.setText(suggestion)
            search.setSelection(suggestion.length)
            commitSearch(suggestion)
            hideSuggestions()
        }
    }

    private fun hideSuggestions() {
        suggestionsContainer.visibility = View.GONE
    }

    private fun commitSearch(q: String) {
        if (q.isBlank()) { loadHome(force = true); return }
        status.text = "Searching for \"$q\"…"
        executor.execute {
            val results = MovieBoxApi.search(q)
            main.post {
                status.text = if (results.isEmpty()) "No results for \"$q\"" else "${results.size} results"
                bindSearchResults(results)
                if (results.isNotEmpty()) bindHero(results.take(5))
            }
        }
    }

    /**
     * MovieBox-style search results: full-width list items with poster,
     * title, year, rating, genre, country and a play button on the right.
     */
    private fun bindSearchResults(results: List<MovieBoxApi.Movie>) {
        val sections = mutableListOf<Any>()
        if (results.isNotEmpty()) {
            sections.add(SectionHeader("Search results", isGrid = false))
            sections.add(SearchResultList(results))
        }
        list.adapter = SectionAdapter(sections) { m -> onMovieClick(m) }
    }

    private fun bindChips(tabs: List<MovieBoxApi.Tab>) {
        chips.clear()
        if (tabs.isEmpty()) {
            // Sensible defaults if /tab-operating returned nothing
            listOf("Trending", "Movie", "TV", "Short Dramas").forEachIndexed { i, n ->
                chips.add(Chip(n, i == 0))
            }
        } else {
            tabs.take(10).forEachIndexed { i, t -> chips.add(Chip(t.title, i == 0)) }
        }
        rvChips.adapter = ChipAdapter(chips) { idx ->
            if (idx == currentChip) return@ChipAdapter
            chips.forEachIndexed { j, c -> chips[j] = c.copy(selected = j == idx) }
            currentChip = idx
            rvChips.adapter?.notifyDataSetChanged()
            applyChip(idx, tabs)
        }
    }

    private fun applyChip(idx: Int, tabs: List<MovieBoxApi.Tab>) {
        if (idx >= tabs.size) {
            // "Trending" / "Movie" / "TV" / "Short Dramas" use the cached rails
            val title = chips[idx].title
            val filtered = when (title.lowercase()) {
                "movie", "movies" -> cachedRails.filter { r ->
                    r.items.any { it.type == "1" } || r.title.contains("movie", true)
                }
                "tv", "series" -> cachedRails.filter { r ->
                    r.items.any { it.type == "2" } || r.title.contains("series", true) || r.title.contains("drama", true)
                }
                "short dramas" -> cachedRails.filter { r -> r.title.contains("short", true) }
                else -> cachedRails
            }
            bindRails(if (filtered.isEmpty()) cachedRails else filtered, cachedTrending)
            return
        }
        val tab = tabs[idx]
        status.text = "Showing ${tab.title}"
        bindRails(listOf(MovieBoxApi.Rail(tab.title, tab.items)), tab.items)
    }

    private fun bindHero(items: List<MovieBoxApi.Movie>) {
        heroAnimator.removeAllViews()
        heroDots.removeAllViews()
        if (items.isEmpty()) {
            val placeholder = TextView(this).apply {
                text = "No featured content"
                setTextColor(0xFF7C8AA5.toInt())
                textSize = 14f
            }
            heroAnimator.addView(placeholder)
            return
        }
        items.forEachIndexed { idx, m ->
            val v = LayoutInflater.from(this).inflate(R.layout.hero_banner, heroAnimator, false)
            val title = v.findViewById<TextView>(R.id.tvHeroTitle)
            val meta = v.findViewById<TextView>(R.id.tvHeroMeta)
            val img = v.findViewById<ImageView>(R.id.ivHero)
            val play = v.findViewById<Button>(R.id.btnHeroPlay)
            val info = v.findViewById<Button>(R.id.btnHeroInfo)
            title.text = m.title
            val parts = listOf(m.year, m.rating, m.genre).filter { it.isNotBlank() }
            meta.text = parts.joinToString(" • ")
            val hero = if (m.backdrop.isNotBlank()) m.backdrop else m.poster
            if (hero.isNotBlank()) ThumbnailCache.loadInto(this, hero, img)
            val onClick = View.OnClickListener { onMovieClick(m) }
            play.setOnClickListener(onClick)
            info.setOnClickListener(onClick)
            v.setOnClickListener(onClick)
            heroAnimator.addView(v)

            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(14, 14).apply {
                    marginStart = 4; marginEnd = 4
                }
                background = getDrawable(if (idx == 0) android.R.drawable.btn_radio else android.R.drawable.btn_radio)
                alpha = if (idx == 0) 1f else 0.4f
            }
            heroDots.addView(dot)
        }
        heroAnimator.displayedChild = 0
    }

    private fun updateDots(active: Int) {
        for (i in 0 until heroDots.childCount) {
            heroDots.getChildAt(i).alpha = if (i == active) 1f else 0.4f
        }
    }

    private fun bindRails(rails: List<MovieBoxApi.Rail>, trending: List<MovieBoxApi.Movie>) {
        val sections = mutableListOf<Any>()
        if (trending.isNotEmpty()) {
            sections.add(SectionHeader("🔥 Trending Now", isGrid = false))
            sections.add(HorizontalRail(trending))
        }
        rails.forEach { r ->
            if (r.items.isNotEmpty()) {
                val isGrid = r.title.contains("Must-Watch", true) ||
                    r.title.contains("Movies in Minutes", true) ||
                    r.title.contains("2025", true) ||
                    r.title.contains("Grid", true)
                sections.add(SectionHeader(r.title, isGrid = isGrid))
                if (isGrid) sections.add(GridRail(r.items))
                else sections.add(HorizontalRail(r.items))
            }
        }
        list.adapter = SectionAdapter(sections) { m -> onMovieClick(m) }
    }

    private fun onMovieClick(m: MovieBoxApi.Movie) {
        startActivity(Intent(this, MovieDetailActivity::class.java).apply {
            putExtra("SUBJECT_ID", m.id)
        })
    }

    /** Cache the rails + trending so the next open paints instantly. */
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
            .putString("moviebox_cache_v2", root.toString())
            .apply()
    }

    private fun renderCache() {
        val raw = getPreferences(MODE_PRIVATE).getString("moviebox_cache_v2", null) ?: return
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
            cachedRails = rails
            cachedTrending = trending
            bindRails(rails, trending)
            if (trending.isNotEmpty()) bindHero(trending.take(5))
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

    private data class Chip(val title: String, val selected: Boolean)
    private data class SectionHeader(val title: String, val isGrid: Boolean)
    private data class HorizontalRail(val items: List<MovieBoxApi.Movie>)
    private data class GridRail(val items: List<MovieBoxApi.Movie>)
    private data class SearchResultList(val items: List<MovieBoxApi.Movie>)

    companion object {
        const val PLAY_URL = "https://netfilm.world/play/"
    }

    /* ---------- Chip adapter ---------- */
    private class ChipAdapter(
        private val items: List<Chip>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChipAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvChip)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = items[position]
            holder.tv.text = c.title
            holder.itemView.setBackgroundResource(if (c.selected) R.drawable.chip_bg_selected else R.drawable.chip_bg)
            holder.itemView.setOnClickListener { onClick(position) }
        }
    }

    /* ---------- Suggestion adapter ---------- */
    private class SuggestionAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SuggestionAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvSuggestion)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_suggestion, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.tv.text = items[position]
            holder.itemView.setOnClickListener { onClick(items[position]) }
        }
    }

    /* ---------- Section adapter (header + rail / grid) ---------- */
    private class SectionAdapter(
        private val items: List<Any>,
        private val onMovieClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_HRAIL = 1
        private val TYPE_GRAIL = 2
        private val TYPE_SEARCH = 3

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is SectionHeader -> TYPE_HEADER
            is HorizontalRail -> TYPE_HRAIL
            is GridRail -> TYPE_GRAIL
            is SearchResultList -> TYPE_SEARCH
            else -> TYPE_HEADER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(inf.inflate(R.layout.item_movie_section_header, parent, false))
                TYPE_HRAIL -> RailHolder(RecyclerView(parent.context).apply {
                    layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                })
                TYPE_GRAIL -> GridHolder(RecyclerView(parent.context).apply {
                    layoutManager = GridLayoutManager(parent.context, 2)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                })
                else -> RailHolder(RecyclerView(parent.context).apply {
                    layoutManager = LinearLayoutManager(parent.context)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                })
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SectionHeader -> (holder as HeaderHolder).tv.text = item.title
                is HorizontalRail -> {
                    val rv = (holder as RailHolder).itemView as RecyclerView
                    rv.adapter = PosterAdapter(item.items, onMovieClick)
                }
                is GridRail -> {
                    val rv = (holder as GridHolder).itemView as RecyclerView
                    rv.adapter = GridAdapter(item.items, onMovieClick)
                }
                is SearchResultList -> {
                    val rv = (holder as RailHolder).itemView as RecyclerView
                    rv.adapter = SearchResultAdapter(item.items, onMovieClick)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvSectionTitle)
        }
        class RailHolder(v: View) : RecyclerView.ViewHolder(v)
        class GridHolder(v: View) : RecyclerView.ViewHolder(v)
    }

    private class PosterAdapter(
        private val items: List<MovieBoxApi.Movie>,
        private val onClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<PosterAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val rating: TextView = v.findViewById(R.id.tvRating)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rail_poster, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = items[position]
            holder.title.text = m.title
            holder.rating.text = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
            holder.rating.visibility = if (m.rating.isNotBlank()) View.VISIBLE else View.GONE
            if (m.poster.isNotBlank()) ThumbnailCache.loadInto(holder.itemView.context, m.poster, holder.poster)
            holder.itemView.setOnClickListener { onClick(m) }
        }
    }

    private class GridAdapter(
        private val items: List<MovieBoxApi.Movie>,
        private val onClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<GridAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val rating: TextView = v.findViewById(R.id.tvRating)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_poster, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = items[position]
            holder.title.text = m.title
            holder.rating.text = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
            holder.rating.visibility = if (m.rating.isNotBlank()) View.VISIBLE else View.GONE
            if (m.poster.isNotBlank()) ThumbnailCache.loadInto(holder.itemView.context, m.poster, holder.poster)
            holder.itemView.setOnClickListener { onClick(m) }
        }
    }

    /**
     * Full-width list item for search results. Mirrors the MovieBox app
     * (poster on the left, title / year / rating / genre / country on the
     * right, play button).
     */
    private class SearchResultAdapter(
        private val items: List<MovieBoxApi.Movie>,
        private val onClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvTitle)
            val meta: TextView = v.findViewById(R.id.tvMeta)
            val sub: TextView = v.findViewById(R.id.tvSub)
            val btnPlay: Button = v.findViewById(R.id.btnPlay)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = items[position]
            holder.title.text = m.title
            val year = m.year
            val rating = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
            val parts = listOfNotNull(
                if (year.isNotBlank()) year else null,
                if (rating.isNotBlank()) rating else null,
                if (m.genre.isNotBlank()) m.genre else null
            )
            holder.meta.text = parts.joinToString(" • ")
            holder.sub.text = if (m.country.isNotBlank()) "Country: ${m.country}" else ""
            holder.sub.visibility = if (m.country.isNotBlank()) View.VISIBLE else View.GONE
            if (m.poster.isNotBlank()) ThumbnailCache.loadInto(holder.itemView.context, m.poster, holder.poster)
            holder.itemView.setOnClickListener { onClick(m) }
            holder.btnPlay.setOnClickListener { onClick(m) }
        }
    }
}
