package com.localassistant.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import com.localassistant.app.ui.common.playWaitingCue
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP client for OpenAI-compatible endpoints (LLM, STT, TTS).
 * 
 * All services run locally - no cloud dependencies.
 */
object ApiClient {
    
    private const val DEFAULT_TIMEOUT = 60L // seconds

    // Rate-limit (429) retry schedule: an exponential backoff that caps at two
    // seconds, then a steady two-second poll. This is an interactive assistant -
    // the user is present and the request has priority - so the retries never
    // give up on their own; the endpoint decides when the load has cleared. The
    // wait must not spam anything: the waiting state rises once per episode and
    // callers show one message for it, not one per attempt.
    private val RATE_LIMIT_BACKOFF_MS = listOf(250L, 500L, 1000L, 2000L)
    private const val RATE_LIMIT_STEADY_DELAY_MS = 2000L

    /**
     * True while any request is being retried because its endpoint answered 429.
     * Callers observe this to show an "Awaiting connection..." message once for
     * the whole episode instead of flashing one at every retry attempt.
     */
    private val _connectionWaiting = MutableStateFlow(false)
    val connectionWaiting: StateFlow<Boolean> = _connectionWaiting.asStateFlow()
    
    /**
     * Executes one request, retrying while the endpoint answers 429 (rate limited).
     *
     * The delay starts at 250ms and doubles up to two seconds, then stays at two
     * seconds for every attempt after that, because an interactive request waits
     * for the endpoint rather than failing the exchange the user is standing in.
     * Each retry plays the low waiting cue and raises [connectionWaiting] so a
     * caller can show its "Awaiting connection..." message - once per episode,
     * never per attempt. Any other status (success or failure) is handed back to
     * the caller untouched, so hard errors like a 400 or a missing model keep
     * failing fast exactly as they did before.
     */
    private suspend fun executeWithRateLimitRetry(
        client: OkHttpClient,
        request: Request
    ): okhttp3.Response {
        var attempt = 0
        while (true) {
            val response = client.newCall(request).execute()
            if (response.code != 429) {
                _connectionWaiting.value = false
                return response
            }
            // The rejected response must be closed before the next attempt, or the
            // connection pool holds the body open.
            response.close()
            _connectionWaiting.value = true
            if (attempt == 0) {
                android.util.Log.w("ApiClient", "Rate limited (429); retrying with backoff")
            }
            // A soft footstep-like cue, low in the mix: the user hears that the
            // request is still working without a spoken interruption.
            playWaitingCue()
            val delayMs =
                if (attempt < RATE_LIMIT_BACKOFF_MS.size) RATE_LIMIT_BACKOFF_MS[attempt]
                else RATE_LIMIT_STEADY_DELAY_MS
            android.util.Log.d("ApiClient", "429: retrying in ${delayMs}ms (attempt ${attempt + 1})")
            delay(delayMs)
            attempt++
        }
    }

    /**
     * The same exchange for callers that hold tools as plain maps - a `name`, a `description`
     * and a `parameters` JSON schema - which is the shape an MCP server reports. The shapes
     * used inside the client stay private to this file, so no conversation service has to
     * name them.
     */
    suspend fun chatCompletionWithTools(
        baseUrl: String,
        apiKey: String? = null,
        messages: List<Map<String, String>>,
        model: String,
        temperature: Float = 7.0f,
        maxTokens: Int = 1024,
        systemPrompt: String? = null,
        stream: Boolean = true,
        reasoningBudgetTokens: Int = 0,
        chatTemplateKwargs: String = "",
        timeoutSeconds: Long = DEFAULT_TIMEOUT,
        toolDescriptors: List<Map<String, String>> = emptyList(),
        runTool: (suspend (String, String) -> String)? = null,
        abortSignal: AtomicBoolean? = null,
        onChunk: ((String) -> Unit)? = null
    ): ChatResponse = chatCompletionToolRounds(
        baseUrl = baseUrl,
        apiKey = apiKey,
        messages = messages,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        systemPrompt = systemPrompt,
        stream = stream,
        reasoningBudgetTokens = reasoningBudgetTokens,
        chatTemplateKwargs = chatTemplateKwargs,
        timeoutSeconds = timeoutSeconds,
        tools = toolDescriptors.map { toolSchemaOf(it) },
        runTool = runTool,
        abortSignal = abortSignal,
        onChunk = onChunk
    )

