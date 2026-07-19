package opensamguk.common.world

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable(with = WorldIdSerializer::class)
@JvmInline
value class WorldId(val value: Int) {
    init {
        require(value > 0) { "WorldId must be positive" }
    }
}

object WorldIdSerializer : KSerializer<WorldId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("opensamguk.common.world.WorldId", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: WorldId) {
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): WorldId {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("WorldId requires a JSON decoder")
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("WorldId must be a JSON integer")
        if (primitive.isString) {
            throw SerializationException("WorldId must be a JSON integer")
        }
        val value = primitive.intOrNull
            ?: throw SerializationException("WorldId must be a JSON integer")
        return try {
            WorldId(value)
        } catch (exception: IllegalArgumentException) {
            throw SerializationException("WorldId must be positive", exception)
        }
    }
}
