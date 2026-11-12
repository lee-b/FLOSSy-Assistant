package ai.flossyapp.test

import ai.flossyapp.data.settings.SettingsRepository
import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.ToolInstance
import ai.flossyapp.domain.model.ToolsConfig
import ai.flossyapp.domain.model.ToolTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for the tool configuration's persistence: the Tools
 * tab's settings are SharedPreferences documents, and only a device can
 * prove that what the Apply bar writes is what the next process reads.
 */
@RunWith(AndroidJUnit4::class)
class ToolsSettingsPersistenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        // A clean slate per test: the SharedPreferences file is deleted so
        // each test sees exactly what it writes.
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        repository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun load(): AppSettings = repository.loadSettings()

    @Test
    fun aFreshInstallShipsTheDefaultTools() {
        val settings = load()
        assertTrue("the migration ran on first read", settings.toolsConfig.isNotBlank())
        assertEquals(5, settings.toolInstances.size)
        assertFalse("URL retrieval starts off", settings.toolInstances.first { it.type == ToolTypes.TYPE_GET_URL }.enabled)
        assertTrue("the dice tool starts on", settings.toolInstances.first { it.type == ToolTypes.TYPE_ROLL_DICE }.enabled)
    }

    @Test
    fun anEditSurvivesAReRead() {
        val edited = ToolsConfig(
            instances = load().toolInstances + ToolInstance(
                "test-searxng", ToolTypes.TYPE_SEARXNG, true,
                "{\"url\": \"http://box:8888\"}"
            )
        ).toJson()
        repository.updateSettings(load().copy(toolsConfig = edited))

        val reloaded = load()
        val searxng = reloaded.toolInstances.firstOrNull { it.id == "test-searxng" }
        assertTrue("the added instance persisted", searxng != null)
        assertEquals("http://box:8888", searxng?.searxngUrl)
        assertTrue("the added instance is enabled", searxng?.enabled == true)
    }

    @Test
    fun legacyMcpSettingsUpgradeIntoInstancesOnce() {
        // An old install: MCP on with two servers, no tools document yet.
        // The keys land before the repository is constructed, because its
        // constructor already runs the first read - and with it the upgrade.
        // (setUp's own repository construction eagerly writes a default
        // document, so this test clears that first.)
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enable_mcp_tools", true)
            .putString("mcp_server_urls", "http://a/mcp\nhttp://b/mcp")
            .commit()
        val legacy = SettingsRepository(context)
        val migrated = legacy.loadSettings()
        assertEquals(2, migrated.toolInstances.count { it.type == ToolTypes.TYPE_MCP })
        assertTrue(migrated.toolInstances.filter { it.type == ToolTypes.TYPE_MCP }.all { it.enabled })

        // The upgrade must not run a second migration on the next read.
        val stable = load()
        assertEquals(migrated.toolInstances, stable.toolInstances)
    }

    @Test
    fun factoryResetReturnsToTheShippedDefaults() {
        val edited = load().copy(
            toolsConfig = ToolsConfig(
                listOf(ToolInstance("only", ToolTypes.TYPE_ROLL_DICE, false, "{}"))
            ).toJson()
        )
        repository.updateSettings(edited)
        repository.resetToDefaults()
        val reset = load()
        assertEquals(5, reset.toolInstances.size)
        assertTrue(reset.toolInstances.first { it.type == ToolTypes.TYPE_ROLL_DICE }.enabled)
    }
}
