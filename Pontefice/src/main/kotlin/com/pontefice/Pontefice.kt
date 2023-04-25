package com.pontefice

import android.annotation.SuppressLint
import com.fasterxml.jackson.annotation.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.pontefice.providers.GuardaSerie
import kotlin.math.roundToInt


open class Pontefice : MainAPI() {
    override var lang = "it"
    override var name = "Pontefice"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
            TvType.Anime,
            TvType.Live
    )


    companion object {
        val interceptor = CloudflareKiller()
        const val userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"


        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private val apiKey = base64DecodeAPI("ZTM=NTg=MjM=MjM=ODc=MzI=OGQ=MmE=Nzk=Nzk=ZjI=NTA=NDY=NDA=MzA=YjA=") // PLEASE DON'T STEAL

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        private fun base64DecodeAPI(api: String): String {
            return api.chunked(4).map { base64Decode(it) }.reversed().joinToString("")
        }

    }

    override val mainPage = mainPageOf(
            "$tmdbAPI/trending/movie/day?api_key=$apiKey&region=IT&language=it-IT" to "Film in Tentenza",
            "$tmdbAPI/trending/tv/day?api_key=$apiKey&region=IT&language=it-IT" to "Serie TV in Tentenza",
            "$tmdbAPI/movie/popular?api_key=$apiKey&region=IT&language=it-IT" to "Film Popolari",
            "$tmdbAPI/tv/popular?api_key=$apiKey&region=IT&language=it-IT" to "Serie TV Popolari",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=213" to "Netflix",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=1024" to "Amazon",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=2739" to "Disney+",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=453" to "Hulu",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=2552" to "Apple TV+",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=49" to "HBO",
            "$tmdbAPI/discover/tv?api_key=$apiKey&region=IT&language=it-IT&with_networks=4330" to "Paramount+",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(
            page: Int,
            request: MainPageRequest
    ): HomePageResponse {
        val adultQuery =
                if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}$adultQuery&page=$page")
                .parsedSafe<Results>()?.results
                ?.mapNotNull { media ->
                    media.toSearchResponse(type)
                } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
                title ?: name ?: originalTitle ?: return null,
                Data(id = id, type = mediaType ?: type).toJson(),
                TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get(
                "$tmdbAPI/search/multi?api_key=$apiKey&language=it-IT&query=$query&page=1&include_adult=${settingsForProvider.enableAdult}"
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&region=IT&language=it-IT&append_to_response=keywords,credits,external_ids,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&region=IT&language=it-IT&append_to_response=keywords,credits,external_ids,videos,recommendations"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
                ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val rating = res.vote_average.toString().toRatingInt()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime =
                genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
                .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                    Actor(
                            cast.name ?: cast.originalName ?: return@mapNotNull null,
                            getImageUrl(cast.profilePath)
                    ),
                    roleString = cast.character
            )
        } ?: return null
        val recommendations =
                res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }
                ?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val lastSeason = res.last_episode_to_air?.season_number
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&region=IT&language=it-IT")
                        .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                            Episode(
                                    LinkData(
                                            data.id,
                                            res.external_ids?.imdb_id,
                                            data.type,
                                            eps.seasonNumber,
                                            eps.episodeNumber,
                                            title = title,
                                            year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                            orgTitle = orgTitle,
                                            isAnime = isAnime,
                                            airedYear = year,
                                            lastSeason = lastSeason,
                                            epsTitle = eps.name,
                                    ).toJson(),
                                    name = eps.name,
                                    season = eps.seasonNumber,
                                    episode = eps.episodeNumber,
                                    posterUrl = getImageUrl(eps.stillPath),
                                    rating = eps.voteAverage?.times(10)?.roundToInt(),
                                    description = eps.overview
                            ).apply {
                                this.addDate(eps.airDate)
                            }
                        }
            }?.flatten() ?: listOf()
            newTvSeriesLoadResponse(
                    title,
                    url,
                    if (isAnime) TvType.Anime else TvType.TvSeries,
                    episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = if (isAnime) keywords else genres
                this.rating = rating
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    LinkData(
                            data.id,
                            res.external_ids?.imdb_id,
                            data.type,
                            title = title,
                            year = year,
                            orgTitle = orgTitle,
                            isAnime = isAnime,
                    ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = if (isAnime) keywords else genres
                this.rating = rating
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
            }
        }
    }


    @SuppressLint("SuspiciousIndentation")
    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseJson<LinkData>(data)
        println("🙃 $res")
        argamap(
                    {
                        StreamingCommunity.invoke(
                                res.title!!,
                                res.airedYear ?: res.year,
                                res.season,
                                res.episode,
                                callback
                        )
                    },
                {
                    if (res.type == "tv" ) GuardaSerie.invoke( //has tv series and anime
                            res.imdbId!!,
                            res.season,
                            res.episode,
                            subtitleCallback,
                            callback
                    )
                },
            )
        return true
    }

    data class LinkData(
            val id: Int? = null,
            val imdbId: String? = null,
            val type: String? = null,
            val season: Int? = null,
            val episode: Int? = null,
            val aniId: String? = null,
            val animeId: String? = null,
            val title: String? = null,
            val year: Int? = null,
            val orgTitle: String? = null,
            val isAnime: Boolean = false,
            val airedYear: Int? = null,
            val lastSeason: Int? = null,
            val epsTitle: String? = null,
    )

    data class Data(
            val id: Int? = null,
            val type: String? = null,
            val aniId: String? = null,
            val malId: Int? = null,
    )

    data class Results(
            @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("name") val name: String? = null,
            @JsonProperty("title") val title: String? = null,
            @JsonProperty("original_title") val originalTitle: String? = null,
            @JsonProperty("media_type") val mediaType: String? = null,
            @JsonProperty("poster_path") val posterPath: String? = null,
    )

    data class Genres(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
            @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
            @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("name") val name: String? = null,
            @JsonProperty("season_number") val seasonNumber: Int? = null,
            @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("name") val name: String? = null,
            @JsonProperty("original_name") val originalName: String? = null,
            @JsonProperty("character") val character: String? = null,
            @JsonProperty("known_for_department") val knownForDepartment: String? = null,
            @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("name") val name: String? = null,
            @JsonProperty("overview") val overview: String? = null,
            @JsonProperty("air_date") val airDate: String? = null,
            @JsonProperty("still_path") val stillPath: String? = null,
            @JsonProperty("vote_average") val voteAverage: Double? = null,
            @JsonProperty("episode_number") val episodeNumber: Int? = null,
            @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
            @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
            @JsonProperty("key") val key: String? = null,
    )

    data class ResultsTrailer(
            @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class ExternalIds(
            @JsonProperty("imdb_id") val imdb_id: String? = null,
            @JsonProperty("tvdb_id") val tvdb_id: String? = null,
    )

    data class Credits(
            @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
            @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
            @JsonProperty("episode_number") val episode_number: Int? = null,
            @JsonProperty("season_number") val season_number: Int? = null,
    )

    data class MediaDetail(
            @JsonProperty("id") val id: Int? = null,
            @JsonProperty("imdb_id") val imdbId: String? = null,
            @JsonProperty("title") val title: String? = null,
            @JsonProperty("name") val name: String? = null,
            @JsonProperty("original_title") val originalTitle: String? = null,
            @JsonProperty("original_name") val originalName: String? = null,
            @JsonProperty("poster_path") val posterPath: String? = null,
            @JsonProperty("backdrop_path") val backdropPath: String? = null,
            @JsonProperty("release_date") val releaseDate: String? = null,
            @JsonProperty("first_air_date") val firstAirDate: String? = null,
            @JsonProperty("overview") val overview: String? = null,
            @JsonProperty("runtime") val runtime: Int? = null,
            @JsonProperty("vote_average") val vote_average: Any? = null,
            @JsonProperty("original_language") val original_language: String? = null,
            @JsonProperty("status") val status: String? = null,
            @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
            @JsonProperty("keywords") val keywords: KeywordResults? = null,
            @JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
            @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
            @JsonProperty("videos") val videos: ResultsTrailer? = null,
            @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
            @JsonProperty("credits") val credits: Credits? = null,
            @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    )



}

