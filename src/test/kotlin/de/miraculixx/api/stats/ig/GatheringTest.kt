package de.miraculixx.api.stats.ig

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatheringTest {

    private val parser = Json { ignoreUnknownKeys = true }

    @Test
    fun `decode IG booleans from 0 and 1`() {
        val payload = """
            [
              {
                "id": 1,
                "name": "Game",
                "type": "Steam",
                "url": "https://example.com/game",
                "short_description": "A game",
                "category": ["action", "rpg"],
                "steam_id": 42,
                "preorder": 1,
                "stock": 1,
                "topseller": 0,
                "retail": "19.99",
                "price": "9.99",
                "discount": 50
              }
            ]
        """.trimIndent()

        val hits = parser.decodeFromString<List<IGHitResponse>>(payload)
        val hit = hits.first()

        assertEquals(1, hit.id)
        assertEquals("Steam", hit.type)
        assertTrue(hit.preorder)
        assertTrue(hit.stock)
        assertFalse(hit.topseller)
        assertEquals(setOf("action", "rpg"), hit.category)
        assertEquals(19.99, hit.retail)
        assertEquals(9.99, hit.price)
    }
}
