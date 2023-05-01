package com.pontefice

import android.text.Html.fromHtml
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.pontefice.Pontefice.Companion.interceptor
import org.json.JSONObject
import java.security.MessageDigest


object StreamingCommunity {
    const val URL = "https://streamingcommunity.download/"
    private const val APIURL = "${URL}/api"

    private var internal_cookies = mapOf<String, String>()

    private suspend fun getCookies(): Map<String, String> {
        if (internal_cookies.isNotEmpty()) {
            return internal_cookies
        }
        var response = app.get(URL, headers = mapOf("user-agent" to Pontefice.userAgent))
        if (response.document.title() == "Just a moment...") {
            response = app.get(URL, headers = mapOf("user-agent" to Pontefice.userAgent), interceptor = interceptor)
        }
        internal_cookies = response.cookies
        return internal_cookies
    }


    private suspend fun getSearchResponse(title: String): SearchResponse {
        val cookies = getCookies()
        val query = mapOf<String, String>("q" to title)
        return app.post("${APIURL}/search",
                headers = mapOf(
                        "user-agent" to Pontefice.userAgent,
                        "referer" to URL),
                data = query, cookies = cookies).parsed()
    }

    private suspend fun getSeason(
            title: String, year: Int?,
            seasonN: Int,
    ): Season? {
        val cookies = getCookies()
        val searchResponse = getSearchResponse(title = title)
        var tvShow: SearchResponseRecord? = null
        for (item in searchResponse.records) {
            if (filterBy(item = item, title = title, year = year, exactTitle = true)) {
                tvShow = item
                break
            }
        }
        if (tvShow == null) {
            return null
        }

        val response = app.get("${URL}/titles/${tvShow.id}-${tvShow.slug}", headers = mapOf("user-agent" to Pontefice.userAgent), cookies = cookies)
        val data =
                fromHtml(response.document.selectFirst("season-select")!!.attr("seasons")).toString()
        val seasons = parseJson<List<Season>>(data)
        return seasons.find { x -> x.number == seasonN }
    }

    private suspend fun getFilm(title: String, year: Int? = null, exact: Boolean = false): SearchResponseRecord? {
        val searchResponse = getSearchResponse(title = title)
        for (item in searchResponse.records) {
            if (filterBy(item = item, title = title, year = year, exactTitle = exact)) {
                return item
            }
        }
        return null
    }

    suspend fun getLink(title: String, year: Int? = null,
                        seasonN: Int? = null,
                        episodeN: Int? = null, exact: Boolean = false): String? {
        if (seasonN != null && episodeN != null) {
            val season = getSeason(title, year, seasonN)
            val episode = season?.episodes?.find { x -> x.number == episodeN } ?: return null
            return generateLink(titleId = season.title_id, episodeId = episode.id)
        } else {
            val film = getFilm(title, year, exact) ?: return null
            return generateLink(titleId = film.id)
        }
    }

    suspend fun invoke(title: String,
                       year: Int? = null,
                       seasonN: Int? = null,
                       episodeN: Int? = null,
                       callback: (ExtractorLink) -> Unit,){
        val link = getLink(title = title, year = year, seasonN = seasonN, episodeN = episodeN, exact = true)
        if (link != null)
            callback.invoke(
                    ExtractorLink(
                            source = title,
                            name = title,
                            url = link,
                            isM3u8 = true,
                            referer = URL,
                            quality = Qualities.Unknown.value
                    )
            )
    }

