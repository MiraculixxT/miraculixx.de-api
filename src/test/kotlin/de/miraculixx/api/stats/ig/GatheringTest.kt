package de.miraculixx.api.stats.ig

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatheringTest {

    private val parser = Json { ignoreUnknownKeys = true }

    @Test
    fun `decode IG booleans from 0 and 1`() {
        val payload = """
            {
              "hits": [
                {
                  "prod_id": 1,
                  "name": "Game",
                  "platform": "Steam",
                  "type": "Steam",
                  "seo_name": "game",
                  "is_dlc": 0,
                  "preorder": 1,
                  "has_stock": 1,
                  "retail": "19.99",
                  "price": 9.99,
                  "discount": 50
                }
              ],
              "nbHits": 1,
              "page": 0
            }
        """.trimIndent()

        val response = parser.decodeFromString<IGResponse>(payload)
        val hit = response.hits.first()

        assertFalse(hit.is_dlc)
        assertTrue(hit.preorder)
        assertTrue(hit.has_stock)
    }
}

