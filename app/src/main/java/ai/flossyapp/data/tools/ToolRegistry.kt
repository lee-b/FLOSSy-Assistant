package ai.flossyapp.data.tools

import ai.flossyapp.data.remote.McpClient
import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.ToolInstance
import ai.flossyapp.domain.model.ToolTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The one seam the model's tools flow through: descriptors are built here and
 * every call is dispatched here, whether the tool is an internal handler, a
 * search engine or an MCP server.
 *
 * There is no internal MCP server: an internal tool is a Kotlin suspend
 * function behind a descriptor, exactly the shape `runTool` already takes.
 * The model cannot tell the difference, and neither can the conversation
 * services - both keep asking [advertisedTools] for the list and
 * [runNamedTool] for a run.
 */
object ToolRegistry {

    /** One finished tool run, as the conversation shows it. */
    data class ToolCallEvent(val name: String, val arguments: String, val result: String)

    /**
     * Every completed tool run flows here once: the chat screen and the wake
     * card both draw the call as a compact row with tap-to-expand detail, and
     * this hub is the one seam that reaches them without the registry knowing
     * either surface. Never replayed to late subscribers - a run belongs to
     * the exchange that made it.
     */
    val toolCallEvents = kotlinx.coroutines.flow.MutableSharedFlow<ToolCallEvent>(
        extraBufferCapacity = 8
    )

    // Fetch limits: a web page can be megabytes, and pouring one into a small
    // local model's context starves the reply long before the phone complains.
    private const val FETCH_TIMEOUT_SECONDS = 15L
    private const val FETCH_MAX_BYTES = 256_000L
    private const val FETCH_MAX_CHARS = 6_000

    // A search answer lists a handful of findings, not a page of them: five
    // results with a trimmed snippet each is what a spoken reply can carry.
    private const val SEARCH_RESULT_COUNT = 5
    private const val SEARCH_SNIPPET_CHARS = 250

    // ---------- Descriptors ----------

    /**
     * Every tool the model may be offered: enabled, configured instances only.
     *
     * [onEndCall] is the surface's own end point (the engine's hang-up), handed
     * to the one tool that needs to act on the call instead of knowing the
     * engine. An MCP tool that shares a name with an internal one loses: the
     * internal handler is the one the model reaches.
     */
    suspend fun advertisedTools(settings: AppSettings, onEndCall: (() -> Unit)? = null): List<Map<String, String>> {
        val instances = settings.toolInstances.filter { it.enabled && it.configured }
        val names = assignedNames(instances)
        val internal = instances.filter { isInternal(it) }.map { descriptorFor(it, names[it.id] ?: it.id, onEndCall) }
        val search = instances.filter { isSearch(it) }.map { descriptorFor(it, names[it.id] ?: it.id) }
        val mcpUrls = instances.filter { it.type == ToolTypes.TYPE_MCP }
        val mcp = if (mcpUrls.isEmpty()) emptyList() else {
            val urls = mcpUrls.joinToString("\n") { it.mcpUrl }
            // Lazy mode is a context-window economy, so one eager server makes
            // the whole list eager: mixing would hide schemas the model needs.
            val lazy = mcpUrls.all { it.mcpLazy }
            McpClient.advertisedTools(urls, lazy)
        }
        return internal + search + mcp
    }

    /**
     * Runs one named tool and answers with text, never by throwing: the model
     * reads the failure and can say so in words.
     */
    suspend fun runNamedTool(settings: AppSettings, name: String, argumentsJson: String, onEndCall: (() -> Unit)? = null): String {
        // Announce the run once it has finished, whatever the result: the
        // conversation surfaces draw the call, so a silent success and a
        // silent failure are equally invisible.
        val result = runNamedToolInternal(settings, name, argumentsJson, onEndCall)
        toolCallEvents.tryEmit(ToolCallEvent(name, argumentsJson, result))
        return result
    }

