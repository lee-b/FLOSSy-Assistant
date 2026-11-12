package ai.flossyapp.ui.chat

import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import ai.flossyapp.R
import ai.flossyapp.ui.common.exportChatToJsonFile
import ai.flossyapp.ui.common.exportChatToTextFile
import ai.flossyapp.ui.common.formatRelativeTime
import ai.flossyapp.ui.common.isNonSpeechTranscript
import ai.flossyapp.ui.common.loadPinnedMessages
import ai.flossyapp.ui.common.playTTSAudio
import ai.flossyapp.domain.model.DialogMode
import ai.flossyapp.domain.model.MessageRole
import ai.flossyapp.domain.model.DialogState
import ai.flossyapp.domain.model.VoiceCallPhase
import ai.flossyapp.ui.common.playListenEndCue
import ai.flossyapp.ui.common.playListenStartCue
import ai.flossyapp.ui.common.recordAudioWithVAD
import ai.flossyapp.ui.common.shareMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maximum number of messages to keep in the conversation before summarization triggers.
 */
private const val SUMMARY_THRESHOLD = 50

/**
 * Main chat screen with voice controls.
 *
 * Displays conversation history and provides voice interaction controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    hasPermissions: Boolean = true,
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val connectionWaiting by viewModel.connectionWaiting.collectAsState()
    val settings by viewModel.settings.collectAsState()
    
    android.util.Log.d("ChatScreen", "Composed with ${messages.size} messages")

    var textInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    val isSendingText by viewModel.isSendingText.collectAsState()
    val streamingContent = viewModel.streamingContent.collectAsState()
    val streamingReasoning = viewModel.streamingReasoning.collectAsState()
    // The dialog's mode and phase come from the one typed state machine; the
    // recording/speaking flags the screen used to keep separately are derived
    // from it, so they cannot disagree with the mode they belong to.
    val isRecording = dialogState.isListening
    val isTtsPlaying = dialogState.isSpeaking
    val voiceCallMode = dialogState.isVoiceCall
    var showMenu by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var lastAssistantMessage by remember { mutableStateOf<String?>(null) }
    // Raised to cut a reply short while it is still being spoken - text-chat
    // TTS only, since a voice call's playback is aborted through the engine.
    val ttsAbortSignal = java.util.concurrent.atomic.AtomicBoolean(false)
    // Set when the mic button pauses the wake service for a call, so the
    // hang-up path knows to hand the microphone back to it.
    var pausedWakeService by remember { mutableStateOf(false) }

    // Every coroutine that carries a piece of a voice call - the spoken cues
    // and the text-chat TTS. The exchange pipeline itself lives in the shared
    // VoiceCallEngine, which cancels its own jobs on hang-up.
    val voiceJobs = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.Job>()

    val coroutineScope = rememberCoroutineScope()

    /**
     * Launches a coroutine on the screen scope and registers it as a voice
     * job so [stopVoiceCall] can cancel it.
     */
    fun launchVoiceJob(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): kotlinx.coroutines.Job {
        val job = coroutineScope.launch { block() }
        voiceJobs.add(job)
        job.invokeOnCompletion { voiceJobs.remove(job) }
        return job
    }
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()

    /**
     * The screen's view of the shared voice-call engine: the pipeline runs in
     * the engine (one capture, one exchange at a time, STT -> LLM -> TTS), and
     * this host is how the exchange lands in this chat - bubbles, phases and
     * streaming partials all through the ChatViewModel.
     */
    val voiceCallHost = remember {
        object : ai.flossyapp.ui.common.VoiceCallEngine.ExchangeHost {
            override fun isActive(): Boolean = viewModel.dialogState.value.isVoiceCall
            override fun settings() = settings
            override fun history(): List<Map<String, String>> =
                viewModel.messages.value.map { msg ->
                    mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
                }
            override fun onUserMessage(text: String) { viewModel.addMessage(MessageRole.USER, text) }
            override fun onAssistantMessage(text: String, reasoning: String?) {
                viewModel.addMessage(MessageRole.ASSISTANT, text, reasoning)
            }
            override fun onPhase(phase: VoiceCallPhase) {
                // Only forward phases while a call is actually open: the
                // engine's exchange sets IDLE at its end even when the call
                // was already hung up, and that must not yank a text chat
                // back into voice mode.
                if (viewModel.dialogState.value.isVoiceCall) viewModel.setDialogPhase(phase)
            }
            override fun onStreamingContent(text: String) { viewModel.updateStreamingContent(text) }
            override fun onStreamingReasoning(text: String) { viewModel.updateStreamingReasoning(text) }
            override fun onSystemNote(text: String) {
                // SYSTEM-role messages render in the chat but are never sent
                // to the LLM, so this stays display-only.
                viewModel.addMessage(MessageRole.SYSTEM, text)
            }
            override fun onError(message: String) {
                android.util.Log.e("ChatScreen", "Voice interaction failed: $message")
            }
        }
    }

    // Auto-scroll to bottom when new messages or streaming content arrives
    var ttsPlayedMessageIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(messages.size, streamingContent.value, streamingReasoning.value, settings) {
        if (messages.isEmpty()) return@LaunchedEffect
        // isNearBottom() reads the CURRENT layoutInfo. On the recomposition
        // that appends a message, that snapshot is often stale - which is
        // exactly "were we at the bottom before the new bubble": the perfect
        // condition. If the user had scrolled back through the reply, they
        // are not near the bottom and we leave the list alone; chasing every
        // streamed chunk otherwise yanks the list and made it unscrollable
        // while a response was playing.
        if (!lazyListState.isNearBottom()) return@LaunchedEffect
        val streaming = streamingContent.value.isNotEmpty() || streamingReasoning.value.isNotEmpty()
        if (streaming) {
            // While a reply streams, scroll by only the amount the text has
            // grown past the viewport bottom: each new line eases the list up
            // one line, so the user reads the output as it arrives. An
            // absolute bottom-align here would jump by whole paragraphs.
            val layout = lazyListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            if (lastVisible != null) {
                val overflow = lastVisible.offset + lastVisible.size - layout.viewportSize.height
                if (overflow > 0) {
                    lazyListState.scroll { scrollBy(overflow.toFloat()) }
                }
            }
        } else {
            // A message just arrived: bottom-align the last bubble. scrollToItem
            // pins the item's top, so a tall bubble would sit with its bottom
            // below the fold; passing Int.MAX_VALUE as scrollOffset pushes the
            // item's start past the viewport top and the list clamps at
            // maximum scroll, leaving the bubble's BOTTOM at the viewport
            // bottom - tall and short bubbles alike.
            lazyListState.scrollToItem(messages.size - 1, Int.MAX_VALUE)
        }

        // Track last assistant message for auto-TTS - only trigger when streaming is COMPLETE
        val lastMsg = messages.lastOrNull { it.role == ai.flossyapp.domain.model.MessageRole.ASSISTANT }
        // A voice call speaks inside the exchange, with the gate closed;
        // this effect would double-speak and, worse, let the capture hear
        // the reply it is meant to stay deaf to.
        if (lastMsg != null && lastMsg.content.isNotBlank() && streamingContent.value.isEmpty() && settings.autoTtsEnabled && !viewModel.dialogState.value.isVoiceCall) {
            val lastIdx = messages.indexOf(lastMsg)
            if (lastIdx != ttsPlayedMessageIndex && lastMsg.content != lastAssistantMessage) {
                lastAssistantMessage = lastMsg.content
                launchVoiceJob {
                    android.util.Log.d("ChatScreen", "Auto-playing TTS for completed message: ${lastMsg.content.take(50)}")
                    ttsAbortSignal.set(false)
                    // Speaking is a phase of the call, not a separate flag -
                    // but only a call speaks through this path; text-chat TTS
                    // leaves the phase alone.
                    if (viewModel.dialogState.value.isVoiceCall) viewModel.setDialogPhase(VoiceCallPhase.SPEAKING)
                    try {
                        playTTSAudio(
                            lastMsg.content,
                            Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                            model = settings.ttsModelName,
                            voice = settings.ttsVoiceName,
                            responseFormat = settings.ttsResponseFormat,
                            enableStreaming = settings.enableTtsStreaming,
                            timeoutSeconds = settings.httpTimeoutSeconds.toLong(),
                            forbidNonLatin = settings.forbidNonLatinTts,
                            abortSignal = ttsAbortSignal,
                            onTtsTooSlow = {
                                // A tick fills the silence and a note explains it.
                                // SYSTEM-role messages render in the chat but are
                                // never sent to the LLM, so this stays display-only.
                                viewModel.addMessage(ai.flossyapp.domain.model.MessageRole.SYSTEM,
                                    "The TTS server or connection is too slow for real-time speech.")
                                launchVoiceJob { ai.flossyapp.ui.common.playWaitingCue() }
                            }
                        )
                    } finally {
                        if (viewModel.dialogState.value.isVoiceCall) viewModel.setDialogPhase(VoiceCallPhase.IDLE)
                    }
                    ttsPlayedMessageIndex = lastIdx
                }
            }
        }
    }

    // Pinned messages state - loaded from SharedPreferences on first composition
    var pinnedMessages by remember { mutableStateOf<List<ai.flossyapp.domain.model.ChatMessage>>(emptyList()) }
    LaunchedEffect(Unit) {
        pinnedMessages = loadPinnedMessages(context.applicationContext)
    }

    // One recording routine shared by the small mic button on the input bar and
    // the large microphone on the empty-state screen, so both start the same
    // capture and the big icon is not decoration. Tapping either one again ends
    // the recording the same way.
    // One recording routine shared by the small mic button on the input bar and
    // the large microphone on the empty-state screen, so both start the same
    // capture and the big icon is not decoration. Tapping either one again ends
    // the recording the same way.
    fun startMicRecording(announce: Boolean = true) {
        // A capture is already in flight - the re-arm after a spoken turn hands
        // back to this guard, and the user's own taps must not stack recorders.
        if (viewModel.dialogState.value.isListening) return
        // Opening the call is one typed transition; every derived flag falls
        // out of it.
        viewModel.setDialogState(DialogMode.VOICE_CALL, VoiceCallPhase.LISTENING)
        ttsAbortSignal.set(false)
        // The background services would hear the same words and answer a second
        // time (their replies never reach this chat), so while a voice call is
        // open the screen owns the microphone - through the shared engine, which
        // holds one capture for the whole call. Only already-running services
        // are paused; starting one here would raise its foreground notification
        // for nothing.
        if (ai.flossyapp.service.VoiceAssistantService.isRunning) {
            runCatching {
                context.applicationContext.startService(
                    ai.flossyapp.service.VoiceAssistantService.buildIntent(
                        context.applicationContext,
                        ai.flossyapp.service.VoiceAssistantService.ACTION_PAUSE
                    )
                )
            }
        }
        // The wake-chat service would hear the same words the screen is
        // answering; wake detection and a voice call are different states and
        // the microphone belongs to the call, so the service is stopped for
        // the call's duration - and remembered, so hang-up hands the mic
        // back to it.
        if (ai.flossyapp.service.WakeChatService.isRunning) {
            pausedWakeService = true
            runCatching {
                context.applicationContext.startService(
                    ai.flossyapp.service.WakeChatService.buildIntent(
                        context.applicationContext,
                        ai.flossyapp.service.WakeChatService.ACTION_STOP
                    )
                )
            }
        }
        launchVoiceJob {
            if (announce) withContext(Dispatchers.IO) { playListenStartCue() }
        }
        // The engine owns the capture and the exchange pipeline from here:
        // every complete utterance arrives at voiceCallHost as one exchange.
        ai.flossyapp.ui.common.VoiceCallEngine.instance(context.applicationContext)
            .startAppMode(voiceCallHost)
    }

    /**
     * Ends voice call mode: any voice output that is still playing is cut
     * short, a half-heard utterance is dropped, and the app falls back to plain
     * text messaging until a tap on the mic opens a new call.
     */
    fun stopVoiceCall() {
        android.util.Log.d("ChatScreen", "Voice call ended by the mic button")
        // One typed transition ends the call: the mode, its phase, the mic
        // and the spoken output all fall away together.
        viewModel.endVoiceCall()
        ttsAbortSignal.set(true)
        // Hand the microphone back - but only the mic this call owns: the
        // engine is torn down when the call is in APP mode, while a WAKE-mode
        // capture belongs to the wake service and a chat hang-up must never
        // kill it (that is what deafened the wake word whenever the chat view
        // was opened and closed mid-listen).
        val engine = ai.flossyapp.ui.common.VoiceCallEngine.instance(context.applicationContext)
        if (engine.mode == ai.flossyapp.ui.common.VoiceCallEngine.Mode.APP) {
            engine.stop()
        }
        // The wake service was paused for this call: give the microphone back
        // to it now that the call is over.
        if (pausedWakeService) {
            pausedWakeService = false
            runCatching {
                context.applicationContext.startService(
                    ai.flossyapp.service.WakeChatService.buildIntent(
                        context.applicationContext,
                        ai.flossyapp.service.WakeChatService.ACTION_START
                    )
                )
            }
        }
        // Cancel every coroutine still carrying the call on the screen side -
        // the spoken cues, any text-chat TTS - so nothing can speak after the
        // user hung up.
        for (job in voiceJobs) job.cancel()
        // The downbeat is the call's closing sound - it plays once, here,
        // never between the turns inside the call.
        coroutineScope.launch {
            withContext(Dispatchers.IO) { playListenEndCue() }
        }
    }

    /**
     * The one tap semantic for both mic buttons: cut voice output and end the
     * call while it is speaking, end the call while it is listening, open a
     * new call when the app is plain text messaging.
     */
    fun onMicTapped() {
        when {
            viewModel.dialogState.value.isSpeaking -> stopVoiceCall()
            viewModel.dialogState.value.isListening -> stopVoiceCall()
            else -> startMicRecording()
        }
    }

    // The notification's End Call action reaches the screen here: during a
    // call the screen owns the microphone, so the service alone cannot end
    // the call - it raises the event, and the screen does the hanging up
    // (ending the capture, cutting TTS, dropping the half-heard utterance).
    LaunchedEffect(Unit) {
        ai.flossyapp.service.VoiceAssistantService.hangUpEvents.collect {
            android.util.Log.d("ChatScreen", "Voice call ended from the notification")
            stopVoiceCall()
        }
    }

    // Launching the app is a voice interaction: the screen's own pipeline takes
    // the microphone straight away, so the mic on the empty screen is already
    // listening when the splash lifts. The background service is not started
    // here - its answers never reach this chat and it would fight the screen
    // for the microphone.
    LaunchedEffect(Unit) {
        if (hasPermissions && settings.autoStartVoiceOnLaunch) startMicRecording()
    }

    // Conversation summarization state - trigger when message count exceeds threshold
    var showSummarizedNotification by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.size > SUMMARY_THRESHOLD) {
            android.util.Log.d("ChatScreen", "Conversation exceeded $SUMMARY_THRESHOLD messages, summarizing...")
            showSummarizedNotification = true
            viewModel.maybeSummarizeConversation(
                llmBaseUrl = settings.llmBaseUrl,
                apiKey = if (settings.llmApiKey.isBlank()) null else settings.llmApiKey,
                model = settings.llmModelName,
                timeoutSeconds = settings.httpTimeoutSeconds.toLong()
            )
        }
    }


    if (!hasPermissions) {
        PermissionDeniedScreen()
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(context.getString(R.string.app_name)) },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = context.getString(R.string.chat_clear_search))
                            }
                        }
                        
                        // Hamburger menu button with DropdownMenu
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                // Auto TTS toggle - controls whether completed assistant
                                // messages are spoken aloud automatically
                                DropdownMenuItem(
                                    text = { Text("Auto TTS") },
                                    trailingIcon = {
                                        androidx.compose.material3.Switch(
                                            checked = settings.autoTtsEnabled,
                                            onCheckedChange = { enabled ->
                                                viewModel.updateSettings(settings.copy(autoTtsEnabled = enabled))
                                            }
                                        )
                                    },
                                    onClick = { menuExpanded = false }
                                )

                                // Reasoning auto-expand toggle
                                DropdownMenuItem(
                                    text = { Text("Auto-expand reasoning") },
                                    trailingIcon = {
                                        androidx.compose.material3.Switch(
                                            checked = settings.autoExpandReasoning,
                                            onCheckedChange = { enabled ->
                                                viewModel.updateSettings(settings.copy(autoExpandReasoning = enabled))
                                            }
                                        )
                                    },
                                    onClick = { menuExpanded = false }
                                )

                                HorizontalDivider()

                                // The exports live in the menu, not the main
                                // window - exporting is rare, so it has no
                                // business occupying a row of prime screen
                                // space above the history.
                                DropdownMenuItem(
                                    text = { Text(context.getString(R.string.chat_export_button)) },
                                    onClick = {
                                        exportChatToTextFile(context, messages)
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export JSON") },
                                    onClick = {
                                        exportChatToJsonFile(context, messages)
                                        menuExpanded = false
                                    }
                                )

                                HorizontalDivider()

                                // Clear history
                                DropdownMenuItem(
                                    text = { Text("Clear History") },
                                    onClick = { 
                                        viewModel.clearHistory()
                                        menuExpanded = false
                                    }
                                )
                                
                                // Settings
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { 
                                        onNavigateToSettings()
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                        
                        val ctx = LocalContext.current
                        val versionName by remember { derivedStateOf { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() } }
                        if (versionName != null) {
                            Text(
                                text = "v$versionName",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                VoiceControlsBar(
                    dialogState = dialogState,
                    isSendingText = isSendingText,
                    connectionWaiting = connectionWaiting,
                    textInput = textInput,
                    onTextInputChange = { textInput = it },
                    onInputFocused = {
                        // Typing and voice capture are two ways of saying the same
                        // thing; the keyboard taking focus means the recording ends.
                        if (viewModel.dialogState.value.isListening) stopVoiceCall()
                    },
                    onSendText = { text ->
                        if (text.isNotBlank()) {
                            viewModel.sendMessage(
                                text,
                                settings.llmBaseUrl,
                                if (settings.llmApiKey.isBlank()) null else settings.llmApiKey,
                                settings.llmModelName
                            )
                            textInput = ""
                        }
                    },
                    onStartRecording = {
                        onMicTapped()
                    },
                    onStopRecording = {
                        stopVoiceCall()
                    },
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Search bar
                if (messages.isNotEmpty()) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(context.getString(R.string.chat_search_placeholder)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (messages.isEmpty()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onMicTapped()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                // A voice call is a call, not a recording
                                // session - the wording follows the call
                                // metaphor everywhere, on and off the mic.
                                contentDescription = if (isRecording) "Hang up" else "Start a voice call",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (isRecording) "In call - just say what you need"
                                    else "Tap the microphone to start a call",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        state = lazyListState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filteredMessages = if (searchQuery.isBlank()) {
                            messages
                        } else {
                            messages.filter { it.content.contains(searchQuery, ignoreCase = true) }
                        }

                        // Show pinned messages at the top
                        if (pinnedMessages.isNotEmpty()) {
                            item {
                                Text(
                                    text = context.getString(R.string.chat_pinned_messages),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            for (message in pinnedMessages) {
                                item(key = "pinned_${message.id}") {
                                    MessageBubble(
                                        message = message,
                                        isPinned = true,
                                        ttsSettings = Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                                        ttsModel = settings.ttsModelName,
                                        ttsVoice = settings.ttsVoiceName,
                                        ttsResponseFormat = settings.ttsResponseFormat,
                                        ttsTimeoutSeconds = settings.httpTimeoutSeconds.toLong(),
                                        autoExpandReasoning = settings.autoExpandReasoning,
                                        onUnpin = {
                                            viewModel.unpinMessage(message.id)
                                            pinnedMessages = loadPinnedMessages(context.applicationContext)
                                        }
                                    )
                                }
                                item { Spacer(modifier = Modifier.height(8.dp)) }
                            }
                        }

                        // Show summarization notification when conversation is summarized
                        if (showSummarizedNotification) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "💬 Conversation summarized (older messages archived)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(onClick = { showSummarizedNotification = false }) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                            }
                        }

                        if (filteredMessages.isEmpty() && searchQuery.isNotBlank()) {
                            item {
                                Text(
                                    text = "No messages match \"$searchQuery\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            itemsIndexed(filteredMessages) { _, message ->
                                MessageBubble(
                                    message = message,
                                    isPinned = false,
                                    ttsSettings = Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                                    ttsModel = settings.ttsModelName,
                                    ttsVoice = settings.ttsVoiceName,
                                    ttsResponseFormat = settings.ttsResponseFormat,
                                    ttsTimeoutSeconds = settings.httpTimeoutSeconds.toLong(),
                                    autoExpandReasoning = settings.autoExpandReasoning,
                                    onPin = {
                                        viewModel.pinMessage(message)
                                        pinnedMessages = loadPinnedMessages(context.applicationContext)
                                    }
                                )
                            }

                            // Show streaming response in progress.
                            // During streaming the last stored message is the USER message
                            // (the assistant message is only appended after streaming completes),
                            // so the indicator must show while streaming content exists and
                            // the last message is NOT an assistant message.
                            val lastMsg = messages.lastOrNull()
                            val isStreaming = (streamingContent.value.isNotEmpty() || streamingReasoning.value.isNotEmpty()) &&
                                lastMsg?.role != ai.flossyapp.domain.model.MessageRole.ASSISTANT
                            if (isStreaming && searchQuery.isBlank()) {
                                item {
                                    StreamingResponseIndicator(
                                        content = streamingContent.value,
                                        reasoningContent = streamingReasoning.value,
                                        ttsSettings = Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                                        autoExpandReasoning = settings.autoExpandReasoning
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}







/**
 * Screen shown when required permissions are denied.
 */
@Composable
private fun PermissionDeniedScreen() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = ctx.getString(R.string.permission_required_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = ctx.getString(R.string.permission_required_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = ctx.getString(R.string.permission_grant_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}









/**
 * Display a single chat message as a bubble.
 * Supports long-press to copy message text to clipboard and pin/unpin messages.
 *
 * @param message The chat message to display
 * @param isPinned Whether this message is pinned at the top of the chat
 * @param onPin Callback when user wants to pin this message (only used when !isPinned)
 * @param onUnpin Callback when user wants to unpin this message (only used when isPinned)
 */
@Composable
internal fun MessageBubble(
    message: ai.flossyapp.domain.model.ChatMessage,
    isPinned: Boolean = false,
    ttsSettings: Pair<String?, String?>? = null, // (baseUrl, apiKey)
    ttsModel: String = "piper-en",
    ttsVoice: String = "en_US-ryan-high",
    ttsResponseFormat: String = "mp3",
    ttsTimeoutSeconds: Long = 60L,
    autoExpandReasoning: Boolean = true,
    onPin: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null
) {
    val isUser = message.role == ai.flossyapp.domain.model.MessageRole.USER
    val context = LocalContext.current
    var showMessageActions by remember { mutableStateOf(false) }

    if (showMessageActions) {
        MessageActionBottomSheet(
            onDismiss = { showMessageActions = false },
            onCopy = {
                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("message", message.content)
                clipboard.setPrimaryClip(clip)
                showMessageActions = false
            },
            onShare = {
                shareMessage(context, message.content)
                showMessageActions = false
            },
            onPinOrUnpin = {
                if (isPinned && onUnpin != null) {
                    onUnpin()
                } else if (!isPinned && onPin != null) {
                    onPin()
                }
                showMessageActions = false
            },
            isPinned = isPinned
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {},
                    onLongPress = {
                        showMessageActions = true
                    }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPinned) 4.dp else 2.dp
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Pinned header row with pin/unpin button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPinned) "📌 Pinned" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row {
                    if (isPinned && onUnpin != null) {
                        TextButton(
                            onClick = { onUnpin() },
                            content = { Text("Unpin", style = MaterialTheme.typography.bodySmall) }
                        )
                    } else if (!isPinned && onPin != null) {
                        TextButton(
                            onClick = { onPin() },
                            content = { Text("📌 Pin", style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Reasoning/thinking section (collapsible) - only for assistant messages with reasoning
            if (!isUser && message.reasoningContent != null && message.reasoningContent.isNotBlank()) {
                // The setting is part of the remember key: a lazily rendered row keeps its
                // remembered state across re-renders, so without it flipping the
                // "auto-expand reasoning" switch would never reach rows already on screen.
                var isReasoningExpanded by remember(message.id, autoExpandReasoning) {
                    mutableStateOf(autoExpandReasoning)
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { isReasoningExpanded = !isReasoningExpanded }
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isReasoningExpanded) "▾ Hide thinking" else "▸ Show thinking",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Icon(
                                imageVector = if (isReasoningExpanded) 
                                    Icons.Default.ExpandLess 
                                else 
                                    Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        if (isReasoningExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = message.reasoningContent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Read aloud button - only show for assistant messages
            var isReadingAloud by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()
            if (!isUser && !isReadingAloud && ttsSettings != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = {
                            isReadingAloud = true
                            coroutineScope.launch {
                                android.util.Log.d("ChatScreen", "Read aloud: ${message.content}")
                                try {
                                    playTTSAudio(
                                        message.content,
                                        ttsSettings,
                                        model = ttsModel,
                                        voice = ttsVoice,
                                        responseFormat = ttsResponseFormat,
                                        timeoutSeconds = ttsTimeoutSeconds
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatScreen", "TTS playback failed: ${e.message}")
                                }
                                isReadingAloud = false
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Read aloud",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (!isUser && isReadingAloud) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatRelativeTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Display a streaming assistant response as it arrives.
 * Shows live typing indicator with real-time text updates.
 */
@Composable
internal fun StreamingResponseIndicator(
    content: String,
    reasoningContent: String = "",
    ttsSettings: Pair<String?, String?>? = null,
    autoExpandReasoning: Boolean = true
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isReasoningExpanded by remember(autoExpandReasoning) { mutableStateOf(autoExpandReasoning) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Assistant is typing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                if (ttsSettings != null) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Read aloud",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Show reasoning/thinking content if available (expandable)
            if (reasoningContent.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { isReasoningExpanded = !isReasoningExpanded }
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isReasoningExpanded) "▾ Thinking..." else "▸ Show thinking...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Icon(
                                imageVector = if (isReasoningExpanded) 
                                    Icons.Default.ExpandLess 
                                else 
                                    Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        if (isReasoningExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = reasoningContent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Clean bottom bar with voice controls and text input.
 */
@Composable
private fun VoiceControlsBar(
    dialogState: DialogState,
    isSendingText: Boolean = false,
    connectionWaiting: Boolean = false,
    textInput: String = "",
    onTextInputChange: (String) -> Unit = {},
    onInputFocused: () -> Unit = {},
    onSendText: (String) -> Unit,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {}
) {
    val isRecording = dialogState.isListening

    Surface(
        // The navigation bar insets have to be applied here: this is a custom
        // bottom bar, and unlike the built-in NavigationBar the Scaffold does not
        // pad it for the virtual back/home/overview bar - without it the Message
        // input renders underneath the system bar. imePadding() comes after so
        // the keyboard pushes the whole bar clear of both.
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Status row - clean and simple
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        connectionWaiting -> "Awaiting connection..."
                        dialogState.phase == VoiceCallPhase.HEARD -> "Heard you..."
                        dialogState.isListening -> "Listening..."
                        dialogState.isThinking -> "Thinking..."
                        dialogState.isSpeaking -> "Speaking..."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (isRecording) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.error,
                                progress = { 1f }
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            // Same metaphor as the rest of the call UI: the
                            // dot is the live call, never a recording lamp.
                            text = "In call",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (dialogState.isThinking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        progress = { 0.7f }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { state ->
                            if (state.isFocused) onInputFocused()
                        },
                    value = textInput,
                    onValueChange = onTextInputChange,
                    placeholder = { Text("Message", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    maxLines = 1,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                // Send button
                if (textInput.isNotBlank()) {
                    IconButton(
                        onClick = { onSendText(textInput) },
                        enabled = !isSendingText,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (isSendingText) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // A voice call is not a recording session - the bar shows one
                // unmistakable way out of it instead of a second mic.
                if (dialogState.isVoiceCall) {
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text("End Call")
                    }
                } else {
                    IconButton(
                        onClick = onStartRecording,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Start a voice call",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}













/**
 * Bottom sheet shown when user long-presses a message.
 * Provides options to copy, share, or pin/unpin the message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionBottomSheet(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPinOrUnpin: () -> Unit,
    isPinned: Boolean
) {
    var visible by remember { mutableStateOf(true) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = {
                visible = false
                onDismiss()
            },
            dragHandle = {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))

                // Copy option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onCopy(); visible = false })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                HorizontalDivider()

                // Share option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onShare(); visible = false })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Share",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                HorizontalDivider()

                // Pin/Unpin option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onPinOrUnpin(); visible = false })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (isPinned) "Unpin" else "Pin",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Dismiss row at bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { visible = false })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Returns true when the list is scrolled to (or very near) the bottom, i.e. the
 * last item is visible or the second-to-last item is fully in view.
 */
private fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return true
    // Consider "near the bottom" when the last visible item is within the last
    // two items of the list.
    val lastVisibleIndex = layout.visibleItemsInfo.last().index
    return lastVisibleIndex >= layout.totalItemsCount - 2
}
        
