package ai.flossyapp.domain.model

// The shipped endpoints name nothing from any developer's network. A fresh install must
// look obviously unconfigured rather than like a dead address that happens to belong to
// someone else, so these placeholders are local and the Settings screen asks for real
// ones while they are still in place.
const val DEFAULT_LLM_BASE_URL = "http://127.0.0.1:8086/v1"
const val DEFAULT_STT_BASE_URL = "http://127.0.0.1:8088/v1"
const val DEFAULT_TTS_BASE_URL = "http://127.0.0.1:8089/v1"

// The language Android says the user speaks: the device's own configured
// default locale, baked into the shipped system prompt so it is in force at
// the start of every session on every call path, and overridden only by an
// explicit user request.
// Null-safe: a plain JVM unit environment (the engine's state-machine
// tests) returns a mocked-default null here, and the fallback locale must
// stand in rather than crash the class initializer.
private val userLocale: java.util.Locale =
    (android.os.LocaleList.getDefault()?.takeIf { it.size() > 0 }?.get(0))
        ?: java.util.Locale.getDefault()
private val userLanguage: String =
    userLocale.getDisplayLanguage(userLocale).ifBlank { userLocale.language }

/**
 * Application settings for the FLOSSy Assistant.
 *
 * All values are persisted via SharedPreferences (see SettingsRepository) and can be configured by the user.
 */
