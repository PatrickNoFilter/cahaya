package com.patrisius.streambox.addon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Stremio addon manifest.json model.
 *
 * Verified live 2026-10-21 against v3-cinemeta.strem.io and
 * opensubtitles-v3.strem.io. Unknown fields are ignored so addons can
 * evolve without breaking us.
 */
@Serializable
data class AddonManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String = "",
    val logo: String? = null,
    val background: String? = null,
    @Serializable(with = ResourceListSerializer::class)
    val resources: List<ResourceRef> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<AddonCatalog> = emptyList(),
    val addonCatalogs: List<AddonCatalog> = emptyList(),
    val behaviorHints: Map<String, JsonElement> = emptyMap(),
)

/** One entry of the manifest "resources" array. */
@Serializable
data class ResourceRef(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
)

@Serializable
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    val genres: List<String> = emptyList(),
    val extra: List<ExtraOption> = emptyList(),
    val extraSupported: List<String> = emptyList(),
)

@Serializable
data class ExtraOption(
    val name: String,
    val options: List<String>? = null,
    val isRequired: Boolean = false,
)

/**
 * Manifest "resources" entries may be plain strings ("catalog") or objects
 * ({name, types, idPrefixes}). Accepts both.
 */
object ResourceListSerializer : KSerializer<List<ResourceRef>> {
    private val listSer = kotlinx.serialization.builtins.ListSerializer(ResourceRef.serializer())
    override val descriptor: SerialDescriptor = listSer.descriptor

    override fun serialize(encoder: Encoder, value: List<ResourceRef>) =
        listSer.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<ResourceRef> {
        val arr: JsonArray = (decoder as JsonDecoder).decodeJsonElement().jsonArray
        return arr.map { el ->
            when (el) {
                is JsonPrimitive -> ResourceRef(name = el.content)
                is JsonObject ->
                    Json.decodeFromJsonElement(ResourceRef.serializer(), el)
                else -> throw SerializationException("Unexpected resource entry: $el")
            }
        }
    }
}
