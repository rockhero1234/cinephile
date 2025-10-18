package binged

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import org.json.JSONArray
import org.jsoup.nodes.Element

class BingedProvider : MainAPI() {
    override var mainUrl = "https://www.binged.com"
    override var name = "Binged"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    val invidUrl= "https://invd.cakestwix.com"
    private suspend fun getData(titled: String, i: Int, platform: String = "",fltr:String=""): List<MovieSearchResponse> {
        val j = if (i == 1) 0 else 21 + (i - 2) * 20
        var data = mutableMapOf(
                "filters[recommend]" to "false",
                "filters[date-from]" to "",
                "filters[date-to]" to "",
                "filters[mode]" to titled,
                "action" to "mi_events_load_data",
                "mode" to titled,
                "start" to "$j",
                "length" to "20",
                "customcatalog" to "0"
        )
       if(platform.isNotEmpty()){
            data["filters[platform][]"] = platform
       }
       if(fltr.isNotEmpty()){
            data["filters[recommendation][]"] = fltr
       }
        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = data,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Referer" to mainUrl
            )
        ).text

        val json = tryParseJson<Map<String, Any>>(response)
        var dataList = json?.get("data") as? List<Map<String, Any>>

        return dataList?.map { entry ->
            newMovieSearchResponse(
                name = entry["title"].toString(),
                url = entry["link"].toString(),
                type = TvType.Movie
            ) {
                this.posterUrl = entry["big-image"].toString()
            }
        } ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val stsoon = getData("streaming-soon", page)
        val stnow = getData("streaming-now", page)
        val mustwatchlist = getData("streaming-now", page,fltr="Must_Watch")
        val goodlist = getData("streaming-now", page,fltr="Good")
        val satisfylist = getData("streaming-now", page,fltr="Satisfactory")
        return newHomePageResponse(
            listOf(
                HomePageList("Streaming Soon", stsoon, false),
                HomePageList("Streaming Now", stnow, false),
                HomePageList("Must Watch", mustwatchlist, false),
                HomePageList("Good", goodlist, false),
                HomePageList("Satisfactory", satisfylist, false),
            ), true
        )
    }

   suspend fun findTrailer(query: String): String? {
        val encodedQuery = URLEncoder.encode("$query trailer", "UTF-8")
        val url = "$invidUrl/api/v1/search?q=$encodedQuery&page=1&type=video&fields=videoId"
    
        return try {
            val first = JSONArray(app.get(url).text)
                .optJSONObject(0)
                ?.optString("videoId")
            if (first.isNullOrBlank()) null else "https://www.youtube.com/watch?v=$first"
        } catch (_: Exception) {
            null
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "mi_events_load_data",
                "test-search" to "1",
                "start" to "0",
                "length" to "20",
                "search[value]" to query,
                "customcatalog" to "0",
                "mode" to "all",
                "filters[search]" to query
            ),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Referer" to mainUrl
            )
        ).text

        val json = tryParseJson<Map<String, Any>>(response)
        val dataList = json?.get("data") as? List<Map<String, Any>>

        return dataList?.map { entry ->
            newMovieSearchResponse(
                name = entry["title"].toString(),
                url = entry["link"].toString(),
                type = TvType.Movie
            ) {
                this.posterUrl = entry["big-image"].toString()
            }
        } ?: emptyList()
    }


fun Element.extractimg(): String? {
    val raw = this.attr("data-bg")
    return try {
        val jsonArray = JSONArray(raw)
        val firstObj = jsonArray.optJSONObject(0)
        firstObj?.optString("url")
    } catch (e: Exception) {
        null
    }
}


override suspend fun load(url: String): LoadResponse? {
    val doc = app.get(url, cacheTime = 60).document

    val title = doc.selectFirst("h1")?.text().orEmpty()
    val dt = doc.select("div.single-mevents-meta").text()
    val dtsplit = dt.split("|")
    val imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
    val trailer = findTrailer(title) ?: ""
    val plot = doc.selectFirst("p")?.text().orEmpty()
    val year = dtsplit.getOrNull(0)?.trim()?.toIntOrNull()

    val actors = doc.select("div.single-castItem").map {
    val name = it.selectFirst("div.single-castItem-name")?.text()
    val imgelement = it.selectFirst("div.single-castItem-image")
    val img = imgelement?.extractimg()
    val type = it.selectFirst("div.single-castItem-type")?.text()
    if (name != null) {
        ActorData(
                Actor(name, img), roleString = type
        )       
    } else {
        null
    }
}.filterNotNull()
    val review = doc.selectFirst("div.our-rating > span.rating-span")?.text() ?: "No Review"
    val tags = listOfNotNull(
        doc.selectFirst("span.single-mevents-platforms-row-date")?.text(),
        doc.selectFirst("img.single-mevents-platforms-row-image")?.attr("alt"),
        doc.selectFirst("span.audiostring")?.text(),
        dtsplit.getOrNull(1)?.trim(),
        dtsplit.getOrNull(2)?.trim(),
        dtsplit.getOrNull(3)?.trim()
    )

    return newMovieLoadResponse(title, url, TvType.Movie, null) {
        this.posterUrl = imageUrl
        this.year = year
        this.plot = plot
        this.tags = tags
        this.actors = actors
        addTrailer(trailer)
        this.contentRating = review
    }
}

    companion object {
        fun String.encodeUri() = URLEncoder.encode(this, "utf8")
    }
}
