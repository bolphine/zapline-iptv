package com.zapline.iptv.ui

import com.zapline.iptv.data.EpgData
import com.zapline.iptv.data.Health
import com.zapline.iptv.data.StreamInfo
import com.zapline.iptv.data.Catchup
import com.zapline.iptv.data.Programme
import com.zapline.iptv.data.Reminder
import com.zapline.iptv.data.Playlists
import com.zapline.iptv.data.SavedPlaylist
import com.zapline.iptv.data.EpgStore
import com.zapline.iptv.data.Library
import com.zapline.iptv.data.M3U_SERIES_SCHEME
import com.zapline.iptv.data.Repository
import com.zapline.iptv.data.Platform
import com.zapline.iptv.parse.PlaylistFilters
import com.zapline.iptv.parse.Xtream
import com.zapline.iptv.lang.Categories
import com.zapline.iptv.lang.ChannelNames
import com.zapline.iptv.lang.LangRules
import com.zapline.iptv.model.Channel
import com.zapline.iptv.player.PlayEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A channel with its effective language/country after overrides and rules. */
data class Tagged(
    val ch: Channel,
    val lang: String,
    val region: String,
    val category: String,
    val nameKey: String,
    val hidden: Boolean,
)

/** One row in the list: every version of a channel ("BBC One HD", "BBC One FHD", "BBC One backup"). */
data class Entry(
    val key: String,
    val name: String,
    /** Best version first. */
    val variants: List<Tagged>,
    val groups: List<String>,
) {
    val main get() = variants[0]
    /** Best known stream details for this channel (the first version, which is the best working one). */
    val info: StreamInfo? get() = Health[variants[0].ch.url]
    /** 1 working, 2 not working (every version failed), 0 not checked yet. */
    val health: Int get() {
        var anyDead = false; var allChecked = true
        for (v in variants) {
            val s = Health[v.ch.url]
            when { s == null || s.state == StreamInfo.BUSY || s.suspect -> allChecked = false; s.ok -> return 1; s.dead -> anyDead = true }
        }
        return if (anyDead && allChecked) 2 else 0
    }
    val lang get() = main.lang
    val region get() = main.region
    val category get() = main.category
    val logo: String? get() = variants.firstNotNullOfOrNull { it.ch.logo }
    fun toPlay(epgId: String? = null) = PlayEntry(key, name, variants.map { it.ch }, epgId)
}

data class GroupInfo(
    val name: String, val count: Int,
    val lang: String, val overridden: Boolean,
    val region: String, val regionOverridden: Boolean,
)

data class ChannelView(
    val entries: List<Entry> = emptyList(),
    val langs: List<Pair<String, Int>> = emptyList(),
    val regions: List<Pair<String, Int>> = emptyList(),
    /** Category -> count after language/country/favourite filters, in display order. */
    val categories: List<Pair<String, Int>> = emptyList(),
    /** Group -> count after all filters except group and search, in playlist order. */
    val groups: List<Pair<String, Int>> = emptyList(),
    val groupTotal: Int = 0,
    val selectedGroup: String? = null,
    val selectedCategory: String? = null,
    val recents: List<Entry> = emptyList(),
    val totalLoaded: Int = 0,
    val totalChannels: Int = 0,
    /** Channels by health (1 working, 2 not working, 0 not checked) within the language/country/favourite filters. */
    val healthCounts: Map<Int, Int> = emptyMap(),
    val healthFilter: Int? = null,
    /** False while a newly loaded channel list is still being sorted and filtered. */
    val ready: Boolean = false,
)

/** Movies or series list after filters. */
data class VodView(
    val items: List<Channel> = emptyList(),
    val groups: List<Pair<String, Int>> = emptyList(),
    val selectedGroup: String? = null,
    val total: Int = 0,
    /** Movies with a saved resume point, newest first, paired with the fraction watched. */
    val continueWatching: List<Pair<Channel, Float>> = emptyList(),
    /** Shows you're part-way through, newest first. */
    val seriesContinue: List<Pair<Channel, com.zapline.iptv.data.SeriesProgress>> = emptyList(),
)

/** Everything that matches a search, across live TV, the guide, films and series. */
data class SearchResults(
    val query: String = "",
    val onNow: List<Pair<Entry, Programme>> = emptyList(),
    val channels: List<Entry> = emptyList(),
    val upcoming: List<Pair<Entry, Programme>> = emptyList(),
    val catchup: List<Pair<Entry, Programme>> = emptyList(),
    val movies: List<Channel> = emptyList(),
    val series: List<Channel> = emptyList(),
) {
    val isEmpty get() = onNow.isEmpty() && channels.isEmpty() && upcoming.isEmpty() && catchup.isEmpty() && movies.isEmpty() && series.isEmpty()
}

