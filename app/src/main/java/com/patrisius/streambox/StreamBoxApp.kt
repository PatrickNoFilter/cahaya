package com.patrisius.streambox

import android.app.Application
import com.patrisius.streambox.addon.AddonManager
import com.patrisius.streambox.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StreamBoxApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var addonManager: AddonManager
        private set
    lateinit var pluginManager: PluginManager
        private set

    override fun onCreate() {
        super.onCreate()
        addonManager = AddonManager(this)
        pluginManager = PluginManager(this)

        // Restore installed addons in the background; UI observes readiness.
        appScope.launch { addonManager.init() }
    }
}