    private suspend fun runNamedToolInternal(settings: AppSettings, name: String, argumentsJson: String, onEndCall: (() -> Unit)? = null): String {
        val instances = settings.toolInstances.filter { it.enabled && it.configured }
        val names = assignedNames(instances)
        val internal = instances.firstOrNull { isInternal(it) && names[it.id] == name }
        if (internal != null) return runInternal(internal, argumentsJson, onEndCall)
        val search = instances.firstOrNull { isSearch(it) && names[it.id] == name }
        if (search != null) return runSearch(search, argumentsJson)
        val mcpUrls = instances.filter { it.type == ToolTypes.TYPE_MCP }
        if (mcpUrls.isNotEmpty()) {
            val urls = mcpUrls.joinToString("\n") { it.mcpUrl }
            return McpClient.runNamedTool(urls, name, argumentsJson)
        }
        return "No enabled tool is called $name."
    }

    /**
     * The name the model sees for one instance. Search engines can be added
     * several times, so a second instance of a type is numbered.
     */
    /**
     * The name the model sees for each instance, by instance id. Internal
     * tools are named by their type; a repeatable search engine keeps its
     * plain type name for the first instance and is numbered ("searxng_2")
     * for the second, so two instances of one type stay distinguishable
     * instead of colliding on one name. An explicit "toolName" setting wins.
     */
    private fun assignedNames(instances: List<ToolInstance>): Map<String, String> {
        val names = mutableMapOf<String, String>()
        val seenTypes = mutableMapOf<String, Int>()
        for (instance in instances) {
            val explicit = try { JSONObject(instance.settingsJson.ifBlank { "{}" }).optString("toolName", "") } catch (_: Exception) { "" }
            names[instance.id] = if (explicit.isNotBlank()) {
                explicit
            } else if (isInternal(instance)) {
                instance.type
            } else {
                val count = seenTypes.merge(instance.type, 1) { a, b -> a + b } ?: 1
                if (count == 1) instance.type else "${instance.type}_$count"
            }
        }
        return names
    }

    private fun isInternal(instance: ToolInstance) = when (instance.type) {
        ToolTypes.TYPE_END_CALL,
        ToolTypes.TYPE_GET_DATETIME,
        ToolTypes.TYPE_GET_LOCALE,
        ToolTypes.TYPE_GET_URL,
        ToolTypes.TYPE_ROLL_DICE -> true
        else -> false
    }

    private fun isSearch(instance: ToolInstance) = when (instance.type) {
        ToolTypes.TYPE_SEARXNG, ToolTypes.TYPE_BRAVE -> true
        else -> false
    }

    // ---------- Internal tools ----------

    private fun descriptorFor(instance: ToolInstance, name: String, onEndCall: (() -> Unit)? = null): Map<String, String> {
        if (isSearch(instance)) {
            val what = if (instance.type == ToolTypes.TYPE_SEARXNG) {
                "Search the web through the configured SearXNG instance."
            } else {
                "Search the web through the Brave Search API."
            }
            return mapOf(
                "name" to name,
                "description" to what,
                "parameters" to "{\"type\": \"object\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": \"What to search for\"}}, \"required\": [\"query\"]}"
            )
        }
        val (description, parameters) = when (instance.type) {
            ToolTypes.TYPE_END_CALL -> Pair(
                "End the current voice call. Call this when the user says they are done, wants to stop, or otherwise ends the conversation.",
                "{\"type\": \"object\", \"properties\": {}}"
            )
            ToolTypes.TYPE_GET_DATETIME -> Pair(
                "The current date and time, with the timezone and its UTC offset.",
                "{\"type\": \"object\", \"properties\": {}}"
            )
            ToolTypes.TYPE_GET_LOCALE -> Pair(
                "The language and locale the device and the app are set to.",
                "{\"type\": \"object\", \"properties\": {}}"
            )
            ToolTypes.TYPE_GET_URL -> Pair(
                "Retrieve the text of one web page. Only http and https addresses are accepted, and very long pages are cut short.",
                "{\"type\": \"object\", \"properties\": {\"url\": {\"type\": \"string\", \"description\": \"The page address, including http:// or https://\"}}, \"required\": [\"url\"]}"
            )
            ToolTypes.TYPE_ROLL_DICE -> Pair(
                "Roll dice for games or decisions. The expression is a sum like 3d8+4 (three eight-sided dice plus four), d20 for one die, or 2d6-1.",
                "{\"type\": \"object\", \"properties\": {\"dice\": {\"type\": \"string\", \"description\": \"The dice expression, for example 3d8+4\"}}, \"required\": [\"dice\"]}"
            )
            else -> Pair(instance.type, "{\"type\": \"object\", \"properties\": {}}")
        }
        return mapOf("name" to name, "description" to description, "parameters" to parameters)
    }

