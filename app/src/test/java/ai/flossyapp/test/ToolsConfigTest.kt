package ai.flossyapp.test

import ai.flossyapp.domain.model.ToolInstance
import ai.flossyapp.domain.model.ToolsConfig
import ai.flossyapp.domain.model.ToolTypes
import org.junit.Test

/**
 * Unit tests for the structured tool document: its JSON round-trip, the
 * migration from the flat MCP settings, and the shipped defaults.
 */
class ToolsConfigTest {

    @Test
    fun roundTripKeepsEveryInstance() {
        val config = ToolsConfig(
            listOf(
                ToolInstance("a", ToolTypes.TYPE_ROLL_DICE, true, "{}"),
                ToolInstance("b", ToolTypes.TYPE_SEARXNG, false, "{\"url\":\"http://box:8888\"}"),
                ToolInstance("c", ToolTypes.TYPE_MCP, true, "{\"url\":\"http://box/mcp\",\"lazy\":true}")
            )
        )
        val read = ToolsConfig.fromJson(config.toJson())
        assert(read.instances.size == 3) { "all three instances survive: ${read.instances}" }
        val searxng = read.instances.first { it.id == "b" }
        assert(searxng.searxngUrl == "http://box:8888") { "the searxng url survives: ${searxng.settingsJson}" }
        assert(!searxng.enabled) { "the disabled switch survives" }
        val mcp = read.instances.first { it.id == "c" }
        assert(mcp.mcpUrl == "http://box/mcp" && mcp.mcpLazy) { "mcp settings survive: ${mcp.settingsJson}" }
    }

    @Test
    fun corruptDocumentBecomesEmpty() {
        assert(ToolsConfig.fromJson("not json").instances.isEmpty())
        assert(ToolsConfig.fromJson("{\"instances\": \"nope\"}").instances.isEmpty())
        assert(ToolsConfig.fromJson("").instances.isEmpty())
    }

    @Test
    fun instancesWithoutIdOrTypeAreDropped() {
        val config = ToolsConfig(
            listOf(
                ToolInstance("", ToolTypes.TYPE_ROLL_DICE, true, "{}"),
                ToolInstance("x", "", true, "{}"),
                ToolInstance("y", ToolTypes.TYPE_ROLL_DICE, true, "{}")
            )
        )
        val read = ToolsConfig.fromJson(config.toJson())
        assert(read.instances.size == 1 && read.instances[0].id == "y") { "addressable instances only: ${read.instances}" }
    }

    @Test
    fun migrationTurnsLegacyServersIntoInstances() {
        val migrated = ToolsConfig.migrateFromLegacy(true, "http://a/mcp\nhttp://b/mcp")
        val mcp = migrated.instances.filter { it.type == ToolTypes.TYPE_MCP }
        assert(mcp.size == 2) { "one instance per server: ${mcp}" }
        assert(mcp.all { it.enabled }) { "the old switch was on" }
        // And every internal tool is in place too.
        assert(migrated.instances.count { it.type != ToolTypes.TYPE_MCP } == 5) {
            "the five internal tools ship: ${migrated.instances}"
        }
    }

    @Test
    fun migrationWithCommaSeparatedUrls() {
        val migrated = ToolsConfig.migrateFromLegacy(false, "http://a/mcp,http://b/mcp")
        assert(migrated.instances.filter { it.type == ToolTypes.TYPE_MCP }.size == 2)
        assert(migrated.instances.filter { it.type == ToolTypes.TYPE_MCP }.all { !it.enabled }) {
            "the old switch was off"
        }
    }

    @Test
    fun defaultsEnableEverythingButUrlRetrieval() {
        val defaults = ToolsConfig.default()
        val getUrl = defaults.instances.first { it.type == ToolTypes.TYPE_GET_URL }
        assert(!getUrl.enabled) { "URL retrieval starts off" }
        assert(defaults.instances.filter { it.type != ToolTypes.TYPE_GET_URL }.all { it.enabled }) {
            "every other internal tool starts on: ${defaults.instances}"
        }
        assert(defaults.instances.size == 5)
    }

    @Test
    fun configurationGatingPerType() {
        assert(!ToolInstance("s", ToolTypes.TYPE_SEARXNG, true, "{}").configured) {
            "a searxng instance without a url is not configured"
        }
        assert(ToolInstance("s", ToolTypes.TYPE_SEARXNG, true, "{\"url\":\"http://x\"}").configured)
        assert(!ToolInstance("b", ToolTypes.TYPE_BRAVE, true, "{}").configured)
        assert(ToolInstance("b", ToolTypes.TYPE_BRAVE, true, "{\"apiKey\":\"k\"}").configured)
        assert(!ToolInstance("m", ToolTypes.TYPE_MCP, true, "{}").configured)
        assert(ToolInstance("d", ToolTypes.TYPE_ROLL_DICE, true, "{}").configured) {
            "internal tools need no settings"
        }
    }
}
