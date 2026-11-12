package ai.flossyapp.data.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A minimal client for Model Context Protocol servers that speak streamable HTTP.
 *
 * Only the two calls the assistant needs are implemented: `tools/list`, to see what a server
 * offers, and `tools/call`, to run one of them. There is no stdio transport, because Android has
 * no process to spawn - a server has to be reachable over HTTP(S). Prompts, resources and
 * elicitation are left out on purpose: the assistant only ever consumes tools.
 *
 * Every entry a server reports is returned as a plain map so the conversation services can hold
 * tools without knowing the shapes used inside the HTTP client.
 */
object McpClient {

    // Discovery sits on the critical path of a request, so a dead server is given a short
    // allowance - it must fail the tool list, not stall the exchange.
    private const val MCP_TIMEOUT_SECONDS = 20L

    // One meta-tool is offered in lazy mode instead of every schema, which keeps a large tool
    // list out of the model's context window.
    const val META_TOOL_NAME = "run_tool"
    const val META_TOOL_DESCRIPTION = "Run one tool offered by a configured MCP server. Pass the exact tool name and its arguments."
    const val META_TOOL_SCHEMA = "{\"type\": \"object\", \"properties\": {\"tool\": {\"type\": \"string\", \"description\": \"Exact name of the tool to run\"}, \"arguments\": {\"type\": \"object\", \"description\": \"Arguments for that tool\"}}, \"required\": [\"tool\"]}"

    private var lastRequestId = 0

