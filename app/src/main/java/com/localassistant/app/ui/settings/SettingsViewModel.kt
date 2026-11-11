package com.localassistant.app.ui.settings

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.ui.common.bestMatchCost
import com.localassistant.app.ui.common.buildSilentWav
import com.localassistant.app.ui.common.clearWakeReferences
import com.localassistant.app.ui.common.extractWakeFeatures
import com.localassistant.app.ui.common.loadBundledTestAudio
import com.localassistant.app.ui.common.loadWakeReferences
import com.localassistant.app.ui.common.MAX_REFERENCE_FRAMES
import com.localassistant.app.ui.common.MAX_REFERENCE_SECONDS
import com.localassistant.app.ui.common.playAudioBytes
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.referenceFrameCount
import com.localassistant.app.ui.common.saveWakeReference
import com.localassistant.app.ui.common.wakeAgreement
import com.localassistant.app.ui.common.wakeCostThreshold
import com.localassistant.app.ui.common.wakeWordHeard
import com.localassistant.app.ui.common.assistantSettingsIntents
import com.localassistant.app.ui.common.isDefaultAssistantHeld
import com.localassistant.app.service.WakeChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A saved settings preset with a name and the full settings snapshot.
 */
data class PresetEntry(
    val id: String,
    val name: String,
    val settings: com.localassistant.app.domain.model.AppSettings
)

/**
 * The outcome of the last health check or test performed against an endpoint.
 *
 * @field working true when the endpoint answered the request successfully.
 * @field message Human-readable detail: the reply/transcript on success, the
 *              HTTP/transport error on failure.
 */
data class EndpointStatus(
    val working: Boolean,
    val message: String,
    // True while the endpoint is rate limiting (429) and the client is still
    // retrying: neither a pass nor a failure, just a wait.
    val pending: Boolean = false
)

