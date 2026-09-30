package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import com.raghav.donation.DonationManager

private const val TAG = "TMF"

private const val DRIVE_REFERER = "https://nexdrive.fit/"
private const val DRIVE_CACHE_MS = 5 * 60 * 1000L

class TheMoviesFlix : MainAPI() {
    override var mainUrl = "https://moviesflixhq.com"
    override var name = "TheMoviesFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val internalDomains = listOf(
        "nexdrive", "mobilejsr", "moviesflix", "wp-", "vglist", "w.org",
        "cloudflare", "googleapi", "googletagmanager", "font-awesome", "gmpg"
    )

    override val mainPage = mainPageOf(
        "" to "Latest Movies & Series",
        "category/web-series" to "Web Series",
        "category/hindi-dubbed-movies" to "Hindi Dubbed Movies",
        "category/english" to "English Movies",
        "category/bollywood" to "Bollywood Movies",
        "category/dual-audio-movies" to "Dual Audio Movies",
        "category/netflix" to "Netflix",
        "category/amazon-prime-video" to "Amazon Prime Video",
        "category/jiohotstar" to "JioHotstar",
        "category/disney-plus-hotstar" to "Disney+ Hotstar",
        "category/2160p" to "4K UHD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        mainUrl = FirebaseDomainHelper.getDomain("themoviesflix") ?: mainUrl
        val path = request.data
        val url = if (path.isBlank()) {
            if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        } else {
            if (page > 1) "$mainUrl/$path/page/$page/" else "$mainUrl/$path/"
        }
        val doc = tmfGet(url, headers = baseHeaders).document
        val items = doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { it.toSearchResult() }
        val hasNext = doc.select("div.navigation a.nextpostslink, div.navigation li a:contains(Next)").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val titleRaw = attr("title").ifBlank { return null }
        val title = cleanTitle(titleRaw)
        val img = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
        val quality = getSearchQuality(titleRaw)

        val isSeries = titleRaw.contains("Season", true) ||
            titleRaw.contains("Series", true) ||
            titleRaw.contains("Web Series", true) ||
            titleRaw.contains("TV Show", true) ||
            Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(titleRaw)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = img
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img
                this.quality = quality
            }
        }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
            .replace(Regex("^Download\\s+", RegexOption.IGNORE_CASE), "")
        listOf(" 480p", " 720p", " 1080p", " {Hindi", " {English",
            " (Hindi", " (English", " Hindi Dubbed", " Dual Audio",
            " [480p", " [720p", " [1080p", " Web Dl", " WEB-DL",
            " BluRay", " Full Movie", " Complete"
        ).forEach { t = t.substringBefore(it) }
        return t.trim().trimEnd('(', '-', ':')
    }

    private fun getSearchQuality(text: String): SearchQuality? = when {
        text.contains("2160p", true) || text.contains("4K", true) || text.contains("UHD", true) -> SearchQuality.FourK
        text.contains("1080p", true) || text.contains("FullHD", true) -> SearchQuality.HD
        text.contains("720p", true) || text.contains("480p", true) -> SearchQuality.SD
        else -> null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("themoviesflix") ?: mainUrl
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = tmfGet(url, headers = baseHeaders).document
        return doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("themoviesflix") ?: mainUrl
        val doc = tmfGet(url, headers = baseHeaders).document
        val entry = doc.selectFirst("div.entry-content") ?: return null

        val titleRaw = doc.selectFirst("h2.mfx-main-title")?.text()
            ?: doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: return null
        val title = cleanTitle(titleRaw)
        val pageTitle = doc.selectFirst("title")?.text() ?: ""

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = entry.selectFirst("div.mfx-plot-box")?.text()?.trim()
        val year = extractYear(entry) ?: extractYearFromTitle(titleRaw)
        val genres = extractListFromInfo(entry, "Genres")
        val cast = extractListFromInfo(entry, "Cast").map { ActorData(Actor(it)) }
        val runtime = extractFieldFromInfo(entry, "Runtime")?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val imdbLink = entry.selectFirst("div.mfx-imdb a[href*=imdb]")?.attr("href") ?: ""
        val imdbId = Regex("""title/(tt\d+)""").find(imdbLink)?.groupValues?.get(1)
        val rating = Regex("""([\d.]+)/10""").find(
            entry.selectFirst("div.mfx-imdb a")?.text() ?: ""
        )?.groupValues?.get(1)?.toFloatOrNull()

        val trailerId = entry.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")
        val trailer = trailerId?.let { "https://www.youtube.com/watch?v=$it" }

        val downloadGroups = extractDownloadGroups(entry)

        val allText = titleRaw + " " + pageTitle + " " + url + " " +
            downloadGroups.joinToString(" ") { it.label }
        val isSeries = allText.contains("Season", true) ||
            allText.contains("Series", true) ||
            allText.contains("Web Series", true) ||
            allText.contains("TV Show", true) ||
            allText.contains("Episode", true) ||
            Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(allText)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonGroups = mutableMapOf<Int, MutableList<DownloadGroup>>()

            for (group in downloadGroups) {
                val seasonNum = extractSeasonNumber(group.label) ?: 1
                seasonGroups.getOrPut(seasonNum) { mutableListOf() }.add(group)
            }

            for ((seasonNum, groups) in seasonGroups) {
                if (groups.isEmpty()) continue
                val allNexdriveUrls = groups.joinToString("|") { it.redirectUrl }

                // every quality group lists the same episodes, the union of
                // all drive pages is the safest episode count
                val episodeNums = coroutineScope {
                    groups.map { group ->
                        async(Dispatchers.IO) {
                            fetchDrivePage(group.redirectUrl)?.episodes?.keys ?: emptySet()
                        }
                    }.awaitAll()
                }.flatten().toSortedSet()

                if (episodeNums.isEmpty()) {
                    episodes.add(newEpisode("$allNexdriveUrls|$seasonNum|1") {
                        this.name = groups.first().label
                        this.episode = 1
                        this.season = seasonNum
                    })
                } else {
                    for (epNum in episodeNums) {
                        episodes.add(newEpisode("$allNexdriveUrls|$seasonNum|$epNum") {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                            this.season = seasonNum
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = rating?.let { Score.from10(it) }
                this.actors = cast
                this.duration = runtime
                if (imdbId != null) addImdbId(imdbId)
                if (trailer != null) addTrailer(trailer)
            }
        } else {
            val dataStr = downloadGroups.joinToString("\n") { it.redirectUrl }
            return newMovieLoadResponse(title, url, TvType.Movie, dataStr) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = rating?.let { Score.from10(it) }
                this.actors = cast
                this.duration = runtime
                if (imdbId != null) addImdbId(imdbId)
                if (trailer != null) addTrailer(trailer)
            }
        }
    }

    private data class DownloadGroup(val label: String, val redirectUrl: String)

    private fun extractDownloadGroups(entry: Element): List<DownloadGroup> {
        return entry.select("div.mfx-download-group").mapNotNull { div ->
            val label = div.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val link = div.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            if (link.isNotBlank() && link != "#") DownloadGroup(label, link) else null
        }
    }

    private fun isInternalLink(href: String): Boolean = internalDomains.any { href.contains(it) }

    private class DrivePage(
        val quality: Int?,
        val info: String,
        val links: List<String>,
        val episodes: Map<Int, List<String>>
    )

    private class CachedDrivePage(val savedAt: Long, val page: DrivePage)

    private val driveCache = ConcurrentHashMap<String, CachedDrivePage>()

    private fun qualityOf(text: String): Int? =
        Regex("""(\d{3,4})[pP]""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (text.contains("4K", true) || text.contains("UHD", true)) 2160 else null

    private fun infoOf(title: String): String =
        Regex("""\[([^\]]+)\]""").findAll(title)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    private fun parseDrivePage(doc: Document): DrivePage {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" – ").substringBefore(" - ").trim()
        val root = doc.selectFirst("article") ?: doc.selectFirst("div.entry-content")
            ?: return DrivePage(qualityOf(title), infoOf(title), emptyList(), emptyMap())

        val links = root.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !isInternalLink(href)) href else null
        }.distinct()

        val episodes = mutableMapOf<Int, List<String>>()
        for (h4 in root.select("h4")) {
            val text = h4.text().trim()
            if (!text.contains("Episode", true)) continue

            val epNum = Regex("""Episode[s]?\s*:\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue

            val epLinks = mutableListOf<String>()
            var sibling = h4.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 3) {
                for (a in sibling.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (href.isNotBlank() && href.startsWith("http") && !isInternalLink(href)) {
                        epLinks.add(href)
                    }
                }
                if (epLinks.isNotEmpty()) break
                sibling = sibling.nextElementSibling()
                attempts++
            }

            if (epLinks.isNotEmpty()) {
                episodes[epNum] = epLinks.distinct()
            }
        }

        return DrivePage(qualityOf(title), infoOf(title), links, episodes)
    }

    private suspend fun fetchDrivePage(url: String): DrivePage? {
        val key = url.replace("mobilejsr.rest", "nexdrive.fit")
        driveCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAt < DRIVE_CACHE_MS) return cached.page
            driveCache.remove(key)
        }
        val res = tmfDriveGet(key, "$mainUrl/") ?: return null
        val page = parseDrivePage(res.document)
        driveCache[key] = CachedDrivePage(System.currentTimeMillis(), page)
        return page
    }

    private suspend fun loadFromDrive(
        driveUrl: String,
        episodeNum: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = fetchDrivePage(driveUrl) ?: return false
        val links = if (episodeNum != null) page.episodes[episodeNum] ?: page.links else page.links

        return coroutineScope {
            links.map { link ->
                async(Dispatchers.IO) {
                    try {
                        loadExtractor(link, DRIVE_REFERER, subtitleCallback) { extracted ->
                            val quality = extracted.quality.takeIf { it != Qualities.Unknown.value }
                                ?: page.quality ?: Qualities.Unknown.value
                            val name = if (page.info.isBlank()) extracted.name
                                else "${extracted.name} · ${page.info}"
                            val relabeled = ExtractorLink(
                                source = extracted.source,
                                name = name,
                                url = extracted.url,
                                referer = extracted.referer,
                                quality = quality,
                                type = extracted.type,
                                headers = extracted.headers
                            )
                            relabeled.extractorData = extracted.extractorData
                            callback(relabeled)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "extractor: ${e.message}")
                        false
                    }
                }
            }.awaitAll().any { it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val parts = data.split("|")
        val isTvEpisode = parts.size >= 3 &&
            parts[parts.size - 2].toIntOrNull() != null &&
            parts[parts.size - 1].toIntOrNull() != null

        val episodeNum: Int?
        val driveUrls: List<String>
        if (isTvEpisode) {
            episodeNum = parts.last().toInt()
            driveUrls = parts.dropLast(2).filter { it.isNotBlank() }
        } else {
            episodeNum = null
            driveUrls = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        }
        if (driveUrls.isEmpty()) return false

        return coroutineScope {
            driveUrls.map { driveUrl ->
                async(Dispatchers.IO) {
                    try {
                        loadFromDrive(driveUrl, episodeNum, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks: ${e.message}")
                        false
                    }
                }
            }.awaitAll().any { it }
        }
    }

    private fun extractYear(entry: Element): Int? =
        entry.selectFirst("div.mfx-info-box li:contains(Released Year)")?.text()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun extractYearFromTitle(title: String): Int? =
        Regex("""(\d{4})""").find(title)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractSeasonNumber(text: String): Int? =
        Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\bS(\d+)\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractFieldFromInfo(entry: Element, fieldName: String): String? =
        entry.selectFirst("div.mfx-info-box li:contains($fieldName)")?.text()
            ?.replace(Regex("$fieldName:\\s*", RegexOption.IGNORE_CASE), "")?.trim()

    private fun extractListFromInfo(entry: Element, fieldName: String): List<String> =
        extractFieldFromInfo(entry, fieldName)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
}
