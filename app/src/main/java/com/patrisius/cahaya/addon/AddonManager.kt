package com.patrisius.cahaya.addon

import android.content.Context
import com.patrisius.cahaya.model.MediaItem
import com.patrisius.cahaya.model.StreamSource
import com.patrisius.cahaya.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Owns the installed addon set: persistence, manifest loading and
 * cross-addon aggregation (catalog rows, meta lookup, stream ranking).
 *
 * All network work is suspending and runs on Dispatchers.IO.
 */
class AddonManager(
    private val context: Context,
    private val http: OkHttpClient = AddonClient.defaultHttp(),
) {
    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** transport base URL -> live client (manifest loaded). */
    private val clients = mutableMapOf<String, AddonClient>()
    private val lock = Any()

    data class InstalledAddon(
        val transportUrl: String,
        val manifest: AddonManifest,
    )

    // -------------------------------------------------------------- lifecycle

    /** Load persisted addons and fetch their manifests. Call once at startup. */
    suspend fun init(): Unit = withContext(Dispatchers.IO) {
        storedUrls().forEach { url ->
            try {
                getOrLoad(url)
            } catch (_: Exception) {
                // Keep the entry; a dead addon shouldn't nuke the list.
            }
        }
    }

    /** Install from a pasted URL or QR scan. Returns the manifest on success. */
    suspend fun install(rawInput: String): Result<AddonManifest> =
        withContext(Dispatchers.IO) {
            val base = AddonClient.normalize(rawInput)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Not a valid addon URL")
                )
            runCatching {
                val client = AddonClient(base, http).also { it.loadManifest() }
                val manifest = client.manifest!!
                synchronized(lock) {
                    clients[base] = client
                    persist(storedUrls() + base)
                }
                manifest
            }
        }

    fun uninstall(transportUrl: String) {
        synchronized(lock) {
            clients.remove(transportUrl)
            persist(storedUrls() - transportUrl)
        }
    }

    fun installed(): List<InstalledAddon> = synchronized(lock) {
        clients.map { (url, c) -> InstalledAddon(url, c.manifest!!) }
    }

    // ------------------------------------------------------------------ query

    /** Addons able to serve catalogs for [type] ("movie"/"series"). */
    fun catalogAddons(type: String): List<AddonClient> = synchronized(lock) {
        clients.values.filter { it.supports("catalog", type, "") }
    }

    /** First meta hit across addons that serve meta for this id. */
    suspend fun metaFor(type: String, id: String): MediaItem? =
        withContext(Dispatchers.IO) {
            val pool = synchronized(lock) {
                clients.values.filter { it.supports("meta", type, id) }
            }
            pool.firstNotNullOfOrNull { c ->
                runCatching { c.meta(type, id) }.getOrNull()
            }
        }

    /**
     * All streams for a video, aggregated across addons and ranked by
     * quality (2160p first), then by addon order.
     */
    suspend fun streamsFor(type: String, videoId: String): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val pool = synchronized(lock) {
                clients.values.filter { it.supports("stream", type, videoId) }
            }
            pool.flatMap { c ->
                runCatching { c.streams(type, videoId) }.getOrDefault(emptyList())
            }.sortedWith(
                compareByDescending<StreamSource> { qualityRank(it.quality) }
            )
        }

    /** Subtitles aggregated across subtitle addons. */
    suspend fun subtitlesFor(type: String, videoId: String): List<SubtitleTrack> =
        withContext(Dispatchers.IO) {
            val pool = synchronized(lock) {
                clients.values.filter { it.supports("subtitles", type, videoId) }
            }
            pool.flatMap { c ->
                runCatching { c.subtitles(type, videoId) }.getOrDefault(emptyList())
            }
        }

    // ------------------------------------------------------------------ store

    private fun storedUrls(): Set<String> =
        prefs.getStringSet(KEY_URLS, emptySet()) ?: emptySet()

    private fun persist(urls: Set<String>) {
        prefs.edit().putStringSet(KEY_URLS, urls.toSet()).apply()
    }

    private fun getOrLoad(url: String): AddonClient = synchronized(lock) {
        clients.getOrPut(url) { AddonClient(url, http).also { it.loadManifest() } }
    }

    private fun qualityRank(q: String?): Int = when (q) {
        "2160p" -> 5
        "1440p" -> 4
        "1080p" -> 3
        "720p" -> 2
        "480p" -> 1
        "360p" -> 0
        else -> -1
    }

    companion object {
        private const val PREFS = "cahaya_addons"
        private const val KEY_URLS = "transport_urls"
    }
}
