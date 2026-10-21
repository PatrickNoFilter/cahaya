package com.patrisius.cahaya.plugin;

/**
 * A Cahaya native plugin: an HTTP service implementing the plugin contract
 * (see bootstrap/PLUGIN_CONTRACT.md). Installed from its base URL; the
 * manifest at {base}/plugin.json describes it.
 */
public class Plugin {
    public final String baseUrl;
    public String name;
    public String version;
    public String description;
    public boolean enabled;

    public Plugin(String baseUrl) {
        this.baseUrl = baseUrl;
        this.name = baseUrl;
        this.version = "?";
        this.description = "";
        this.enabled = true;
    }
}