    private suspend fun runInternal(instance: ToolInstance, argumentsJson: String, onEndCall: (() -> Unit)?): String {
        val arguments = try { JSONObject(argumentsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        return when (instance.type) {
            ToolTypes.TYPE_END_CALL -> {
                // The tool ends the interaction through the surface's own end
                // point; it does not know what a call is.
                if (onEndCall != null) {
                    onEndCall()
                    "The call has been ended."
                } else {
                    "There is no active call to end."
                }
            }
            ToolTypes.TYPE_GET_DATETIME -> {
                val now = java.time.OffsetDateTime.now()
                val zone = java.time.ZoneId.systemDefault()
                val clock = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                "The current local time is ${now.format(clock)}, " +
                    "zone ${zone.id}, offset ${now.offset}, UTC ${java.time.Instant.now()}. " +
                    "Today is ${now.dayOfWeek}."
            }
            ToolTypes.TYPE_GET_LOCALE -> {
                // LocaleList is framework code that answers null in a plain
                // JVM test, so the device default stands in when it does.
                val list = try {
                    val system = android.os.LocaleList.getDefault()
                    val locales = mutableListOf<java.util.Locale>()
                    if (system != null) {
                        for (i in 0 until system.size()) locales.add(system[i])
                    }
                    if (locales.isEmpty()) locales.add(java.util.Locale.getDefault())
                    locales
                } catch (_: Exception) {
                    listOf(java.util.Locale.getDefault())
                }
                val names = list.joinToString(", ") {
                    "${it.toLanguageTag()} (${it.displayLanguage}${if (it.displayCountry.isBlank()) "" else "/" + it.displayCountry})"
                }
                "The device locales are $names. The app answers in ${list.first().displayLanguage}."
            }
            ToolTypes.TYPE_GET_URL -> fetchUrl(arguments.optString("url", ""))
            ToolTypes.TYPE_ROLL_DICE -> DiceParser.roll(arguments.optString("dice", "1d20"))
            else -> "This tool has no handler."
        }
    }

    // ---------- Search tools ----------

    private suspend fun runSearch(instance: ToolInstance, argumentsJson: String): String {
        val arguments = try { JSONObject(argumentsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val query = arguments.optString("query", "").trim()
        if (query.isBlank()) return "The search was asked without a query."
        return when (instance.type) {
            ToolTypes.TYPE_SEARXNG -> runSearxng(instance, query)
            ToolTypes.TYPE_BRAVE -> runBrave(instance, query)
            else -> "This search engine has no handler."
        }
    }

    private suspend fun runSearxng(instance: ToolInstance, query: String): String {
        val base = instance.searxngUrl.trim().trimEnd('/')
        if (base.isBlank()) return "The SearXNG instance has no address configured."
        val url = "$base/search?q=${urlEncode(query)}&format=json&safesearch=1"
        val body = httpGet(url, mapOf("Accept" to "application/json")) ?: return "The SearXNG instance did not answer."
        return try {
            val root = JSONObject(body)
            val results = root.optJSONArray("results")
            if (results == null || results.length() == 0) return "No results for \"$query\"."
            formatResults(results, query)
        } catch (_: Exception) {
            "The SearXNG answer could not be read."
        }
    }

    private suspend fun runBrave(instance: ToolInstance, query: String): String {
        val key = instance.braveApiKey.trim()
        if (key.isBlank()) return "The Brave search has no API token configured."
        val url = "https://api.search.brave.com/res/v1/search?q=${urlEncode(query)}&count=$SEARCH_RESULT_COUNT"
        val body = httpGet(url, mapOf("Accept" to "application/json", "X-Subscription-Token" to key))
            ?: return "The Brave search did not answer."
        return try {
            val web = JSONObject(body).optJSONObject("data")?.optJSONObject("web")
            val results = web?.optJSONArray("results")
            if (results == null || results.length() == 0) return "No results for \"$query\"."
            formatResults(results, query)
        } catch (_: Exception) {
            "The Brave answer could not be read."
        }
    }

    /**
     * The first handful of results, each trimmed to a snippet the model can
     * quote without swallowing the whole page.
     */
    private fun formatResults(results: JSONArray, query: String): String {
        val lines = mutableListOf<String>()
        val count = minOf(results.length(), SEARCH_RESULT_COUNT)
        for (i in 0 until count) {
            val row = results.optJSONObject(i) ?: continue
            val title = row.optString("title", "").trim()
            val link = row.optString("url", "").trim()
            val snippet = row.optString("content", "").ifBlank { row.optString("description", "") }
                .trim().replace('\n', ' ')
            val line = buildString {
                append("${i + 1}. $title")
                if (link.isNotBlank()) append(" ($link)")
                if (snippet.isNotBlank()) append(" - ${snippet.take(SEARCH_SNIPPET_CHARS)}")
            }
            lines.add(line)
        }
        return "Results for \"$query\":\n${lines.joinToString("\n")}"
    }

    // ---------- Shared helpers ----------

    /**
     * One web page, cut to size.
     *
     * Only http(s) is allowed, the body is read up to [FETCH_MAX_BYTES] and the
     * text kept below [FETCH_MAX_CHARS]: a tool that hands the model a whole
     * page drowns the reply. An HTML page has its tags stripped so the model
     * reads words, not markup.
     */
    private suspend fun fetchUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        if (url.isBlank()) return "No URL was given."
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Only http and https addresses can be fetched."
        }
        val body = httpGet(url, mapOf("Accept" to "text/html,application/json,text/plain")) ?: return "The page could not be fetched."
        val text = if (body.contains("<html") || body.contains("<!DOCTYPE")) stripHtml(body) else body
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "The page has no text content."
        if (trimmed.length > FETCH_MAX_CHARS) {
            return "The page text (shortened):\n${trimmed.take(FETCH_MAX_CHARS)}"
        }
        return "The page text:\n$trimmed"
    }

    private suspend fun httpGet(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                val builder = Request.Builder().url(url).get()
                for ((key, value) in headers) builder.addHeader(key, value)
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w("ToolRegistry", "The fetch of $url answered ${response.code}")
                        return@withContext null
                    }
                    val source = response.body ?: return@withContext null
                    // The cap is enforced while reading: a huge page is cut
                    // off rather than buffered whole.
                    val buffer = java.io.ByteArrayOutputStream()
                    val input = source.byteStream()
                    val chunk = ByteArray(8192)
                    var read: Int
                    var total = 0L
                    while (true) {
                        read = input.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > FETCH_MAX_BYTES) {
                            buffer.write(chunk, 0, (read - (total - FETCH_MAX_BYTES)).toInt().coerceAtLeast(0))
                            break
                        }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toString("UTF-8")
                }
            } catch (e: Exception) {
                android.util.Log.w("ToolRegistry", "The fetch of $url failed: ${e.message}")
                null
            }
        }

    private fun stripHtml(html: String): String {
        val withoutScripts = html
            .replace(Regex("(?is)<(script|style).*?>.*?</(script|style)>"), " ")
            .replace(Regex("(?is)<(head).*?>.*?</(head)>"), " ")
        val text = withoutScripts
            .replace(Regex("(?s)<.*?>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&\\w+;"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun urlEncode(value: String): String {
        val builder = StringBuilder()
        for (c in value) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~' -> builder.append(c)
                else -> {
                    for (byte in c.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)) {
                        builder.append('%').append(((byte.toInt() + 0x100) and 0xFF).toString(16).padStart(2, '0'))
                    }
                }
            }
        }
        return builder.toString()
    }
}
