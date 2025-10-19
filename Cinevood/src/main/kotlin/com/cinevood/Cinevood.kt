package com.cinephile.cinevood
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element


class Cinevood : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl ="https://1cinevood.agency"
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
//        "web-series" to "Series",
        "bollywood" to "Bollywood",
        "hollywood" to "Hollywood",
        "gujarati" to "Gujarati",
        "marathi" to "Marathi",
        "tamil" to "Tamil",
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
            ?.text()

//        val type = if (typeText == "Movie") TvType.Movie else TvType.TvSeries

//        val imdbHref = maindoc.selectFirst("a[href^=https://www.imdb]")?.attr("href")
//        val imdbId = imdbHref?.substringAfter("https://www.imdb.com/title/")?.takeIf { it.isNotBlank() && it != "0" }
//
//        val responseData = imdbId?.let { id ->
//            val jsonUrl = "$cinemeta_url/movie/$id.json"
//            val jsonText = app.get(jsonUrl).text
//            jsonText.takeIf { it.startsWith("{") }?.let { Gson().fromJson(it, ResponseData::class.java) }
//        }
        var title = maindoc.selectFirst("meta[property=og:title]")?.attr("content")?.replace("Download ", "").toString()
        var description = maindoc.select("span#summary").text().toString().removePrefix("Summary:")
        var posterUrl = maindoc.select("meta[property^=og:image]").attr("content")
        var backgroundUrl = posterUrl
//        if(responseData != null){
//            title = responseData.meta?.name.toString()
//            description = responseData.meta?.description.toString()
//            posterUrl = responseData.meta?.poster.toString()
//            backgroundUrl = responseData.meta?.background.toString()
//        }
        val type = if (maindoc.select("a.maxbutton-oxxfile").first()?.attr("href")?.contains("/p/") == true) "series" else "movie"

        if(type == "movie"){
            val validLinks = mutableListOf<String>()

            maindoc.select("a.maxbutton-oxxfile").forEach { anchor ->
                val href = anchor.attr("href")
                validLinks+=href
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
            val linkCollection = mutableListOf<List<Pair<String, String>>>()
            val episodes = mutableListOf<Episode>()

            maindoc.select("a.maxbutton-oxxfile").forEach { anchor ->
                val href = anchor.attr("href")
                val response = app.get(href.replace("/p/", "/api/packs/")).text
                val json = JsonParser.parseString(response).asJsonObject
                val items = json["pack"].asJsonObject["items"].asJsonArray

                val filePairs = items.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val fullName = obj["file_name"]?.asString
                    val link = obj["hubcloud_link"]?.asString

                    val title = Regex("""S\d+E\d+""").find(fullName ?: "")?.value ?: fullName
                    if (title != null && link != null) Pair(title, link) else null
                }.sortedWith(compareBy(
                    { pair ->
                        // Sort key: episode number if matched, else Int.MAX_VALUE
                        Regex("""S\d+E(\d+)""").find(pair.first)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
                    },
                    { pair -> pair.first } // Secondary sort by title for stability
                ))


                linkCollection += filePairs
            }
            val transposed = (0 until linkCollection[0].size).map { i ->
                linkCollection.map { it[i] }
            }

            transposed.forEach { episodeData ->
                val links = episodeData.map { it.second } // hubcloud_link
                val title = episodeData.firstOrNull()?.first ?: "Untitled"

                episodes += newEpisode(links.toJson()) {
                    this.name = title
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries,episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
//                this.year = year
                this.plot = description
//                this.tags = tags
//                this.score = rating
//                this.contentRating = source
//                addActors(actors)
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

        val links = (tryParseJson<List<String>>(data)
            ?.mapNotNull { it -> it.trim().takeIf { it.isNotBlank() } }
            ?: data.trim()
                .removePrefix("[")
                .removeSuffix("]")
                .split(',')
                .mapNotNull { it -> it.trim().removeSurrounding("\"").removeSurrounding("'").takeIf { it.isNotBlank() } })
            .distinct()
//        https://new4.oxxfile.info/api/s/4JfGSDxnqd/hubcloud
        if (links.isEmpty()) return false
        Log.d("validlinks:",links.toString())
        for (link in links) {
            if(link.contains("oxxfile")){
                val apiUrl = link.replace("/s/","/api/s/") + "/hubcloud"
                val hubcloudLink = app.get(apiUrl).url
                if ("hubcloud." in hubcloudLink) {
                    loadExtractor(hubcloudLink, name, subtitleCallback, callback)
                }
            }else{
                loadExtractor(link, name, subtitleCallback, callback)
            }

        }

        return true
    }

//    data class Meta(
//        val id: String?,
//        val imdb_id: String?,
//        val type: String?,
//        val poster: String?,
//        val logo: String?,
//        val background: String?,
//        val moviedb_id: Int?,
//        val name: String?,
//        val description: String?,
//        val genre: List<String>?,
//        val releaseInfo: String?,
//        val status: String?,
//        val runtime: String?,
//        val cast: List<String>?,
//        val language: String?,
//        val country: String?,
//        val imdbRating: String?,
//        val slug: String?,
//        val year: String?,
//        val videos: List<EpisodeDetails>?
//    )
//
//    data class EpisodeDetails(
//        val id: String?,
//        val name: String?,
//        val title: String?,
//        val season: Int?,
//        val episode: Int?,
//        val released: String?,
//        val overview: String?,
//        val thumbnail: String?,
//        val moviedb_id: Int?
//    )
//
//    data class ResponseData(
//        val meta: Meta?
//    )
//
//    data class EpisodeLink(
//        val source: String
//    )
}

