package com.localassistant.app.ui.chat

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
import com.localassistant.app.R
import com.localassistant.app.ui.common.exportChatToJsonFile
import com.localassistant.app.ui.common.exportChatToTextFile
import com.localassistant.app.ui.common.formatRelativeTime
import com.localassistant.app.ui.common.isNonSpeechTranscript
import com.localassistant.app.ui.common.loadPinnedMessages
import com.localassistant.app.ui.common.playTTSAudio
import com.localassistant.app.domain.model.DialogMode
import com.localassistant.app.domain.model.DialogState
import com.localassistant.app.domain.model.VoiceCallPhase
import com.localassistant.app.ui.common.playListenEndCue
import com.localassistant.app.ui.common.playListenStartCue
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.shareMessage
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
    var recordedAudioBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isRecordingActive by remember { mutableStateOf(false) }
    val recordingStopSignal = java.util.concurrent.atomic.AtomicBoolean(false)
    // Raised to cut a reply short while it is still being spoken.
    val ttsAbortSignal = java.util.concurrent.atomic.AtomicBoolean(false)
    // Raised when the user ends a call mid-listen, so the half-heard utterance
    // is discarded instead of sent on to STT.
    val discardCurrentUtterance = java.util.concurrent.atomic.AtomicBoolean(false)

    // Every coroutine that carries a piece of a voice call - the exchange
    // pipeline, its re-arms, the spoken prompts. Hanging up cancels them all,
    // so a call can neither answer nor speak a word after its user ended it.
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

    // Auto-scroll to bottom when new messages or streaming content arrives
    var ttsPlayedMessageIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(messages.size, streamingContent.value, streamingReasoning.value, settings) {
        val layout = lazyListState.layoutInfo
        // Auto-scroll to the bottom only while the user is already near it -
        // chasing every streamed chunk would yank the list away from somebody
        // scrolling back through the reply, which made the list unscrollable
        // exactly while a response was playing.
        if (layout.totalItemsCount > 0 && lazyListState.isNearBottom()) {
            // Scroll by the distance the last item's bottom sits below the
            // viewport bottom, not by item index: an absolute jump pins an
            // item's *top*, so every line a growing response added past its
            // previous end fell below the fold. Re-reading the layout each
            // recomposition chases the bottom as the reply grows.
            val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
            val overflow = lastVisible.offset + lastVisible.size - layout.viewportSize.height
            if (overflow > 0) {
                lazyListState.scroll {
                    scrollBy(overflow.toFloat())
                }
            }
        }

        // Track last assistant message for auto-TTS - only trigger when streaming is COMPLETE
        val lastMsg = messages.lastOrNull { it.role == com.localassistant.app.domain.model.MessageRole.ASSISTANT }
        if (lastMsg != null && lastMsg.content.isNotBlank() && streamingContent.value.isEmpty() && settings.autoTtsEnabled) {
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
                            abortSignal = ttsAbortSignal
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
    var pinnedMessages by remember { mutableStateOf<List<com.localassistant.app.domain.model.ChatMessage>>(emptyList()) }
    LaunchedEffect(Unit) {
        pinnedMessages = loadPinnedMessages(context.applicationContext)
    }

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
        discardCurrentUtterance.set(false)
        // The background service would hear the same words and answer a second
        // time (its replies never reach this chat), so while a voice call is
        // open the screen owns the microphone. Only an already-running service
        // is paused - starting one here would raise its foreground notification
        // for nothing.
        if (com.localassistant.app.service.VoiceAssistantService.isRunning) {
            runCatching {
                context.applicationContext.startService(
                    com.localassistant.app.service.VoiceAssistantService.buildIntent(
                        context.applicationContext,
                        com.localassistant.app.service.VoiceAssistantService.ACTION_PAUSE
                    )
                )
            }
        }
        recordedAudioBytes = null
        recordingStopSignal.set(false)

        launchVoiceJob {
            try {
                // The upbeat is the call's opening sound, played once before the
                // microphone opens. The re-arms between turns pass announce =
                // false and stay quiet - the cues mark the call, not the turns
                // inside it.
                if (announce) withContext(Dispatchers.IO) { playListenStartCue() }
                val wavBytes = withContext(Dispatchers.IO) {
                    recordAudioWithVAD(context.applicationContext, recordingStopSignal)
                }

                if (wavBytes == null || wavBytes.isEmpty()) {
                    // Nothing voiced enough to be a word came back - silence,
                    // a door click or the speaker's own tail. Apologising to
                    // somebody who said nothing is exactly the noise this used
                    // to make, so the call quietly re-arms instead; only a
                    // blank transcript after real speech gets the spoken
                    // repeat prompt further down.
                    if (viewModel.dialogState.value.isVoiceCall) {
                        launchVoiceJob {
                            delay(800)
                            if (viewModel.dialogState.value.isVoiceCall) withContext(Dispatchers.Main) { startMicRecording(announce = false) }
                        }
                    }
                    return@launchVoiceJob
                }

                recordedAudioBytes = wavBytes

                                                                 // Automatically process the recording after VAD stops
                launchVoiceJob {
                    try {
                        viewModel.setDialogPhase(VoiceCallPhase.TRANSCRIBING)

                        // The user ended the call while this take was still being
                        // heard - the half-captured words are theirs to drop, not
                        // to send on to STT.
                        if (discardCurrentUtterance.get()) {
                            viewModel.endVoiceCall()
                            return@launchVoiceJob
                        }

                        val transcribedText = withContext(Dispatchers.IO) {
                            com.localassistant.app.data.remote.ApiClient.transcribeAudio(
                                baseUrl = settings.sttBaseUrl,
                                apiKey = if (settings.sttApiKey.isBlank()) null else settings.sttApiKey,
                                audioData = wavBytes,
                                model = settings.sttModelName,
                                timeoutSeconds = settings.httpTimeoutSeconds.toLong()
                            )
                        }

                        // whisper.cpp answers silence with markers like [BLANK_AUDIO]; they must not
                        // become a user message or reach the LLM (same guard as the other services).
                        val nonSpeech = isNonSpeechTranscript(transcribedText)
                        if (nonSpeech) {
                            android.util.Log.d("ChatScreen", "Ignoring non-speech transcript: $transcribedText")
                        }

                        if (!nonSpeech && transcribedText.isNotBlank()) {
                            android.util.Log.d("ChatScreen", "STT result: $transcribedText")
                            viewModel.addMessage(com.localassistant.app.domain.model.MessageRole.USER, transcribedText)
                            viewModel.setDialogPhase(VoiceCallPhase.THINKING)

                            var fullResponse = StringBuilder()
                            var reasoningContent = StringBuilder()

                            withContext(Dispatchers.IO) {
                                // Include the full conversation history for context
                                val history = viewModel.messages.value.map { msg ->
                                    mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
                                }

                                com.localassistant.app.data.remote.ApiClient.chatCompletion(
                                    baseUrl = settings.llmBaseUrl,
                                    apiKey = if (settings.llmApiKey.isBlank()) null else settings.llmApiKey,
                                    messages = history,
                                    model = settings.llmModelName,
                                    stream = true,
                                    maxTokens = settings.llmMaxTokens,
                                    reasoningBudgetTokens = settings.llmReasoningBudgetTokens,
                                    chatTemplateKwargs = settings.llmChatTemplateKwargs,
                                    systemPrompt = settings.systemPrompt,
                                    timeoutSeconds = settings.httpTimeoutSeconds.toLong()
                                ) { chunk: String ->
                                    if (chunk.startsWith("\u0001")) {
                                        reasoningContent.append(chunk.substring(1))
                                        viewModel.updateStreamingReasoning(reasoningContent.toString())
                                    } else {
                                        fullResponse.append(chunk)
                                        viewModel.updateStreamingContent(fullResponse.toString())
                                    }
                                }
                            }

                            val finalText = fullResponse.toString().trim()
                            val reasoningText = reasoningContent.toString().trim()
                            android.util.Log.d("ChatScreen", "Voice LLM response: ${finalText.length} chars, reasoning: ${reasoningText.length} chars")

                            withContext(Dispatchers.Main) {
                                if (finalText.isNotEmpty()) {
                                    viewModel.addMessage(
                                        com.localassistant.app.domain.model.MessageRole.ASSISTANT,
                                        finalText,
                                        if (reasoningText.isNotEmpty()) reasoningText else null
                                    )
                                } else {
                                    Toast.makeText(context, "No response from LLM", Toast.LENGTH_SHORT).show()
                                }
                                // Clear streaming state AFTER the final message is added
                                viewModel.updateStreamingContent("")
                                if (reasoningText.isNotEmpty()) {
                                    viewModel.updateStreamingReasoning("")
                                }
                                // A voice call carries on: the answer is spoken
                                // through the speaker, so the mic re-arms the
                                // moment it has finished, like the wake chat.
                                viewModel.setDialogPhase(VoiceCallPhase.IDLE)
                                if (viewModel.dialogState.value.isVoiceCall) launchVoiceJob {
                                    // The speaker is still ringing in the room
                                    // right after playback returns; a beat of
                                    // settle keeps the first capture from
                                    // hearing the answer's own echo.
                                    delay(500)
                                    if (viewModel.dialogState.value.isVoiceCall) withContext(Dispatchers.Main) { startMicRecording(announce = false) }
                                }
                            }
                        } else {
                            // Nothing intelligible came back: hands-free means
                            // spoken, not printed - the screen may be off or
                            // away, so a toast would never be seen.
                            withContext(Dispatchers.Main) {
                                viewModel.setDialogPhase(VoiceCallPhase.IDLE)
                            }
                            if (viewModel.dialogState.value.isVoiceCall) {
                                launchVoiceJob {
                                    ttsAbortSignal.set(false)
                                    if (viewModel.dialogState.value.isVoiceCall) viewModel.setDialogPhase(VoiceCallPhase.SPEAKING)
                                    try {
                                        playTTSAudio(
                                            "Sorry, I misheard you. Please repeat.",
                                            Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                                            model = settings.ttsModelName,
                                            voice = settings.ttsVoiceName,
                                            responseFormat = settings.ttsResponseFormat,
                                            enableStreaming = settings.enableTtsStreaming,
                                            timeoutSeconds = settings.httpTimeoutSeconds.toLong(),
                                            abortSignal = ttsAbortSignal
                                        )
                                    } finally {
                                        if (viewModel.dialogState.value.isVoiceCall) viewModel.setDialogPhase(VoiceCallPhase.IDLE)
                                    }
                                }
                                launchVoiceJob {
                                    // Re-arm the mic once the prompt has been spoken.
                                    delay(3000)
                                    if (viewModel.dialogState.value.isVoiceCall) withContext(Dispatchers.Main) {
                                        startMicRecording(announce = false)
                                    }
                                }
                            }
                        }
                    } catch (e: java.util.concurrent.CancellationException) {
                        // Hanging up cancelled this job on purpose - falling
                        // through to the error toast would greet the person
                        // who just ended the call with an error popup.
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            viewModel.setDialogPhase(VoiceCallPhase.IDLE)
                            android.util.Log.e("ChatScreen", "Voice interaction failed", e)
                            Toast.makeText(context.applicationContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.setDialogPhase(VoiceCallPhase.IDLE)
                Toast.makeText(context.applicationContext, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Ends an in-flight recording and lets its VAD stop send the clip on.
     */
    fun stopMicRecording() {
        android.util.Log.d("ChatScreen", "Manual stop recording requested")
        if (!viewModel.dialogState.value.isListening) {
            android.util.Log.w("ChatScreen", "Stop called but not recording!")
            return
        }
        recordingStopSignal.set(true)
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
        discardCurrentUtterance.set(true)
        stopMicRecording()
        // Cancel every coroutine still carrying the call - the exchange
        // pipeline, its re-arms, any spoken prompt it was about to give - so
        // nothing can transcribe, answer, or speak after the user hung up.
        // The abort signal above only silences what is already on the speaker;
        // the cancellations stop the rest of the pipeline that hasn't spoken
        // yet.
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
        com.localassistant.app.service.VoiceAssistantService.hangUpEvents.collect {
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
                        if (viewModel.dialogState.value.isListening) stopMicRecording()
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
                                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Tap the microphone to start",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
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
                                lastMsg?.role != com.localassistant.app.domain.model.MessageRole.ASSISTANT
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
    message: com.localassistant.app.domain.model.ChatMessage,
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
    val isUser = message.role == com.localassistant.app.domain.model.MessageRole.USER
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
                            text = "Recording",
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
                            contentDescription = "Start recording",
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
        