private data class Filters(
    val langs: Set<String>, val regions: Set<String>, val group: String?, val category: String?,
    val favOnly: Boolean, val favs: Set<String>,
    val favOrder: List<String> = emptyList(),
    /** null all, 1 working only, 2 not working only, 0 not checked yet. */
    val health: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Everything behind the app's screens, shared by the phone and Windows apps: the channel list with
 * filters, duplicates merged and best versions first, search, guide, catch-up, sport, channel checks,
 * playlists and settings. [scope] is the screen's lifetime (Android's viewModelScope on the phone).
 */
class MainViewModel(private val platform: Platform, scope: kotlinx.coroutines.CoroutineScope) {
    private val viewModelScope = scope
    private val repo = platform.repository
    val prefs get() = repo.prefs

    private val all = MutableStateFlow<List<Channel>>(emptyList())
    val movies = MutableStateFlow<List<Channel>>(emptyList())
    val series = MutableStateFlow<List<Channel>>(emptyList())
    private val m3uEpisodes = MutableStateFlow<List<Channel>>(emptyList())
    /** Bumped when resume points may have changed (returning from the player). */
    private val resumeTick = MutableStateFlow(0)

    val movieQuery = MutableStateFlow("")
    val movieGroup = MutableStateFlow<String?>(null)
    val seriesQuery = MutableStateFlow("")
    val seriesGroup = MutableStateFlow<String?>(null)

    val query = MutableStateFlow("")
    val langFilter = MutableStateFlow(prefs.langFilter)
    val regionFilter = MutableStateFlow(prefs.regionFilter)
    val groupRegion = MutableStateFlow(prefs.groupRegion)
    val selectedGroup = MutableStateFlow<String?>(null)
    val selectedCategory = MutableStateFlow<String?>(null)
    val favOnly = MutableStateFlow(false)
    val favourites = MutableStateFlow(prefs.favourites)
    val favOrder = MutableStateFlow(prefs.favOrder.ifEmpty { prefs.favourites.toList() })
    val hiddenGroups = MutableStateFlow(prefs.hiddenGroups)
    val groupLang = MutableStateFlow(prefs.groupLang)
    val rulesText = MutableStateFlow(prefs.rules)
    val recentKeys = MutableStateFlow(prefs.recents)

    /** TV guide; EpgData.EMPTY until loaded. */
    val epg = MutableStateFlow(EpgData.EMPTY)
    /** Status line for the guide download ("" when idle). */
    val epgStatus = MutableStateFlow("")
    val epgError = MutableStateFlow<String?>(null)
    /** Wall clock, ticking every 30 s so now/next and progress bars move. */
    val now = MutableStateFlow(System.currentTimeMillis())

    val loading = MutableStateFlow(false)
    /** True from launch until the saved playlist has been read and sorted, so screens show placeholders, not "no channels". */
    val starting = MutableStateFlow(true)
    val progress = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val hasSource = MutableStateFlow(prefs.sourceType.isNotEmpty())