    private suspend fun generateLink(titleId: Int, episodeId: Int? = null): String {
        println("🙃 episodeId${episodeId}")
        val cookies = getCookies()
        val ip = app.get("https://api.ipify.org/").text
        var url = "$URL/watch/${titleId}";
        if (episodeId != null) {
            url=url+"?e=" +episodeId
        }
        val response = app.get(url, headers = mapOf("user-agent" to Pontefice.userAgent), cookies = cookies)
        val scwsidJs = response.document.select("video-player").attr("response").replace("&quot;", """"""")
        val jsn = JSONObject(scwsidJs)
        val scwsid = jsn.getString("scws_id")
        val expire = (System.currentTimeMillis() / 1000 + 172800).toString()

        val token0 = "$expire$ip Yc8U6r8KjAKAepEA".toByteArray()
        val token1 = MessageDigest.getInstance("MD5").digest(token0)
        val token2 = base64Encode(token1)
        val token = token2.replace("=", "").replace("+", "-").replace("/", "_")
        println("🙃 \"https://scws.work/master/$scwsid?token=$token&expires=$expire&n=1\"")
        return "https://scws.work/master/$scwsid?token=$token&expires=$expire&n=1"
    }

    private suspend fun filterBy(item: SearchResponseRecord, title: String, year: Int?, exactTitle: Boolean?): Boolean {
        val p = preview(id = item.id)
        return (year == null || p.release_date.contains(year.toString())) && (exactTitle == false || p.name.lowercase() == title.lowercase())
    }


    private suspend fun preview(id: Int): PreviewResponse {
        val cookies = getCookies()
        val r = app.post("$APIURL/titles/preview/${id}", headers = mapOf("user-agent" to Pontefice.userAgent,
                "referer" to URL), cookies = cookies)
        return r.parsed()
    }

    private data class PreviewResponse(
            @JsonProperty("id") val id: Int,
            @JsonProperty("name") val name: String,
            @JsonProperty("release_date") val release_date: String,

            )

    private data class SearchResponse(
            @JsonProperty("offset") val offset: Int,
            @JsonProperty("records") val records: ArrayList<SearchResponseRecord>,
    )

    private data class SearchResponseRecord(
            @JsonProperty("id") val id: Int,
            @JsonProperty("slug") val slug: String,
    )

    private data class Movie(
            @JsonProperty("id") val id: Long,
            @JsonProperty("name") val name: String,
            @JsonProperty("type") val type: String,
            @JsonProperty("release_date") val releaseDate: String,
            @JsonProperty("seasons_count") val seasonsCount: Long? = null,
            @JsonProperty("genres") val genres: List<Genre>,
            @JsonProperty("votes") val votes: List<Vote>,
            @JsonProperty("runtime") val runtime: Long? = null
    )

    private data class Season(
            @JsonProperty("id") val id: Long,
            @JsonProperty("name") val name: String? = "",
            @JsonProperty("plot") val plot: String? = "",
            @JsonProperty("date") val date: String? = "",
            @JsonProperty("number") val number: Int,
            @JsonProperty("title_id") val title_id: Int,
            @JsonProperty("createdAt") val createdAt: String? = "",
            @JsonProperty("updated_at") val updatedAt: String? = "",
            @JsonProperty("episodes") val episodes: List<Episodejson>
    )

    private data class Episodejson(
            @JsonProperty("id") val id: Int,
            @JsonProperty("number") val number: Int,
            @JsonProperty("name") val name: String? = "",
            @JsonProperty("plot") val plot: String? = "",
            @JsonProperty("season_id") val seasonID: Long,
            @JsonProperty("images") val images: List<ImageSeason>
    )

    private data class ImageSeason(
            @JsonProperty("imageable_id") val imageableID: Long,
            @JsonProperty("imageable_type") val imageableType: String,
            @JsonProperty("server_id") val serverID: Long,
            @JsonProperty("proxy_id") val proxyID: Long,
            @JsonProperty("url") val url: String,
            @JsonProperty("type") val type: String,
            @JsonProperty("original_url") val originalURL: String
    )


    private data class Genre(
            @JsonProperty("name") val name: String,
            @JsonProperty("pivot") val pivot: Pivot,
    )

    private data class Pivot(
            @JsonProperty("titleID") val titleID: Long,
            @JsonProperty("genreID") val genreID: Long,
    )

    private data class Vote(
            @JsonProperty("title_id") val title_id: Long,
            @JsonProperty("average") val average: String,
            @JsonProperty("count") val count: Long,
            @JsonProperty("type") val type: String,
    )
}
