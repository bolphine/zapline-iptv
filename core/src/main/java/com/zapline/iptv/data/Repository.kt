package com.zapline.iptv.data

import com.zapline.iptv.lang.LanguageTagger
import com.zapline.iptv.lang.RegionTagger
import com.zapline.iptv.model.Channel
import com.zapline.iptv.parse.M3uParser
import com.zapline.iptv.parse.PlaylistFilters
import com.zapline.iptv.parse.Xtream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit


/** Everything loaded from the playlist, split into sections. */
data class Library(
    val live: List<Channel> = emptyList(),
    val movies: List<Channel> = emptyList(),
    /** One entry per show; url is "xtseries:<id>" or "m3useries:<key>". */
    val series: List<Channel> = emptyList(),
    /** Episodes from M3U playlists only (Xtream episodes are fetched per show); group = the show's url. */
    val episodes: List<Channel> = emptyList(),
    /** Non-fatal problems loading movies/series, shown to the user. */
    val warning: String? = null,
)

/**
 * Downloads, parses and caches playlists and the TV guide.
 * [openFile] opens a playlist the user picked (a content URI on Android, a path on Windows);
 * [newXmlParser] makes an XML pull parser (Android has one built in; desktop uses kxml2).
 */
class Repository(
    private val dir: File,
    val prefs: Prefs,
    private val openFile: (String) -> java.io.InputStream?,
    private val newXmlParser: () -> org.xmlpull.v1.XmlPullParser,
) {
    private val liveFile = File(dir, "channels.bin")
    private val moviesFile = File(dir, "movies.bin")
    private val seriesFile = File(dir, "series.bin")
    private val episodesFile = File(dir, "episodes.bin")
    private val epgFile = File(dir, "epg.bin")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Forget the downloaded channels, films, series and guide (used when switching playlist). */
    fun clearCaches() {
        listOf(liveFile, moviesFile, seriesFile, episodesFile, epgFile).forEach { it.delete() }
    }

    fun cacheAgeMs(): Long = if (liveFile.exists()) System.currentTimeMillis() - liveFile.lastModified() else Long.MAX_VALUE

    suspend fun loadCached(): Library? = withContext(Dispatchers.IO) {
        val live = ChannelCache.load(liveFile) ?: return@withContext null
        Library(
            live = live,
            movies = ChannelCache.load(moviesFile) ?: emptyList(),
            series = ChannelCache.load(seriesFile) ?: emptyList(),
            episodes = ChannelCache.load(episodesFile) ?: emptyList(),
        )
    }

    /** Xtream credentials for the current source, if it is (or links to) an Xtream server. */
    fun xtreamCreds(): Xtream.Creds? = when (prefs.sourceType) {
        "xtream" -> Xtream.Creds(prefs.xtServer, prefs.xtUser, prefs.xtPass, prefs.xtFormat)
        "m3u" -> Xtream.fromGetPhp(prefs.m3uUrl)
        else -> null
    }

    /** Downloads/reads the configured source, splits it into sections, tags languages/countries, caches it. */
    suspend fun refresh(progress: (String) -> Unit): Library = withContext(Dispatchers.IO) {
        val wantVod = prefs.loadVod
        val lib: Library = when (prefs.sourceType) {
            "xtream" -> fetchXtream(xtreamCreds()!!, wantVod, progress)
            "m3u" -> {
                val url = prefs.m3uUrl.trim()
                val creds = Xtream.fromGetPhp(url)
                if (creds != null) {
                    // An Xtream get.php link: the JSON API is cleaner and faster than the M3U export.
                    try { fetchXtream(creds, wantVod, progress) } catch (e: IOException) { split(fetchM3u(url, progress), wantVod) }
                } else split(fetchM3u(url, progress), wantVod)
            }
            "file" -> split(readFile(prefs.fileUri, progress), wantVod)
            else -> throw IOException("No playlist set up")
        }
        progress("Tidying…")
        fun clean(l: List<Channel>) = l.filter { !PlaylistFilters.isSeparator(it.name) }
        val live = clean(lib.live)
        if (live.isEmpty() && lib.movies.isEmpty() && lib.series.isEmpty()) throw IOException("No channels found - is that a valid playlist?")

        progress("Detecting languages and countries…")
        fun tag(l: List<Channel>) = RegionTagger.detectAll(LanguageTagger.detectAll(l))
        // Providers often list the same title in several categories; lists need one entry per item.
        val out = Library(
            tag(live),
            tag(clean(lib.movies).distinctBy { it.url }),
            tag(clean(lib.series).distinctBy { it.url }),
            lib.episodes.distinctBy { it.url },
            lib.warning,
        )
        ChannelCache.save(liveFile, out.live)
        ChannelCache.save(moviesFile, out.movies)
        ChannelCache.save(seriesFile, out.series)
        ChannelCache.save(episodesFile, out.episodes)
        out
    }

    /** Splits a flat M3U into live, movies and series (episodes grouped into shows by their SxxExx names). */
    private fun split(all: List<Channel>, wantVod: Boolean): Library {
        val live = ArrayList<Channel>(); val movies = ArrayList<Channel>()
        val shows = LinkedHashMap<String, Channel>(); val episodes = ArrayList<Channel>()
        for (c in all) {
            when (PlaylistFilters.kindOf(c)) {
                PlaylistFilters.Kind.LIVE -> live += c
                PlaylistFilters.Kind.MOVIE -> if (wantVod) movies += c
                PlaylistFilters.Kind.EPISODE -> if (wantVod) {
                    val show = PlaylistFilters.episodeName(c.name).show
                    val key = M3U_SERIES_SCHEME + show.lowercase().replace(Regex("\\s+"), " ")
                    shows.getOrPut(key) { Channel(name = show, url = key, group = c.group, logo = c.logo) }
                    episodes += c.copy(group = key, tvgId = c.group)   // tvgId keeps the original group for display
                }
            }
        }
        return Library(live, movies, shows.values.toList(), episodes)
    }

    private fun get(url: String): okhttp3.Response {
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_USER_AGENT).build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException(
                when (resp.code) {
                    401, 403 -> "The server refused access (HTTP ${resp.code}) - check your login or link"
                    404 -> "Playlist not found (HTTP 404) - check the link"
                    else -> "Server error (HTTP ${resp.code})"
                }
            )
        }
        return resp
    }

    private fun getString(url: String) = get(url).use { it.body!!.string() }

    /** Streams a response body to [block] without holding it all in memory. */
    private fun <T> getStreamed(url: String, block: (java.io.Reader) -> T): T =
        get(url).use { block(it.body!!.charStream().buffered(1 shl 16)) }

    private fun fetchM3u(url: String, progress: (String) -> Unit): List<Channel> {
        if (url.isEmpty()) throw IOException("Enter a playlist link")
        progress("Downloading playlist…")
        return get(url).use { resp ->
            val reader = resp.body!!.charStream().buffered(1 shl 16)
            val r = M3uParser.parse(reader) { n -> progress("Reading playlist… ${"%,d".format(n)} entries") }
            prefs.playlistEpgUrl = r.epgUrl ?: ""
            r.channels
        }
    }

    private fun fetchXtream(c: Xtream.Creds, wantVod: Boolean, progress: (String) -> Unit): Library {
        if (c.server.isBlank() || c.user.isBlank() || c.pass.isBlank()) throw IOException("Enter server, username and password")
        progress("Logging in…")
        val cats = getString(Xtream.apiUrl(c, "get_live_categories"))
        Xtream.checkCategoriesResponse(cats)
        try { Xtream.serverTimezone(getString(Xtream.apiUrl(c, null)))?.let { prefs.xtTimezone = it } } catch (_: Exception) {}
        progress("Downloading live channels…")
        val live = try {
            getStreamed(Xtream.apiUrl(c, "get_live_streams")) { Xtream.mapStreams(Xtream.categories(java.io.StringReader(cats)), it, c) }
        } catch (e: com.google.gson.JsonParseException) {
            throw IOException("The server sent data in an unexpected format")
        } catch (e: IllegalStateException) {
            throw IOException("The server sent data in an unexpected format")
        }
        if (!wantVod) return Library(live = live)

        // Movies and series are a bonus: if they fail, live TV still loads.
        val problems = ArrayList<String>()
        // Throwable, not Exception: an OutOfMemoryError on a huge catalogue must not crash the app.
        val movies = try {
            progress("Downloading movies…")
            val vodCats = getStreamed(Xtream.apiUrl(c, "get_vod_categories")) { Xtream.categories(it) }
            getStreamed(Xtream.apiUrl(c, "get_vod_streams")) { r ->
                Xtream.mapMovies(vodCats, r, c)
            }
        } catch (e: Throwable) { problems += "movies"; emptyList() }
        val series = try {
            progress("Downloading series…")
            val seriesCats = getStreamed(Xtream.apiUrl(c, "get_series_categories")) { Xtream.categories(it) }
            getStreamed(Xtream.apiUrl(c, "get_series")) { r -> Xtream.mapSeries(seriesCats, r) }
        } catch (e: Throwable) { problems += "series"; emptyList() }
        val warning = if (problems.isEmpty()) null else "Couldn't load ${problems.joinToString(" or ")} from this server."
        return Library(live, movies, series, emptyList(), warning)
    }

    /** Episodes of an Xtream show, fetched on demand. */
    suspend fun xtreamEpisodes(series: Channel): Xtream.SeriesInfo = withContext(Dispatchers.IO) {
        val c = xtreamCreds() ?: throw IOException("This playlist isn't an Xtream server")
        val id = series.url.removePrefix(Xtream.SERIES_SCHEME)
        val json = getString(Xtream.apiUrl(c, "get_series_info") + "&series_id=$id")
        try { Xtream.parseSeriesInfo(json, c) } catch (e: org.json.JSONException) {
            throw IOException("The server sent episode data in an unexpected format")
        }
    }

    private fun readFile(uri: String, progress: (String) -> Unit): List<Channel> {
        progress("Reading file…")
        val input = try { openFile(uri) } catch (e: Exception) { null } ?: throw IOException("Couldn't open that file")
        return input.bufferedReader().use { r ->
            val res = M3uParser.parse(r) { n -> progress("Reading playlist… ${"%,d".format(n)} entries") }
            prefs.playlistEpgUrl = res.epgUrl ?: ""
            res.channels
        }
    }

    // ---- TV guide ----

    fun epgAgeMs(): Long = if (epgFile.exists()) System.currentTimeMillis() - epgFile.lastModified() else Long.MAX_VALUE

    suspend fun loadCachedEpg(): EpgData? = withContext(Dispatchers.IO) { EpgCache.load(epgFile) }

    /** Where the guide comes from: the user's URL, else the Xtream server's xmltv.php, else the M3U header. */
    fun epgUrls(): List<String> {
        prefs.epgUrl.trim().takeIf { it.isNotEmpty() }?.let { return it.split(',').map(String::trim).filter(String::isNotEmpty) }
        xtreamCreds()?.let { c ->
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
            return listOf("${Xtream.normalizeServer(c.server)}/xmltv.php?username=${enc(c.user)}&password=${enc(c.pass)}")
        }
        return prefs.playlistEpgUrl.split(',').map(String::trim).filter { it.startsWith("http") }
    }

    /** Downloads and parses the guide for the given live channels; keeps 3 hours back to 36 hours ahead. */
    suspend fun refreshEpg(live: List<Channel>, progress: (String) -> Unit): EpgData = withContext(Dispatchers.IO) {
        val urls = epgUrls()
        if (urls.isEmpty()) throw IOException("No TV guide available for this playlist. Add a guide URL in Change playlist.")
        val ids = live.mapNotNullTo(HashSet()) { it.tvgId }
        val nameKeys = live.mapTo(HashSet()) { com.zapline.iptv.lang.ChannelNames.key(it.name) }
        val now = System.currentTimeMillis()
        // Keep a day and a bit of past programmes so catch-up can be browsed in the guide.
        val from = now - 26 * 3600_000L; val to = now + 36 * 3600_000L
        val byId = HashMap<String, List<Programme>>(); val names = HashMap<String, String>()
        var lastError: Exception? = null
        for (u in urls) {
            try {
                progress("Downloading TV guide…")
                get(u).use { resp ->
                    val input = XmltvParser.maybeGunzip(resp.body!!.byteStream())
                    val parser = newXmlParser()
                    parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(input, null)
                    val e = XmltvParser.parse(parser, ids, nameKeys, from, to) { n -> progress("Reading TV guide… ${"%,d".format(n)} programmes") }
                    byId.putAll(e.byId); e.idByNameKey.forEach { (k, v) -> names.putIfAbsent(k, v) }
                }
            } catch (e: Exception) { lastError = e }
        }
        if (byId.isEmpty()) throw lastError?.let { IOException("TV guide failed: ${it.message ?: it.javaClass.simpleName}") }
            ?: IOException("The TV guide had no programmes for your channels")
        val data = EpgData(byId, names, System.currentTimeMillis())
        EpgCache.save(epgFile, data)
        data
    }
}
