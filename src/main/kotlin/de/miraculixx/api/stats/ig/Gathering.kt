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
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

object Gathering {

    suspend fun refreshInstantGaming() {
        Updater.logger.info("--- Start collecting data ---")

        val url = "https://www.instant-gaming.com/en/exportCatalog/json/?igr=miraculixx&with_short_description=1&topseller=1"
        val response = client.post(url) {
            header("User-Agent", "IG-ExportCatalog-Fetcher")
        }
        if (!response.status.isSuccess()) {
            Updater.logger.error("Failed to fetch data from Instant Gaming: ${response.status} - ${response.bodyAsText()}")
            return
        }
        val hits = try {
            response.body<List<IGHitResponse>>()
        } catch (e: Exception) {
            Updater.logger.error("Failed to parse Instant Gaming response", e)
            return
        }

        Updater.logger.info("--- Scanning scraped data ---")
        val total = hits.size
        val available = hits.filter { it.stock }
        Updater.logger.info("Collected $total games (${"%.2f".format((available.size.toDouble() / total.coerceAtLeast(1)) * 100)}% in stock)")
        if (available.isEmpty()) {
            Updater.logger.warn("No games in stock - skipping database update")
            return
        }

        SQLInstant.saveSnapshot(hits)
    }
}