/**
 * ViewModel for the settings screen.
 * 
 * Manages app settings and persists them via SharedPreferences through SettingsRepository.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application.applicationContext)
    private val appContext = application.applicationContext

    // Health checks run when the settings screen opens, so they must fail fast: a
    // dead base URL shows as an error in seconds instead of hanging for a minute.
    private val probeTimeoutSeconds = 5L

    // The enrollment recorder's windows. The wake phrase is a little over a second,
    // so a take that has not started speaking within five seconds is a dead mic, not
    // a slow user - give up then and say so instead of holding the screen at
    // "Recording…" for the recorder's full default 60-second cap.
    private val enrollmentClipSeconds = 10
    private val enrollmentSilenceMs = 1000L
    private val enrollmentNoSpeechMs = 5000L

    private val _settings = MutableStateFlow(repository.loadSettings())
    val settings: StateFlow<com.localassistant.app.domain.model.AppSettings> = _settings.asStateFlow()

    /**
     * Update a single setting by key.
     */
    fun updateSetting(key: String, value: Any?) {
        when (key) {
            "llmBaseUrl" -> {
                _settings.update { it.copy(llmBaseUrl = value as String) }
                _llmStatus.value = null
            }
            "llmModelName" -> _settings.update { it.copy(llmModelName = value as String) }
            "llmApiKey" -> _settings.update { it.copy(llmApiKey = value as String) }
            "llmMaxTokens" -> _settings.update { it.copy(llmMaxTokens = (value as Int).coerceIn(64, 32768)) }
            "llmReasoningBudgetTokens" -> _settings.update { it.copy(llmReasoningBudgetTokens = (value as Int).coerceIn(0, 32768)) }
            "llmChatTemplateKwargs" -> _settings.update { it.copy(llmChatTemplateKwargs = value as String) }

            "sttBaseUrl" -> {
                _settings.update { it.copy(sttBaseUrl = value as String) }
                _sttStatus.value = null
            }
            "sttModelName" -> _settings.update { it.copy(sttModelName = value as String) }
            "sttApiKey" -> _settings.update { it.copy(sttApiKey = value as String) }

            "ttsBaseUrl" -> {
                _settings.update { it.copy(ttsBaseUrl = value as String) }
                _ttsStatus.value = null
            }
            "ttsModelName" -> _settings.update { it.copy(ttsModelName = value as String) }
            "ttsVoiceName" -> _settings.update { it.copy(ttsVoiceName = value as String) }
            "ttsApiKey" -> _settings.update { it.copy(ttsApiKey = value as String) }
            "ttsResponseFormat" -> _settings.update { it.copy(ttsResponseFormat = value as String) }

            "systemPrompt" -> _settings.update { it.copy(systemPrompt = value as String) }

            "wakeWordName" -> _settings.update { it.copy(wakeWordName = value as String) }
            "wakeWordSensitivity" -> _settings.update { it.copy(wakeWordSensitivity = value as Float) }
            "enableWakeWordDetection" -> _settings.update { it.copy(enableWakeWordDetection = value as Boolean) }
            "wakeReplySeconds" -> _settings.update { it.copy(wakeReplySeconds = (value as Int).coerceIn(1, 300)) }
            "wakeChatCloseSeconds" -> _settings.update { it.copy(wakeChatCloseSeconds = (value as Int).coerceIn(1, 300)) }

            "vadSensitivity" -> _settings.update { it.copy(vadSensitivity = value as Float) }
            "vadMinSilenceDurationMs" -> _settings.update { it.copy(vadMinSilenceDurationMs = value as Int) }

            "enableForegroundService" -> _settings.update { it.copy(enableForegroundService = value as Boolean) }
            "continuousConversationMode" -> _settings.update { it.copy(continuousConversationMode = value as Boolean) }
            "autoStartOnBoot" -> _settings.update { it.copy(autoStartOnBoot = value as Boolean) }
            "darkModeEnabled" -> _settings.update { it.copy(darkModeEnabled = value as Boolean) }
            "autoStartVoiceOnLaunch" -> _settings.update { it.copy(autoStartVoiceOnLaunch = value as Boolean) }
            "autoTtsEnabled" -> _settings.update { it.copy(autoTtsEnabled = value as Boolean) }
            "autoExpandReasoning" -> _settings.update { it.copy(autoExpandReasoning = value as Boolean) }

            // TTS Settings
            "enableTtsStreaming" -> _settings.update { it.copy(enableTtsStreaming = value as Boolean) }

            // MCP tool servers
            "enableMcpTools" -> _settings.update { it.copy(enableMcpTools = value as Boolean) }
            "mcpServerUrls" -> _settings.update { it.copy(mcpServerUrls = value as String) }
            "mcpLazyTools" -> _settings.update { it.copy(mcpLazyTools = value as Boolean) }

            // Advanced / Debug Settings
            "debugLoggingEnabled" -> _settings.update { it.copy(debugLoggingEnabled = value as Boolean) }
            "httpTimeoutSeconds" -> _settings.update { it.copy(httpTimeoutSeconds = value as Int) }
            "forceHttps" -> _settings.update { it.copy(forceHttps = value as Boolean) }
            "preferredLocale" -> _settings.update { it.copy(preferredLocale = value as String) }
        }

        // Persist to SharedPreferences via SettingsRepository
        repository.updateSettings(_settings.value)
    }

    /**
     * Reset all settings to defaults.
     */
    fun resetToDefaults() {
        _settings.value = com.localassistant.app.domain.model.AppSettings()
        _llmStatus.value = null
        _sttStatus.value = null
        _ttsStatus.value = null
        repository.resetToDefaults()
    }

    // ==================== Preset Management ====================

    // Presets live in SharedPreferences, so the list is read back on startup -
    // otherwise a preset saved in an earlier session could never be shown again.
    private val presetList = MutableStateFlow<List<PresetEntry>>(repository.getAllPresets())
    val presets: StateFlow<List<PresetEntry>> = presetList.asStateFlow()

    /**
     * Save current settings as a named preset.
     */
    fun savePreset(name: String) {
        // Re-using a name replaces the old snapshot on disk too. The repository keys
        // presets by id, so keeping the stale copy would list two presets with one name.
        presetList.value.firstOrNull { it.name == name }?.let { repository.deletePreset(it.id) }
        val entry = PresetEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            settings = _settings.value
        )
        repository.savePreset(entry)
        presetList.update { current ->
            val existingIndex = current.indexOfFirst { it.name == name }
            if (existingIndex >= 0) {
                current.toMutableList().also { list -> list[existingIndex] = entry }
            } else {
                current + entry
            }
        }
    }

    /**
     * Load a preset, updating current settings.
     */
    fun loadPreset(preset: PresetEntry) {
        _settings.value = preset.settings
        repository.updateSettings(_settings.value)
        // A preset carries its own base URLs, so the previous health checks no longer
        // describe the endpoints that are now configured.
        _llmStatus.value = null
        _sttStatus.value = null
        _ttsStatus.value = null
    }

    /**
     * Delete a preset by ID.
     */
    fun deletePreset(id: String) {
        repository.deletePreset(id)
        presetList.update { current -> current.filterNot { it.id == id } }
    }

    // ==================== Model Management ====================

    private val viewModelScope = CoroutineScope(Dispatchers.IO)

    // LLM Models
    private val _llmModels = MutableStateFlow<List<String>>(emptyList())
    val llmModels: StateFlow<List<String>> = _llmModels.asStateFlow()

    // STT Models
    private val _sttModels = MutableStateFlow<List<String>>(emptyList())
    val sttModels: StateFlow<List<String>> = _sttModels.asStateFlow()

    // TTS Models
    private val _ttsModels = MutableStateFlow<List<String>>(emptyList())
    val ttsModels: StateFlow<List<String>> = _ttsModels.asStateFlow()

    // TTS Voices
    private val _ttsVoices = MutableStateFlow<List<String>>(emptyList())
    val ttsVoices: StateFlow<List<String>> = _ttsVoices.asStateFlow()

    // Loading states
    private val _llmModelLoading = MutableStateFlow(false)
    val llmModelLoading: StateFlow<Boolean> = _llmModelLoading.asStateFlow()

    private val _sttModelLoading = MutableStateFlow(false)
    val sttModelLoading: StateFlow<Boolean> = _sttModelLoading.asStateFlow()

    private val _ttsModelLoading = MutableStateFlow(false)
    val ttsModelLoading: StateFlow<Boolean> = _ttsModelLoading.asStateFlow()

    // ==================== Endpoint health & tests ====================

    // Null means "not checked yet"; cleared whenever a Base URL is edited.
    private val _llmStatus = MutableStateFlow<EndpointStatus?>(null)
    val llmStatus: StateFlow<EndpointStatus?> = _llmStatus.asStateFlow()

    private val _sttStatus = MutableStateFlow<EndpointStatus?>(null)
    val sttStatus: StateFlow<EndpointStatus?> = _sttStatus.asStateFlow()

    private val _ttsStatus = MutableStateFlow<EndpointStatus?>(null)
    val ttsStatus: StateFlow<EndpointStatus?> = _ttsStatus.asStateFlow()

    private val _llmTesting = MutableStateFlow(false)
    val llmTesting: StateFlow<Boolean> = _llmTesting.asStateFlow()

    private val _sttTesting = MutableStateFlow(false)
    val sttTesting: StateFlow<Boolean> = _sttTesting.asStateFlow()

    private val _ttsTesting = MutableStateFlow(false)
    val ttsTesting: StateFlow<Boolean> = _ttsTesting.asStateFlow()

    private val _wakeWordStatus = MutableStateFlow<EndpointStatus?>(null)
    val wakeWordStatus: StateFlow<EndpointStatus?> = _wakeWordStatus.asStateFlow()

    private val _wakeWordTesting = MutableStateFlow(false)
    val wakeWordTesting: StateFlow<Boolean> = _wakeWordTesting.asStateFlow()

    // What the wake section is doing right now. The wake actions spend most of
    // their time at the microphone, so the line has to say "Recording" rather
    // than the generic endpoint wording used by the LLM/STT/TTS probes.
    private val _wakeBusyLabel = MutableStateFlow("Contacting endpoint…")
    val wakeBusyLabel: StateFlow<String> = _wakeBusyLabel.asStateFlow()

    // How many wake-word references the on-device detector can match against; read
    // from the enrollment file so the count survives an app restart.
    private val _wakeReferenceCount = MutableStateFlow(loadWakeReferences(appContext).size)
    val wakeReferenceCount: StateFlow<Int> = _wakeReferenceCount.asStateFlow()

    // Whether the platform has bound this app as its default digital assistant.
    // Null means the platform has no assistant role to ask about, which the screen
    // must render as "cannot tell" rather than as a problem the user can fix.
    private val _defaultAssistantHeld = MutableStateFlow(isDefaultAssistantHeld(appContext))
    val defaultAssistantHeld: StateFlow<Boolean?> = _defaultAssistantHeld.asStateFlow()

    init {
        // A 429 while a test runs is not a failure: the API client retries on its
        // own exponential backoff, so the status line only notes the wait once per
        // episode and the final result overwrites it when the request lands.
        viewModelScope.launch {
            com.localassistant.app.data.remote.ApiClient.connectionWaiting.collect { waiting ->
                if (!waiting) return@collect
                val waitingStatus = EndpointStatus(
                    false,
                    "Awaiting connection... (rate limited, retrying)",
                    pending = true
                )
                when {
                    _llmTesting.value -> _llmStatus.value = waitingStatus
                    _sttTesting.value -> _sttStatus.value = waitingStatus
                    _ttsTesting.value -> _ttsStatus.value = waitingStatus
                }
            }
        }
    }

    /**
     * Re-reads the assistant role after the user has been taken to the Settings
     * page, so the status line reflects whatever they just chose.
     */
    private fun refreshAssistantRole() {
        _defaultAssistantHeld.value = isDefaultAssistantHeld(appContext)
    }

    /**
     * Opens the platform's page for choosing the default digital assistant.
     *
     * The candidates are tried in order because a vendor build may register only
     * one of them; the first one the platform resolves is the one launched. The
     * role is held only after the user finishes choosing on that page, so the
     * status is re-read here to pick up whatever they had already set.
     */
    fun openAssistantSettings() {
        val intents = assistantSettingsIntents(appContext)
        var lastFailure: Exception? = null
        for (intent in intents) {
            try {
                appContext.startActivity(intent)
                refreshAssistantRole()
                return
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        lastFailure?.let {
            android.util.Log.w(
                "LocalAssistant",
                "openAssistantSettings: none of ${intents.map { i -> i.action }} resolved - ${it.message}",
            )
        }
    }

    /**
     * Opens the system page that exempts this app from battery optimizations.
     *
     * The always-on service is a foreground service, and Doze can suspend it while the
     * device idles, which ends a wake chat mid-listen. The platform refuses that request
     * when a service process launches it ("Calling startActivity() from outside of an
     * Activity context"), so the user taps here instead and the intent is started from the
     * app's own context, which is allowed.
     */
    fun requestBatteryExemption() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w(
                "SettingsViewModel",
                "requestBatteryExemption: the exemption page could not be opened - ${describeFailure(e)}",
            )
        }
    }

    /**
     * Names the failure the way the user should see it: the exception type plus
     * its message, so a transport error is never reported as "Unknown error".
     */
    private fun describeFailure(e: Exception): String {
        val detail = e.toString()
        return if (detail.length > 300) "${detail.take(300)}…" else detail
    }

    /**
     * Points a setting at the first option the endpoint actually offers when the
     * saved value is blank or no longer available.
     */
    private fun autoPopulateSetting(key: String, available: List<String>) {
        if (available.isEmpty()) return
        val current = when (key) {
            "llmModelName" -> _settings.value.llmModelName
            "ttsVoiceName" -> _settings.value.ttsVoiceName
            else -> return
        }
        if (current.isBlank() || available.none { it.equals(current, ignoreCase = true) }) {
            updateSetting(key, available.first())
        }
    }

    /**
     * Fetch available models for a given endpoint type.
     * This calls the API and updates the model state flows.
     */
    fun fetchModelsForEndpoint(endpointType: String) {
        val currentSettings = _settings.value
        when (endpointType.lowercase()) {
            "llm" -> {
                _llmModelLoading.value = true
                viewModelScope.launch {
                    try {
                        val models = ApiClient.fetchModels(
                            currentSettings.llmBaseUrl,
                            if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                            probeTimeoutSeconds
                        )
                        _llmModels.value = models
                        _llmStatus.value = EndpointStatus(true, "LLM endpoint reachable (${models.size} models)")
                        autoPopulateSetting("llmModelName", models)
                        android.util.Log.d("SettingsViewModel", "Fetched ${models.size} LLM models")
                    } catch (e: Exception) {
                        _llmStatus.value = EndpointStatus(false, describeFailure(e))
                        android.util.Log.e("SettingsViewModel", "Failed to fetch LLM models: ${e.message}")
                    }
                    _llmModelLoading.value = false
                }
            }
            "stt" -> {
                _sttModelLoading.value = true
                viewModelScope.launch {
                    try {
                        val models = ApiClient.fetchModels(
                            currentSettings.sttBaseUrl,
                            if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                            probeTimeoutSeconds
                        )
                        _sttModels.value = models
                        android.util.Log.d("SettingsViewModel", "Fetched ${models.size} STT models")
                    } catch (e: Exception) {
                        // /v1/models is not part of the OpenAI audio API, so a 404 here is
                        // expected on whisper.cpp and friends - health is probed by transcribing.
                        android.util.Log.w("SettingsViewModel", "STT model listing unavailable: ${e.message}")
                    }
                    probeSttEndpoint(currentSettings)
                    _sttModelLoading.value = false
                }
            }
            "tts" -> {
                _ttsModelLoading.value = true
                viewModelScope.launch {
                    try {
                        val voices = ApiClient.fetchVoices(
                            currentSettings.ttsBaseUrl,
                            if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                            probeTimeoutSeconds
                        )
                        _ttsVoices.value = voices
                        _ttsStatus.value = EndpointStatus(true, "TTS endpoint reachable (${voices.size} voices)")
                        autoPopulateSetting("ttsVoiceName", voices)
                        android.util.Log.d("SettingsViewModel", "Fetched ${voices.size} TTS voices")
                    } catch (e: Exception) {
                        // Servers without a voice-listing route still work, so fall back to a
                        // real synthesis request before calling the endpoint dead.
                        try {
                            val audio = ApiClient.synthesizeSpeech(
                                baseUrl = currentSettings.ttsBaseUrl,
                                apiKey = if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                                text = "a",
                                model = currentSettings.ttsModelName,
                                voice = currentSettings.ttsVoiceName,
                                responseFormat = "wav",
                                timeoutSeconds = probeTimeoutSeconds
                            )
                            _ttsStatus.value = EndpointStatus(
                                audio.isNotEmpty(),
                                if (audio.isEmpty()) "TTS endpoint returned 0 bytes"
                                else "TTS endpoint reachable (voice listing unavailable)"
                            )
                        } catch (e2: Exception) {
                            _ttsStatus.value = EndpointStatus(false, "${describeFailure(e)} / synthesis probe: ${describeFailure(e2)}")
                        }
                        android.util.Log.e("SettingsViewModel", "Failed to fetch TTS voices: ${e.message}")
                    }
                    _ttsModelLoading.value = false
                }
            }
        }
    }

    /**
     * Probes an STT endpoint by transcribing a short silent WAV. A model listing
     * is not a valid health check: /v1/models is absent from the audio API.
     */
    private suspend fun probeSttEndpoint(currentSettings: com.localassistant.app.domain.model.AppSettings) {
        try {
            val transcript = ApiClient.transcribeAudio(
                currentSettings.sttBaseUrl,
                if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                buildSilentWav(),
                currentSettings.sttModelName,
                probeTimeoutSeconds
            )
            _sttStatus.value = EndpointStatus(
                true,
                if (transcript.isBlank()) "STT endpoint responded (empty transcript)"
                else "STT endpoint responded: ${transcript.trim().take(60)}"
            )
        } catch (e: Exception) {
            _sttStatus.value = EndpointStatus(false, describeFailure(e))
        }
    }

    /**
     * Runs a real round-trip test against one endpoint and records the outcome in
     * that endpoint's status: the LLM gets a test prompt, the STT endpoint
     * transcribes the bundled sample recording, and the TTS endpoint synthesizes
     * a phrase that is played on the device.
     */
    fun runEndpointTest(endpointType: String) {
        val currentSettings = _settings.value
        when (endpointType.lowercase()) {
            "llm" -> {
                _llmTesting.value = true
                viewModelScope.launch {
                    try {
                        // The test carries the configured generation controls,
                        // because that is the only way it can see the request the
                        // chats actually send: a plain request stays green while
                        // every chat 400s.
                        val response = ApiClient.chatCompletion(
                            baseUrl = currentSettings.llmBaseUrl,
                            apiKey = if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                            messages = listOf(mapOf("role" to "user", "content" to "Reply with just: ok")),
                            model = currentSettings.llmModelName,
                            maxTokens = currentSettings.llmMaxTokens,
                            reasoningBudgetTokens = currentSettings.llmReasoningBudgetTokens,
                            chatTemplateKwargs = currentSettings.llmChatTemplateKwargs,
                            stream = false
                        )
                        val reply = response.content.trim()
                        _llmStatus.value = if (reply.isEmpty()) {
                            EndpointStatus(false, "LLM replied with an empty message")
                        } else {
                            EndpointStatus(true, "LLM replied: ${reply.take(80)}")
                        }
                    } catch (e: Exception) {
                        _llmStatus.value = EndpointStatus(false, describeFailure(e))
                    }
                    _llmTesting.value = false
                }
            }
            "stt" -> {
                _sttTesting.value = true
                viewModelScope.launch {
                    try {
                        val audio = loadBundledTestAudio(appContext)
                        if (audio.isEmpty()) {
                            _sttStatus.value = EndpointStatus(false, "Bundled test audio (assets/voice_clone.wav) is missing")
                        } else {
                            val transcript = ApiClient.transcribeAudio(
                                currentSettings.sttBaseUrl,
                                if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                                audio,
                                currentSettings.sttModelName
                            )
                            _sttStatus.value = if (transcript.isBlank()) {
                                EndpointStatus(true, "STT endpoint responded (empty transcript)")
                            } else {
                                EndpointStatus(true, "Transcribed: ${transcript.trim().take(120)}")
                            }
                        }
                    } catch (e: Exception) {
                        _sttStatus.value = EndpointStatus(false, describeFailure(e))
                    }
                    _sttTesting.value = false
                }
            }
            "tts" -> {
                _ttsTesting.value = true
                viewModelScope.launch {
                    try {
                        val audio = ApiClient.synthesizeSpeech(
                            baseUrl = currentSettings.ttsBaseUrl,
                            apiKey = if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                            text = "Test successful",
                            model = currentSettings.ttsModelName,
                            voice = currentSettings.ttsVoiceName,
                            responseFormat = currentSettings.ttsResponseFormat
                        )
                        if (audio.isEmpty()) {
                            _ttsStatus.value = EndpointStatus(false, "TTS endpoint returned 0 bytes")
                        } else {
                            var played = playAudioBytes(audio)
                            var playedBytes = audio.size
                            if (!played && currentSettings.ttsResponseFormat != "wav") {
                                val wav = ApiClient.synthesizeSpeech(
                                    baseUrl = currentSettings.ttsBaseUrl,
                                    apiKey = if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                                    text = "Test successful",
                                    model = currentSettings.ttsModelName,
                                    voice = currentSettings.ttsVoiceName,
                                    responseFormat = "wav"
                                )
                                if (wav.isNotEmpty() && playAudioBytes(wav)) {
                                    played = true
                                    playedBytes = wav.size
                                }
                            }
                            _ttsStatus.value = EndpointStatus(
                                true,
                                if (played) "Played ${playedBytes} bytes on the device"
                                else "Received ${audio.size} bytes, but playback failed"
                            )
                        }
                    } catch (e: Exception) {
                        _ttsStatus.value = EndpointStatus(false, describeTtsFailure(currentSettings, e))
                    }
                    _ttsTesting.value = false
                }
            }
        }
    }

    /**
     * Turns a failed TTS round-trip into an actionable message. A rejected voice is
     * the common case, so the endpoint's real voice list is fetched and pushed into
     * the UI: the user taps a valid voice and runs the test again.
     */
    private suspend fun describeTtsFailure(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        failure: Exception
    ): String {
        val detail = describeFailure(failure)
        if (!detail.lowercase().contains("voice")) return detail

        try {
            val voices = ApiClient.fetchVoices(
                currentSettings.ttsBaseUrl,
                if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                probeTimeoutSeconds
            )
            if (voices.isNotEmpty()) {
                _ttsVoices.value = voices
                return "Voice '${currentSettings.ttsVoiceName}' is not valid here - pick one of: ${voices.joinToString(", ")} and test again"
            }
            return "Voice '${currentSettings.ttsVoiceName}' is not valid here and this endpoint offers no voices - it will use its default"
        } catch (e: Exception) {
            return "Voice '${currentSettings.ttsVoiceName}' is not valid here (voice listing unavailable)"
        }
    }

    /**
     * Records one bounded utterance from the microphone, used by the wake-word
     * enrollment and test actions.
     *
     * The always-on wake loop holds an [AudioRecord] essentially all of the time,
     * and the platform runs only one at a time: opening a second instance starves
     * the first, which is what made the enrollment and the test capture a half-dead
     * mic - the wake loop's own chunks fell to RMS 50 while this one heard the room.
     * So the loop is paused for the duration of the clip and handed the mic back
     * afterwards, leaving exactly one capture live.
     *
     * The recorder windows are passed explicitly rather than left to their defaults:
     * the default no-speech timeout of zero disables the give-up entirely and the
     * default cap is 60 seconds, so a starved or silent enrollment mic used to hold
     * this screen at "Recording…" for a full minute before answering anything.
     */
    private suspend fun recordWakeClip(): ByteArray? {
        val paused = pauseWakeLoopMic()
        return try {
            withContext(Dispatchers.IO) {
                recordAudioWithVAD(
                    appContext,
                    java.util.concurrent.atomic.AtomicBoolean(false),
                    maxSeconds = enrollmentClipSeconds,
                    silenceThresholdMs = enrollmentSilenceMs,
                    noSpeechTimeoutMs = enrollmentNoSpeechMs
                )
            }
        } finally {
            if (paused) resumeWakeLoopMic()
        }
    }

    /**
     * Asks the wake service to let go of its clip so this screen can own the mic.
     * Answers whether the loop is left paused, so the caller knows whether it has
     * to hand the mic back.
     */
    private fun pauseWakeLoopMic(): Boolean =
        try {
            appContext.startForegroundService(
                WakeChatService.buildIntent(appContext, WakeChatService.ACTION_STOP)
            )
            true
        } catch (e: Exception) {
            android.util.Log.w("WakeEnroll", "Wake loop could not be paused for the mic: ${describeFailure(e)}")
            false
        }

    private fun resumeWakeLoopMic() {
        try {
            appContext.startForegroundService(
                WakeChatService.buildIntent(appContext, WakeChatService.ACTION_START)
            )
        } catch (e: Exception) {
            android.util.Log.w("WakeEnroll", "Wake loop could not be resumed: ${describeFailure(e)}")
        }
    }

    /**
     * Records the wake phrase and enrolls it as a reference the on-device detector
     * matches against. Several takes make matching more reliable, so this is
     * expected to be tapped repeatedly.
     */
    fun recordWakeReference() {
        _wakeWordTesting.value = true
        viewModelScope.launch {
            _wakeBusyLabel.value = "Recording…"
            val clip = recordWakeClip()
            _wakeBusyLabel.value = "Analysing on-device…"
            if (clip == null || clip.isEmpty()) {
                android.util.Log.w("WakeEnroll", "No speech heard in the clip")
                _wakeWordStatus.value = EndpointStatus(false, "No speech heard - say the wake phrase while recording")
            } else {
                val features = extractWakeFeatures(clip)
                // Every branch has to name itself in the log: an enrollment that
                // reaches the screen but never lands in the file is invisible
                // otherwise, and the count the screen shows is then not the count
                // the matcher can use.
                android.util.Log.w(
                    "WakeEnroll",
                    "Clip ${clip.size / 2} samples -> ${referenceFrameCount(features)} frames, limit $MAX_REFERENCE_FRAMES"
                )
                if (features.isEmpty()) {
                    android.util.Log.w("WakeEnroll", "No wake features built from the clip")
                    _wakeWordStatus.value = EndpointStatus(false, "No speech found in the clip - speak the whole phrase")
                } else if (referenceFrameCount(features) > MAX_REFERENCE_FRAMES) {
                    android.util.Log.w("WakeEnroll", "Clip rejected - too many frames")
                    _wakeWordStatus.value = EndpointStatus(
                        false,
                        "Clip too long - keep the phrase under $MAX_REFERENCE_SECONDS seconds"
                    )
                } else if (!saveWakeReference(appContext, features)) {
                    _wakeWordStatus.value = EndpointStatus(
                        false,
                        "Could not save the reference - the enrollment file is not writable",
                    )
                } else {
                    _wakeReferenceCount.value = loadWakeReferences(appContext).size
                    _wakeWordStatus.value = EndpointStatus(
                        true,
                        "Reference enrolled - ${_wakeReferenceCount.value} recorded. More takes match better."
                    )
                }
            }
            _wakeWordTesting.value = false
        }
    }

    /**
     * Drops every enrolled wake-word reference, so the detector matches nothing
     * until new takes are recorded.
     */
    fun clearWakeWordReferences() {
        clearWakeReferences(appContext)
        _wakeReferenceCount.value = 0
        _wakeWordStatus.value = null
    }

    /**
     * Starts one exchange without waiting for the wake phrase.
     *
     * The debug path for the rest of the pipeline - record the request,
     * transcribe it, ask the LLM, speak the reply - so an endpoint can be
     * exercised without the wake phrase being recognised first. The exchange
     * itself is carried by the service, so this only reports that the request
     * reached it: the overlay and the persistent notification carry the rest.
     */
    fun triggerWakeChat() {
        _wakeWordStatus.value = try {
            val intent = WakeChatService.buildIntent(appContext, WakeChatService.ACTION_TRIGGER_EXCHANGE)
            appContext.startForegroundService(intent)
            EndpointStatus(
                true,
                "Exchange started - the card raises and the reply follows on the notification",
            )
        } catch (e: Exception) {
            EndpointStatus(false, describeFailure(e))
        }
    }

    /**
     * Tests the on-device detector end to end: records a live clip and reports the
     * same decision the wake service makes, with the cost and threshold that
     * decision was taken against. No endpoint is involved.
     */
    fun runWakeWordTest() {
        val currentSettings = _settings.value
        _wakeWordTesting.value = true
        viewModelScope.launch {
            val references = loadWakeReferences(appContext)
            // Every branch has to name itself in the log, or a test that produced
            // no WakeMatch line at all leaves the adb reader guessing which of the
            // three exits the taps actually took.
            android.util.Log.w(
                "WakeMatch",
                "Test started against ${references.size} enrolled reference(s)"
            )
            if (references.isEmpty()) {
                android.util.Log.w("WakeMatch", "Test exit: enrollment empty")
                _wakeWordStatus.value = EndpointStatus(false, "No references enrolled - record the wake phrase first")
            } else {
                _wakeBusyLabel.value = "Recording…"
                val clip = recordWakeClip()
                _wakeBusyLabel.value = "Matching on-device…"
                if (clip == null || clip.isEmpty()) {
                    // The recorder answers with nothing when the VAD never heard
                    // speech, so a miss here means the mic never got the phrase -
                    // the wake loop holding the same mic is the prime suspect.
                    android.util.Log.w(
                        "WakeMatch",
                        "Test exit: no speech in the clip (${clip?.size ?: 0} samples)"
                    )
                    _wakeWordStatus.value = EndpointStatus(false, "No speech heard - say the wake phrase while testing")
                } else {
                    val features = extractWakeFeatures(clip)
                    // The verdict has to come from the same decision the wake loop
                    // makes, or a test that passes would not predict the device.
                    val heard = wakeWordHeard(features, references, currentSettings.wakeWordSensitivity)
                    val cost = formatCost(bestMatchCost(features, references))
                    val threshold = formatCost(wakeCostThreshold(currentSettings.wakeWordSensitivity, references))
                    // The best cost alone cannot explain a miss: one enrolled take
                    // that is not really the phrase (a cough, a half-spoken warm-up)
                    // costs far above the bar forever, and no sensitivity can reach
                    // it while the honest takes still match each other. Scoring every
                    // take separately names the one to record over, which is the only
                    // way the user can act on a miss.
                    val scores = references.map { formatCost(bestMatchCost(features, listOf(it))) }
                    val scoreLine = scores.joinToString(" ")
                    // The cost is not the whole story: a take can sit under the bar
                    // and still confirm on too few window positions to vote. Naming
                    // each take's confirming windows against its own bound is what
                    // makes that failure actionable, and the log line is what makes
                    // it readable from adb without the screen in front of the user.
                    val agreementLine = wakeAgreement(features, references, currentSettings.wakeWordSensitivity).joinToString(" ")
                    // A take enrolled with far more frames than the live clip is
                    // compared against a window of the clip's own length, so its
                    // length is the second quantity that explains a cost.
                    val takeFrames = references.map { referenceFrameCount(it) }.joinToString(" ")
                    android.util.Log.w(
                        "WakeMatch",
                        "clip ${referenceFrameCount(features)} frames, takes $takeFrames frames, cost $cost, threshold $threshold, take by take $scoreLine, agreement $agreementLine -> ${if (heard) "MATCH" else "NO MATCH"}"
                    )
                    _wakeWordStatus.value = EndpointStatus(
                        heard,
                        if (heard)
                            "Matched '${currentSettings.wakeWordName}' on-device (cost $cost, threshold $threshold)"
                        else
                            "No match (cost $cost, threshold $threshold) - take by take: $scoreLine, agreement: $agreementLine. Clear and re-record the takes that miss."
                    )
                }
            }
            _wakeWordTesting.value = false
        }
    }

    /**
     * Two decimals, not one: the whole sensitivity slider spans 0.377 to 0.448, and a
     * one-decimal truncation prints both ends as "0.3" - exactly the comparison the
     * costs above it have to be read against.
     */
    private fun formatCost(value: Float): String =
        if (value >= Float.MAX_VALUE / 2.0f) "no match"
        else ((value * 100.0f).toInt() / 100.0f).toString()
}
