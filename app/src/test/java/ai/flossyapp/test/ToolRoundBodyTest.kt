package ai.flossyapp.test

import ai.flossyapp.data.remote.ApiClient
import org.json.JSONObject
import org.junit.Test

/**
 * Unit tests for the body a tool round sends back to the server.
 *
 * The assistant turn's "tool_calls" must be a real JSON array of call objects,
 * never a quoted string: OpenAI-compatible servers parse the message strictly,
 * and a stringified array is refused with "Missing tool call type", which used
 * to kill every second tool round - the model asked for the time, the tool
 * answered, and the follow-up request died before any reply was spoken.
 */
class ToolRoundBodyTest {

    private val call = ApiClient.ToolCall(id = "call-1", name = "get_datetime", arguments = "{}")

    private fun parse(body: String): JSONObject = JSONObject(body)

    @Test
    fun assistantTurnCarriesAToolCallsArray() {
        val turn = ApiClient.assistantToolCallTurn(
            ai.flossyapp.data.remote.ApiClient.ChatResponse(content = "", toolCalls = listOf(call))
        )
        val body = ApiClient.buildChatRequestBody(
            model = "m",
            messages = listOf(turn),
            temperature = 0.7f,
            maxTokens = 1024,
            stream = true
        )
        val messages = parse(body).getJSONArray("messages")
        val assistant = messages.getJSONObject(0)
        // The field must parse as an array, not a string.
        val calls = assistant.getJSONArray("tool_calls")
        assert(calls.length() == 1) { "one call object: $calls" }
        val node = calls.getJSONObject(0)
        assert(node.getString("id") == "call-1") { "the call id survives: $node" }
        assert(node.getString("type") == "function") { "the type field is present: $node" }
        val fn = node.getJSONObject("function")
        assert(fn.getString("name") == "get_datetime") { "the name survives: $fn" }
        assert(fn.getString("arguments") == "{}") { "the arguments survive: $fn" }
    }

    @Test
    fun toolResultTurnIsAnswerable() {
        val turn = ApiClient.assistantToolCallTurn(
            ai.flossyapp.data.remote.ApiClient.ChatResponse(content = "", toolCalls = listOf(call))
        )
        val toolTurn = mapOf(
            "role" to "tool",
            "tool_call_id" to "call-1",
            "content" to "The current local time is 2026-01-01T00:00:00."
        )
        val body = ApiClient.buildChatRequestBody(
            model = "m",
            messages = listOf(turn, toolTurn),
            temperature = 0.7f,
            maxTokens = 1024,
            stream = true
        )
        val messages = parse(body).getJSONArray("messages")
        assert(messages.length() == 2)
        val result = messages.getJSONObject(1)
        assert(result.getString("tool_call_id") == "call-1") { "the result names its call: $result" }
        assert(result.getString("content").isNotEmpty()) { "the result carries text: $result" }
        // The whole two-round body parses as one JSON document - the shape
        // the server refused before the fix.
        parse(body)
    }

    @Test
    fun plainContentIsStillAString() {
        val body = ApiClient.buildChatRequestBody(
            model = "m",
            messages = listOf(mapOf("role" to "user", "content" to "hi")),
            temperature = 0.7f,
            maxTokens = 1024,
            stream = true
        )
        val content = parse(body).getJSONArray("messages").getJSONObject(0).get("content")
        assert(content is String) { "a plain turn's content is a string: ${content::class}" }
    }

    @Test
    fun argumentsWithQuotesSurviveTheRoundTrip() {
        val nested = ApiClient.ToolCall(id = "call-2", name = "roll_dice", arguments = "{\"dice\":\"3d8+4\"}")
        val turn = ApiClient.assistantToolCallTurn(
            ai.flossyapp.data.remote.ApiClient.ChatResponse(content = "", toolCalls = listOf(nested))
        )
        val body = ApiClient.buildChatRequestBody(
            model = "m",
            messages = listOf(turn),
            temperature = 0.7f,
            maxTokens = 1024,
            stream = true
        )
        val fn = parse(body).getJSONArray("messages").getJSONObject(0)
            .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
        assert(fn.getString("arguments") == "{\"dice\":\"3d8+4\"}") { "nested JSON survives: $fn" }
    }

    @Test
    fun multipleToolsAreOneJsonArray() {
        // The tools list is a JSON array of schemas: objects joined by commas
        // alone are invalid JSON with two or more tools ("unexpected '{';
        // expected string literal" - the second object reads as an object
        // key), which refused every request that had more than one tool
        // enabled. One tool alone parses, so the bug hid until a second was
        // turned on.
        val body = ApiClient.buildChatRequestBody(
            model = "m",
            messages = listOf(mapOf("role" to "user", "content" to "hi")),
            temperature = 0.7f,
            maxTokens = 1024,
            stream = true,
            tools = listOf(
                ApiClient.ToolSchema(name = "get_datetime", description = "time", parametersJson = "{}"),
                ApiClient.ToolSchema(name = "searxng", description = "search", parametersJson = "{}")
            )
        )
        val root = parse(body)
        val tools = root.getJSONArray("tools")
        assert(tools.length() == 2) { "two schemas in one array: $tools" }
        assert(tools.getJSONObject(0).getJSONObject("function").getString("name") == "get_datetime")
        assert(tools.getJSONObject(1).getJSONObject("function").getString("name") == "searxng")
    }
}
