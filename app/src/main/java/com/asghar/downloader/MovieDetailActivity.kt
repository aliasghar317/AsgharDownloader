package com.asghar.downloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asghar.downloader.utils.MovieBoxApi
import com.asghar.downloader.utils.ThumbnailCache
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors

/**
 * Movie detail screen shown after the user taps a poster in [MoviesActivity].
 *
 * Mirrors the MovieBox detail page:
 *   - Hero backdrop + title + rating + year + duration + type chip
 *   - "▶ Play" and "Download" action buttons
 *   - "Resources / Uploaded by" section with a "Hindi dub" language
 *     dropdown (the same dropdown MovieBox uses) and a big play button
 *   - "Available Qualities" rail with file size + duration
 *   - "Dubbing / Audio" rail
 *   - "Subtitles" text
 *   - "More Like This" related rail
 *
 * The Play button either hands off the BFF stream URL to
 * [MoviePlayerActivity], or — when the BFF streams are gated — opens
 * the netfilm.world WebView so the user can log in.
 *
 * The Download button opens a MovieBox-style bottom sheet that lists
 * every available quality with its file size + duration and lets the
 * user pick one or several at a time.
 */
class MovieDetailActivity : AppCompatActivity() {

    private val executor = Executors.newFixedThreadPool(2)
    private lateinit var subjectId: String
    private var subjectInfo: MovieBoxApi.SubjectInfo? = null
    private var streams: List<MovieBoxApi.Stream> = emptyList()

    private lateinit var ivBackdrop: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvType: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCountry: TextView
    private lateinit var tvDescription: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnDownload: Button
    private lateinit var rvQualities: RecyclerView
    private lateinit var rvRelated: RecyclerView
    private lateinit var tvSubtitles: TextView
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: Toolbar
    private lateinit var collapsing: CollapsingToolbarLayout
    private lateinit var rvDubs: RecyclerView
    private lateinit var tvDubbingEmpty: TextView
    private lateinit var tvUploader: TextView
    private lateinit var btnLanguageDropdown: LinearLayout
    private lateinit var tvSelectedLanguage: TextView
    private lateinit var btnPlayResource: Button
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvSeasonsHeader: TextView
    private lateinit var btnSeasonDropdown: LinearLayout
    private lateinit var tvSelectedSeason: TextView

