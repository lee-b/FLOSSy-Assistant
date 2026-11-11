package com.localassistant.app.domain.model

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
private val userLocale: java.util.Locale =
    (android.os.LocaleList.getDefault().takeIf { it.size() > 0 }?.get(0))
        ?: java.util.Locale.getDefault()
private val userLanguage: String =
    userLocale.getDisplayLanguage(userLocale).ifBlank { userLocale.language }

/**
 * Application settings for the Local Assistant.
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
    // or the reply comes back empty.
    val llmMaxTokens: Int = 1024,
    // How much of that budget the model may spend thinking. 0 omits the
    // parameter entirely, which is what a server without reasoning control needs.
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

    // MCP tool servers (Model Context Protocol over streamable HTTP, no stdio).
    val enableMcpTools: Boolean = false,
    // One server endpoint per line. Their tools are offered to the model, which is
    // what lets it answer "what time is it" from a clock tool instead of guessing.
    val mcpServerUrls: String = "",
    // Offer one meta-tool that names a server tool as an argument instead of
    // putting every schema in the request, which keeps a large tool list out of
    // the context window.
    val mcpLazyTools: Boolean = true,
    
    // System Prompt - the language rule is part of the shipped default, named
    // by the device's own locale, not by whatever the conversation is in.
    // The prompt is sent verbatim, so its formatting matters: a previous
    // version prefixed every line with a literal '|' (raw-string leftovers),
    // and that strange wall of pipes is exactly the kind of noise that made
    // a Chinese-trained model slip into Chinese despite the language line.
    val systemPrompt: String = """
You are LocalAssistant, running on the user's Android device.
Respond in $userLanguage only, unless the user explicitly asks for another language. Never output characters from other scripts.
Answer in one or two short sentences, in plain words that will be read aloud.
Give the answer directly: do not explain your reasoning, list steps, repeat the question or greet the user.
Never use markdown, bullet points, numbering or emoji.
If you are unsure or cannot help, say so honestly in one short sentence.""".trim() + "\n",
    // Wake Word Configuration
    val wakeWordName: String = "hey assistant",
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
