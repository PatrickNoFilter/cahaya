package com.patrisius.streambox.plugin

import android.content.Context

/**
 * Phase 3 — native plugin SDK.
 *
 * The Stremio-protocol addon engine (see addon/) already covers catalogs,
 * streams and subtitles. Native plugins exist for what that protocol can't
 * express: custom scrapers, extra metadata providers, UI extensions —
 * the CloudStream-extension style of plugin, but designed originally for
 * StreamBox (no code taken from CloudStream).
 *
 * This file is the contract stub. Phase 3 work: plugin packaging (.apk with
 * a Plugin entry-point), sandboxed class loading, and provider registration.
 */
interface StreamBoxPlugin {
    val id: String
    val name: String
    val version: String

    fun onLoad(host: PluginHost)
    fun onUnload()
}

/** Services the host app exposes to plugins. Grows in phase 3. */
interface PluginHost {
    val context: Context
    // Phase 3: registerCatalogProvider(), registerStreamProvider(),
    //         httpClient(), preferences(), ...
}

class PluginManager(private val context: Context) {
    private val plugins = mutableListOf<StreamBoxPlugin>()

    fun installedPlugins(): List<StreamBoxPlugin> = plugins.toList()

    /** Phase 3: load a plugin package and call onLoad(). */
    fun load(packagePath: String): Result<StreamBoxPlugin> =
        Result.failure(UnsupportedOperationException("Native plugins land in phase 3"))

    fun unload(id: String) {
        plugins.filter { it.id == id }.forEach { it.onUnload() }
        plugins.removeAll { it.id == id }
    }
}