    /**
     * The JSON-RPC body for one MCP method, plus the session id the server handed back.
     *
     * A streamable HTTP server may answer either a plain JSON body or an event stream, so both
     * shapes are unwrapped here. An empty result means the call failed; the reason is logged.
     */
    private suspend fun rpc(endpoint: String, method: String, paramsJson: String, sessionId: String): Pair<String, String> {
        lastRequestId += 1
        val body = "{\"jsonrpc\": \"2.0\", \"id\": $lastRequestId, \"method\": \"$method\", \"params\": $paramsJson}"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (sessionId.isNotBlank()) request.addHeader("Mcp-Session-Id", sessionId)
        try {
            val client = ApiClient.createClient(endpoint, null, MCP_TIMEOUT_SECONDS)
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("McpClient", "The MCP server at $endpoint answered ${response.code} for $method")
                    return Pair("", "")
                }
                val carried = response.headers("Mcp-Session-Id")?.firstOrNull() ?: ""
                val body = response.body ?: throw IOException("Empty response")
                return Pair(unwrapEventStream(body.string()), carried)
            }
        } catch (e: Exception) {
            android.util.Log.w("McpClient", "The MCP call $method failed: ${e.message}")
            return Pair("", "")
        }
    }

    /**
     * The JSON object out of a body that may have arrived as an SSE event stream.
     */
    private fun unwrapEventStream(text: String): String {
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("data:")) return trimmed.substring(5).trim()
        }
        return text.trim()
    }

    /**
     * Ask a server for its tools.
     *
     * The session id from `initialize` is carried into `tools/list`, which is what a stateful
     * server requires; servers that ignore sessions still work, because the header is omitted
     * when nothing came back.
     *
     * @return one entry per tool, with its `name`, `description`, `parameters` (a JSON schema,
     *         verbatim), and the `endpoint` it came from.
     */
    suspend fun discoverTools(endpoint: String): List<Map<String, String>> {
        val started = rpc(endpoint, "initialize", "{\"protocolVersion\": \"2025-06-18\", \"clientInfo\": {\"name\": \"FLOSSyAssistant\", \"version\": \"1.0\"}, \"capabilities\": {}}", "")
        val listed = rpc(endpoint, "tools/list", "{}", started.second)
        return toolRowsFrom(listed.first, endpoint)
    }

    /**
     * Run one tool a server reported and return what it answered as text.
     *
     * An empty string means the call failed - the model is told that in the tool result rather
     * than being left to invent an answer.
     */
    suspend fun callTool(endpoint: String, name: String, argumentsJson: String): String {
        val arguments = if (argumentsJson.isBlank()) "{}" else argumentsJson
        val started = rpc(endpoint, "initialize", "{\"protocolVersion\": \"2025-06-18\", \"clientInfo\": {\"name\": \"FLOSSyAssistant\", \"version\": \"1.0\"}, \"capabilities\": {}}", "")
        val result = rpc(endpoint, "tools/call", "{\"name\": \"$name\", \"arguments\": $arguments}", started.second)
        if (result.first.isEmpty()) return "The tool call failed."
        return answerFrom(result.first)
    }

    /**
     * The text a `tools/call` result carries. Content parts are concatenated; a server that
     * reports `isError` has its text surfaced verbatim so the model sees the failure.
     */
    private fun answerFrom(resultJson: String): String {
        try {
            val root = org.json.JSONObject(resultJson)
            if (root.optBoolean("isError", false)) return "The tool reported: ${root.optString("text", "")}"
            if (!root.has("content")) return root.optString("text", resultJson)
            val parts = root.getJSONArray("content")
            val texts = mutableListOf<String>()
            for (i in 0..<parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optString("type", "") == "text") texts.add(part.optString("text", ""))
            }
            return if (texts.isEmpty()) resultJson else texts.joinToString(" ")
        } catch (e: Exception) {
            android.util.Log.w("McpClient", "Could not read the tool answer: ${e.message}")
            return resultJson
        }
    }

    /**
     * Read `tools/list` into the plain maps the conversation services hold.
     *
     * A tool whose schema is missing is still offered: a model that gets no parameter shape can
     * ask for it with no arguments, which is correct for the many zero-argument tools servers
     * expose.
     */
    private fun toolRowsFrom(resultJson: String, endpoint: String): List<Map<String, String>> {
        if (resultJson.isBlank()) return emptyList()
        try {
            val root = org.json.JSONObject(resultJson)
            val tools = root.optJSONArray("tools") ?: return emptyList()
            val rows = mutableListOf<Map<String, String>>()
            for (i in 0..<tools.length()) {
                val tool = tools.getJSONObject(i)
                val schema = tool.optJSONObject("inputSchema")
                rows.add(
                    mapOf(
                        "name" to tool.optString("name", ""),
                        "description" to tool.optString("description", ""),
                        "parameters" to if (schema == null) "{\"type\": \"object\", \"properties\": {}}" else schema.toString(),
                        "endpoint" to endpoint
                    )
                )
            }
            return rows
        } catch (e: Exception) {
            android.util.Log.w("McpClient", "Could not read the tool list: ${e.message}")
            return emptyList()
        }
    }

    // Which server answers to which advertised name, recorded by the last discovery so a call
    // is dispatched without asking the servers again. Three parallel lists, because the project
    // holds no mutable maps.
    private var routeAdvertised: List<String> = emptyList()
    private var routePlain: List<String> = emptyList()
    private var routeEndpoints: List<String> = emptyList()

    // Two servers can offer a tool under the same name, so the position in the configured list
    // prefixes the advertised name - the model reads that name back verbatim.
    const val NAME_SEPARATOR = "__"

    /**
     * What every configured server offers, in the shape the model is shown.
     *
     * Eager mode advertises each server's schemas, namespaced per server. Lazy mode advertises one
     * meta-tool whose description carries the names, which is what keeps a large tool list out of
     * the model's context window. Either way the routes to the real tools are recorded, so the
     * call path is identical.
     *
     * A server that cannot be reached contributes nothing but a log line, so one dead server does
     * not hide the tools of the others.
     *
     * @param serverUrls one endpoint per line, or separated by commas.
     */
    suspend fun advertisedTools(serverUrls: String, lazy: Boolean): List<Map<String, String>> {
        val urls = serverUrls.replace(",", "\n").split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val advertised = mutableListOf<Map<String, String>>()
        val names = mutableListOf<String>()
        val plain = mutableListOf<String>()
        val endpoints = mutableListOf<String>()
        for (index in urls.indices) {
            val url = urls[index]
            for (row in discoverTools(url)) {
                val offered = row["name"] ?: ""
                if (offered.isEmpty()) continue
                // A single server keeps the names the server itself documents, which is what the
                // user sees when they browse a server's tools.
                val name = if (urls.size > 1) "s${index + 1}$NAME_SEPARATOR$offered" else offered
                names.add(name)
                plain.add(offered)
                endpoints.add(url)
                advertised.add(
                    mapOf(
                        "name" to name,
                        "description" to (row["description"] ?: ""),
                        "parameters" to (row["parameters"] ?: "{\"type\": \"object\", \"properties\": {}}")
                    )
                )
            }
        }
        routeAdvertised = names
        routePlain = plain
        routeEndpoints = endpoints
        if (!lazy) return advertised
        if (names.isEmpty()) return emptyList()
        // Lazy mode still names the tools the model can ask for, because the meta-tool alone
        // would leave the model to invent a name.
        return listOf(
            mapOf(
                "name" to META_TOOL_NAME,
                "description" to "$META_TOOL_DESCRIPTION Known tools: ${names.joinToString(", ")}",
                "parameters" to META_TOOL_SCHEMA
            )
        )
    }

    /**
     * Run the tool the model asked for, on whichever configured server offers it.
     *
     * A lazy exchange names the meta-tool and carries the real name inside its arguments, so the
     * lookup uses that inner name. A tool nobody offers answers as text rather than throwing: the
     * model reads the failure and can say so in words instead of inventing a result.
     */
    suspend fun runNamedTool(serverUrls: String, name: String, argumentsJson: String): String {
        var toolName = name
        var arguments = argumentsJson
        if (name == META_TOOL_NAME) {
            toolName = metaToolNameOf(argumentsJson)
            arguments = metaToolArgumentsOf(argumentsJson)
        }
        if (toolName.isEmpty()) return "The model asked for a tool without naming one."
        var index = routeIndexOf(toolName)
        if (index < 0) {
            // The routes may predate a settings change, so one rediscovery is allowed per call.
            advertisedTools(serverUrls, true)
            index = routeIndexOf(toolName)
        }
        if (index < 0) return "No configured MCP server offers a tool called $toolName."
        return callTool(routeEndpoints[index], routePlain[index], arguments)
    }

    /**
     * The position of an advertised tool in the recorded routes, or -1 when nothing offers it.
     */
    private fun routeIndexOf(name: String): Int {
        for (i in routeAdvertised.indices) if (routeAdvertised[i] == name) return i
        return -1
    }

    /**
     * The tool name a meta-tool call carries.
     */
    private fun metaToolNameOf(argumentsJson: String): String {
        try {
            val root = org.json.JSONObject(argumentsJson)
            return root.optString("tool", "")
        } catch (e: Exception) {
            android.util.Log.w("McpClient", "The meta-tool call could not be read: ${e.message}")
            return ""
        }
    }

    /**
     * The arguments of the tool a meta-tool call names.
     */
    private fun metaToolArgumentsOf(argumentsJson: String): String {
        try {
            val root = org.json.JSONObject(argumentsJson)
            val inner = root.optJSONObject("arguments") ?: return "{}"
            return inner.toString()
        } catch (e: Exception) {
            return "{}"
        }
    }
}
