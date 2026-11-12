package ai.flossyapp.ui.settings

import android.content.ClipData
import android.content.Context.CLIPBOARD_SERVICE
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.flossyapp.data.settings.SettingsRepository
import ai.flossyapp.domain.model.AppSettings
import ai.flossyapp.domain.model.ToolInstance
import ai.flossyapp.domain.model.ToolTypes

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

/**
 * Parses a token-count entry typed as either a bare whole number ("32768")
 * or a K-suffixed shorthand ("32K", "32k", "512K") into a token count. A
 * bare number is taken literally; the shorthand multiplies by 1024. Anything
 * that parses to nothing returns null so the caller keeps the old value.
 */
fun parseTokenCount(input: String): Int? {
    val text = input.trim().uppercase()
    return if (text.endsWith("K")) {
        text.removeSuffix("K").trim().toIntOrNull()?.times(1024)
    } else {
        text.toIntOrNull()
    }
}

/**
 * A token-budget setting presented as an editable combo box: the standard
 * sizes (4k .. 1M) are one click away, but the field stays typable and
 * accepts either a K-suffix ("32K") or the raw count ("32768"). The chosen
 * value is always shown as the full token count so what the server receives
 * is never ambiguous.
 */
@Composable
fun SettingTokenComboBox(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    placeholder: String,
) {
    val options = listOf("4K", "8K", "16K", "32K", "64K", "128K", "256K", "512K", "1M")
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value.toString(),
                onValueChange = { entered ->
                    // Live-parse: "32" becomes 32 tokens while typing, "32K"
                    // only once the K lands. A prefix that parses ("", "3")
                    // is committed as-is; a garbage entry keeps the old value.
                    parseTokenCount(entered)?.let(onValueChange)
                },
                placeholder = { Text(placeholder) },
                maxLines = 1,
                shape = RoundedCornerShape(8.dp),
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = if (expanded) "Collapse list" else "Expand list"
                        )
                    }
                }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            // Resolve the option to the same token count the
                            // field would hold, and show both forms.
                            val tokens = parseTokenCount(option) ?: 0
                            Text("$option  ($tokens tokens)")
                        },
                        onClick = {
                            expanded = false
                            parseTokenCount(option)?.let(onValueChange)
                        }
                    )
                }
            }
        }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))

        var expanded by remember { mutableStateOf(false) }
        var typed by remember(current) { mutableStateOf(current) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        // Commit whatever is typed the moment the field loses
                        // focus, so a hand-entered id survives without a
                        // separate confirm button.
                        if (!state.isFocused && typed != current) onSelect(typed)
                    },
                placeholder = { Text("Type or pick from the list") },
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }, enabled = options.isNotEmpty()) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = if (expanded) "Collapse list" else "Expand list"
                        )
                    }
                }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                // The height cap belongs on the scrollable itself: a popup
                // measures its content with unbounded height, and a vertical
                // scrollable under infinite constraints throws
                // IllegalStateException the moment the menu opens (this is
                // what crashed the ASR/TTS dropdowns). Capping the scrolling
                // Column gives it the finite maximum it requires, and the
                // long listing scrolls inside the dropdown instead of
                // unrolling into a wall of buttons across the screen.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { option ->
                        val active = option.equals(current, ignoreCase = true)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option,
                                    maxLines = 2,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                typed = option
                                onSelect(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// ==================== Settings tabs ====================

/**
 * The tabs of the settings area. Every tab is a tidy group of sections; the
 * action bar beneath them applies, undoes, resets or cancels the whole edit.
 */
enum class SettingsTab(val title: String) {
    Endpoints("Endpoints"),
    Tools("Tools"),
    Wake("Wake"),
    General("General")
}

// ==================== Settings Screen ====================

/**
 * Settings screen for configuring the FLOSSy Assistant.
 *
 * The screen is a multi-tab area over one draft: editing a field anywhere
 * changes the draft only, and the bar beneath the tabs applies it, undoes it,
 * resets it to defaults, or cancels back to what is persisted.
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

    var currentTab by remember { mutableStateOf(SettingsTab.Endpoints) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var factoryResetConfirmed by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var endpointsProbed by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dirty = viewModel.hasUnappliedEdits()

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
        },
        bottomBar = {
            // The one action bar for every tab: apply, undo, reset or cancel
            // the draft as a whole. Undo and cancel return to the persisted
            // state, reset stages the shipped defaults, apply persists.
            Surface(tonalElevation = 3.dp) {
                // Edge-to-edge is enforced on Android 15 (targetSdk 35), so
                // the bar has to reserve the gesture-navigation area itself:
                // without this padding it sits underneath the home/back bar.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    if (dirty) {
                        Text(
                            text = "Unapplied edits",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.applyEdits()
                                android.widget.Toast.makeText(context, "Settings applied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Apply") }
                        OutlinedButton(
                            onClick = { viewModel.undoEdits() },
                            enabled = dirty,
                            modifier = Modifier.weight(1f)
                        ) { Text("Undo") }
                        OutlinedButton(
                            onClick = { viewModel.resetEdits() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reset") }
                        TextButton(
                            onClick = { viewModel.cancelEdits() },
                            enabled = dirty,
                            modifier = Modifier.weight(1f)
                        ) { Text("Cancel") }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = currentTab.ordinal) {
                SettingsTab.values().forEach { tab ->
                    Tab(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        text = { Text(tab.title) }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (!endpointsProbed) {
                    endpointsProbed = true
                    viewModel.fetchModelsForEndpoint("llm")
                    viewModel.fetchModelsForEndpoint("stt")
                    viewModel.fetchModelsForEndpoint("tts")
                }

                when (currentTab) {
                    SettingsTab.Endpoints -> EndpointsTab(
                        settings, viewModel, llmModels, sttModels, ttsVoices,
                        llmStatus, sttStatus, ttsStatus,
                        llmBusy, llmTesting, sttBusy, sttTesting, ttsBusy, ttsTesting
                    )
                    SettingsTab.Tools -> ToolsTab(settings, viewModel)
                    SettingsTab.Wake -> WakeTab(
                        settings, viewModel, wakeWordStatus, wakeWordTesting,
                        wakeReferenceCount, wakeBusyLabel, defaultAssistantHeld
                    )
                    SettingsTab.General -> GeneralTab(
                        settings, viewModel, savedPresets,
                        showClearDataDialog, showFactoryResetDialog, factoryResetConfirmed,
                        showSavePresetDialog, presetNameInput,
                        onSetDialogState = { clear, factory, confirmed, save, name ->
                            showClearDataDialog = clear
                            showFactoryResetDialog = factory
                            factoryResetConfirmed = confirmed
                            showSavePresetDialog = save
                            presetNameInput = name
                        }
                    )
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

// ==================== Tabs ====================

@Composable
private fun EndpointsTab(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    llmModels: List<String>,
    sttModels: List<String>,
    ttsVoices: List<String>,
    llmStatus: EndpointStatus?,
    sttStatus: EndpointStatus?,
    ttsStatus: EndpointStatus?,
    llmBusy: Boolean,
    llmTesting: Boolean,
    sttBusy: Boolean,
    sttTesting: Boolean,
    ttsBusy: Boolean,
    ttsTesting: Boolean
) {
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
        SettingTokenComboBox(
            "Max Tokens",
            settings.llmMaxTokens,
            { viewModel.updateSetting("llmMaxTokens", it) },
            "e.g. 32768 or 32K - total reply budget, thinking included"
        )
        SettingTokenComboBox(
            "Reasoning Budget (tokens)",
            settings.llmReasoningBudgetTokens,
            { viewModel.updateSetting("llmReasoningBudgetTokens", it) },
            "e.g. 1024 or 1K - 0 leaves the server decide"
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
        // The TTS spells out leaked foreign characters one by one; this
        // drops them before synthesis so speech stays in the user's language.
        SettingSwitch("Forbid non-Latin characters in speech", settings.forbidNonLatinTts, { viewModel.updateSetting("forbidNonLatinTts", it) })
        // Privacy by default: a headset coming off ends the call, unless the
        // user opts into continuing on the phone's own mic and speaker.
        SettingSwitch("Continue calls on phone when headset disconnects", settings.continueCallsOnPhoneWhenHeadsetDisconnects, { viewModel.updateSetting("continueCallsOnPhoneWhenHeadsetDisconnects", it) })
        TestButton("Test TTS", true, ttsTesting) { viewModel.runEndpointTest("tts") }
    }
}

@Composable
private fun WakeTab(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    wakeWordStatus: EndpointStatus?,
    wakeWordTesting: Boolean,
    wakeReferenceCount: Int,
    wakeBusyLabel: String,
    defaultAssistantHeld: Boolean?
) {
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
                false -> "Not set as the default digital assistant - the wake chat only appears inside this app. Choose FLOSSyAssistant in the system's list to have it host conversations everywhere else."
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
}

@Composable
private fun GeneralTab(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    savedPresets: List<PresetEntry>,
    showClearDataDialog: Boolean,
    showFactoryResetDialog: Boolean,
    factoryResetConfirmed: Boolean,
    showSavePresetDialog: Boolean,
    presetNameInput: String,
    onSetDialogState: (clear: Boolean, factory: Boolean, confirmed: Boolean, save: Boolean, name: String) -> Unit
) {
    val context = LocalContext.current
    SettingsSection("Appearance", "🎨", "How the interface looks") {
        SettingSwitch("Dark Mode (gray Gruvbox)", settings.darkModeEnabled, { viewModel.updateSetting("darkModeEnabled", it) })
        // Launching the app is a voice interaction: the pipeline starts
        // listening immediately rather than waiting for the mic button.
        SettingSwitch("Voice on Launch", settings.autoStartVoiceOnLaunch, { viewModel.updateSetting("autoStartVoiceOnLaunch", it) })
        SettingSwitch(
            "Close services when the app is swiped away",
            settings.stopServicesOnTaskRemoved,
            { viewModel.updateSetting("stopServicesOnTaskRemoved", it) }
        )
        Text(
            text = "Off keeps the voice services running after the app is swiped from recents - a voice assistant has to stay alive to hear the wake phrase. Turning this on ends the listening when the app is swiped away.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SettingsSection("Data Management", "💾", "Export, import, and manage your settings data") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSetDialogState(true, false, false, false, "") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Clear Data") }
            Button(onClick = { onSetDialogState(false, true, false, false, "") }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Factory Reset") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                copyEndpointsToClipboard(context, settings)
                android.widget.Toast.makeText(context, "Endpoints copied!", android.widget.Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("Copy Endpoints") }
            Button(onClick = { onSetDialogState(false, false, false, true, "") }, modifier = Modifier.weight(1f)) { Text("Save Preset") }
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

// ==================== Tools tab ====================

/**
 * The Tools tab: one row per tool instance, each with an enable check (ghosted
 * while its settings are missing), a cog for its settings and a remove button
 * that removes only that row. The add button lists the supported types, where
 * a single-instance type appears only while it is absent and a multi-instance
 * type can be added again and again.
 */
@Composable
private fun ToolsTab(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val instances = settings.toolInstances
    var addMenuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ToolInstance?>(null) }

    SettingsSection("Tools", "🔧", "What the model may ask the app and servers to do") {
        Text(
            text = "Each line is one tool the model can be offered. The checkmark switches it on; a ghosted checkmark means the tool's settings are still missing. The cog opens those settings, and the bin removes the one tool - the checkmarks are switches, never a selection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (instances.isEmpty()) {
            Text(
                text = "No tools configured - add one below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        instances.forEach { instance ->
            ToolInstanceRow(
                instance = instance,
                onToggle = { enabled ->
                    viewModel.toggleToolInstance(instance.id, enabled)
                },
                onConfigure = {
                    if (hasSettingsDialog(instance)) editing = instance
                },
                onRemove = {
                    viewModel.removeToolInstance(instance.id)
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Box {
            Button(onClick = { addMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add a tool")
            }
            DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                availableAddTypes(instances).forEach { spec ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(spec.displayName)
                                Text(
                                    text = spec.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            addMenuOpen = false
                            viewModel.addToolInstance(spec.type)
                        }
                    )
                }
            }
        }
    }

    editing?.let { instance ->
        ToolSettingsDialog(
            instance = instance,
            onSave = { updated ->
                viewModel.updateToolInstance(updated.id) { updated }
                editing = null
                android.widget.Toast.makeText(context, "Tool settings updated (Apply to persist)", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { editing = null }
        )
    }
}

/**
 * Whether a cog dialog exists for this type: internal tools without settings
 * have nothing to configure.
 */
private fun hasSettingsDialog(instance: ToolInstance): Boolean = when (instance.type) {
    ToolTypes.TYPE_SEARXNG, ToolTypes.TYPE_BRAVE, ToolTypes.TYPE_MCP -> true
    else -> false
}

/**
 * The types the add button offers: a multi-instance type is always addable, a
 * single-instance type only while it is not in the list yet.
 */
private fun availableAddTypes(instances: List<ToolInstance>): List<ToolTypes.Spec> =
    ToolTypes.ALL.filter { spec ->
        spec.multiInstance || instances.none { it.type == spec.type }
    }

@Composable
private fun ToolInstanceRow(
    instance: ToolInstance,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onRemove: () -> Unit
) {
    val spec = ToolTypes.byType(instance.type)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The check is the enable/disable switch. It is ghosted while the
        // type's required settings are missing: a tool the model cannot
        // reach must not be offered.
        val canEnable = instance.configured
        Checkbox(
            checked = instance.enabled,
            onCheckedChange = onToggle,
            enabled = canEnable
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instance.displayName,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = when {
                    !canEnable -> "Needs configuration before it can be enabled"
                    instance.enabled -> "Enabled"
                    else -> spec?.description?.let { "$it" } ?: "Disabled"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !canEnable -> MaterialTheme.colorScheme.error
                    instance.enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            // A configured MCP or search tool shows the value it will use,
            // so the user can see what the cog dialog holds without opening it.
            val detail = when (instance.type) {
                ToolTypes.TYPE_SEARXNG -> instance.searxngUrl
                ToolTypes.TYPE_BRAVE -> if (instance.braveApiKey.isNotBlank()) "token set" else ""
                ToolTypes.TYPE_MCP -> instance.mcpUrl
                else -> ""
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (hasSettingsDialog(instance)) {
            IconButton(onClick = onConfigure) {
                Text(text = "⚙️")
            }
        }
        IconButton(onClick = onRemove) {
            Text(text = "🗑️")
        }
    }
}

/**
 * The cog dialog for one tool instance: the fields the type requires, saved
 * back into the instance's settings document on Save.
 */
@Composable
private fun ToolSettingsDialog(
    instance: ToolInstance,
    onSave: (ToolInstance) -> Unit,
    onDismiss: () -> Unit
) {
    // The fields are read from the instance's own document and written back
    // whole, so a type that gains a setting later keeps the ones it had.
    var searxngUrl by remember(instance.id) { mutableStateOf(instance.searxngUrl) }
    var braveApiKey by remember(instance.id) { mutableStateOf(instance.braveApiKey) }
    var mcpUrl by remember(instance.id) { mutableStateOf(instance.mcpUrl) }
    var mcpLazy by remember(instance.id) { mutableStateOf(instance.mcpLazy) }

    val title = when (instance.type) {
        ToolTypes.TYPE_SEARXNG -> "SearXNG search settings"
        ToolTypes.TYPE_BRAVE -> "Brave search settings"
        ToolTypes.TYPE_MCP -> "MCP server settings"
        else -> "Tool settings"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                when (instance.type) {
                    ToolTypes.TYPE_SEARXNG -> {
                        SettingTextField(
                            "SearXNG URL",
                            searxngUrl,
                            { searxngUrl = it },
                            "http://192.168.1.2:8888 - the instance must allow the JSON format"
                        )
                        Text(
                            text = "The instance is queried at /search?q=…&format=json, so \"format=json\" must be allowed by its settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ToolTypes.TYPE_BRAVE -> {
                        SettingTextField(
                            "API token",
                            braveApiKey,
                            { braveApiKey = it },
                            "The subscription token from the Brave Search API"
                        )
                    }
                    ToolTypes.TYPE_MCP -> {
                        SettingTextField(
                            "Server URL",
                            mcpUrl,
                            { mcpUrl = it },
                            "http://192.168.1.2:3000/mcp - streamable HTTP"
                        )
                        SettingSwitch(
                            "Advertise tools lazily",
                            mcpLazy,
                            { mcpLazy = it }
                        )
                        Text(
                            text = "Lazy mode offers the model one entry point that names a tool as an argument, keeping a large tool list out of its context; turning it off sends every schema.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(instance.buildUpdated(searxngUrl, braveApiKey, mcpUrl, mcpLazy))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Writes the dialog's fields back into a copy of the instance, leaving the
 * settings of every other type untouched.
 */
private fun ToolInstance.buildUpdated(searxngUrl: String, braveApiKey: String, mcpUrl: String, mcpLazy: Boolean): ToolInstance =
    when (type) {
        ToolTypes.TYPE_SEARXNG -> withSearxngUrl(searxngUrl)
        ToolTypes.TYPE_BRAVE -> withBraveApiKey(braveApiKey)
        ToolTypes.TYPE_MCP -> withMcpUrl(mcpUrl).withMcpLazy(mcpLazy)
        else -> this
    }

// ==================== Utility Functions ====================

fun copyEndpointsToClipboard(context: android.content.Context, settings: AppSettings) {
    val text = buildString {
        append("=== FLOSSy Assistant Endpoints ===\n")
        append("LLM: ${settings.llmBaseUrl}\n")
        append("STT: ${settings.sttBaseUrl}\n")
        append("TTS: ${settings.ttsBaseUrl}\n")
    }
    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Endpoints", text))
}
