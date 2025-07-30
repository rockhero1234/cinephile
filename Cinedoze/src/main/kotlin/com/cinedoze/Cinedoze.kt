package com.cinedoze

//import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import android.util.Log
import okhttp3.FormBody
class Cinedoze : MainAPI() {

    override var mainUrl = "https://cinedoze.com/"
    override var name = "Cinedoze"
    override val hasMainPage= true
    override var lang= "hi"
    override val supportedTypes = setOf(
        TvType.Movie
    )
    val directUrl =""
    override val mainPage = mainPageOf(
        "movies" to "latest",
        "genre/tv-series-shows" to "Series",
        "genre/sports" to "Sports",
        "genre/hindi-dubbed" to "Hindi Dubbed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if(page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url).document
        val home =
            document.select("div.items.normal article, div#archive-content article, div.items.full article").mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episodes/") -> {
                var title = uri.substringAfter("$mainUrl/episodes/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            uri.contains("/seasons/") -> {
                var title = uri.substringAfter("$mainUrl/seasons/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 > a")?.text() ?: return null
        val href = getProperLink(fixUrl(this.selectFirst("h3 > a")!!.attr("href")))
        var posterUrl = this.select("div.poster img").last()?.getImageAttr()
        if (posterUrl != null) {
            if (posterUrl.contains(".gif")) {
                posterUrl = fixUrlNull(this.select("div.poster img").attr("data-wpfc-original-src"))
            }
        }
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.result-item").map {
            val title =
                it.selectFirst("div.title > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(it.selectFirst("div.title > a")!!.attr("href"))
            val posterUrl = it.selectFirst("img")!!.attr("src").toString()
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        val document = request.document
        //val directUrl = getBaseUrl(request.url)
        val title =
            document.selectFirst("div.data > h1")?.text()?.trim().toString()
        var background= fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        var posterUrl = fixUrlNull(document.select("div.poster img").attr("src"))
        
        val description = document.select("div.wp-content > p").text().trim()
         
         return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot=description 
            this.backgroundPosterUrl=background
            
        }
        }


    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
    
        doc.select("a[href^=https://cinedoze.com/links/]").forEach { link ->
            val finalDoc = app.get(link.attr("href")).document
    
            finalDoc.select("li > a[href]").forEach { aTag ->
                val href = aTag.attr("href")
                if (href.isNotBlank()) {
                    loadExtractor(
                        href,
                        "$directUrl/",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    
        return true
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    

    data class LinkData(
        val type: String? = null,
        val post: String? = null,
        val nume: String? = null,
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String?,
    )
}