    /**
     * Runs a whole tool exchange: ask, run what the model asked for, answer again, and keep
     * going until the model replies with words instead of calls.
     *
     * Both the in-app pipeline and the hands-free wake chat route through here, so a tool
     * answer reads back the same way wherever the conversation lives. With an empty [tools]
     * list this is one ordinary completion: [runTool] is never consulted.
     *
     * @param runTool called with a tool's name and raw JSON arguments, returning what the model
     *               should read back; a null executor answers the first turn and stops.
     * @param onChunk forwarded to every round, so streaming survives the loop.
     * @throws IOException from a round that failed, including a cancelled stream.
     */
    suspend fun chatCompletionToolRounds(
        baseUrl: String,
        apiKey: String? = null,
        messages: List<Map<String,String>>,
        model: String,
        temperature: Float = 7.0f,
        maxTokens: Int = 1024,
        systemPrompt: String? = null,
        stream: Boolean = true,
        reasoningBudgetTokens: Int = 0,
        chatTemplateKwargs: String = "",
        timeoutSeconds: Long = DEFAULT_TIMEOUT,
        tools: List<ToolSchema> = emptyList(),
        runTool: (suspend (String, String) -> String)? = null,
        abortSignal: AtomicBoolean? = null,
        onChunk: ((String) -> Unit)? = null
    ): ChatResponse {
        // A model that keeps asking for tools would loop forever, so the exchange has a budget.
        val maxToolRounds = 5
        val history = mutableListOf<Map<String,String>>()
        history.addAll(messages)
        var reply = ChatResponse(content = "")
        for (round in 1..maxToolRounds) {
            reply = chatCompletion(
                baseUrl = baseUrl,
                apiKey = apiKey,
                messages = history,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = stream,
                reasoningBudgetTokens = reasoningBudgetTokens,
                chatTemplateKwargs = chatTemplateKwargs,
                timeoutSeconds = timeoutSeconds,
                tools = tools,
                abortSignal = abortSignal,
                onChunk = onChunk
            )
            if (reply.toolCalls.isEmpty()) return reply
            if (runTool == null) return reply
            if (abortSignal?.get() == true) return reply
            history.add(assistantToolCallTurn(reply))
            for (call in reply.toolCalls) {
                val outcome = runTool(call.name, call.arguments)
                history.add(
                    mapOf(
                        "role" to "tool",
                        "tool_call_id" to call.resolvedId,
                        "content" to outcome
                    )
                )
                android.util.Log.i("ApiClient", "Tool ${call.name} answered with ${outcome.take(160)}")
            }
        }
        android.util.Log.w("ApiClient", "The model still asked for tools after $maxToolRounds rounds; the last words it sent are the answer.")
        return reply
    }

