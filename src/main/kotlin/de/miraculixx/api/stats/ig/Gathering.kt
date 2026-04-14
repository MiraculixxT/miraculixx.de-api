package de.miraculixx.api.stats.ig

import de.miraculixx.api.client
import de.miraculixx.api.data.apiKeys
import de.miraculixx.api.stats.Updater
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

object Gathering {

    suspend fun refreshInstantGaming() {
        var page = 0
        val hits = mutableListOf<IGHitResponse>()
        Updater.logger.info("--- Start scraping data ---")
        while (true) {
            val response = craftInstantGamingRequest(page)
            if (response.status.value != 200) {
                Updater.logger.info("Failed to fetch page $page - status code: ${response.status.value} (${response.bodyAsText()}).")
                break
            }
            val data = response.body<IGResponse>()
            if (data.hits.isEmpty()) {
                Updater.logger.info("Done with page $page - ${data.nbHits} scanned entries")
                break
            }
            val waitTime = (3500..5500).random().milliseconds // wait 3.5 to 5.5 seconds
            Updater.logger.info("Fetched page $page: ${data.hits.size} hits (${hits.size}/${data.nbHits} total). Wait $waitTime...")
            hits.addAll(data.hits)
            page++
            delay(waitTime)
        }
        Updater.logger.info("--- Scanning scraped data ---")
        val total = hits.size
        val available = hits.filter { it.has_stock }
        Updater.logger.info("Collected $total games (${"%.2f".format((available.size.toDouble() / total.coerceAtLeast(1)) * 100)}% in stock)")
        if (available.isEmpty()) {
            Updater.logger.warn("No games in stock - skipping database update")
            return
        }

        SQLInstant.saveSnapshot(available)
    }

    suspend fun craftInstantGamingRequest(page: Int): HttpResponse {
        val url = "https://qknhp8tc3y-dsn.algolia.net/1/indexes/produits_de/query"

        return client.post(url) {
            parameter("x-algolia-agent", "Algolia for vanilla JavaScript (lite) 3.24.7")
            parameter("x-algolia-application-id", apiKeys.ig_id)
            parameter("x-algolia-api-key", apiKeys.ig_key)

            // Mimic browser request to satisfy algolia
            header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0")
            header("Accept", "application/json")
            header("Accept-Language", "en-US,en;q=0.9")
            header("Content-Type", "application/x-www-form-urlencoded")
            header("Origin", "https://www.instant-gaming.com")
            header("Referer", "https://www.instant-gaming.com/")
            header("Connection", "keep-alive")
            header("Sec-Fetch-Dest", "empty")
            header("Sec-Fetch-Mode", "cors")
            header("Sec-Fetch-Site", "cross-site")

            setBody("{\"params\":\"query=&hitsPerPage=1000&filters=(country_whitelist%3A%22DE%22%20OR%20country_whitelist%3A%22worldwide%22%20OR%20country_whitelist%3A%22WW%22)%20AND%20(NOT%20country_blacklist%3A%22DE%22)&facets=%5B%22search_tags%22%5D&maxValuesPerFacet=1000&page=$page\"}")
        }
    }
}