    private val availableLanguages: MutableList<String> = mutableListOf("Original Audio")
    private var selectedLanguage: String = "Original Audio"
    private var currentSeasons: List<MovieBoxApi.Season> = emptyList()
    private var currentSeasonIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_detail)

        subjectId = intent.getStringExtra("SUBJECT_ID").orEmpty()
        if (subjectId.isBlank()) { finish(); return }

        ivBackdrop = findViewById(R.id.ivBackdrop)
        tvTitle = findViewById(R.id.tvTitle)
        tvRating = findViewById(R.id.tvRating)
        tvYear = findViewById(R.id.tvYear)
        tvDuration = findViewById(R.id.tvDuration)
        tvType = findViewById(R.id.tvType)
        tvGenre = findViewById(R.id.tvGenre)
        tvCountry = findViewById(R.id.tvCountry)
        tvDescription = findViewById(R.id.tvDescription)
        btnPlay = findViewById(R.id.btnPlay)
        btnDownload = findViewById(R.id.btnDownload)
        rvQualities = findViewById(R.id.rvQualities)
        rvRelated = findViewById(R.id.rvRelated)
        tvSubtitles = findViewById(R.id.tvSubtitles)
        rvDubs = findViewById(R.id.rvDubs)
        tvDubbingEmpty = findViewById(R.id.tvDubbingEmpty)
        tvUploader = findViewById(R.id.tvUploader)
        btnLanguageDropdown = findViewById(R.id.btnLanguageDropdown)
        tvSelectedLanguage = findViewById(R.id.tvSelectedLanguage)
        btnPlayResource = findViewById(R.id.btnPlayResource)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        tvSeasonsHeader = findViewById(R.id.tvSeasonsHeader)
        btnSeasonDropdown = findViewById(R.id.btnSeasonDropdown)
        tvSelectedSeason = findViewById(R.id.tvSelectedSeason)
        progress = findViewById(R.id.progress)
        toolbar = findViewById(R.id.toolbar)
        collapsing = findViewById(R.id.collapsingToolbar)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rvQualities.layoutManager = LinearLayoutManager(this)
        rvRelated.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvDubs.layoutManager = LinearLayoutManager(this)

        btnPlay.setOnClickListener { onPlay() }
        btnDownload.setOnClickListener { showDownloadSheet() }
        btnLanguageDropdown.setOnClickListener { showLanguageSheet() }
        btnPlayResource.setOnClickListener { onPlay() }

        progress.visibility = View.VISIBLE
        executor.execute {
            val info = MovieBoxApi.subjectInfo(subjectId)
            val related = MovieBoxApi.related(subjectId)
            val detail = MovieBoxApi.detail(subjectId)
            val playInfo = MovieBoxApi.playInfo(subjectId)
            val seasons = MovieBoxApi.seasons(subjectId)
            val allDubs = MovieBoxApi.dubs(subjectId)
            runOnUiThread {
                progress.visibility = View.GONE
                if (info == null) {
                    Toast.makeText(this, "Could not load this title", Toast.LENGTH_SHORT).show()
                    finish(); return@runOnUiThread
                }
                subjectInfo = info
                streams = playInfo?.streams?.ifEmpty { detail?.streams.orEmpty() } ?: detail?.streams.orEmpty()
                bind(info, related, seasons, allDubs)
            }
        }
    }

    private fun bind(
        info: MovieBoxApi.SubjectInfo,
        related: List<MovieBoxApi.Movie>,
        seasons: List<MovieBoxApi.Season>,
        allDubs: List<MovieBoxApi.Dub>
    ) {
        collapsing.title = info.title
        tvTitle.text = info.title
        tvRating.text = if (info.rating.isNotBlank()) "★ ${info.rating}" else ""
        tvRating.visibility = if (info.rating.isNotBlank()) View.VISIBLE else View.GONE
        tvYear.text = info.year
        tvYear.visibility = if (info.year.isNotBlank()) View.VISIBLE else View.GONE
        tvDuration.text = info.durationFormatted
        tvDuration.visibility = if (info.durationFormatted.isNotBlank()) View.VISIBLE else View.GONE
        tvType.text = info.typeLabel
        tvType.visibility = View.VISIBLE
        tvGenre.text = info.genre
        tvGenre.visibility = if (info.genre.isNotBlank()) View.VISIBLE else View.GONE
        tvCountry.text = if (info.country.isNotBlank()) "Country: ${info.country}" else ""
        tvCountry.visibility = if (info.country.isNotBlank()) View.VISIBLE else View.GONE
        tvDescription.text = info.description
        tvDescription.visibility = if (info.description.isNotBlank()) View.VISIBLE else View.GONE
        tvSubtitles.text = if (info.subtitles.isNotEmpty()) info.subtitles.joinToString("  •  ")
                          else "No subtitles listed"
        if (info.backdrop.isNotBlank()) ThumbnailCache.loadInto(this, info.backdrop, ivBackdrop)
        else if (info.poster.isNotBlank()) ThumbnailCache.loadInto(this, info.poster, ivBackdrop)

        // Build the language list: every dub from the BFF, plus
        // "Original Audio" and any language hinted at by the title's
        // [Language] suffix. MovieBox shows all of them in the language
        // dropdown so the user can pick before they play.
        availableLanguages.clear()
        availableLanguages.add("Original Audio")
        (allDubs.ifEmpty { info.dubs }).forEach { d ->
            val label = d.dubName.ifBlank { d.dubLang }.ifBlank { "Dub" }
            if (label.isNotBlank() && !availableLanguages.contains(label))
                availableLanguages.add(label)
        }
        if (availableLanguages.size == 1) {
            // BFF did not return any dubs for this title. Use the title
            // suffix to derive something useful, e.g. "Avengers: Endgame
            // [Hindi]" → "Hindi dub".
            val bracket = Regex("\\[(.+?)\\]").find(info.title)?.groupValues?.getOrNull(1)
            if (!bracket.isNullOrBlank()) availableLanguages.add("$bracket dub")
        }
        tvSelectedLanguage.text = selectedLanguage

        val resourceTitle = info.title
        btnPlayResource.text = "▶  $resourceTitle"
        btnPlayResource.visibility = View.VISIBLE

        val qualityList = ArrayList<MovieBoxApi.Stream>()
        if (streams.isNotEmpty()) qualityList.addAll(streams)
        else {
            val max = 1080
            listOfNotNull(
                if (360 <= max) MovieBoxApi.Stream("360P", "hls", "", 0) else null,
                if (480 <= max) MovieBoxApi.Stream("480P", "hls", "", 0) else null,
                if (720 <= max) MovieBoxApi.Stream("720P", "hls", "", 0) else null,
                if (1080 <= max) MovieBoxApi.Stream("1080P", "hls", "", 0) else null
            ).let { qualityList.addAll(it) }
        }
        rvQualities.adapter = QualityAdapter(qualityList) { stream ->
            onQualityDownload(stream)
        }

        val dubsToShow = (allDubs.ifEmpty { info.dubs })
        if (dubsToShow.isNotEmpty()) {
            tvDubbingEmpty.visibility = View.GONE
            rvDubs.visibility = View.VISIBLE
            rvDubs.adapter = DubbingAdapter(dubsToShow) { dub ->
                val label = dub.dubName.ifBlank { dub.dubLang }
                selectedLanguage = label
                tvSelectedLanguage.text = label
                Toast.makeText(this, "Audio: $label", Toast.LENGTH_SHORT).show()
            }
        } else {
            tvDubbingEmpty.visibility = View.VISIBLE
            rvDubs.visibility = View.GONE
        }

        // Series: show a Season dropdown + episode rail
        if (seasons.isNotEmpty()) {
            tvSeasonsHeader.visibility = View.VISIBLE
            rvEpisodes.visibility = View.VISIBLE
            btnSeasonDropdown.visibility = View.VISIBLE
            tvSelectedSeason.text = seasons.first().title
            currentSeasons = seasons
            currentSeasonIndex = 0
            bindEpisodes(seasons.first().episodes)
            btnSeasonDropdown.setOnClickListener { showSeasonSheet() }
        } else {
            tvSeasonsHeader.visibility = View.GONE
            rvEpisodes.visibility = View.GONE
            btnSeasonDropdown.visibility = View.GONE
        }

        rvRelated.adapter = RelatedAdapter(related) { m ->
            val i = Intent(this, MovieDetailActivity::class.java)
                .putExtra("SUBJECT_ID", m.id)
            startActivity(i)
        }
    }

    private fun bindEpisodes(episodes: List<MovieBoxApi.Episode>) {
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvEpisodes.adapter = EpisodeAdapter(episodes) { ep ->
            onPlayEpisode(ep)
        }
    }

    private fun showSeasonSheet() {
        val seasons = currentSeasons
        if (seasons.isEmpty()) return
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_seasons, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvSeasons)
        view.findViewById<ImageButton>(R.id.btnCloseSeasons).setOnClickListener { sheet.dismiss() }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = SeasonAdapter(seasons, currentSeasonIndex) { idx, s ->
            currentSeasonIndex = idx
            tvSelectedSeason.text = s.title
            bindEpisodes(s.episodes)
            sheet.dismiss()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun onPlayEpisode(ep: MovieBoxApi.Episode) {
        val info = subjectInfo ?: return
        val i = Intent(this, MoviePlayerActivity::class.java).apply {
            putExtra("STREAM_URL", "")  // resolved at play time
            putExtra("TITLE", "${info.title} - ${ep.title}")
            putExtra("FORMAT", "hls")
            putExtra("SUBJECT_ID", info.id)
            putExtra("SEASON", ep.seasonNumber)
            putExtra("EPISODE", ep.number)
            putExtra("EPISODE_ID", ep.id)
        }
        startActivity(i)
    }

    private fun onPlay() {
        val info = subjectInfo ?: return
        if (streams.isNotEmpty()) {
            val s = streams.first()
            val i = Intent(this, MoviePlayerActivity::class.java).apply {
                putExtra("STREAM_URL", s.url)
                putExtra("TITLE", info.title)
                putExtra("FORMAT", s.format)
            }
            startActivity(i)
            return
        }
        if (info.detailPath.isNotBlank()) {
            val i = Intent(this, MovieWebPlayerActivity::class.java).apply {
                putExtra("PLAY_URL", "${MovieBoxApi.PLAY_DOMAIN}/play/${info.detailPath}")
                putExtra("TITLE", info.title)
            }
            startActivity(i)
        } else {
            Toast.makeText(this, "Stream locked. Try a different title.", Toast.LENGTH_LONG).show()
        }
    }

    private fun onQualityDownload(stream: MovieBoxApi.Stream) {
        val info = subjectInfo ?: return
        if (stream.url.isBlank()) {
            Toast.makeText(this, "This quality is gated by sign-in. Tap Play to open the WebView player.", Toast.LENGTH_LONG).show()
            return
        }
        startSystemDownload(stream.url, "${info.title} - ${stream.quality}", stream.quality)
    }

    private fun showLanguageSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_language, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvLanguages)
        view.findViewById<ImageButton>(R.id.btnCloseLanguage).setOnClickListener { sheet.dismiss() }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = LanguageAdapter(availableLanguages, selectedLanguage) { lang ->
            selectedLanguage = lang
            tvSelectedLanguage.text = lang
            sheet.dismiss()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun showDownloadSheet() {
        val info = subjectInfo ?: return
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_download, null)
        val tvSheetUploader = view.findViewById<TextView>(R.id.tvSheetUploader)
        val tvSheetLanguage = view.findViewById<TextView>(R.id.tvSheetLanguage)
        val btnSheetLanguage = view.findViewById<LinearLayout>(R.id.btnSheetLanguageDropdown)
        val rv = view.findViewById<RecyclerView>(R.id.rvQualityOptions)
        val cbSelectAll = view.findViewById<CheckBox>(R.id.cbSelectAll)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmDownload)
        view.findViewById<ImageButton>(R.id.btnCloseDownload).setOnClickListener { sheet.dismiss() }

        tvSheetUploader.text = tvUploader.text
        tvSheetLanguage.text = selectedLanguage

        btnSheetLanguage.setOnClickListener {
            sheet.dismiss()
            showLanguageSheet()
        }

        val options = buildQualityOptions(info)
        val states = BooleanArray(options.size)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = QualityOptionAdapter(options, states) { index, checked ->
            states[index] = checked
            cbSelectAll.setOnCheckedChangeListener(null)
            cbSelectAll.isChecked = states.all { it }
            cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                for (i in states.indices) states[i] = isChecked
                rv.adapter?.notifyDataSetChanged()
                updateConfirmLabel(btnConfirm, options, states)
            }
            updateConfirmLabel(btnConfirm, options, states)
        }
        rv.adapter = adapter
        updateConfirmLabel(btnConfirm, options, states)

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            for (i in states.indices) states[i] = isChecked
            rv.adapter?.notifyDataSetChanged()
            updateConfirmLabel(btnConfirm, options, states)
        }

        btnConfirm.setOnClickListener {
            val chosen = options.filterIndexed { i, _ -> states[i] }
            if (chosen.isEmpty()) {
                Toast.makeText(this, "Select at least one quality", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            for (q in chosen) {
                if (q.stream.url.isBlank()) continue
                startSystemDownload(q.stream.url, "${info.title} [${selectedLanguage}]", q.stream.quality)
            }
            sheet.dismiss()
        }

        sheet.setContentView(view)
        sheet.show()
    }

    private fun buildQualityOptions(info: MovieBoxApi.SubjectInfo): List<QualityOption> {
        val result = ArrayList<QualityOption>()
        val sources = if (streams.isNotEmpty()) streams else
            listOf(
                MovieBoxApi.Stream("360P", "hls", "", 0),
                MovieBoxApi.Stream("480P", "hls", "", 0),
                MovieBoxApi.Stream("720P", "hls", "", 0),
                MovieBoxApi.Stream("1080P", "hls", "", 0)
            )
        for (s in sources) {
            val height = parseQuality(s.quality)
            val bitrate = when (height) {
                in 0..360 -> 700
                in 361..480 -> 1200
                in 481..720 -> 2500
                else -> 5000
            }
            val bytes = (bitrate * 1000L / 8L) * (info.duration.coerceAtLeast(1))
            result.add(QualityOption(s, formatBytes(bytes), info.durationFormatted))
        }
        return result
    }

    private fun updateConfirmLabel(btn: Button, opts: List<QualityOption>, states: BooleanArray) {
        val totalBytes = opts.filterIndexed { i, _ -> states[i] }
            .sumOf { it.sizeBytes() }
        btn.text = "⬇  Download · ${formatBytes(totalBytes)}"
    }

    private fun formatBytes(b: Long): String {
        if (b <= 0) return "—"
        val mb = b / 1024.0 / 1024.0
        return if (mb < 1024) String.format("%.1fMB", mb) else String.format("%.2fGB", mb / 1024.0)
    }

    private fun parseQuality(q: String): Int {
        return Regex("(\\d+)").find(q)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 480
    }

    private fun startSystemDownload(url: String, title: String, quality: String) {
        if (url.isBlank()) return
        val safe = title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setDescription("Downloading $safe [$quality]")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "AsgharDownloader/Movies/$safe.$quality.mp4"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("Referer", MovieBoxApi.PLAY_DOMAIN + "/")
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
        Toast.makeText(this, "Download started: $quality", Toast.LENGTH_SHORT).show()
    }

    private data class QualityOption(val stream: MovieBoxApi.Stream, val sizeLabel: String, val durationLabel: String) {
        fun sizeBytes(): Long {
            if (stream.size > 0) return stream.size.toLong()
            val label = sizeLabel.removeSuffix("MB").removeSuffix("GB")
            val v = label.toDoubleOrNull() ?: return 0L
            return if (sizeLabel.endsWith("GB")) (v * 1024 * 1024 * 1024).toLong()
                   else (v * 1024 * 1024).toLong()
        }
    }

    private class QualityAdapter(
        private val items: List<MovieBoxApi.Stream>,
        private val onClick: (MovieBoxApi.Stream) -> Unit
    ) : RecyclerView.Adapter<QualityAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val quality: TextView = v.findViewById(R.id.tvQuality)
            val size: TextView = v.findViewById(R.id.tvSize)
            val format: TextView = v.findViewById(R.id.tvFormat)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_quality, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            holder.quality.text = s.quality.uppercase()
            holder.size.text = if (s.size > 0) "~${(s.size / (1024 * 1024))} MB" else "—"
            holder.format.text = s.format.uppercase()
            holder.itemView.setOnClickListener { onClick(s) }
        }
    }

    private class QualityOptionAdapter(
        private val items: List<QualityOption>,
        private val states: BooleanArray,
        private val onCheck: (Int, Boolean) -> Unit
    ) : RecyclerView.Adapter<QualityOptionAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val cb: CheckBox = v.findViewById(R.id.cbQuality)
            val name: TextView = v.findViewById(R.id.tvQualityName)
            val meta: TextView = v.findViewById(R.id.tvQualityMeta)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_quality_option, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = states[position]
            holder.name.text = "${item.stream.quality.uppercase()}  ${item.stream.titleSuffix()}"
            holder.meta.text = "${item.sizeLabel}  ·  ${item.durationLabel.ifBlank { "—" }}"
            holder.cb.setOnCheckedChangeListener { _, checked -> onCheck(position, checked) }
            holder.itemView.setOnClickListener {
                holder.cb.isChecked = !holder.cb.isChecked
            }
        }
    }

    private class RelatedAdapter(
        private val items: List<MovieBoxApi.Movie>,
        private val onClick: (MovieBoxApi.Movie) -> Unit
    ) : RecyclerView.Adapter<RelatedAdapter.Holder>() {
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

    private class DubbingAdapter(
        private val items: List<MovieBoxApi.Dub>,
        private val onClick: (MovieBoxApi.Dub) -> Unit
    ) : RecyclerView.Adapter<DubbingAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tvDubLang: TextView = v.findViewById(R.id.tvDubLang)
            val tvDubBadge: TextView = v.findViewById(R.id.tvDubBadge)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_dub, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val d = items[position]
            holder.tvDubLang.text = d.dubName.ifBlank { d.dubLang }
            holder.tvDubBadge.text = if (position == 0) "Default" else "Alt"
            holder.itemView.setOnClickListener { onClick(d) }
        }
    }

    private class LanguageAdapter(
        private val items: List<String>,
        private val selected: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvLanguage)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_language, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val lang = items[position]
            holder.tv.text = lang
            val isSelected = lang == selected
            holder.tv.setBackgroundResource(
                if (isSelected) R.drawable.chip_bg_selected else R.drawable.chip_bg
            )
            holder.tv.setTextColor(
                if (isSelected) 0xFF10B981.toInt() else 0xFFFFFFFF.toInt()
            )
            holder.itemView.setOnClickListener { onClick(lang) }
        }
    }

    /**
     * Vertical list of seasons. Mirrors the MovieBox dropdown — each
     * row is tappable and shows the season number.
     */
    private class SeasonAdapter(
        private val items: List<MovieBoxApi.Season>,
        private val selected: Int,
        private val onClick: (Int, MovieBoxApi.Season) -> Unit
    ) : RecyclerView.Adapter<SeasonAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tvSeason: TextView = v.findViewById(R.id.tvSeasonName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_season, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            holder.tvSeason.text = s.title
            val isSel = position == selected
            holder.tvSeason.setTextColor(
                if (isSel) 0xFF10B981.toInt() else 0xFFFFFFFF.toInt()
            )
            holder.itemView.setOnClickListener { onClick(position, s) }
        }
    }

    /**
     * Horizontal rail of episode cards (poster + number + duration).
     * Tapping one plays that specific episode via the MoviePlayer.
     */
    private class EpisodeAdapter(
        private val items: List<MovieBoxApi.Episode>,
        private val onClick: (MovieBoxApi.Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivEpisode)
            val tvNum: TextView = v.findViewById(R.id.tvEpisodeNum)
            val tvDur: TextView = v.findViewById(R.id.tvEpisodeDur)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
            return Holder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ep = items[position]
            val num = if (ep.number > 0) ep.number.toString().padStart(2, '0') else (position + 1).toString().padStart(2, '0')
            holder.tvNum.text = num
            holder.tvDur.text = ep.durationFormatted.ifBlank { "—" }
            if (ep.thumbnail.isNotBlank()) ThumbnailCache.loadInto(holder.itemView.context, ep.thumbnail, holder.iv)
            holder.itemView.setOnClickListener { onClick(ep) }
        }
    }
}

private fun MovieBoxApi.Stream.titleSuffix(): String =
    if (url.isNotBlank()) format.uppercase() else "Web"
