package com.cinephile.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId

class Cinevood : MainAPI() {
    override var mainUrl = "https://1cinevood.world"
    override var name = "Cinevood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    private val cinemetaUrl = "https://v3-cinemeta.strem.io/meta"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private fun toResult(post: Element): SearchResponse {
        val url = post.select("a").attr("href")
        val title = post.select("a").attr("title")
        val imageUrl = post.select("img").attr("src")
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = imageUrl
            this.quality=getSearchQuality(title)
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood",
        "gujarati" to "Gujarati",
        "marathi" to "Marathi",
        "tamil" to "Tamil"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.latestpost").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.latestpost").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val imdbHref = doc.selectFirst("a[href^=https://www.imdb]")?.attr("href")
        val imdbId = imdbHref?.substringAfter("title/")?.substringBefore("/")?.takeIf { it.isNotBlank() }
        var title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.removePrefix("Download ") ?: "Untitled"
        var description = doc.select("span#summary").text().removePrefix("Summary:")
        var posterUrl = doc.select("meta[property^=og:image]").attr("content")
        var backgroundUrl = posterUrl
        val type = if (Regex("(?i)(series|S\\d{2})").containsMatchIn(title)) TvType.TvSeries else TvType.Movie
        val responseData = imdbId?.let { id ->
            val typeSlug = if (type == TvType.TvSeries) "series" else "movie"
            val jsonUrl = "$cinemetaUrl/$typeSlug/$id.json"
            val jsonText = app.get(jsonUrl).text
            jsonText.takeIf { it.startsWith("{") }?.let { Gson().fromJson(it, ResponseData::class.java) }
        }
        
        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        
        val seasonRegex = Regex("""S\d{2}""", RegexOption.IGNORE_CASE)
        val seasonMatch = seasonRegex.find(title)
        val extractedSeason = seasonMatch?.value?.uppercase()
        
        responseData?.meta?.let {
            title = it.name ?: title
            description = it.description ?: description
            posterUrl = it.poster ?: posterUrl
            backgroundUrl = it.background ?: posterUrl
            cast = it.cast ?: emptyList()
            genre = it.genre ?: emptyList()
            imdbRating = it.imdbRating ?: ""
            year = it.releaseInfo ?: ""
        }
        
        if (type == TvType.TvSeries && extractedSeason != null) {
            if (!title.endsWith(extractedSeason, ignoreCase = true)) {
                title = "${title.trim()} $extractedSeason"
            }
        }
        
        val isSingle = doc.select("a[href*='oxxfile']").first()?.attr("href")?.contains("/p/") != true
        return if (isSingle) {
            var links = doc.select("a[href*='oxxfile']").mapNotNull { it.attr("href") }.distinct()
            if (links.isEmpty()) {
                links = doc.select("a[href*='hubcloud']").mapNotNull { it.attr("href") }.distinct()
            }
            newMovieLoadResponse(title, url,type, links.toJson()) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                addActors(cast)
                addImdbId(imdbId)
            }
        } else {
            val linkCollection = doc.select("a[href*='oxxfile']").mapNotNull { anchor ->
                val href = anchor.attr("href")
                val response = app.get(href.replace("/p/", "/api/packs/")).text
                val json = JsonParser.parseString(response).asJsonObject
                val items = json["pack"].asJsonObject["items"].asJsonArray

                items.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val fullName = obj["file_name"]?.asString
                    val link = obj["hubcloud_link"]?.asString
                    val title = Regex("""S\d+E\d+""").find(fullName ?: "")?.value ?: fullName
                    if (title != null && link != null) Pair(title, link) else null
                }.sortedWith(compareBy(
                    { Regex("""S\d+E(\d+)""").find(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.first }
                ))
            }

            val transposed = (0 until linkCollection.first().size).map { i ->
                linkCollection.mapNotNull { it.getOrNull(i) }
            }

            val episodes = transposed.map { episodeData ->
                val links = episodeData.map { it.second }
                val epTitle = episodeData.firstOrNull()?.first ?: "Untitled"
                newEpisode(links.toJson()) {
                    this.name = epTitle
                }
            }

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                addActors(cast)
                addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val links = tryParseJson<List<String>>(data)?.filterNot { it.isBlank() }
            ?: data.trim()
                .removePrefix("[")
                .removeSuffix("]")
                .split(',')
                .mapNotNull { raw ->
                    raw.trim().removeSurrounding("\"").removeSurrounding("'").takeIf { it.isNotBlank() }
                }

        if (links.isEmpty()) return false

        for (link in links.distinct()) {
            val finalLink = if (link.contains("oxxfile")) {
                val apiUrl = link.replace("/s/", "/api/s/") + "/hubcloud"
                val resolved = app.get(apiUrl).url
                if ("hubcloud." in resolved) resolved else link
            } else link

            loadExtractor(finalLink, name, subtitleCallback, callback)
        }

        return true
    }
    private fun getSearchQuality(check: String?): SearchQuality? {
        val lowercaseCheck = check?.lowercase()
        if (lowercaseCheck != null) {
            return when {
                lowercaseCheck.contains("webrip") || lowercaseCheck.contains("web-dl") -> SearchQuality.WebRip
                lowercaseCheck.contains("bluray") -> SearchQuality.BlueRay
                lowercaseCheck.contains("hdts") || lowercaseCheck.contains("hdcam") || lowercaseCheck.contains("hdtc") -> SearchQuality.HdCam
                lowercaseCheck.contains("dvd") -> SearchQuality.DVD
                lowercaseCheck.contains("camrip") || lowercaseCheck.contains("rip") -> SearchQuality.CamRip
                lowercaseCheck.contains("cam") -> SearchQuality.Cam
                lowercaseCheck.contains("hdrip") || lowercaseCheck.contains("hdtv") -> SearchQuality.HD
                lowercaseCheck.contains("hq") -> SearchQuality.HQ
                else -> null
            }
        }
        return null
    }

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val slug: String?,
        val year: String?
    )


    data class ResponseData(val meta: Meta?)
}