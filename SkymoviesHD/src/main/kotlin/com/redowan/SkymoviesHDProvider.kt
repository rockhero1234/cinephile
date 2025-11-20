package com.redowan


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SkymoviesHDProvider : MainAPI() {
    override var mainUrl = "https://skymovieshd.mba"
    override var name = "SkymoviesHD"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.NSFW
    )

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "Bollywood-Movies" to "Bollywood Movies",
        "All-Web-Series" to "All Web Series",
        "South-Indian-Hindi-Dubbed-Movies" to "South Indian Hindi Dubbed Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/category/${request.data}/$page.html"
        val doc = app.get(url).document
        val homeResponse = doc.select("div.L")
        val home = homeResponse.mapNotNull { post ->
            toResult(post)
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun toResult(post: Element): SearchResponse {
        var title = post.text()
        val size = "\\[.*?B]".toRegex().find(title)?.value
        if (size != null) {
            val newTitle = title.replace(size, "")
            title = "$size $newTitle"
        }
        val url = mainUrl + post.select("a").attr("href")
        val doc = app.get(url).document
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = doc.select(".movielist > img:nth-child(1)").attr("src")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search.php?search=$query&cat=All").document
        val searchResponse = doc.select("div.L")
        return searchResponse.mapNotNull { post ->
            toResult(post)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cacheTime = 60).document
        val title = doc.select("div.Robiul").first()!!.text()
        val year = "(?<=\\()\\d{4}(?=\\))".toRegex().find(title)?.value?.toIntOrNull()
        val imageUrl = doc.select(".movielist > img:nth-child(1)").attr("src")
        val plot = doc.select("div.Let").text().ifBlank { "No description available." }
        val links = doc.select(".Bolly")

        if (links.select("a").any { it.text().contains("Episode", true) }) {
            val episodesData = mutableListOf<Episode>()
            links.select("a").forEach {
                if (it.text().isNotBlank()) episodesData.add(newEpisode(it.attr("href")){
                    this.name=it.text()
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = imageUrl
                this.year = year
                this.plot = plot
            }
        } else {
            var link = ""
            val aLinks = links.select("a")
            for (i in aLinks.indices) {
                if (aLinks[i].text().isNotBlank()) {
                    link += aLinks[i].attr("href") + " ; "
                    if (aLinks[i].attr("href").contains("howblogs")) break
                }
            }
            return newMovieLoadResponse(title, url, TvType.Movie, link) {
                this.posterUrl = imageUrl
                this.year = year
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").forEach { link ->
            if (link.isNotBlank()) postMan(link, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun postMan(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val link = if (url.contains("howblogs")) {
            howBlogs(url) ?: url
        } else {
            url
        }
        loadExtractor(link,name,subtitleCallback, callback)
    }

    private suspend fun howBlogs(url: String): String? {
        val doc = app.get(url).document
        doc.select(".cotent-box > a").forEach {
            val link = it.attr("href")
            if ("hubcloud" in link) return link
        }
        return null
    }

    private fun getVideoQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}