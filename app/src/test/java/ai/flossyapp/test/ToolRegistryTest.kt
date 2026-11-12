package ai.flossyapp.test

import ai.flossyapp.data.tools.ToolRegistry
import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.ToolInstance
import ai.flossyapp.domain.model.ToolsConfig
import ai.flossyapp.domain.model.ToolTypes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit tests for the tool registry seam: what the model is offered, what a
 * named call dispatches to, and how enable/configuration gating works. No
 * network is involved - the MCP paths only exercise the empty and unknown
 * shapes here.
 */
class ToolRegistryTest {

    private fun settingsWith(vararg instances: ToolInstance): AppSettings =
        AppSettings(toolsConfig = ToolsConfig(instances.toList()).toJson())

    @Test
    fun disabledInstancesAreNotAdvertised() {
        val settings = settingsWith(
            ToolInstance("d", ToolTypes.TYPE_ROLL_DICE, false, "{}")
        )
        val advertised = runBlocking { ToolRegistry.advertisedTools(settings) }
        assert(advertised.isEmpty()) { "a disabled tool is not offered: $advertised" }
    }

    @Test
    fun unconfiguredInstancesAreNotAdvertised() {
        val settings = settingsWith(
            ToolInstance("s", ToolTypes.TYPE_SEARXNG, true, "{}")
        )
        val advertised = runBlocking { ToolRegistry.advertisedTools(settings) }
        assert(advertised.isEmpty()) { "a tool without its settings is not offered: $advertised" }
    }

    @Test
    fun internalToolsAreAdvertisedByTheirNames() {
        val settings = settingsWith(*ToolsConfig.default().instances.toTypedArray())
        val advertised = runBlocking { ToolRegistry.advertisedTools(settings) }
        val names = advertised.map { it["name"] }.toSet()
        // get_url ships disabled on purpose (the user's own phone is not
        // a page the model should fetch unasked), so it is not advertised.
        assert(names == setOf("end_call", "get_datetime", "get_locale", "roll_dice")) {
            "the enabled internal tools, by name: $names"
        }
        val enabled = settingsWith(
            ToolInstance("u", ToolTypes.TYPE_GET_URL, true, "{}")
        )
        val urlAdvertised = runBlocking { ToolRegistry.advertisedTools(enabled) }
        assert(urlAdvertised.map { it["name"] } == listOf("get_url")) {
            "once enabled, get_url is offered: $urlAdvertised"
        }
        // Every descriptor carries a schema the request builder can splice in.
        assert(advertised.all { (it["parameters"] ?: "").startsWith("{") }) { "schemas are present: $advertised" }
    }

    @Test
    fun rollDiceDispatchesAndAnswers() {
        val settings = settingsWith(ToolInstance("d", ToolTypes.TYPE_ROLL_DICE, true, "{}"))
        val answer = runBlocking {
            ToolRegistry.runNamedTool(settings, "roll_dice", "{\"dice\": \"3d8+4\"}")
        }
        assert(answer.startsWith("3d8+4 ->")) { "the dice parser answered: $answer" }
    }

    @Test
    fun endCallGoesThroughTheCallback() {
        val settings = settingsWith(ToolInstance("e", ToolTypes.TYPE_END_CALL, true, "{}"))
        var hung = false
        val answer = runBlocking {
            ToolRegistry.runNamedTool(settings, "end_call", "{}") { hung = true }
        }
        assert(hung) { "the tool ended the call through the surface's own end point" }
        assert(answer.contains("ended")) { "the model is told the call ended: $answer" }
    }

    @Test
    fun endCallWithoutCallbackSaysSo() {
        val settings = settingsWith(ToolInstance("e", ToolTypes.TYPE_END_CALL, true, "{}"))
        val answer = runBlocking {
            ToolRegistry.runNamedTool(settings, "end_call", "{}", onEndCall = null)
        }
        assert(!answer.contains("has been ended")) { "the model is not lied to: $answer" }
    }

    @Test
    fun unknownNamesAreAnsweredAsText() {
        val settings = settingsWith(ToolInstance("d", ToolTypes.TYPE_ROLL_DICE, true, "{}"))
        val answer = runBlocking {
            ToolRegistry.runNamedTool(settings, "nonexistent_tool", "{}")
        }
        assert(answer.isNotBlank() && !answer.contains("Exception")) { "a readable refusal: $answer" }
    }

    @Test
    fun datetimeAndLocaleAnswerSomething() {
        val settings = settingsWith(*ToolsConfig.default().instances.toTypedArray())
        val datetime = runBlocking { ToolRegistry.runNamedTool(settings, "get_datetime", "{}") }
        assert(datetime.contains("zone") || datetime.contains("UTC")) { "the datetime answer names a zone: $datetime" }
        val locale = runBlocking { ToolRegistry.runNamedTool(settings, "get_locale", "{}") }
        assert(locale.contains("locale")) { "the locale answer names the locale: $locale" }
    }

    @Test
    fun getUrlRequiresAnHttpUrl() {
        val settings = settingsWith(ToolInstance("u", ToolTypes.TYPE_GET_URL, true, "{}"))
        val refused = runBlocking { ToolRegistry.runNamedTool(settings, "get_url", "{\"url\": \"ftp://x\"}") }
        assert(refused.contains("http")) { "non-http is refused: $refused" }
        val empty = runBlocking { ToolRegistry.runNamedTool(settings, "get_url", "{}") }
        assert(empty.isNotBlank()) { "a missing url is refused, not crashed: $empty" }
    }

    @Test
    fun searchInstancesAreNamedByType() {
        val settings = settingsWith(
            ToolInstance("s1", ToolTypes.TYPE_SEARXNG, true, "{\"url\": \"http://box:8888\"}"),
            ToolInstance("s2", ToolTypes.TYPE_SEARXNG, true, "{\"url\": \"http://box2:8888\"}")
        )
        val advertised = runBlocking { ToolRegistry.advertisedTools(settings) }
        val names = advertised.map { it["name"] }
        assert(names == listOf("searxng", "searxng_2")) {
            "the first instance keeps the plain name, the second is numbered: $names"
        }
        // And the numbered name is the one that dispatches.
        val byName = runBlocking {
            ToolRegistry.runNamedTool(settings, "searxng_2", "{}")
        }
        assert(byName.isNotBlank()) { "the second instance answers to its name: $byName" }
    }
}
