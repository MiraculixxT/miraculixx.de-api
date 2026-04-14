package de.miraculixx.api.stats.ig

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

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
 * @param seo_name Used for URL building "/en/<id>-buy-<seo_name>"
 * @param is_dlc Whether this is a DLC or not
 * @param preorder Whether this is a preorder or not
 * @param retail Normal price (formatted as string, e.g. "19.99")
 * @param price Discounted price (formatted as double, e.g. 9.99)
 * @param discount Discount in percent (formatted as int, e.g. 50)
 */
@Serializable
data class IGHitResponse(
    val prod_id: Int = -1,
    val name: String = "none",
    val platform: String = "none",
    val seo_name: String = "none",

    @Serializable(with = ZeroOneBooleanSerializer::class)
    val is_dlc: Boolean = false,
    @Serializable(with = ZeroOneBooleanSerializer::class)
    val preorder: Boolean = false,
    @Serializable(with = ZeroOneBooleanSerializer::class)
    val is_prepaid: Boolean = false,
    @Serializable(with = ZeroOneBooleanSerializer::class)
    val is_subscription: Boolean = false,
    @Serializable(with = ZeroOneBooleanSerializer::class)
    val has_stock: Boolean = false,

    val retail: String = "-1.00",
    val price: Double = -1.0,
    val discount: Int = -1
)

object ZeroOneBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ZeroOneBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val input = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val primitive = input.decodeJsonElement() as? JsonPrimitive
            ?: throw IllegalStateException("Expected primitive value for boolean field")

        return when (primitive.content.trim().lowercase()) {
            "0", "false" -> false
            "1", "true" -> true
            else -> throw IllegalStateException("Expected 0/1 or true/false for boolean field but got '${primitive.content}'")
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeInt(if (value) 1 else 0)
    }
}

