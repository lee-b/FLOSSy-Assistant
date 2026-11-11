package com.localassistant.app.ui.settings

import android.content.ClipData
import android.content.Context.CLIPBOARD_SERVICE
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localassistant.app.data.settings.SettingsRepository

// ==================== Helper Composables (defined first for forward reference) ====================

@Composable
fun SettingsSection(title: String, icon: String, description: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = icon, modifier = Modifier.size(24.dp), style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxLines: Int = 2,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            maxLines = maxLines,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

/**
 * A whole-number setting. The field only accepts digits, so a value that is not
 * a number never reaches the ViewModel - an empty field falls back to the
 * default the caller passes.
 */
@Composable
fun SettingNumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    placeholder: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value.toString(),
            onValueChange = { entered ->
                val parsed = entered.trim().toIntOrNull()
                if (parsed != null) onValueChange(parsed)
            },
            placeholder = { Text(placeholder) },
            maxLines = 1,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TestButton(label: String, enabled: Boolean, isLoading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !isLoading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (enabled && !isLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
        Text(text = label)
        if (isLoading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * Renders the live health of one endpoint right below its Base URL field: a spinner
 * while a probe/test is running, then the outcome (green check / red cross) with the
 * message the ViewModel collected - including the HTTP error body on failures.
 */
@Composable
fun EndpointStatusLine(
    status: EndpointStatus?,
    busy: Boolean,
    busyLabel: String = "Contacting endpoint…",
) {
    if (busy) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = busyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        val icon = if (status == null) "❔" else when {
            status.working -> "✅"
            status.pending -> "⏳"
            else -> "❌"
        }
        val color = when {
            status == null -> MaterialTheme.colorScheme.onSurfaceVariant
            status.working -> MaterialTheme.colorScheme.primary
            status.pending -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.error
        }
        val message = status?.message ?: "Not checked yet - tap Test to probe this endpoint"
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Lists the models/voices the endpoint actually offers as selectable chips, so the
 * Model Name and Voice Name fields can be filled from real values instead of typed
 * by hand. The chip matching the saved value is highlighted.
 */
@Composable
fun EndpointChoices(title: String, options: List<String>, current: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(6.dp))
    // One full-width choice per line: model ids are long ("llama/local/medium"
    // and friends), and a three-column chip grid both truncates them badly and
    // makes tapping the right one fiddly. The text wraps over two lines rather
    // than clipping, so the whole id stays readable.
    options.take(20).forEach { option ->
        val active = option.equals(current, ignoreCase = true)
        Button(
            onClick = { onSelect(option) },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(text = option, maxLines = 2)
        }
    }
}

// ==================== Settings Screen ====================

/**
 * Settings screen for configuring the Local Assistant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val llmModels by viewModel.llmModels.collectAsState()
    val sttModels by viewModel.sttModels.collectAsState()
    val ttsVoices by viewModel.ttsVoices.collectAsState()
    val llmStatus by viewModel.llmStatus.collectAsState()
    val sttStatus by viewModel.sttStatus.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val llmBusy by viewModel.llmModelLoading.collectAsState()
    val llmTesting by viewModel.llmTesting.collectAsState()
    val sttBusy by viewModel.sttModelLoading.collectAsState()
    val sttTesting by viewModel.sttTesting.collectAsState()
    val ttsBusy by viewModel.ttsModelLoading.collectAsState()
    val ttsTesting by viewModel.ttsTesting.collectAsState()
    val wakeWordStatus by viewModel.wakeWordStatus.collectAsState()
    val wakeWordTesting by viewModel.wakeWordTesting.collectAsState()
    val wakeReferenceCount by viewModel.wakeReferenceCount.collectAsState()
    val wakeBusyLabel by viewModel.wakeBusyLabel.collectAsState()
    val defaultAssistantHeld by viewModel.defaultAssistantHeld.collectAsState()
    val savedPresets by viewModel.presets.collectAsState()

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var factoryResetConfirmed by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var endpointsProbed by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Settings, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!endpointsProbed) {
                endpointsProbed = true
                viewModel.fetchModelsForEndpoint("llm")
                viewModel.fetchModelsForEndpoint("stt")
                viewModel.fetchModelsForEndpoint("tts")
            }

            if (settings.endpointsUnconfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Endpoints not configured",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "The LLM, STT and TTS addresses still hold the placeholders this app ships with, so nothing will answer yet. Set the three Base URL fields to your own servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            SettingsSection("LLM (Language Model)", "🧠", "Large language model endpoint configuration") {
                SettingTextField("Base URL", settings.llmBaseUrl, { viewModel.updateSetting("llmBaseUrl", it) }, "http://192.168.1.2:8080/v1")
                EndpointStatusLine(llmStatus, llmBusy || llmTesting)
                SettingTextField("Model Name", settings.llmModelName, { viewModel.updateSetting("llmModelName", it) }, "llama-3.1-8b-instruct")
                EndpointChoices("Available models", llmModels, settings.llmModelName) { viewModel.updateSetting("llmModelName", it) }
                SettingTextField("API Key (optional)", settings.llmApiKey, { viewModel.updateSetting("llmApiKey", it) }, "Leave empty if not required")
                SettingNumberField(
                    "Max Tokens",
                    settings.llmMaxTokens,
                    { viewModel.updateSetting("llmMaxTokens", it) },
                    "Total reply budget, thinking included"
                )
                SettingNumberField(
                    "Reasoning Budget (tokens)",
                    settings.llmReasoningBudgetTokens,
                    { viewModel.updateSetting("llmReasoningBudgetTokens", it) },
                    "0 leaves the server decide"
                )
                SettingTextField(
                    "Chat Template Arguments",
                    settings.llmChatTemplateKwargs,
                    { viewModel.updateSetting("llmChatTemplateKwargs", it) },
                    "e.g. enable_thinking=false, reasoning_effort=low",
                    maxLines = 4
                )
                Text(
                    text = "Max Tokens is the whole reply budget: a thinking model spends it on reasoning first, so a value that is too small answers with an empty message. The reasoning budget caps only the thinking; leave it at 0 when the server has no such control. Chat template arguments are sent as the server's chat_template_kwargs object - separate them with commas, for example enable_thinking=false, reasoning_effort=low, preserve_thinking=true. Leave it empty for servers that take none.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingSwitch("Auto-expand Reasoning", settings.autoExpandReasoning, { viewModel.updateSetting("autoExpandReasoning", it) })
                SettingTextField(
                    "System Prompt",
                    settings.systemPrompt,
                    { viewModel.updateSetting("systemPrompt", it) },
                    "How the assistant should answer",
                    maxLines = 6
                )
                Text(
                    text = "The default asks for one or two spoken sentences without reasoning, which is what a small local model and the text-to-speech reply want. Edit it to change tone or verbosity; the wake chat uses it too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { viewModel.updateSetting("systemPrompt", SettingsRepository.DEFAULT_SYSTEM_PROMPT) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore default prompt")
                }
                TestButton("Test LLM", true, llmTesting) { viewModel.runEndpointTest("llm") }
            }

            SettingsSection("Speech-to-Text (STT)", "🎤", "Voice recognition endpoint configuration") {
                SettingTextField("Base URL", settings.sttBaseUrl, { viewModel.updateSetting("sttBaseUrl", it) }, "http://192.168.1.2:8080/v1")
                EndpointStatusLine(sttStatus, sttBusy || sttTesting)
                SettingTextField("Model Name", settings.sttModelName, { viewModel.updateSetting("sttModelName", it) }, "whisper-large-v3")
                EndpointChoices("Available models", sttModels, settings.sttModelName) { viewModel.updateSetting("sttModelName", it) }
                SettingTextField("API Key (optional)", settings.sttApiKey, { viewModel.updateSetting("sttApiKey", it) }, "Leave empty if not required")
                TestButton("Test STT", true, sttTesting) { viewModel.runEndpointTest("stt") }
            }
            
            SettingsSection("Text-to-Speech (TTS)", "🔊", "Speech synthesis endpoint and voice settings") {
                SettingTextField("Base URL", settings.ttsBaseUrl, { viewModel.updateSetting("ttsBaseUrl", it) }, "http://192.168.1.2:5001/v1")
                EndpointStatusLine(ttsStatus, ttsBusy || ttsTesting)
                SettingTextField("Model Name", settings.ttsModelName, { viewModel.updateSetting("ttsModelName", it) }, "default")
                SettingTextField("Voice Name", settings.ttsVoiceName, { viewModel.updateSetting("ttsVoiceName", it) }, "en_US-ryan-high")
                EndpointChoices("Available voices", ttsVoices, settings.ttsVoiceName) { viewModel.updateSetting("ttsVoiceName", it) }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Format:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    Button(
                        onClick = { viewModel.updateSetting("ttsResponseFormat", "mp3") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.ttsResponseFormat == "mp3") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("MP3") }
                    Button(
                        onClick = { viewModel.updateSetting("ttsResponseFormat", "wav") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.ttsResponseFormat == "wav") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("WAV") }
                }
                SettingSwitch("Auto TTS Playback", settings.autoTtsEnabled, { viewModel.updateSetting("autoTtsEnabled", it) })
                SettingSwitch("Enable Streaming TTS", settings.enableTtsStreaming, { viewModel.updateSetting("enableTtsStreaming", it) })
                TestButton("Test TTS", true, ttsTesting) { viewModel.runEndpointTest("tts") }
            }

            SettingsSection("Tool Servers", "🔧", "Let the model ask a server to do something for it") {
                SettingSwitch("Enable MCP Tools", settings.enableMcpTools, { viewModel.updateSetting("enableMcpTools", it) })
                SettingTextField(
                    "Server URLs",
                    settings.mcpServerUrls,
                    { viewModel.updateSetting("mcpServerUrls", it) },
                    "One streamable-HTTP endpoint per line",
                    maxLines = 4
                )
                SettingSwitch("Advertise Tools Lazily", settings.mcpLazyTools, { viewModel.updateSetting("mcpLazyTools", it) })
                Text(
                    text = "Tools reach the model only when both switches are on and a server is named. Lazy mode offers the model one entry point that names a tool as an argument, which keeps a large tool list out of its context; turning it off sends every schema and lets the model choose directly. A server that cannot be reached is skipped, and its tools are simply not offered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSection("Appearance", "🎨", "How the interface looks") {
                SettingSwitch("Dark Mode (gray Gruvbox)", settings.darkModeEnabled, { viewModel.updateSetting("darkModeEnabled", it) })
                // Launching the app is a voice interaction: the pipeline starts
                // listening immediately rather than waiting for the mic button.
                SettingSwitch("Voice on Launch", settings.autoStartVoiceOnLaunch, { viewModel.updateSetting("autoStartVoiceOnLaunch", it) })
            }

            SettingsSection("Wake Word", "🔑", "Say the phrase to start a conversation without tapping") {
                EndpointStatusLine(wakeWordStatus, wakeWordTesting, busyLabel = wakeBusyLabel)
                SettingTextField("Wake Phrase", settings.wakeWordName, { viewModel.updateSetting("wakeWordName", it) }, "hey assistant")
                Text(
                    text = "The phrase is recognised on-device from audio you record here: the clip is reduced to mel-frequency cepstral features and matched against your recordings with dynamic time warping. No model download and no endpoint is involved, so record the phrase a few times to make matching reliable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Recorded references: ${wakeReferenceCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (wakeReferenceCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.recordWakeReference() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Record Reference") }
                    Button(
                        onClick = { viewModel.clearWakeWordReferences() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Clear References") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Sensitivity:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    Slider(
                        value = settings.wakeWordSensitivity,
                        onValueChange = { viewModel.updateSetting("wakeWordSensitivity", it) },
                        valueRange = 0f..1f,
                        steps = 20,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Drag it anywhere between the ends - now at ${(settings.wakeWordSensitivity * 100).toInt()}%. At 0% anything close to a reference wakes the app; at 100% only the exact takes you enrolled do. The threshold is measured from your own recordings, so it needs no absolute number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingSwitch("Wake Word Detection", settings.enableWakeWordDetection, { viewModel.updateSetting("enableWakeWordDetection", it) })
                TestButton("Test Wake Word", true, wakeWordTesting) { viewModel.runWakeWordTest() }
            }

            SettingsSection("Wake Word Chat", "🗗", "How long an exchange stays open once the phrase is heard") {
                Text(
                    text = "The reply window is how long you get to say the request after the wake phrase matches - nothing heard inside it ends the exchange instead of holding the microphone open. The close timeout is how long the card stays on screen after the last thing happened on it: streamed text, a spoken reply and tapping the card all restart that clock, so a reply that is still working is never taken away. Tapping the area above the card closes it, and its history is dropped either way - a wake chat holds one exchange, never a log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingNumberField(
                    "Reply Window (seconds)",
                    settings.wakeReplySeconds,
                    { viewModel.updateSetting("wakeReplySeconds", it) },
                    "Time to say the request"
                )
                SettingNumberField(
                    "Chat Close Timeout (seconds)",
                    settings.wakeChatCloseSeconds,
                    { viewModel.updateSetting("wakeChatCloseSeconds", it) },
                    "How long an idle card stays open"
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.triggerWakeChat() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Trigger An Exchange") }
                EndpointStatusLine(wakeWordStatus, wakeWordTesting)
            }

            SettingsSection("Default Digital Assistant", "🎙️", "Let the system host the wake chat over any screen") {
                Text(
                    text = when (defaultAssistantHeld) {
                        true -> "Set as the default digital assistant - the wake chat is drawn over the home screen and over any other app, and its reply arrives on the notification."
                        false -> "Not set as the default digital assistant - the wake chat only appears inside this app. Choose LocalAssistant in the system's list to have it host conversations everywhere else."
                        else -> "This platform has no default-assistant setting, so the wake chat stays inside the app."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (defaultAssistantHeld) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (defaultAssistantHeld != true) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.openAssistantSettings() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Open the assistant settings") }
                }
            }

            SettingsSection("Battery Optimization", "🔋", "Keep the always-on service alive while the tab sleeps") {
                Text(
                    text = "Android may suspend a foreground service once the device is idle, which ends a wake chat mid-listen. Tapping the button opens the system page that exempts this app from that; the request has to come from the app, not from the service itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.requestBatteryExemption() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Request Battery Exemption") }
            }

            SettingsSection("Data Management", "💾", "Export, import, and manage your settings data") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showClearDataDialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Clear Data") }
                    Button(onClick = { 
                        factoryResetConfirmed = false
                        showFactoryResetDialog = true 
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Factory Reset") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { 
                        copyEndpointsToClipboard(context, settings)
                        android.widget.Toast.makeText(context, "Endpoints copied!", android.widget.Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text("Copy Endpoints") }
                    Button(onClick = { showSavePresetDialog = true }, modifier = Modifier.weight(1f)) { Text("Save Preset") }
                }
            }

            SettingsSection("Saved Presets", "🗂️", "Apply or remove a configuration you stored earlier") {
                if (savedPresets.isEmpty()) {
                    Text(
                        text = "No presets saved yet - use Save Preset above to store the current settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                savedPresets.forEach { preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = preset.name,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                viewModel.loadPreset(preset)
                                android.widget.Toast.makeText(context, "Preset '${preset.name}' applied!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Apply") }
                        Button(
                            onClick = { viewModel.deletePreset(preset.id) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
    
    // Dialogs
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("🗑️ Clear All Data") },
            text = { Text("This will reset all settings to defaults AND delete any stored data including chat history. This action cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.resetToDefaults()
                    showClearDataDialog = false
                    android.widget.Toast.makeText(context, "All data cleared!", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Clear All Data") }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") } }
        )
    }
    
    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryResetDialog = false; factoryResetConfirmed = false },
            title = { Text("⚠️ Reset to Factory Defaults") },
            text = {
                Column {
                    val resetMessage = if (factoryResetConfirmed) {
                        "Tap 'RESET' one more time to confirm. This will erase ALL data including settings, chat history, and SharedPreferences."
                    } else {
                        "This is a DEEP factory reset that clears EVERYTHING - settings, chat history, preferences, and all stored data. This cannot be undone!\n\nTap 'CONFIRM' below to proceed with the factory reset."
                    }
                    Text(resetMessage)
                    if (!factoryResetConfirmed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { factoryResetConfirmed = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🔒 Confirm Intent") }
                    }
                }
            },
            confirmButton = {
                if (factoryResetConfirmed) {
                    Button(onClick = {
                        viewModel.resetToDefaults()
                        showFactoryResetDialog = false
                        factoryResetConfirmed = false
                        android.widget.Toast.makeText(context, "Factory reset complete!", android.widget.Toast.LENGTH_LONG).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🔓 RESET") }
                } else {
                    Button(onClick = { factoryResetConfirmed = true }) { Text("Confirm Reset") }
                }
            },
            dismissButton = { TextButton(onClick = { showFactoryResetDialog = false; factoryResetConfirmed = false }) { Text("Cancel") } }
        )
    }
    
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("💾 Save Preset") },
            text = {
                Column {
                    Text("Save current settings as a named preset for quick loading later.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        placeholder = { Text("e.g., Home, Office, Car") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (presetNameInput.isNotBlank()) {
                        viewModel.savePreset(presetNameInput.trim())
                        presetNameInput = ""
                        showSavePresetDialog = false
                        android.widget.Toast.makeText(context, "Preset saved!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = presetNameInput.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") } }
        )
    }
}

// ==================== Utility Functions ====================

fun copyEndpointsToClipboard(context: android.content.Context, settings: com.localassistant.app.domain.model.AppSettings) {
    val text = buildString {
        append("=== Local Assistant Endpoints ===\n")
        append("LLM: ${settings.llmBaseUrl}\n")
        append("STT: ${settings.sttBaseUrl}\n")
        append("TTS: ${settings.ttsBaseUrl}\n")
    }
    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Endpoints", text))
}
