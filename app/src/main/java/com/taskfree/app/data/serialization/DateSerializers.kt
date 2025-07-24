package com.taskfree.app.data.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** ISO-8601 yyyy-MM-dd */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    override fun serialize(encoder: Encoder, value: LocalDate) =
        encoder.encodeString(fmt.format(value))

    override fun deserialize(decoder: Decoder): LocalDate =
        LocalDate.parse(decoder.decodeString(), fmt)
}

/** ISO-8601 instant with Z */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.toString())       // Instant#toString is ISO-8601

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}
