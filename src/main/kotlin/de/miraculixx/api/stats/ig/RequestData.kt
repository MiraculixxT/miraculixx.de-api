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
data class IGHitResponse(
    val id: Int = -1,
    val name: String = "none",
    val type: String = "none",
    val url: String = "none",
    val short_description: String = "none",
    val category: Set<String> = emptySet(),
    val steam_id: Int = -1,

    @Serializable(with = ZeroOneBooleanSerializer::class)
    val preorder: Boolean = false,
    @Serializable(with = ZeroOneBooleanSerializer::class)
    val stock: Boolean = false,
    @Serializable(with = ZeroOneBooleanSerializer::class)
    val topseller: Boolean = false,

    @Serializable(with = StringDoubleSerializer::class)
    val retail: Double = -1.00,
    @Serializable(with = StringDoubleSerializer::class)
    val price: Double = -1.00,
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

object StringDoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringDouble", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Double {
        val input = decoder.decodeString()
        return input.toDoubleOrNull() ?: -1.0
    }

    override fun serialize(encoder: Encoder, value: Double) {
        encoder.encodeString(value.toString())
    }
}