    /**
     * Create an OkHttp client with the given base URL and optional API key.
     *
     * @param timeoutSeconds connect/read timeout; callers that only check whether an
     *                      endpoint is alive pass a short value so the UI resolves fast.
     */
    fun createClient(baseUrl: String, apiKey: String? = null, timeoutSeconds: Long = DEFAULT_TIMEOUT): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        
        // An empty key means "no authentication" - sending "Bearer " on its own makes
        // strict servers (llama.cpp for one) answer 400, so only a real key adds the
        // header. Callers may pass the settings value straight through.
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            builder.addInterceptor { chain ->
                val original = chain.request()
                val newRequest = original.newBuilder()
                    .header("Authorization", "Bearer $key")
                    .build()
                chain.proceed(newRequest)
            }
        }
        
        return builder.build()
    }
    
    /**
     * Response from LLM containing both reasoning and final answer.
     */
    data class ChatResponse(
        val content: String,
        val reasoningContent: String? = null,
        // What the model asked to be run. It is answered in the same response so the
        // caller can run it and ask again, and empty on every reply that is plain words.
        val toolCalls: List<ToolCall> = emptyList()
    ) {
        val hasReasoning: Boolean get() = !reasoningContent.isNullOrEmpty()
        val requestedTools: Boolean get() = toolCalls.isNotEmpty()
    }

    /**
     * A tool the model may call, described in the shape every OpenAI-compatible server
     * reads. [parametersJson] is the parameter document as text - the MCP client already
     * has it, and re-typing it here would need a schema model in the client. An empty
     * document means the tool takes no arguments.
     */
    data class ToolSchema(
        val name: String,
        val description: String = "",
        val parametersJson: String = ""
    )

    /**
     * A tool the caller holds as a plain map, turned into the shape the endpoint reads.
     * A descriptor with no `parameters` entry is read as a tool that takes no arguments.
     */
    private fun toolSchemaOf(descriptor: Map<String, String>): ToolSchema = ToolSchema(
        name = descriptor["name"] ?: "",
        description = descriptor["description"] ?: "",
        parametersJson = descriptor["parameters"] ?: ""
    )

    /**
     * One tool call the model asked for. [arguments] is the argument document exactly
     * as the model wrote it; it is never parsed here, because what a tool does with its
     * arguments is the tool's own decision.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    ) {
        // Servers that do not name a call still have to be answered with the same id the
        // model will read back, so a stable one is filled in for the round trip.
        val resolvedId: String get() = if (id.isNotBlank()) id else "call_${name}_${arguments.length}"
    }
    
    /**
     * Send a chat completion request to the LLM endpoint.
     *
     * When [stream] is true the SSE stream is always consumed, so the returned
     * content is the reassembled answer and never the raw event stream; [onChunk]
     * is optional and only decides whether deltas are forwarded while reading.
     *
     * [abortSignal] is raised by the caller to stop the answer mid-flight - the
     * deltas read after it is raised are dropped, so the caller never sees an
     * answer it has already interrupted.
     */
    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String?,
        messages: List<Map<String, String>>,
        model: String = "llama-3.1-8b-instruct",
        temperature: Float = 0.7f,
        maxTokens: Int = 4096,
        stream: Boolean = true,
        systemPrompt: String = "",
        reasoningBudgetTokens: Int = 0,
        chatTemplateKwargs: String = "",
        timeoutSeconds: Long = DEFAULT_TIMEOUT,
        tools: List<ToolSchema> = emptyList(),
        abortSignal: AtomicBoolean? = null,
        onChunk: ((String) -> Unit)? = null
    ): ChatResponse {
        android.util.Log.d("ApiClient", "chatCompletion called, stream=$stream")
        val client = createClient(baseUrl, apiKey, timeoutSeconds)

        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/chat/completions")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/chat/completions")).trimEnd('/')
        }

        val requestMessages = if (systemPrompt.isBlank()) messages
        else listOf(mapOf("role" to "system", "content" to systemPrompt)) + messages

        val requestBody = buildChatRequestBody(
            model = model,
            messages = requestMessages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = stream,
            reasoningBudgetTokens = reasoningBudgetTokens,
            chatTemplateKwargs = chatTemplateKwargs,
            tools = tools
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$cleanUrl/chat/completions")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                executeWithRateLimitRetry(client, request).use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error body"
                        android.util.Log.e("ApiClient", "Chat completion failed: ${response.code} - $errorBody")
                        throw IOException("Chat completion failed: ${response.code}")
                    }
                    
                    val body = response.body ?: throw IOException("Empty response")
                    
                    if (stream) {
                        android.util.Log.d("ApiClient", "Using streaming response handler")
                        handleStreamingResponse(body, onChunk, abortSignal)
                    } else {
                        android.util.Log.d("ApiClient", "Using non-streaming response")
                        val content = body.string()
                        parseReasoningAndContent(content)
                    }
                }
            } catch (e: Exception) {
                throw IOException("Failed to get chat completion: ${e.message}", e)
            }
        }
    }
    
    /**
     * Parse LLM response to extract reasoning/thinking content and final answer.
     * Handles various formats like <think>...</think>, reasoning fields, etc.
     */
    fun parseReasoningAndContent(response: String): ChatResponse {
        var reasoning = ""
        var content = ""
        // What the model asked to be run in this reply, if anything.
        val requestedCalls = mutableListOf<ToolCall>()
        
        // Try to parse as nested JSON structure first (OpenAI format)
        try {
            val root = org.json.JSONObject(response)
            
            // Check for "choices" array (standard OpenAI format)
            if ((root.optJSONArray("choices")?.length() ?: 0) > 0) {
                val choices = root.getJSONArray("choices")
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                
                content = message.optString("content", "")

                // A non-streaming reply that asked for tools carries the calls complete in the
                // message, unlike the streamed response, where they arrive in pieces.
                if (message.has("tool_calls")) {
                    val calls = message.getJSONArray("tool_calls")
                    for (i in 0..<calls.length()) {
                        val call = calls.getJSONObject(i)
                        val function = call.getJSONObject("function")
                        requestedCalls.add(
                            ToolCall(
                                id = call.optString("id", ""),
                                name = function.optString("name", ""),
                                arguments = function.optString("arguments", "")
                            )
                        )
                    }
                }
                
                // Check for reasoning_content field (newer models)
                if (message.has("reasoning_content") && !message.getString("reasoning_content").isNullOrEmpty()) {
                    reasoning = message.getString("reasoning_content")
                } else if (message.has("reasoning") && !message.getString("reasoning").isNullOrEmpty()) {
                    reasoning = message.getString("reasoning")
                }
            } else {
                // Not a nested structure, use tag-based parsing
                val thinkPatterns = listOf("<think>" to "</think>", "<reasoning>" to "</reasoning>", "<thinking>" to "</thinking>")
                
                for ((startTag, endTag) in thinkPatterns) {
                    val startIndex = response.indexOf(startTag)
                    if (startIndex >= 0) {
                        val endIndex = response.indexOf(endTag)
                        if (endIndex > startIndex) {
                            reasoning = response.substring(startIndex + startTag.length, endIndex).trim()
                            content = response.substring(endIndex + endTag.length).trim()
                            break
                        }
                    }
                }
                
                // If still no content found, use the whole response as content
                if (content.isEmpty()) {
                    content = response
                }
            }
        } catch (_: Exception) {
            // JSON parsing failed, fall back to tag-based parsing or use raw response
            val thinkPatterns = listOf("<think>" to "</think>", "<reasoning>" to "</reasoning>", "<thinking>" to "</thinking>")
            
            for ((startTag, endTag) in thinkPatterns) {
                val startIndex = response.indexOf(startTag)
                if (startIndex >= 0) {
                    val endIndex = response.indexOf(endTag)
                    if (endIndex > startIndex) {
                        reasoning = response.substring(startIndex + startTag.length, endIndex).trim()
                        content = response.substring(endIndex + endTag.length).trim()
                        break
                    }
                }
            }
            
            if (content.isEmpty()) {
                content = response
            }
        }
        
        return ChatResponse(content = content, reasoningContent = if (reasoning.isBlank()) null else reasoning, toolCalls = requestedCalls)
    }
    
    /**
     * Send audio to the STT endpoint for transcription.
     */
    suspend fun transcribeAudio(
        baseUrl: String,
        apiKey: String?,
        audioData: ByteArray,
        model: String = "whisper-large-v3",
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): String {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)
        
        // Handle base URL - ensure we don't double-append /audio/transcriptions
        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/audio/transcriptions")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/audio/transcriptions")).trimEnd('/')
        } else if (cleanUrl.contains("/audio/")) {
            val idx = cleanUrl.indexOf("/audio/")
            cleanUrl = cleanUrl.substring(0, idx)
        }
        
        // Use multipart/form-data for STT endpoint (required by Ollama/Whisper)
        val mediaPart = audioData.toRequestBody("audio/wav".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", mediaPart)
            .addFormDataPart("model", model)
            .build()
        
        val request = Request.Builder()
            .url("$cleanUrl/audio/transcriptions")
            .post(requestBody)
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                executeWithRateLimitRetry(client, request).use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("STT failed: ${response.code} - ${response.body?.string()}")
                    }
                    val body = response.body ?: throw IOException("Empty STT response")
                    
                    parseTextFromJson(body.string())
                }
            } catch (e: Exception) {
                throw IOException("Failed to transcribe audio: ${e.message}", e)
            }
        }
    }
    
    /**
     * Synthesize speech synchronously (non-streaming).
     */
    suspend fun synthesizeSpeech(
        baseUrl: String,
        apiKey: String?,
        text: String,
        model: String = "piper-en",
        voice: String? = null,
        responseFormat: String = "mp3",
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): ByteArray {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)

        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/audio/speech")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/audio/speech")).trimEnd('/')
        }

        // Many local TTS servers (e.g. Piper proxies) do not support the `voice`
        // field and reject the request with HTTP 400 when it is present. Only
        // include it when a value is configured so unsupported servers fall back
        // to their default voice.
        val voiceField = if (voice.isNullOrBlank()) "" else ",\"voice\": ${escapeJsonString(voice)}"
        val jsonBody = """{
            "model": ${escapeJsonString(model)},
            "input": ${escapeJsonString(text)}$voiceField,
            "response_format": ${escapeJsonString(responseFormat)}
        }""".trimIndent()
        
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$cleanUrl/audio/speech")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                executeWithRateLimitRetry(client, request).use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string()?.take(200) ?: "no body"
                        android.util.Log.e("ApiClient", "TTS failed: ${response.code} - $errBody")
                        throw IOException("TTS failed: ${response.code} - $errBody")
                    }
                    val body = response.body ?: throw IOException("Empty TTS response")
                    val bytes = body.bytes()
                    bytes
                }
            } catch (e: Exception) {
                android.util.Log.e("ApiClient", "TTS exception: ${e.message}")
                throw IOException("Failed to synthesize speech: ${e.message}", e)
            }
        }
    }
    
    /**
     * The turn we hand back when the model asked for tools: the calls it made, verbatim, so the
     * next round can see what it already asked for.
     */
    private fun assistantToolCallTurn(reply: ChatResponse): Map<String, String> {
        val calls = mutableListOf<String>()
        for (call in reply.toolCalls) {
            calls.add(
                "{\"id\": \"${call.resolvedId}\", \"type\": \"function\", \"function\": {\"name\": \"${call.name}\", \"arguments\": ${escapeJsonString(call.arguments)}}}"
            )
        }
        return mapOf(
            "role" to "assistant",
            "content" to reply.content,
            "tool_calls" to "[${calls.joinToString(", ")}]"
        )
    }

    private fun buildMessagesJson(messages: List<Map<String, String>>): String {
        val jsonList = messages.joinToString(",\n") { msg ->
            val fields = mutableListOf(
                "\"role\": \"${msg["role"] ?: "user"}\"",
                "\"content\": ${escapeJsonString(msg["content"] ?: "")}"
            )
            // A tool round has to survive the next request unchanged: the assistant
            // turn that asked for a tool carries the call array back verbatim, and the
            // turn answering it names that call. Without both the server cannot see a
            // completed round and refuses to call tools at all.
            msg["tool_calls"]?.let { calls ->
                if (calls.isNotBlank() && calls != "[]") fields.add("\"tool_calls\": $calls")
            }
            msg["tool_call_id"]?.let { callId ->
                if (callId.isNotBlank()) fields.add("\"tool_call_id\": ${escapeJsonString(callId)}")
            }
            "{${fields.joinToString(", ")}}"
        }
        return "[$jsonList]"
    }

    /**
     * Renders the tool schemas a request offers the model, in the shape every
     * OpenAI-compatible server reads.
     *
     * [ToolSchema.parametersJson] is carried as raw JSON because a parameter document
     * is an object of arbitrary depth: re-escaping it field by field would need a JSON
     * writer in this client, and every producer here (the MCP client) already has the
     * document as text. An absent document becomes the empty object schema, which is
     * what a tool that takes no arguments needs.
     */
    private fun buildToolsJson(tools: List<ToolSchema>): String {
        return tools.joinToString(",") { tool ->
            """{"type": "function", "function": {"name": ${escapeJsonString(tool.name)}, "description": ${escapeJsonString(tool.description)}, "parameters": ${tool.parametersJson.ifBlank { """{"type": "object", "properties": {}}""" }}}}"""
        }
    }

    /**
     * Assemble the chat request body, including the optional generation controls
     * the user configured.
     *
     * The optional parameters belong before the closing brace, never after it: a
     * body ending `…},"chat_template_kwargs": {…}` is a complete JSON value
     * followed by junk, and llama.cpp answers that whole request with
     * 400 "parse error … unexpected ','; expected end of input" - which is why
     * the settings test (a plain request) said "ok" while every chat failed.
     */
    fun buildChatRequestBody(
        model: String,
        messages: List<Map<String, String>>,
        temperature: Float,
        maxTokens: Int,
        stream: Boolean,
        reasoningBudgetTokens: Int = 0,
        chatTemplateKwargs: String = "",
        tools: List<ToolSchema> = emptyList()
    ): String {
        val body = StringBuilder()
        body.append("{\n\"model\": ${escapeJsonString(model)},")
        body.append("\n\"messages\": ${buildMessagesJson(messages)},")
        body.append("\n\"temperature\": $temperature,")
        body.append("\n\"max_tokens\": $maxTokens,")
        body.append("\n\"stream\": $stream")
        // Optional generation controls are appended only when the user set them,
        // so a server that knows nothing about reasoning keeps receiving the plain
        // request it accepted before. The tool list is offered with an explicit
        // "auto" choice: naming no tool forces a call, and omitting the choice
        // leaves the behaviour to whatever the server happens to default to.
        if (tools.isNotEmpty()) {
            body.append(",\n\"tools\": ${buildToolsJson(tools)},\n\"tool_choice\": \"auto\"")
        }
        if (reasoningBudgetTokens > 0) {
            body.append(",\n\"reasoning_budget_tokens\": $reasoningBudgetTokens")
        }
        buildChatTemplateKwargsJson(chatTemplateKwargs)?.let { kwargs ->
            body.append(",\n\"chat_template_kwargs\": $kwargs")
        }
        body.append("\n}")
        return body.toString()
    }

    /**
     * Turn the user's chat-template arguments into the JSON object a server
     * expects under "chat_template_kwargs".
     *
     * The settings field is typed on a phone, so the accepted spelling is the
     * friendlier one: one "key=value" per line - or separated by commas - with
     * "true"/"false", numbers, and bare words as strings. A value in quotes is
     * taken verbatim. Anything that is not a well-formed pair is skipped rather
     * than sent, because a half-typed argument would otherwise break every
     * request the app makes.
     *
     * Returns null when nothing was entered, so the request body stays exactly as
     * a server without chat-template control expects it.
     */
    fun buildChatTemplateKwargsJson(spec: String): String? {
        val pairs = spec.replace(",", "\n").split("\n")
            .map { it.trim() }
            .filterNot { it.isBlank() || it.startsWith("#") }
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val key = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                if (!isTemplateKey(key) || value.isEmpty()) return@mapNotNull null
                "${escapeJsonString(key)}:${jsonValueOfKeyword(value)}"
            }
        if (pairs.isEmpty()) return null
        return "{${pairs.joinToString(",")}}"
    }

    private fun jsonValueOfKeyword(value: String): String {
        val unquoted = if (value.length >= 2 &&
                ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))) {
            value.substring(1, value.length - 1)
        } else value
        return when {
            unquoted.equals("true", ignoreCase = true) -> "true"
            unquoted.equals("false", ignoreCase = true) -> "false"
            isNumberLiteral(unquoted) -> unquoted
            else -> escapeJsonString(unquoted)
        }
    }

    /**
     * A chat-template key is a bare identifier - the names servers accept
     * ("enable_thinking", "reasoning_effort") - and nothing else, so a half-typed
     * line can never inject stray JSON.
     */
    private fun isTemplateKey(key: String): Boolean {
        if (key.isEmpty()) return false
        if (!key[0].isLetter() && key[0] != '_') return false
        return key.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
    }

    private fun isNumberLiteral(value: String): Boolean {
        var digits = 0
        var dots = 0
        for (i in 0 until value.length) {
            val c = value[i]
            if (c == '-' && i == 0) continue
            if (c == '.') {
                dots++
                if (dots > 1) return false
            } else if (c.isDigit()) digits++
            else return false
        }
        return digits > 0
    }
    
    /**
     * Renders [s] as a JSON string literal.
     *
     * The JSON grammar forbids every control character from U+0000 to U+001F bare in a
     * string, not just the line breaks and tabs that model output happens to emit most
     * often; a single raw control byte makes llama.cpp answer 400 for the whole request,
     * which fails the turn rather than the one field.
     */
    private fun escapeJsonString(s: String): String {
        val hexDigits = "0123456789abcdef"
        val out = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u0008' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (c >= ' ') {
                        out.append(c)
                    } else {
                        val code = c.toInt()
                        out.append("\\u00")
                        out.append(hexDigits[(code shr 4) and 0xF])
                        out.append(hexDigits[code and 0xF])
                    }
                }
            }
        }
        out.append('"')
        return out.toString()
    }
    
    /**
     * Read an SSE chat-completion stream, keeping reasoning deltas (`\u0001`-prefixed,
     * as produced by [extractContentFromJson]) apart from the answer so callers that
     * do not pass an [onChunk] still receive clean content.
     */
    private suspend fun handleStreamingResponse(
        body: ResponseBody,
        onChunk: ((String) -> Unit)?,
        abortSignal: AtomicBoolean? = null
    ): ChatResponse {
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        // The raw JSON of every tool-call fragment the server sent, in order. A call is
        // not one delta: the id and the function name arrive in the first fragment and
        // the argument document arrives split over the rest, so the pieces are kept
        // here and joined once the stream has ended - a half document cannot be run.
        val callFragments = mutableListOf<String>()
        android.util.Log.d("ApiClient", "Starting SSE stream read")
        var lineCount = 0
        try {
            // Use buffered reader for proper streaming
            val reader = java.io.BufferedReader(java.io.InputStreamReader(body.byteStream()))
            while (true) {
                if (abortSignal?.get() == true) {
                    android.util.Log.w("ApiClient", "SSE stream abandoned after $lineCount lines - the caller interrupted the answer")
                    break
                }
                val line = reader.readLine()
                if (line == null) {
                    android.util.Log.d("ApiClient", "SSE stream ended after $lineCount lines")
                    break
                }
                lineCount++
                if (line.startsWith("data: ")) {
                    val jsonStr = line.substring(6).trim()

                    if (jsonStr == "[DONE]") {
                        android.util.Log.d("ApiClient", "Received [DONE] marker")
                        break
                    }

                    // Extract content from SSE chunk
                    val chunk = extractContentFromJson(jsonStr)
                    if (chunk.isNotEmpty()) {
                        if (chunk.startsWith("\u0001")) {
                            reasoningBuilder.append(chunk.substring(1))
                        } else {
                            contentBuilder.append(chunk)
                        }
                        android.util.Log.d("ApiClient", "Extracted chunk: '$chunk'")
                        try {
                            onChunk?.invoke(chunk)
                        } catch (e: Exception) {
                            android.util.Log.e("ApiClient", "Callback error for chunk: ${e.message}")
                            // Don't break streaming - continue processing remaining chunks
                        }
                    } else if (jsonStr != "[DONE]") {
                        android.util.Log.w("ApiClient", "Empty chunk from JSON: $jsonStr")
                    }
                    // A tool call is not one delta: the server opens it with the id and the
                    // function name and then streams the argument document in pieces, so the
                    // fragments are stitched here and only handed to the caller once the
                    // stream has ended - a half-written argument document cannot be run.
                    if (jsonStr.contains("tool_calls")) {
                        for (fragment in toolCallFragmentJson(jsonStr)) {
                            callFragments.add(fragment)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // A swallowed read error is indistinguishable from a model that answered
            // with nothing, and reasoning models already answer with nothing on their
            // first chunk - so the UI showed a blank reply for what was really a lost
            // stream. Every caller wraps this call in a handler that reports the reason.
            android.util.Log.e("ApiClient", "SSE stream read failed after $lineCount lines: ${e.message}", e)
            throw IOException("LLM stream interrupted after $lineCount lines: ${e.message ?: "connection lost"}")
        }

        // Answers that embed <think> tags inside the content deltas are split here as well,
        // so a single non-streaming server and a streaming one report the same way.
        val parsed = parseReasoningAndContent(contentBuilder.toString())
        val streamedReasoning = reasoningBuilder.toString().trim()
        val reasoning = if (streamedReasoning.isNotEmpty()) streamedReasoning else parsed.reasoningContent.orEmpty().trim()
        val toolCalls = completeToolCallFragments(callFragments)
        if (toolCalls.isNotEmpty()) {
            android.util.Log.i("ApiClient", "The model asked for ${toolCalls.size} tool(s): ${toolCalls.joinToString(", ") { it.name }}")
        }
        android.util.Log.d("ApiClient", "Streaming completed, got ${parsed.content.length} chars")
        return ChatResponse(
            content = parsed.content,
            reasoningContent = reasoning.takeIf { it.isNotEmpty() },
            toolCalls = toolCalls
        )
    }

    /**
     * The "tool_calls" fragments one SSE chunk carries, as raw JSON objects.
     *
     * They are handed over verbatim because one fragment is not one call: a call is
     * spread over the chunks that follow it, and only [completeToolCallFragments] can
     * tell where one call ends.
     */
    private fun toolCallFragmentJson(json: String): List<String> {
        try {
            val root = org.json.JSONObject(json)
            if ((root.optJSONArray("choices")?.length() ?: 0) == 0) return emptyList()
            val firstChoice = root.getJSONArray("choices").getJSONObject(0)
            if (!firstChoice.has("delta")) return emptyList()
            val calls = firstChoice.getJSONObject("delta").optJSONArray("tool_calls") ?: return emptyList()
            val fragments = mutableListOf<String>()
            for (position in 0 until calls.length()) {
                fragments.add(calls.getJSONObject(position).toString())
            }
            return fragments
        } catch (e: Exception) {
            android.util.Log.w("ApiClient", "Could not read a tool-call fragment: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Joins the streamed fragments into whole calls.
     *
     * A fragment opens a call when it carries an id that has not been seen yet; every
     * other fragment is the next piece of the argument document of the call most
     * recently opened. That is the shape an OpenAI-compatible server writes, and it
     * survives both the servers that repeat the id in each fragment and the ones that
     * leave it out altogether.
     */
    private fun completeToolCallFragments(fragments: List<String>): List<ToolCall> {
        val ids = mutableListOf<String>()
        val names = mutableListOf<String>()
        val arguments = mutableListOf<StringBuilder>()
        for (fragment in fragments) {
            try {
                val node = org.json.JSONObject(fragment)
                if (!node.has("function")) continue
                val function = node.getJSONObject("function")
                val id = node.optString("id", "")
                val name = function.optString("name", "")
                var seen = false
                for (slot in 0 until ids.size) {
                    if (id.isNotEmpty() && ids[slot] == id) {
                        seen = true
                        break
                    }
                }
                if (!seen && (id.isNotEmpty() || ids.isEmpty())) {
                    ids.add(id)
                    names.add(name)
                    arguments.add(StringBuilder())
                }
                if (arguments.isNotEmpty()) {
                    arguments[arguments.size - 1].append(function.optString("arguments", ""))
                    if (name.isNotEmpty() && names[names.size - 1].isEmpty()) {
                        names[names.size - 1] = name
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ApiClient", "Could not read a tool-call fragment: ${e.message}")
            }
        }
        val calls = mutableListOf<ToolCall>()
        for (slot in 0 until ids.size) {
            if (names[slot].isNotEmpty()) {
                calls.add(ToolCall(id = ids[slot], name = names[slot], arguments = arguments[slot].toString()))
            }
        }
        return calls
    }

    private fun extractContentFromJson(json: String): String {
        try {
            val root = org.json.JSONObject(json)
            
            // Standard OpenAI streaming format: choices[0].delta.content or reasoning_content
            if ((root.optJSONArray("choices")?.length() ?: 0) > 0) {
                val firstChoice = root.getJSONArray("choices").getJSONObject(0)
                if (firstChoice.has("delta")) {
                    val delta = firstChoice.getJSONObject("delta")
                    
                    // Check for reasoning_content field (common in newer models)
                    if (delta.has("reasoning_content")) {
                        val content = delta.getString("reasoning_content")
                        if (!content.isNullOrEmpty() && content != "null") {
                            android.util.Log.d("ApiClient", "Reasoning chunk: '$content'")
                            return "\u0001$content" // Use null char as separator for reasoning
                        }
                    }
                    
                    // Check for thinking field (alternative)
                    if (delta.has("thinking")) {
                        val content = delta.getString("thinking")
                        if (!content.isNullOrEmpty() && content != "null") {
                            return "\u0001$content"
                        }
                    }
                    
                    // Get regular content
                    val content = if (delta.has("content")) {
                        val c = delta.getString("content")
                        if (c == "null" || c.isEmpty()) "" else c
                    } else {
                        ""
                    }
                    
                    return content
                }
                // Fallback: direct content field
                val directContent = if (firstChoice.has("content")) {
                    val c = firstChoice.getString("content")
                    if (c == "null" || c.isEmpty()) "" else c
                } else {
                    ""
                }
                return directContent
            }
            
            return ""
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Failed to extract content from JSON: $json")
            return ""
        }
    }
    
    private fun parseTextFromJson(json: String): String {
        android.util.Log.d("ApiClient", "STT response raw: ${json.take(200)}")
        
        try {
            val root = org.json.JSONObject(json)
            
            // Try common field names for transcription text
            val textFields = listOf("text", "transcript", "content", "message")
            for (field in textFields) {
                if (root.has(field)) {
                    val value = root.getString(field)
                    android.util.Log.d("ApiClient", "STT found field '$field': $value")
                    return value.trim()
                }
            }
            
            // If no known field, try to extract any text content
            val start = json.indexOf("\"text\":\"") + 7
            if (start > 7) {
                var end = json.indexOf("\"", start)
                if (end == -1) end = json.length
                return json.substring(start, end).trim()
            }
            
            // Last resort: remove JSON brackets and quotes
            val cleaned = json.trim().trim('"', '{', '}', '\n', '\r')
            android.util.Log.d("ApiClient", "STT fallback: $cleaned")
            return cleaned
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Failed to parse STT response: ${e.message}")
            throw IOException("Failed to parse STT response: ${json.take(50)}", e)
        }
    }
    
    /**
     * Fetch available models from an OpenAI-compatible endpoint.
     * Expects baseUrl to end with /v1 (e.g., http://192.168.1.2:8080/v1).
     * Appends /models to get the full endpoint.
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): List<String> {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)
        
        // baseUrl should end with /v1, so we append /models
        val modelEndpoint = baseUrl.trimEnd('/') + "/models"
        
        android.util.Log.d("ApiClient", "Fetching models from: $modelEndpoint")
        
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(modelEndpoint)
                    .get()
                    .build()
                
                val response = executeWithRateLimitRetry(client, request)
                android.util.Log.d("ApiClient", "Response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    throw IOException("Failed to fetch models: ${response.code} - $errorBody")
                }
                
                val body = response.body ?: throw IOException("Empty response")
                val json = body.string()
                android.util.Log.d("ApiClient", "Response JSON: ${json.take(500)}")
                
                val models = mutableListOf<String>()
                val root = org.json.JSONObject(json)
                
                val dataArray = root.optJSONArray("data")
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.optJSONObject(i)
                        var id = item?.optString("id")
                        if (!id.isNullOrEmpty()) {
                            if (id.contains("/models/")) {
                                id = id.substring(id.lastIndexOf("/") + 1)
                            }
                            models.add(id)
                            android.util.Log.d("ApiClient", "Found model (OpenAI format): $id")
                        }
                    }
                } else {
                    val modelsArray = root.optJSONArray("models")
                    if (modelsArray != null) {
                        for (i in 0 until modelsArray.length()) {
                            val item = modelsArray.optJSONObject(i)
                            var id = item?.optString("name") ?: item?.optString("id")
                            if (!id.isNullOrEmpty()) {
                                if (id.contains("/models/")) {
                                    id = id.substring(id.lastIndexOf("/") + 1)
                                }
                                models.add(id)
                                android.util.Log.d("ApiClient", "Found model (alternative format): $id")
                            }
                        }
                    }
                }
                
                android.util.Log.d("ApiClient", "Total models found: ${models.size}")
                models
            } catch (e: Exception) {
                throw IOException("Failed to fetch models: ${e.message}", e)
            }
        }
    }

    /**
     * Fetch available TTS voices from the /v1/audio/voices endpoint.
     * Expects baseUrl to end with /v1 (e.g., http://192.168.1.2:5001/v1).
     * Appends /audio/voices to get the full endpoint.
     * Returns a list of voice names/IDs.
     */
    suspend fun fetchVoices(
        baseUrl: String,
        apiKey: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): List<String> {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)
        
        // baseUrl should end with /v1, so we append /audio/voices
        val voicesEndpoint = baseUrl.trimEnd('/') + "/audio/voices"
        
        android.util.Log.d("ApiClient", "Fetching voices from: $voicesEndpoint")
        
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(voicesEndpoint)
                    .get()
                    .build()
                
                val response = executeWithRateLimitRetry(client, request)
                android.util.Log.d("ApiClient", "Voices response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    throw IOException("Failed to fetch voices: ${response.code} - $errorBody")
                }
                
                val body = response.body ?: throw IOException("Empty response")
                val json = body.string()
                android.util.Log.d("ApiClient", "Voices response JSON: $json")
                
                val voices = mutableListOf<String>()
                val root = org.json.JSONObject(json)
                
                val voicesArray = root.optJSONArray("voices")
                if (voicesArray != null) {
                    for (i in 0 until voicesArray.length()) {
                        val item = voicesArray.optJSONObject(i)
                        // qwentts.cpp lists plain names, other servers wrap them in objects -
                        // accept both so the settings screen always shows real voices.
                        val name = item?.optString("name") ?: item?.optString("id") ?: item?.optString("voice")
                            ?: if (item == null) voicesArray.optString(i) else null
                        if (!name.isNullOrEmpty()) {
                            voices.add(name)
                            android.util.Log.d("ApiClient", "Found voice: $name")
                        }
                    }
                } else {
                    android.util.Log.d("ApiClient", "No voices found in response")
                }
                
                android.util.Log.d("ApiClient", "Total voices found: ${voices.size}")
                voices
            } catch (e: Exception) {
                throw IOException("Failed to fetch voices: ${e.message}", e)
            }
        }
    }
}
