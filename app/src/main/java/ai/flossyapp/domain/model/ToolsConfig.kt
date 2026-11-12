package ai.flossyapp.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * One configurable tool the model may be offered.
 *
 * Every tool - an internal handler, a search engine, an MCP server - is an
 * instance of a type named by [type], carrying its own switch and its own
 * settings document. The instance is what the Tools tab lists: a row with an
 * enable check, a cog for its settings and a remove button, not a global form.
 *
 * [settingsJson] holds whatever the type requires (a SearXNG URL, a Brave
 * token, an MCP endpoint) as a small JSON object; a type with no settings
 * carries "{}".
 */
data class ToolInstance(
    val id: String,
    val type: String,
    val enabled: Boolean,
    val settingsJson: String
) {
    /** A setting from the instance's own document, or empty when absent. */
    fun setting(key: String): String {
        try {
            return JSONObject(settingsJson).optString(key, "")
        } catch (_: Exception) {
            return ""
        }
    }

    fun booleanSetting(key: String, default: Boolean = false): Boolean {
        try {
            val node = JSONObject(settingsJson)
            return if (!node.has(key)) default else node.optBoolean(key, default)
        } catch (_: Exception) {
            return default
        }
    }

    private fun withSetting(key: String, value: Any): ToolInstance {
        val node = try { JSONObject(settingsJson) } catch (_: Exception) { JSONObject() }
        node.put(key, value)
        return copy(settingsJson = node.toString())
    }

    val searxngUrl: String get() = setting("url")
    val braveApiKey: String get() = setting("apiKey")
    val mcpUrl: String get() = setting("url")
    val mcpLazy: Boolean get() = booleanSetting("lazy", true)

    /**
     * Whether the type's required settings are populated. An unconfigured
     * instance can sit in the list but its enable check is ghosted: a tool
     * the model cannot reach must never be advertised.
     */
    val configured: Boolean
        get() = when (type) {
            ToolTypes.TYPE_SEARXNG -> searxngUrl.isNotBlank()
            ToolTypes.TYPE_BRAVE -> braveApiKey.isNotBlank()
            ToolTypes.TYPE_MCP -> mcpUrl.isNotBlank()
            else -> true
        }

    fun withSearxngUrl(url: String) = withSetting("url", url)
    fun withBraveApiKey(key: String) = withSetting("apiKey", key)
    fun withMcpUrl(url: String) = withSetting("url", url)
    fun withMcpLazy(lazy: Boolean) = withSetting("lazy", lazy)

    /**
     * What the user reads in the Tools tab. Falls back to the raw type for a
     * future type that forgot its label, so a row never renders blank.
     */
    val displayName: String get() = ToolTypes.byType(type)?.displayName ?: type
}

/**
 * The catalogue of tool types the app supports, which drives the add-button
 * list, the cog dialogs and the enable gating.
 *
 * [multiInstance] types (search engines, MCP servers) can be added several
 * times; a single-instance type appears in the add list only while absent.
 */
object ToolTypes {
    const val TYPE_END_CALL = "end_call"
    const val TYPE_GET_DATETIME = "get_datetime"
    const val TYPE_GET_LOCALE = "get_locale"
    const val TYPE_GET_URL = "get_url"
    const val TYPE_ROLL_DICE = "roll_dice"
    const val TYPE_SEARXNG = "searxng"
    const val TYPE_BRAVE = "brave"
    const val TYPE_MCP = "mcp"

    data class Spec(
        val type: String,
        val displayName: String,
        val description: String,
        val multiInstance: Boolean
    )

    val ALL: List<Spec> = listOf(
        Spec(TYPE_END_CALL, "End call", "Let the model end the voice call when the user says they are done", false),
        Spec(TYPE_GET_DATETIME, "Date & time", "The current date and time, with timezone and UTC offset", false),
        Spec(TYPE_GET_LOCALE, "Locale", "The device and app locales the assistant should answer in", false),
        Spec(TYPE_GET_URL, "Fetch URL", "Retrieve a web page the model is given, with strict size limits", false),
        Spec(TYPE_ROLL_DICE, "Dice roller", "Roll dice expressed as NdM+K, for example 3d8+4", false),
        Spec(TYPE_SEARXNG, "SearXNG search", "Search the web through a SearXNG instance returning JSON", true),
        Spec(TYPE_BRAVE, "Brave search", "Search the web through the Brave Search API", true),
        Spec(TYPE_MCP, "MCP server", "Every tool one Model Context Protocol server offers", true)
    )

