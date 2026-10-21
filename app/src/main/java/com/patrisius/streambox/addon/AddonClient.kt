package com.patrisius.streambox.addon

import com.patrisius.streambox.model.Episode
import com.patrisius.streambox.model.MediaItem
import com.patrisius.streambox.model.StreamSource
import com.patrisius.streambox.model.SubtitleTrack
import com.patrisius.streambox.model.Trailer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for one Stremio-protocol addon.
 *
 * Transport URL forms accepted:
 *   https://host/                (manifest at /manifest.json)
 *   https://host/manifest.json    (manifest URL pasted directly)
 */
class AddonClient(
    transportUrl: String,
    private val http: OkHttpClient = defaultHttp(),
) {
    /** Base URL with any trailing /manifest.json stripped. */
    val baseUrl: String = transportUrl
        .trim()
        .removeSuffix("/")
        .removeSuffix("/manifest.json")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    var manifest: AddonManifest? = null
        private set

    // ------------------------------------------------------------------ fetch

    /** Downloads and parses manifest.json. Throws IOException on failure. */
    @Throws(IOException::class)
    fun loadManifest(): AddonManifest {
        val body = get("$baseUrl/manifest.json")
        return json.decodeFromString(AddonManifest.serializer(), body).also {
            manifest = it
        }
    }

    /**
     * Catalog rows: GET /catalog/{type}/{id}.json?search=&skip=&genre=
     * Addons may HTTP-redirect (Cinemeta does); OkHttp follows by default.
     */
    @Throws(IOException::class)
    fun catalog(
        type: String,
        id: String,
        extras: Map<String, String> = emptyMap(),
    ): List<MediaItem> {
        val url = buildString {
            append("$baseUrl/catalog/$type/$id.json")
            if (extras.isNotEmpty()) {
                append("?")
                append(extras.entries.joinToString("&") { (k, v) -> "$k=$v" })
            }
        }
        val root = json.parseToJsonElement(get(url)).jsonObject
        return root["metas"]?.jsonArray?.map { parseMeta(it.jsonObject) } ?: emptyList()
    }

    /** GET /meta/{type}/{id}.json -> the single meta object, or null. */
    @Throws(IOException::class)
    fun meta(type: String, id: String): MediaItem? {
        val root = json.parseToJsonElement(get("$baseUrl/meta/$type/$id.json")).jsonObject
        return root["meta"]?.let { parseMeta(it.jsonObject) }
    }

    /**
     * GET /stream/{type}/{videoId}.json -> playable streams.
     * For series, videoId is the episode id (e.g. "tt0903747:1:1").
     */
    @Throws(IOException::class)
    fun streams(type: String, videoId: String): List<StreamSource> {
        val m = manifest ?: error("loadManifest() first")
        val root = json.parseToJsonElement(get("$baseUrl/stream/$type/$videoId.json")).jsonObject
        return root["streams"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull
            val hints = o["behaviorHints"]?.jsonObject?.entries?.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            } ?: emptyMap()
            StreamSource(
                url = url,
                title = title,
                quality = title?.let(::parseQuality),
                addonId = m.id,
                addonName = m.name,
                behaviorHints = hints,
            )
        } ?: emptyList()
    }

    /** GET /subtitles/{type}/{videoId}.json */
    @Throws(IOException::class)
    fun subtitles(type: String, videoId: String): List<SubtitleTrack> {
        val m = manifest ?: error("loadManifest() first")
        val root = json.parseToJsonElement(get("$baseUrl/subtitles/$type/$videoId.json")).jsonObject
        return root["subtitles"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SubtitleTrack(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: url,
                url = url,
                lang = o["lang"]?.jsonPrimitive?.contentOrNull ?: "und",
                addonName = m.name,
            )
        } ?: emptyList()
    }

    // ---------------------------------------------------------------- routing

    /**
     * True when this addon can serve [resource] ("catalog", "meta", "stream",
     * "subtitles") for [type]/[id]. Matches manifest resources, falling back
     * to manifest-level types/idPrefixes when a resource entry omits them.
     */
    fun supports(resource: String, type: String, id: String): Boolean {
        val m = manifest ?: return false
        val ref = m.resources.firstOrNull { it.name == resource } ?: return false
        val types = ref.types.ifEmpty { m.types }
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = ref.idPrefixes ?: m.idPrefixes
        val bareId = id.substringBefore(':')
        if (!prefixes.isNullOrEmpty() && prefixes.none { bareId.startsWith(it) }) return false
        return true
    }

    // ------------------------------------------------------------------ parse

    private fun parseMeta(o: JsonObject): MediaItem {
        fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        fun strList(key: String) = o[key]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()

        return MediaItem(
            id = str("id") ?: str("imdb_id") ?: "",
            type = str("type") ?: "",
            name = str("name") ?: "",
            poster = str("poster"),
            background = str("background"),
            description = str("description"),
            genres = strList("genre"),
            cast = strList("cast"),
            director = strList("director"),
            imdbRating = str("imdbRating"),
            year = str("year") ?: str("released")?.take(4),
            runtime = str("runtime"),
            trailers = o["trailers"]?.jsonArray?.mapNotNull { t ->
                val to = t.jsonObject
                val src = to["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Trailer(src, to["type"]?.jsonPrimitive?.contentOrNull ?: "Trailer")
            } ?: emptyList(),
            videos = o["videos"]?.jsonArray?.map { v ->
                val vo = v.jsonObject
                fun vs(k: String) = vo[k]?.jsonPrimitive?.contentOrNull
                Episode(
                    id = vs("id") ?: "",
                    season = vo["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    number = vo["number"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: vo["episode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    name = vs("name") ?: vs("title") ?: "",
                    overview = vs("overview"),
                    thumbnail = vs("thumbnail"),
                    firstAired = vs("firstAired") ?: vs("released"),
                    rating = vs("rating"),
                )
            } ?: emptyList(),
        )
    }

    private val qualityRe = Regex("""\b(2160|1440|1080|720|480|360)p\b""")

    private fun parseQuality(title: String): String? =
        qualityRe.find(title)?.value

    @Throws(IOException::class)
    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "StreamBox/0.1.0 (Android TV)")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** Normalize anything the user pastes into a transport base URL. */
        fun normalize(input: String): String? {
            val t = input.trim().removeSuffix("/")
            if (t.isEmpty()) return null
            // Accept full manifest URLs too.
            val base = t.removeSuffix("/manifest.json")
            return base.toHttpUrlOrNull()?.toString()?.removeSuffix("/")?.let {
                // keep the /manifest.json-stripped form; AddonClient re-adds it
                if (t.endsWith("/manifest.json")) base else it
            }
        }
    }
}
