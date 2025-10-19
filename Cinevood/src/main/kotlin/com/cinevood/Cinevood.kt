package com.cinephile.cinevood
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.google.gson.Gson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.util.Locale


class Cinevood : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl ="https://1cinevood.agency"
    val oxxApiUrl = "https://new4.oxxfile.info/api/s/"
    override var name = "Cinevood"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://v3-cinemeta.strem.io/meta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )
    
 private fun toResult(post: Element): SearchResponse {
    val url = post.select("a").attr("href")
    val title = post.select("a").attr("title").toString()
    val imageUrl= post.select("img").attr("src")
    // Log.d("post", post.toString())
    // val quality = post.select(".video-label").text()
    return newMovieSearchResponse(title, url, TvType.Movie) {
        this.posterUrl = imageUrl
    }
 }
    override val mainPage = mainPageOf(
        "" to "Latest",
        "web-series" to "Series",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood"
        )
        
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url =if(page==1) "$mainUrl/${request.data}/" else  "$mainUrl/${request.data}/page/$page/" 
        val document = app.get(url).document
        val home = document.select("article.latestpost").mapNotNull {
            toResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("article.latestpost").mapNotNull {
            toResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val maindoc = app.get(url).document

        val typeText = maindoc.selectFirst("div.thecontent span[style=color: #008000;] strong")
            ?.text()?.lowercase(Locale.getDefault())

        val type = if (typeText == "movie") TvType.Movie else TvType.TvSeries

        val imdbHref = maindoc.selectFirst("a[href^=https://www.imdb]")?.attr("href")
        val imdbId = imdbHref?.substringAfter("https://www.imdb.com/")?.takeIf { it.isNotBlank() && it != "0" }

        val responseData = imdbId?.let { id ->
            val jsonUrl = "$cinemeta_url/meta/$type/$id.json"
            val jsonText = app.get(jsonUrl).text
            jsonText.takeIf { it.startsWith("{") }?.let { Gson().fromJson(it, ResponseData::class.java) }
        }
        var title = maindoc.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Download ", "").toString()
        var description = maindoc.select("span#summary")?.text().toString().removePrefix("Summary:")
        var posterUrl = maindoc.select("meta[property^=og:image]")?.attr("content")
        var backgroundUrl = posterUrl
        if(responseData != null){
            title = responseData.meta?.name.toString()
            description = responseData.meta?.description.toString()
            posterUrl = responseData.meta?.poster.toString()
            backgroundUrl = responseData.meta?.background.toString()
        }
        if(type == TvType.Movie){
            val validLinks = mutableListOf<String>()

            maindoc.select("a.maxbutton-oxxfile").forEach { anchor ->
                val href = anchor.attr("href")
                val id = href.substringAfter("/s/").substringBefore("/")
                val apiUrl = "$oxxApiUrl$id/hubcloud"

                val responseText = app.get(apiUrl).text
                if ("hubcloud" in responseText) {
                    validLinks += responseText
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie,validLinks.toJson()) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
//                this.year = year
                this.plot = description
//                this.tags = tags
//                this.score = rating
//                this.contentRating = source
//                addActors(actors)
            }
        }else{
            val validLinks = mutableListOf<String>()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if(data.isEmpty()) return false
        val links = tryParseJson<List<String>>(data)
        if (links != null) {
            links.map {
                loadExtractor(it, subtitleCallback, callback)
            }
        }
        return true   
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
        val year: String?,
        val videos: List<EpisodeDetails>?
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?,
        val released: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?
    )

    data class ResponseData(
        val meta: Meta?
    )

    data class EpisodeLink(
        val source: String
    )
}

