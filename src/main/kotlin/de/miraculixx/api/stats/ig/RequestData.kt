package de.miraculixx.api.stats.ig

import kotlinx.serialization.Serializable

@Serializable
data class IGResponse(
    val hits: List<IGHitResponse>,
    val nbHits: Int,
    val page: Int
)

/**
 * @param prod_id Game ID (on IG)
 * @param name Game name
 * @param platform Platform (e.g. "Steam", "epic",)
 * @param type Type, this is either the Platform (for games) or idk
 * @param seo_name Used for URL building "/en/<id>-buy-<seo_name>"
 * @param is_dlc Whether this is a DLC or not
 * @param preorder Whether this is a preorder or not
 * @param retail Normal price (formatted as string, e.g. "19.99")
 * @param price Discounted price (formatted as double, e.g. 9.99)
 * @param discount Discount in percent (formatted as int, e.g. 50)
 */
@Serializable
data class IGHitResponse(
    val prod_id: Int,
    val name: String,
    val platform: String,
    val type: String,
    val seo_name: String,

    val is_dlc: Boolean,
    val preorder: Boolean,
    val has_stock: Boolean,

    val retail: String,
    val price: Double,
    val discount: Int
)

@Serializable
data class IGQueryRequest(
    val params: String
)
