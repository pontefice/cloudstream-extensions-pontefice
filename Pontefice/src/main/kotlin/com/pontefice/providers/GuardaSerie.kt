package com.pontefice.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.pontefice.fixUrl

object GuardaSerie {
    val URL =  "https://guardaserie.one/"

    suspend fun invoke(
            id: String? = null,
            season: Int? = null,
            episode: Int? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val url = app.post(
                URL, data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to id!!
        )
        ).document.selectFirst("h2>a")?.attr("href") ?: return

        val document = app.get(url).document
        document.select("div.tab-content > div").forEachIndexed { seasonData, data ->
            data.select("li").forEachIndexed { epNum, epData ->
                if (season == seasonData + 1 && episode == epNum + 1) {
                    epData.select("div.mirrors > a.mr").forEach {
                        loadExtractor(
                                fixUrl(it.attr("data-link"), ),
                                URL,
                                subtitleCallback,
                                callback
                        )
                    }
                }
            }
        }
    }
}