    fun byType(type: String): Spec? = ALL.firstOrNull { it.type == type }
}

/**
 * The whole tool configuration: the instance list the Tools tab edits and the
 * JSON document persisted under one settings key.
 *
 * One document instead of a field per tool, because the instance list grows
 * with the user's own servers, and because a preset then carries the tools
 * like any other setting through the existing per-field machinery.
 */
data class ToolsConfig(val instances: List<ToolInstance> = emptyList()) {

    fun toJson(): String {
        val array = JSONArray()
        for (instance in instances) {
            val node = JSONObject()
            node.put("id", instance.id)
            node.put("type", instance.type)
            node.put("enabled", instance.enabled)
            node.put("settings", JSONObject(instance.settingsJson.ifBlank { "{}" }))
            array.put(node)
        }
        // put() is not chained: a stubbed org.json answers null for it in
        // the unit tests, and the document must build the same way on the
        // device and on the JVM.
        val root = JSONObject()
        root.put("instances", array)
        return root.toString()
    }

    companion object {
        /**
         * Reads a stored document. Anything unreadable becomes an empty list
         * rather than a crash: a corrupt document must not take the settings
         * screen down.
         */
        fun fromJson(json: String): ToolsConfig {
            if (json.isBlank()) return ToolsConfig()
            try {
                val array = JSONObject(json).optJSONArray("instances") ?: return ToolsConfig()
                val instances = mutableListOf<ToolInstance>()
                for (i in 0 until array.length()) {
                    val node = array.getJSONObject(i)
                    instances.add(
                        ToolInstance(
                            id = node.optString("id", ""),
                            type = node.optString("type", ""),
                            enabled = node.optBoolean("enabled", false),
                            settingsJson = node.optJSONObject("settings")?.toString() ?: "{}"
                        )
                    )
                }
                // An instance with no id is unaddressable - drop it rather than
                // let two rows answer to the same taps.
                return ToolsConfig(instances.filter { it.id.isNotBlank() && it.type.isNotBlank() })
            } catch (_: Exception) {
                return ToolsConfig()
            }
        }

        /**
         * The shipped configuration: every internal tool present and enabled,
         * except URL retrieval - fetching a page is the one tool that can
         * pour a large document into the context, so it starts off.
         */
        fun default(): ToolsConfig = ToolsConfig(
            listOf(
                ToolInstance("internal-end-call", ToolTypes.TYPE_END_CALL, true, "{}"),
                ToolInstance("internal-get-datetime", ToolTypes.TYPE_GET_DATETIME, true, "{}"),
                ToolInstance("internal-get-locale", ToolTypes.TYPE_GET_LOCALE, true, "{}"),
                ToolInstance("internal-get-url", ToolTypes.TYPE_GET_URL, false, "{}"),
                ToolInstance("internal-roll-dice", ToolTypes.TYPE_ROLL_DICE, true, "{}")
            )
        )

        /**
         * A first-run upgrade from the legacy flat MCP settings: every
         * configured server becomes an MCP instance, enabled when the old
         * switch was on.
         */
        fun migrateFromLegacy(enableMcpTools: Boolean, serverUrls: String): ToolsConfig {
            val urls = serverUrls.replace(",", "\n").split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val instances = mutableListOf<ToolInstance>()
            urls.forEachIndexed { index, url ->
                val node = JSONObject().put("url", url)
                instances.add(
                    ToolInstance(
                        id = "mcp-$index",
                        type = ToolTypes.TYPE_MCP,
                        enabled = enableMcpTools,
                        settingsJson = node.toString()
                    )
                )
            }
            return ToolsConfig(default().instances + instances)
        }
    }
}
