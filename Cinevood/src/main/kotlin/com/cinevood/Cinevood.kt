package com.cinephile.cinevood

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.nodes.Element

class Cinevood : MainAPI() {
    override var mainUrl = "https://1cinevood.agency"
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

        val responseData = imdbId?.let { id ->
            val jsonUrl = "$cinemetaUrl/movie/$id.json"
            val jsonText = app.get(jsonUrl).text
            jsonText.takeIf { it.startsWith("{") }?.let { Gson().fromJson(it, ResponseData::class.java) }
        }

        responseData?.meta?.let {
            title = it.name ?: title
            description = it.description ?: description
            posterUrl = it.poster ?: posterUrl
            backgroundUrl = it.background ?: posterUrl
        }

        val isMovie = doc.select("a.maxbutton-oxxfile").first()?.attr("href")?.contains("/p/") != true

        return if (isMovie) {
            val links = doc.select("a.maxbutton-oxxfile").mapNotNull { it.attr("href") }.distinct()
            newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
            }
        } else {
            val linkCollection = doc.select("a.maxbutton-oxxfile").mapNotNull { anchor ->
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

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.plot = description
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

    data class Meta(
        val name: String?,
        val description: String?,
        val poster: String?,
        val background: String?
    )

    data class ResponseData(val meta: Meta?)
}