data class AppSettings(
    // LLM Configuration
    val llmBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val llmModelName: String = "/models/Qwen3.5-4B-Q4_K_M.gguf",
    val llmApiKey: String = "",
    // Generation budget. A reasoning model spends its tokens on the chain of
    // thought first, so max_tokens must cover both the reasoning and the answer
    // or the reply comes back empty. Up to 1M tokens (1048576) is accepted;
    // servers that support less clamp it themselves.
    val llmMaxTokens: Int = 1024,
    // How much of that budget the model may spend thinking. 0 omits the
    // parameter entirely, which is what a server without reasoning control
    // needs. Up to 1M tokens (1048576) is accepted.
    val llmReasoningBudgetTokens: Int = 512,
    // Extra chat-template arguments, separated by commas, e.g.
    // "enable_thinking=false, reasoning_effort=low". Empty sends none.
    val llmChatTemplateKwargs: String = "",

    // STT Configuration
    val sttBaseUrl: String = DEFAULT_STT_BASE_URL,
    val sttModelName: String = "ggml-medium.bin",
    val sttApiKey: String = "",

    // TTS Configuration
    val ttsBaseUrl: String = DEFAULT_TTS_BASE_URL,
    val ttsModelName: String = "qwen-talker-1.7b-customvoice-Q4_K_M.gguf",
    val ttsVoiceName: String = "serena",
    val ttsApiKey: String = "",
    val ttsResponseFormat: String = "mp3",
    // A whole-reply synthesis costs the server seconds before a single word is audible,
    // so the default is the per-sentence path: the first sentence plays while the next
    // is still being synthesized.
    val enableTtsStreaming: Boolean = true,
    // A model can leak a run of foreign characters into a reply and the TTS
    // then spells them out one by one. Forbidding non-Latin scripts before
    // synthesis keeps speech in the user's language; the visible text keeps
    // whatever the model wrote.
    val forbidNonLatinTts: Boolean = true,
    // Privacy default: a headset coming off ends the voice call, so the
    // microphone never keeps hearing for the user on an open speaker.
    // Opting in keeps the call on the phone's own mic and earpiece instead.
    val continueCallsOnPhoneWhenHeadsetDisconnects: Boolean = false,

    // MCP tool servers (Model Context Protocol over streamable HTTP, no stdio).
    // Kept only to upgrade an old install into tool instances: the Tools tab
    // edits the structured document below instead, and the flat fields are not
    // read once "tools_config" exists on disk.
    val enableMcpTools: Boolean = false,
    // One server endpoint per line.
    val mcpServerUrls: String = "",

    // The structured tool configuration: internal handlers, search engines and
    // MCP servers, each an instance with its own switch and settings. Serialized
    // by ToolsConfig; blank means the migration has not run yet.
    val toolsConfig: String = "",

    // System Prompt - the language rule is part of the shipped default, named
    // by the device's own locale, not by whatever the conversation is in.
    // The prompt is sent verbatim, so its formatting matters: a previous
    // version prefixed every line with a literal '|' (raw-string leftovers),
    // and that strange wall of pipes is exactly the kind of noise that made
    // a Chinese-trained model slip into Chinese despite the language line.
    // The default is the prompt the user has tuned on their own device; it
    // is carried in code so a fresh install and a factory reset land on it.
    val systemPrompt: String = """
You are Jarvis, a helpful assistant running on the user's Android device.
Respond in English only, unless the user explicitly asks for another language. Maintain brief, natural conversational dialog, suitable for voice calls. Always remember that the user is communicating with you via phone keyboard and imperfect ASR, and do your best to interpret the dialog you receive as coherent English.
Answer in one or two short sentences, in plain words that will be read aloud.
Give the answer directly: do not explain your reasoning, list steps, repeat the question or greet the user. Do not be lazy; do not suggest that the user try other solutions if the user requested you to act.
Never use markdown, bullet points, numbering, emoji or other output which would be awkwardly pronounced by a TTS model unless explicitly requested.
If you are unsure or cannot help, say so honestly in one short sentence.""".trim() + "\n",
    // Wake Word Configuration
    val wakeWordName: String = "hey jarvis",
    val wakeWordSensitivity: Float = 0.5f,
    val enableWakeWordDetection: Boolean = true,
    // How long the app waits for the request once the wake phrase has matched.
    // Nothing heard within this window ends the exchange instead of holding the
    // microphone open for a minute.
    val wakeReplySeconds: Int = 5,
    // How long the wake chat stays open after the last activity on it. Streaming
    // text, spoken replies and the user touching the card all reset that clock,
    // so an exchange that is still working never closes in front of the user.
    val wakeChatCloseSeconds: Int = 5,
    
    // Voice Activity Detection
    val vadSensitivity: Float = 0.4f,
    val vadMinSilenceDurationMs: Int = 800,
    val continuousConversationMode: Boolean = false,
    
    // Service Settings
    val enableForegroundService: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    // Open the app as a live voice session: launching the app starts the voice
    // pipeline listening straight away instead of waiting for the mic button.
    val autoStartVoiceOnLaunch: Boolean = true,
    // Swiping the app away from recents also closes the voice services when
    // this is on; off leaves the hands-free listening alive, which is what a
    // voice assistant needs to hear the wake phrase at any time.
    val stopServicesOnTaskRemoved: Boolean = false,
    val batteryOptimizationExempted: Boolean = false,
    // Chat Display Settings
    val autoTtsEnabled: Boolean = true,
    val autoExpandReasoning: Boolean = true,
    val darkModeEnabled: Boolean = true,

    // Advanced / Debug Settings (hidden by default)
    val debugLoggingEnabled: Boolean = false,
    val httpTimeoutSeconds: Int = 60, // 5-60 seconds
    val forceHttps: Boolean = false,

    // Localization
    val preferredLocale: String = "" // empty = use system default
) {
    /** Every tool instance the Tools tab lists, in the order the user arranged. */
    val toolInstances: List<ToolInstance>
        get() = ToolsConfig.fromJson(toolsConfig).instances

    /** True when at least one enabled tool is fully configured. */
    val toolsReady: Boolean
        get() = toolInstances.any { it.enabled && it.configured }

    /**
     * True while every base URL is still the shipped placeholder. Nothing can work in that
     * state, so the Settings screen shows a "configure endpoints" prompt instead of letting
     * a plausible-looking local address fail silently.
     */
    val endpointsUnconfigured: Boolean
        get() = llmBaseUrl == DEFAULT_LLM_BASE_URL &&
            sttBaseUrl == DEFAULT_STT_BASE_URL &&
            ttsBaseUrl == DEFAULT_TTS_BASE_URL

    /**
     * True only when tools are switched on and at least one server is named, so a service
     * can skip discovery entirely when the user has never opted in.
     */
    val mcpReady: Boolean
        get() = enableMcpTools && mcpServerUrls.isNotBlank()
}