    private val rules: StateFlow<LangRules> = rulesText.map { LangRules.parse(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LangRules.parse(prefs.rules))

    /** Name keys are the costly bit; computed once per loaded playlist. */
    private val nameKeys: StateFlow<Map<String, String>> = all.map { list ->
        val m = HashMap<String, String>(list.size * 2)
        for (c in list) m.getOrPut(c.name) { ChannelNames.key(c.name) }
        m as Map<String, String>
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Which channel list the latest tagging / entries were computed from (by identity). */
    @Volatile private var taggedFrom: List<Channel>? = null
    private val entriesFrom = MutableStateFlow<List<Channel>?>(null)

    private val tagged: StateFlow<List<Tagged>> =
        combine(all, nameKeys, groupLang, groupRegion, rules) { list, keys, overrides, regionOverrides, r ->
            val groupCat = HashMap<String, String>()
            taggedFrom = list
            list.map { c ->
                val ruled = if (r.rules.isEmpty()) null else r.match(c)
                val lang = overrides[c.group] ?: ruled?.takeIf { it != LangRules.HIDE } ?: c.detectedLang
                val gc = groupCat.getOrPut(c.group) { Categories.forGroup(c.group) }
                Tagged(
                    ch = c, lang = lang,
                    region = regionOverrides[c.group] ?: c.detectedRegion,
                    category = Categories.detect(c.group, c.name, gc),
                    nameKey = keys[c.name] ?: c.name.uppercase(),
                    hidden = ruled == LangRules.HIDE,
                )
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Visible channels collapsed into entries, in playlist order. */
    /** Health changes, at most every few seconds, so a running check doesn't re-sort 10,000 channels continuously. */
    private val healthTick = MutableStateFlow(0)

    private val entries: StateFlow<List<Entry>> = combine(tagged, hiddenGroups, healthTick) { t, hidden, _ ->
        val source = taggedFrom
        val byKey = LinkedHashMap<String, MutableList<Tagged>>()
        for (x in t) {
            if (x.hidden || x.ch.group in hidden) continue
            byKey.getOrPut("${x.region}|${x.lang}|${x.nameKey}") { ArrayList(2) } += x
        }
        byKey.map { (key, vs) ->
            // Best working version first: ones that play, by picture then sound; then unchecked; failed ones last.
            val sorted = if (vs.size == 1) vs else vs.sortedWith(
                compareBy<Tagged> { v -> Health[v.ch.url].let { s -> when { s == null || s.state == StreamInfo.BUSY -> 1; s.ok -> 0; s.suspect -> 2; else -> 3 } } }
                    .thenByDescending { v -> Health[v.ch.url]?.score ?: 0L }
                    .thenBy { ChannelNames.qualityRank(it.ch.name) }
            )
            Entry(key, ChannelNames.display(sorted[0].ch.name), sorted, vs.map { it.ch.group }.distinct())
        }.also { if (t.size == (source?.size ?: -1)) entriesFrom.value = source }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val healthFilter = MutableStateFlow<Int?>(null)
    private val facets = combine(langFilter, regionFilter, selectedCategory, healthFilter) { l, r, c, h -> Facets(l, r, c, h) }
    private data class Facets(val l: Set<String>, val r: Set<String>, val c: String?, val h: Int?)

    private val filters = combine(facets, selectedGroup, favOnly, favourites, favOrder) { fc, g, f, fv, order ->
        Filters(fc.l, fc.r, g, fc.c, f, fv, order, fc.h)
    }

    private val entriesState = combine(entries, entriesFrom) { e, from -> e to from }

    val view: StateFlow<ChannelView> = combine(entriesState, query, filters, recentKeys, all) { (e, from), q, f, rec, a ->
        // Ready only once the entries were built from the channel list we're showing; before that, the screen
        // shows a loading state instead of "nothing found".
        Inputs(e, q, f, rec, a.size, ready = from === a)
    }
        .mapLatest { buildView(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelView())

    private data class Inputs(val entries: List<Entry>, val query: String, val f: Filters, val recents: List<String>, val total: Int, val ready: Boolean)

    val groupInfo: StateFlow<List<GroupInfo>> = combine(tagged, groupLang, groupRegion) { t, overrides, regionOverrides ->
        val byGroup = LinkedHashMap<String, Pair<MutableMap<String, Int>, MutableMap<String, Int>>>()
        for (x in t) {
            if (x.hidden) continue
            val (l, r) = byGroup.getOrPut(x.ch.group) { HashMap<String, Int>() to HashMap() }
            l[x.lang] = (l[x.lang] ?: 0) + 1
            r[x.region] = (r[x.region] ?: 0) + 1
        }
        byGroup.map { (g, m) ->
            val (langs, regions) = m
            GroupInfo(
                g, langs.values.sum(),
                langs.maxByOrNull { it.value }?.key ?: "", overrides.containsKey(g),
                regions.maxByOrNull { it.value }?.key ?: "", regionOverrides.containsKey(g),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val vodFacets = combine(langFilter, regionFilter, groupLang, groupRegion) { l, r, gl, gr -> listOf(l, r, gl, gr) }

    val movieView: StateFlow<VodView> = vodFlow(movies, movieQuery, movieGroup, withContinue = true)
    val seriesView: StateFlow<VodView> = vodFlow(series, seriesQuery, seriesGroup, withContinue = false)

    @Suppress("UNCHECKED_CAST")
    private fun vodFlow(
        list: StateFlow<List<Channel>>, q: StateFlow<String>, g: StateFlow<String?>, withContinue: Boolean,
    ): StateFlow<VodView> = combine(list, q, g, vodFacets, resumeTick) { items, query, group, facets, _ ->
        val langs = facets[0] as Set<String>; val regions = facets[1] as Set<String>
        val gl = facets[2] as Map<String, String>; val gr = facets[3] as Map<String, String>
        // Untagged titles stay visible: most VOD categories ("Action", "Comedy") carry no language at all.
        val base = items.filter { c ->
            val l = gl[c.group] ?: c.detectedLang
            val r = gr[c.group] ?: c.detectedRegion
            (langs.isEmpty() || l.isEmpty() || l in langs) && (regions.isEmpty() || r.isEmpty() || r in regions)
        }
        val groups = base.groupingBy { it.group }.eachCount()
        val sel = group?.takeIf { groups.containsKey(it) }
        val inGroup = if (sel != null) base.filter { it.group == sel } else base
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = if (terms.isEmpty()) inGroup else inGroup.filter { c ->
            val hay = (c.name + " " + c.group).lowercase(); terms.all { hay.contains(it) }
        }
        val cont = if (!withContinue) emptyList() else {
            val byUrl = items.associateBy { it.url }
            prefs.resumeList().mapNotNull { (u, f) -> byUrl[u]?.let { it to f } }.take(15)
        }
        val seriesCont = if (withContinue) emptyList() else {
            val byUrl = items.associateBy { it.url }
            com.zapline.iptv.data.SeriesWatch.all(prefs).mapNotNull { p -> byUrl[p.showUrl]?.let { it to p } }.take(15)
        }
        VodView(result, groups.map { it.key to it.value }, sel, base.size, cont, seriesCont)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, VodView())

    private fun apply(lib: Library) {
        all.value = lib.live
        movies.value = lib.movies
        series.value = lib.series
        m3uEpisodes.value = lib.episodes
    }

    /** Entry key -> guide channel id, recomputed when the guide or the channel list changes. */
    val epgIds: StateFlow<Map<String, String>> = combine(entries, epg) { es, e ->
        if (e.channelCount == 0) emptyMap() else {
            val m = HashMap<String, String>(es.size)
            for (x in es) e.resolve(x.variants.map { it.ch })?.let { m[x.key] = it }
            m
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            Health.load(platform.filesDir)
            var seen = Health.version.value
            healthTick.value++
            while (true) {
                delay(3_000)
                val v = Health.version.value
                if (v != seen) { seen = v; healthTick.value++ }
            }
        }
        viewModelScope.launch {
            // Once the channel list is ready, quietly check the channels inside the current filters (Wi-Fi only).
            view.first { it.ready && it.totalChannels > 0 }
            delay(8_000)
            if (prefs.autoCheck && !isMetered()) checkChannels(force = false)
        }
        viewModelScope.launch {
            val cached = repo.loadCached()
            if (cached != null) apply(cached)
            repo.loadCachedEpg()?.let { setEpg(it) }
            // Sign in to an Xtream provider on every launch (and so after every app update): panels often refuse
            // streams from a player that hasn't signed in recently, even though the saved channel list still loads.
            if (hasSource.value && cached != null) launch(Dispatchers.IO) {
                val st = com.zapline.iptv.data.XtreamAuth.login(prefs, force = true)
                // Offline at launch isn't worth a warning; a rejected login or expired account is.
                if (!st.ok && st.message?.startsWith("Couldn't reach") != true) error.value = st.message
            }
            if (hasSource.value && (cached == null || repo.cacheAgeMs() > 12 * 3600_000L)) refresh()
            else if (hasSource.value && repo.epgAgeMs() > 6 * 3600_000L) refreshEpg()
            // Stay in the starting state until the cached channels have made it through sorting and filtering.
            if (cached != null && cached.live.isNotEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(15_000) { view.first { it.totalChannels > 0 && it.ready } }
            }
            starting.value = false
        }
        viewModelScope.launch {
            while (true) {
                now.value = System.currentTimeMillis()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    private fun setEpg(e: EpgData) { epg.value = e; EpgStore.data = e }

    /** Guide loads in the background; the channel list is usable meanwhile and a failure is never fatal. */
    fun refreshEpg() {
        if (epgStatus.value.isNotEmpty() || all.value.isEmpty()) return
        viewModelScope.launch {
            epgStatus.value = "Loading TV guide…"
            epgError.value = null
            try {
                setEpg(repo.refreshEpg(all.value) { epgStatus.value = it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                epgError.value = if (e is OutOfMemoryError) "The TV guide is too large for this phone" else e.message ?: "TV guide failed"
            } finally {
                epgStatus.value = ""
            }
        }
    }

    /** Episodes of a show: from the API for Xtream, from the playlist for M3U. */
    suspend fun episodesFor(show: Channel): Xtream.SeriesInfo =
        if (show.url.startsWith(M3U_SERIES_SCHEME)) {
            val eps = m3uEpisodes.value.filter { it.group == show.url }.map { c ->
                val n = PlaylistFilters.episodeName(c.name)
                Xtream.Episode(n.season, n.number, c.name, c.url, c.logo, null)
            }.distinctBy { it.url }.sortedWith(compareBy({ it.season }, { it.number }))
            Xtream.SeriesInfo(null, eps)
        } else repo.xtreamEpisodes(show).let { it.copy(episodes = it.episodes.distinctBy { e -> e.url }) }

    fun resumePosition(url: String) = prefs.resumePosition(url)

    fun resumeFraction(url: String): Float = prefs.resumeList().firstOrNull { it.first == url }?.second ?: 0f

    /**
     * Picks a show up where you left off: the saved episode (resumes inside it), or the next one if you finished it.
     * Queues the rest of the show after it, so episodes keep coming.
     */
    fun continueSeries(show: Channel, p: com.zapline.iptv.data.SeriesProgress, play: (List<PlayEntry>, Int) -> Unit) {
        viewModelScope.launch {
            val eps = try { episodesFor(show).episodes } catch (_: Exception) { emptyList() }
            val at = eps.indexOfFirst { it.url == p.episodeUrl }
            if (eps.isEmpty() || at < 0) { play(emptyList(), 0); return@launch }
            val start = if (p.finished) (at + 1).coerceAtMost(eps.size - 1) else at
            play(episodeQueue(show, eps), start)
        }
    }


    private fun buildView(inp: Inputs): ChannelView {
        val f = inp.f
        val base = inp.entries
        fun counts(xs: List<Entry>, key: (Entry) -> String) = xs.groupingBy(key).eachCount()
            .entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key.isEmpty() }.thenByDescending { it.value })
            .map { it.key to it.value }

        // Each facet's counts respect the other facet, so "English" shows how many English channels are in the UK.
        val regionOnly = if (f.regions.isEmpty()) base else base.filter { it.region in f.regions }
        val langOnly = if (f.langs.isEmpty()) base else base.filter { it.lang in f.langs }
        val langCounts = counts(regionOnly) { it.lang }
        val regionCounts = counts(langOnly) { it.region }
        val langOk = if (f.regions.isEmpty()) langOnly else langOnly.filter { it.region in f.regions }
        val favOnlyOk = if (f.favOnly) langOk.filter { e -> e.variants.any { it.ch.url in f.favs } } else langOk
        val healthOf = HashMap<String, Int>(favOnlyOk.size * 2)
        for (e in favOnlyOk) healthOf[e.key] = e.health
        val healthCounts = healthOf.values.groupingBy { it }.eachCount()
        val favOk = if (f.health == null) favOnlyOk else favOnlyOk.filter { healthOf[it.key] == f.health }

        val catCount = favOk.groupingBy { it.category }.eachCount()
        val categories = (Categories.labels.keys + "").mapNotNull { c -> catCount[c]?.let { c to it } }
        val category = f.category?.takeIf { catCount.containsKey(it) }
        // Adult channels only appear when that category is picked explicitly.
        val inCategory = when (category) {
            null -> favOk.filter { it.category != Categories.ADULT }
            else -> favOk.filter { it.category == category }
        }

        val groups = LinkedHashMap<String, Int>()
        for (e in inCategory) for (g in e.groups) groups[g] = (groups[g] ?: 0) + 1
        val group = f.group?.takeIf { groups.containsKey(it) }
        val inGroup = if (group != null) inCategory.filter { group in it.groups } else inCategory

        val terms = inp.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var result = if (terms.isEmpty()) inGroup else inGroup.filter { e ->
            val hay = (e.name + " " + e.main.ch.name + " " + e.groups.joinToString(" ")).lowercase()
            terms.all { hay.contains(it) }
        }
        // Favourites follow the viewer's own line-up order.
        if (f.favOnly) {
            val pos = f.favOrder.withIndex().associate { it.value to it.index }
            result = result.sortedBy { e -> e.variants.minOfOrNull { pos[it.ch.url] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
        }

        val byKey = if (inp.recents.isEmpty()) emptyMap() else base.associateBy { it.key }
        val recents = inp.recents.mapNotNull { byKey[it] }.take(15)

        return ChannelView(
            entries = result, langs = langCounts, regions = regionCounts, categories = categories,
            groups = groups.map { it.key to it.value }, groupTotal = inCategory.size,
            selectedGroup = group, selectedCategory = category, recents = recents,
            totalLoaded = base.size, totalChannels = inp.total, ready = inp.ready,
            healthCounts = healthCounts, healthFilter = f.health,
        )
    }

    // ---- actions ----

    fun refresh(onSuccess: (() -> Unit)? = null) {
        if (loading.value) return
        loading.value = true   // set before launching, so there's never a frame of "nothing loaded"
        viewModelScope.launch {
            error.value = null
            progress.value = "Connecting…"
            try {
                val lib = repo.refresh { progress.value = it }
                com.zapline.iptv.data.XtreamAuth.markSignedIn()
                apply(lib)
                lib.warning?.let { error.value = it }
                refreshEpg()
                hasSource.value = true
                onSuccess?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = friendly(e)
            } finally {
                loading.value = false
                progress.value = ""
            }
        }
    }

    fun saveSource(type: String, m3uUrl: String, server: String, user: String, pass: String, format: String,
                   fileUri: String, fileName: String, loadVod: Boolean, epgUrl: String = prefs.epgUrl) {
        // Keep the previous source in the saved list so it can be switched back to.
        Playlists.saveCurrent(prefs)
        prefs.epgUrl = epgUrl.trim()
        prefs.sourceType = type
        prefs.m3uUrl = m3uUrl.trim()
        prefs.xtServer = server.trim(); prefs.xtUser = user.trim(); prefs.xtPass = pass.trim(); prefs.xtFormat = format
        prefs.fileUri = fileUri; prefs.fileName = fileName
        prefs.loadVod = loadVod
        selectedGroup.value = null
        val fresh = SavedPlaylist("", "", type, m3uUrl.trim(), server.trim(), user.trim(), pass.trim(), format, fileUri, fileName)
        prefs.activePlaylist = Playlists.all(prefs).firstOrNull { it.sameSource(fresh) }?.id ?: ""
        prefs.xtTimezone = ""
        Playlists.saveCurrent(prefs)
        playlists.value = Playlists.all(prefs)
    }

    fun reloadRecents() { recentKeys.value = prefs.recents; resumeTick.value++ }

    fun setLangFilter(s: Set<String>) { langFilter.value = s; prefs.langFilter = s }

    fun setRegionFilter(s: Set<String>) { regionFilter.value = s; prefs.regionFilter = s }

    /** code == null removes the override (back to automatic). */
    fun setGroupRegion(group: String, code: String?) {
        val m = groupRegion.value.toMutableMap()
        if (code == null) m.remove(group) else m[group] = code
        groupRegion.value = m; prefs.groupRegion = m
    }

    fun isFav(e: Entry, favs: Set<String>) = e.variants.any { it.ch.url in favs }

    fun toggleFav(e: Entry) {
        val s = favourites.value.toMutableSet()
        val order = favOrder.value.toMutableList()
        if (isFav(e, s)) {
            e.variants.forEach { s.remove(it.ch.url); order.remove(it.ch.url) }
        } else {
            s.add(e.main.ch.url); order.add(e.main.ch.url)   // new favourites join the end of the line-up
        }
        favourites.value = s; prefs.favourites = s
        favOrder.value = order; prefs.favOrder = order
    }

    /** Favourite entries in line-up order (for the reorder screen). */
    fun lineup(): List<Entry> {
        val byUrl = HashMap<String, Entry>()
        for (e in entriesSnapshot()) for (v in e.variants) byUrl[v.ch.url] = e
        return favOrder.value.mapNotNull { byUrl[it] }.distinctBy { it.key }
    }

    private fun entriesSnapshot(): List<Entry> = entries.value

    fun moveInLineup(e: Entry, delta: Int) {
        val order = favOrder.value.toMutableList()
        val url = e.variants.map { it.ch.url }.firstOrNull { it in order } ?: return
        val lineup = lineup()
        val i = lineup.indexOfFirst { it.key == e.key }
        val j = (i + delta).coerceIn(0, lineup.size - 1)
        if (i < 0 || i == j) return
        val target = lineup[j].variants.map { it.ch.url }.first { it in order }
        order.remove(url)
        val at = order.indexOf(target)
        order.add(if (delta > 0) at + 1 else at, url)
        favOrder.value = order; prefs.favOrder = order
    }

    fun moveToTop(e: Entry) {
        val order = favOrder.value.toMutableList()
        val url = e.variants.map { it.ch.url }.firstOrNull { it in order } ?: return
        order.remove(url); order.add(0, url)
        favOrder.value = order; prefs.favOrder = order
    }

    fun setGroupHidden(group: String, hide: Boolean) {
        val s = hiddenGroups.value.toMutableSet()
        if (hide) s.add(group) else s.remove(group)
        hiddenGroups.value = s; prefs.hiddenGroups = s
    }

    fun unhideAll() { hiddenGroups.value = emptySet(); prefs.hiddenGroups = emptySet() }

    /** code == null removes the override (back to automatic). */
    fun setGroupLang(group: String, code: String?) {
        val m = groupLang.value.toMutableMap()
        if (code == null) m.remove(group) else m[group] = code
        groupLang.value = m; prefs.groupLang = m
    }

    fun saveRules(text: String) { rulesText.value = text; prefs.rules = text }

    // ---- sport ----

    data class SportState(
        val matches: List<com.zapline.iptv.data.Sports.Match> = emptyList(),
        /** Match id -> channels showing it (from the TV guide), best first. */
        val listings: Map<String, List<Pair<Entry, Programme>>> = emptyMap(),
        val loading: Boolean = false,
        val failed: Boolean = false,
        val updatedAt: Long = 0,
    )

    val sport = MutableStateFlow(SportState())
    private var sportJob: kotlinx.coroutines.Job? = null

    /** Today's games, refreshed every minute while the Sport screen is open. */
    fun watchSport() {
        if (sportJob?.isActive == true) return
        sportJob = viewModelScope.launch {
            var first = true
            while (true) {
                sport.value = sport.value.copy(loading = true)
                val ms = try { com.zapline.iptv.data.Sports.today(force = !first) } catch (_: Exception) { emptyList() }
                first = false
                val listings = if (ms.isEmpty()) emptyMap() else kotlinx.coroutines.withContext(Dispatchers.Default) { sportListings(ms) }
                sport.value = SportState(ms, listings, loading = false, failed = ms.isEmpty() && sport.value.matches.isEmpty(), updatedAt = System.currentTimeMillis())
                delay(if (ms.any { it.isLive }) 60_000 else 180_000)
            }
        }
    }

    fun stopSport() { sportJob?.cancel(); sportJob = null }

    /** Finds each game in the TV guide, among channels inside the language/country filters. */
    private fun sportListings(ms: List<com.zapline.iptv.data.Sports.Match>): Map<String, List<Pair<Entry, Programme>>> {
        val g = epg.value
        if (g.channelCount == 0) return emptyMap()
        val ids = epgIds.value
        val langs = langFilter.value; val regions = regionFilter.value
        val now = System.currentTimeMillis()
        val out = HashMap<String, MutableList<Pair<Entry, Programme>>>()
        for (e in entries.value) {
            if (!(langs.isEmpty() || e.lang in langs) || !(regions.isEmpty() || e.region in regions)) continue
            if (e.category == Categories.ADULT) continue
            val id = ids[e.key] ?: continue
            for (p in g.programmes(id)) {
                if (p.end < now - 3_600_000 || p.start > now + 20 * 3_600_000L) continue
                val text = if (com.zapline.iptv.data.Sports.fixture(p.title) != null) p.title
                    else p.desc?.takeIf { com.zapline.iptv.data.Sports.fixture(it) != null } ?: continue
                val m = com.zapline.iptv.data.Sports.find(ms, text) ?: continue
                // Must be on around kick-off, not a replay later in the day.
                if (m.start > 0 && (p.end < m.start || p.start > m.start + 2 * 3_600_000L)) continue
                out.getOrPut(m.id) { ArrayList() } += e to p
            }
        }
        return out.mapValues { (_, l) ->
            l.distinctBy { it.first.key }.sortedWith(compareBy<Pair<Entry, Programme>> { if (it.first.health == 2) 1 else 0 }
                .thenByDescending { it.first.info?.score ?: 0L }).take(6)
        }
    }

    // ---- channel health ----

    val healthScan = Health.scan

    /** Every version of every channel inside the language and country filters (adult only once unlocked). */
    fun checkTargets(): List<Pair<String, Channel>> {
        val langs = langFilter.value; val regions = regionFilter.value
        val showAdult = adultUnlocked.value || prefs.adultPin.isEmpty()
        return entries.value.asSequence()
            .filter { (langs.isEmpty() || it.lang in langs) && (regions.isEmpty() || it.region in regions) }
            .filter { showAdult || it.category != Categories.ADULT }
            .flatMap { e -> e.variants.asSequence().map { e.name to it.ch } }
            .toList()
    }

    fun checkChannels(force: Boolean) = Health.startScan(checkTargets(), prefs, force)
    fun stopChecking() = Health.stopScan()
    fun forgetHealth() { Health.stopScan(); Health.clear() }

    private fun isMetered(): Boolean = platform.isMetered()

    // ---- reminders ----

    val reminders = MutableStateFlow(platform.reminders.all())

    fun hasReminder(r: List<Reminder>, e: Entry, p: Programme) = r.any { it.channelKey == e.key && it.start == p.start }

    fun addReminder(e: Entry, p: Programme) {
        platform.reminders.add(Reminder(p.title, e.key, e.name, epgIds.value[e.key], p.start))
        reminders.value = platform.reminders.all()
    }

    fun removeReminder(r: Reminder) { platform.reminders.remove(r); reminders.value = platform.reminders.all() }

    fun removeReminder(e: Entry, p: Programme) {
        reminders.value.firstOrNull { it.channelKey == e.key && it.start == p.start }?.let { removeReminder(it) }
    }

    /** Waits for the channel list, then finds a channel by its entry key (used when a reminder is tapped). */
    suspend fun entryForKey(key: String): Entry? {
        val list = kotlinx.coroutines.withTimeoutOrNull(20_000) { entries.first { it.isNotEmpty() } } ?: return null
        return list.firstOrNull { it.key == key }
            ?: list.firstOrNull { it.key.substringAfterLast('|') == key.substringAfterLast('|') }
    }

    fun epgIdFor(e: Entry) = epgIds.value[e.key]

    // ---- catch-up ----

    fun canCatchUp(e: Entry, p: Programme, now: Long = System.currentTimeMillis()) =
        p.start < now && Catchup.channelFor(e.variants.map { it.ch }, p.start, now) != null

    /** The programme from the provider's archive, played like a film (seekable, resumable). */
    fun catchupEntry(e: Entry, p: Programme): PlayEntry? =
        Catchup.playEntry(e.key, e.name, e.variants.map { it.ch }, p, prefs.xtTimezone)

    // ---- search everything ----

    val searchQuery = MutableStateFlow("")
    val adultUnlocked = MutableStateFlow(false)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val search: StateFlow<SearchResults> = combine(searchQuery.debounce(250), entries, epgIds, langFilter, regionFilter) { q, es, ids, l, r ->
        listOf(q, es, ids, l, r)
    }.mapLatest { (q, es, ids, l, r) ->
        @Suppress("UNCHECKED_CAST")
        searchAll(q as String, es as List<Entry>, ids as Map<String, String>, l as Set<String>, r as Set<String>)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults())

    private fun searchAll(q: String, es: List<Entry>, ids: Map<String, String>, langs: Set<String>, regions: Set<String>): SearchResults {
        val terms = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty() || q.trim().length < 2) return SearchResults(q)
        fun hit(text: String): Boolean { val t = text.lowercase(); return terms.all { t.contains(it) } }
        val showAdult = adultUnlocked.value || prefs.adultPin.isEmpty()
        val pool = es.filter { e ->
            (langs.isEmpty() || e.lang in langs) && (regions.isEmpty() || e.region in regions) &&
                (showAdult || e.category != Categories.ADULT)
        }
        val channels = pool.filter { e -> hit(e.name + " " + e.groups.joinToString(" ")) }.take(60)
        val now = System.currentTimeMillis()
        val g = epg.value
        val onNow = ArrayList<Pair<Entry, Programme>>()
        val upcoming = ArrayList<Pair<Entry, Programme>>()
        val past = ArrayList<Pair<Entry, Programme>>()
        if (g.channelCount > 0) {
            for (e in pool) {
                val id = ids[e.key] ?: continue
                for (p in g.programmes(id)) {
                    if (!hit(p.title)) continue
                    when {
                        now in p.start until p.end -> onNow += e to p
                        p.start >= now -> upcoming += e to p
                        canCatchUp(e, p, now) -> past += e to p
                    }
                }
            }
        }
        fun vodOk(c: Channel): Boolean {
            val l = groupLang.value[c.group] ?: c.detectedLang
            val rg = groupRegion.value[c.group] ?: c.detectedRegion
            return (langs.isEmpty() || l.isEmpty() || l in langs) && (regions.isEmpty() || rg.isEmpty() || rg in regions) && hit(c.name)
        }
        return SearchResults(
            query = q,
            onNow = onNow.distinctBy { it.first.key }.take(40),
            channels = channels,
            upcoming = upcoming.sortedBy { it.second.start }.distinctBy { it.first.key + it.second.start }.take(60),
            catchup = past.sortedByDescending { it.second.start }.take(40),
            movies = movies.value.filter(::vodOk).take(40),
            series = series.value.filter(::vodOk).take(40),
        )
    }

    // ---- adult PIN ----

    fun pinRequired(category: String?) = category == Categories.ADULT && prefs.adultPin.isNotEmpty() && !adultUnlocked.value
    fun unlock(pin: String): Boolean = (pin == prefs.adultPin).also { if (it) adultUnlocked.value = true }

    // ---- several playlists ----

    val playlists = MutableStateFlow(Playlists.all(prefs))

    /** Saves the current source under a name, so it can be switched back to later. */
    fun rememberCurrentPlaylist(name: String? = null) {
        Playlists.saveCurrent(prefs, name)
        playlists.value = Playlists.all(prefs)
    }

    fun switchPlaylist(p: SavedPlaylist) {
        Playlists.saveCurrent(prefs)
        Playlists.apply(prefs, p)
        repo.clearCaches()
        all.value = emptyList(); movies.value = emptyList(); series.value = emptyList(); m3uEpisodes.value = emptyList()
        setEpg(EpgData.EMPTY)
        selectedGroup.value = null; selectedCategory.value = null
        playlists.value = Playlists.all(prefs)
        refresh()
    }

    /** The setup form opens empty, to add another provider; the current one is kept in the list. */
    val blankSetup = MutableStateFlow(false)
    fun startNewPlaylist() { rememberCurrentPlaylist(); blankSetup.value = true }

    fun renamePlaylist(p: SavedPlaylist, name: String) { Playlists.rename(prefs, p, name); playlists.value = Playlists.all(prefs) }

    fun deletePlaylist(p: SavedPlaylist) { Playlists.delete(prefs, p); playlists.value = Playlists.all(prefs) }

    // ---- backup ----

    fun reloadFromPrefs() {
        langFilter.value = prefs.langFilter; regionFilter.value = prefs.regionFilter
        groupRegion.value = prefs.groupRegion; groupLang.value = prefs.groupLang
        favourites.value = prefs.favourites; favOrder.value = prefs.favOrder.ifEmpty { prefs.favourites.toList() }
        hiddenGroups.value = prefs.hiddenGroups; rulesText.value = prefs.rules; recentKeys.value = prefs.recents
        hasSource.value = prefs.sourceType.isNotEmpty()
        playlists.value = Playlists.all(prefs)
        reminders.value = platform.reminders.all()
        platform.reminders.rescheduleAll()
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "Can't find that server - check the address and your connection"
        is SocketTimeoutException -> "The server took too long to respond"
        is javax.net.ssl.SSLException -> "Secure connection failed - try http:// instead of https://"
        is IOException -> e.message ?: "Network error"
        is IllegalArgumentException -> "That address doesn't look valid"
        else -> e.message ?: e.javaClass.simpleName
    }
}
