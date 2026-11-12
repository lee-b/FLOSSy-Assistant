package ai.flossyapp.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.flossyapp.data.remote.ApiClient
import ai.flossyapp.data.settings.SettingsRepository
import ai.flossyapp.domain.model.ChatMessage
import ai.flossyapp.domain.model.DialogMode
import ai.flossyapp.domain.model.DialogState
import ai.flossyapp.domain.model.MessageRole
import ai.flossyapp.domain.model.VoiceCallPhase
import ai.flossyapp.service.VoiceAssistantService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the chat screen.
 * 
 * Manages conversation history and coordinates with the voice assistant service.
 */
class ChatViewModel(application: android.app.Application) : AndroidViewModel(application) {
    
    private val context = application.applicationContext
    private val settingsRepository = SettingsRepository(context)
    
    // App settings - exposed as StateFlow for UI observation
    val settings: StateFlow<ai.flossyapp.domain.model.AppSettings> = settingsRepository.settingsFlow
    
    // Conversation messages - exposed as StateFlow for UI observation
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    // Dialog state - one typed machine instead of the stringly flags it used
    // to be, so the mode and its phase can never disagree with each other.
    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    // True while an LLM request is being retried after a 429: the API client
    // handles the backoff itself, the screen only has to say it once.
    val connectionWaiting: StateFlow<Boolean> = ApiClient.connectionWaiting
    
    /**
     * Move the dialog into [mode]; in voice call mode it starts at [phase].
     * In text-chat mode the phase is always IDLE - the phases belong to calls.
     */
    fun setDialogState(mode: DialogMode, phase: VoiceCallPhase = VoiceCallPhase.IDLE) {
        _dialogState.value = DialogState(mode, if (mode == DialogMode.VOICE_CALL) phase else VoiceCallPhase.IDLE)
    }

    /**
     * Advance the phase within the current mode; a no-op in text-chat mode.
     */
    fun setDialogPhase(phase: VoiceCallPhase) {
        val current = _dialogState.value
        if (current.isVoiceCall) _dialogState.value = current.copy(phase = phase)
    }

    /**
     * End a voice call: the mode drops back to text chat, and every part of
     * the call that follows this state (End Call button, re-listening, spoken
     * prompts) falls away with it in one place.
     */
    fun endVoiceCall() {
        _dialogState.value = DialogState()
    }

    // Tracks whether a text message is currently being sent (for loading indicator)
    private val _isSendingText = MutableStateFlow(false)
    val isSendingText: StateFlow<Boolean> = _isSendingText.asStateFlow()
    
    /**
     * Stream for the current streaming assistant response content.
     */
    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()
    
    /**
     * Update streaming content from outside (used by ChatScreen for voice interactions).
     */
    fun updateStreamingContent(chunk: String) {
        _streamingContent.value = chunk
    }
    
    /**
     * Update streaming reasoning/thinking content.
     */
    fun updateStreamingReasoning(chunk: String) {
        _streamingReasoning.value = chunk
    }
    
    /**
     * Update app settings via the settings repository.
     */
    fun updateSettings(settings: ai.flossyapp.domain.model.AppSettings) {
        settingsRepository.updateSettings(settings)
    }
    
    /**
     * Stream for the current streaming reasoning content.
     */
    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()

    
    /**
     * Add a new message to the conversation.
     */
    fun addMessage(role: MessageRole, content: String) {
        val message = ChatMessage(
            role = role,
            content = content
        )
        
        _messages.update { current ->
            current + listOf(message)
        }
    }
    
    /**
     * Send a text message to the LLM and display the response with streaming.
     */
    fun sendMessage(text: String, llmBaseUrl: String, apiKey: String?, model: String) {
        android.util.Log.d("ChatViewModel", "sendMessage called with text length: ${text.length}")
        addMessage(MessageRole.USER, text)
        _isSendingText.value = true
        _streamingContent.value = ""
        _streamingReasoning.value = ""
        // A text message is exactly that: text-chat mode. If a voice call was
        // somehow still open, typing is the user choosing the keyboard.
        _dialogState.value = DialogState()

        viewModelScope.launch {
            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()

            try {
                val messagesList = _messages.value.map { msg ->
                    mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
                }

                ApiClient.chatCompletion(
                    baseUrl = llmBaseUrl,
                    apiKey = apiKey,
                    messages = messagesList,
                    model = model,
                    stream = true,
                    maxTokens = settings.value.llmMaxTokens,
                    reasoningBudgetTokens = settings.value.llmReasoningBudgetTokens,
                    chatTemplateKwargs = settings.value.llmChatTemplateKwargs,
                    systemPrompt = settings.value.systemPrompt,
                    timeoutSeconds = settings.value.httpTimeoutSeconds.toLong()
                ) { chunk: String ->
                    // Reasoning chunks are prefixed with the \u0001 separator -
                    // keep them out of the visible content
                    if (chunk.startsWith("\u0001")) {
                        reasoningBuilder.append(chunk.substring(1))
                        _streamingReasoning.value = reasoningBuilder.toString()
                    } else {
                        contentBuilder.append(chunk)
                        _streamingContent.value = contentBuilder.toString()
                    }
                }

                // Parse the accumulated content for <think>...</think> style tags
                val parsed = ApiClient.parseReasoningAndContent(contentBuilder.toString())
                val content = parsed.content.trim()
                val reasoning = reasoningBuilder.toString().trim()
                    .ifEmpty { parsed.reasoningContent.orEmpty() }
                    .trim()

                android.util.Log.d("ChatViewModel", "Got response: ${content.take(100)}")

                if (content.isNotEmpty()) {
                    addMessage(MessageRole.ASSISTANT, content, reasoning.takeIf { it.isNotEmpty() })
                } else {
                    addMessage(
                        MessageRole.ASSISTANT,
                        "Sorry, I couldn't get a response."
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error in sendMessage", e)
                val errorMsg = "Error: ${e.message ?: "Failed to connect to LLM endpoint"}"
                addMessage(MessageRole.ASSISTANT, errorMsg)
            } finally {
                // Clear streaming state AFTER the final message is added
                _streamingContent.value = ""
                _streamingReasoning.value = ""
                _isSendingText.value = false
            }
        }
    }
    
    /**
     * Add a new message to the conversation with optional reasoning content.
     */
    fun addMessage(role: MessageRole, content: String, reasoningContent: String? = null) {
        val message = ChatMessage(
            role = role,
            content = content,
            reasoningContent = reasoningContent
        )
        
        _messages.update { current ->
            current + listOf(message)
        }
    }
    
    /**
     * Start the voice assistant service.
     */
    fun startVoiceAssistant() {
        val intent = VoiceAssistantService.buildIntent(context, VoiceAssistantService.ACTION_START)
        context.startForegroundService(intent)
        _dialogState.value = DialogState(DialogMode.VOICE_CALL, VoiceCallPhase.LISTENING)
    }
    
    /**
     * Stop the voice assistant service.
     */
    fun stopVoiceAssistant() {
        val intent = VoiceAssistantService.buildIntent(context, VoiceAssistantService.ACTION_STOP)
        context.startService(intent)
        _dialogState.value = DialogState()
    }
    
    /**
     * Clear all conversation history.
     */
    fun clearHistory() {
        _messages.value = emptyList()
    }

    // ==================== Conversation Summarization ====================

    /**
     * Threshold for triggering automatic conversation summarization.
     * When message count exceeds this, older messages are summarized via the LLM API.
     */
    private companion object {
        const val SUMMARY_THRESHOLD = 50
        // The collapsed history has to stay below SUMMARY_THRESHOLD, otherwise the
        // trigger in ChatScreen can never fire a second time and the conversation
        // grows without bound.
        const val SUMMARY_KEEP = 20
    }

    /**
     * Check if the current conversation needs summarization and perform it asynchronously.
     *
     * When the number of messages exceeds [SUMMARY_THRESHOLD], this method uses the LLM API
     * to create a concise summary of older messages, keeping only recent context in memory.
     */
    fun maybeSummarizeConversation(llmBaseUrl: String, apiKey: String?, model: String, timeoutSeconds: Long) {
        viewModelScope.launch {
            val currentMessages = _messages.value
            if (currentMessages.size > SUMMARY_THRESHOLD) {
                android.util.Log.d("ChatViewModel", "Conversation has ${currentMessages.size} messages, triggering summarization...")
                try {
                    // Keep the last SUMMARY_KEEP messages, summarize the rest
                    val keepCount = SUMMARY_KEEP
                    val messagesToSummarize = currentMessages.take(currentMessages.size - keepCount)
                    val recentMessages = currentMessages.takeLast(keepCount)

                    val summaryPrompt = buildString {
                        append("Please summarize the following conversation history concisely:\n\n")
                        for (msg in messagesToSummarize) {
                            val role = if (msg.role == ai.flossyapp.domain.model.MessageRole.USER) "User" else "Assistant"
                            append("[$role]: ${msg.content}\n\n")
                        }
                    }

                    val messagesList = listOf(
                        mapOf("role" to "system", "content" to "You are a conversation summarizer. Provide concise, accurate summaries."),
                        mapOf("role" to "user", "content" to summaryPrompt)
                    )

                    // Non-streaming call returns the full response in the ChatResponse -
                    // the onChunk callback is not invoked when stream = false
                    val summaryResponse = ai.flossyapp.data.remote.ApiClient.chatCompletion(
                        baseUrl = llmBaseUrl,
                        apiKey = apiKey,
                        messages = messagesList,
                        model = model,
                        stream = false,
                        timeoutSeconds = timeoutSeconds
                    )
                    val summaryText = summaryResponse.content.trim()

                    if (summaryText.isNotBlank()) {
                        val summaryMessage = ai.flossyapp.domain.model.ChatMessage(
                            role = ai.flossyapp.domain.model.MessageRole.ASSISTANT,
                            content = "📝 Conversation Summary: $summaryText",
                            timestamp = System.currentTimeMillis()
                        )

                        _messages.value = listOf(summaryMessage) + recentMessages
                        android.util.Log.d("ChatViewModel", "Conversation summarized. New count: ${_messages.value.size}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChatViewModel", "Summarization failed: ${e.message}")
                }
            }
        }
    }

    // ==================== Message Pinning ====================

    /**
     * Pin a message to SharedPreferences so it appears at the top of the chat.
     */
    fun pinMessage(message: ai.flossyapp.domain.model.ChatMessage) {
        val prefs = context.getSharedPreferences("chat_prefs", android.content.Context.MODE_PRIVATE)
        val count = prefs.getInt("pinned_count", 0)

        // If already pinned, update it in place
        for (i in 0 until count) {
            if (prefs.getLong("pinned_id_$i", -1) == message.id) {
                with(prefs.edit()) {
                    putLong("pinned_id_$i", message.id)
                    putString("pinned_role_$i", message.role.name)
                    putString("pinned_content_$i", message.content)
                    putLong("pinned_timestamp_$i", message.timestamp)
                    apply()
                }
                return
            }
        }

        // Add as new pinned message (max 10)
        if (count < 10) {
            with(prefs.edit()) {
                putInt("pinned_count", count + 1)
                putLong("pinned_id_$count", message.id)
                putString("pinned_role_$count", message.role.name)
                putString("pinned_content_$count", message.content)
                putLong("pinned_timestamp_$count", message.timestamp)
                apply()
            }
        }
    }

    /**
     * Unpin a message by removing it from SharedPreferences.
     */
    fun unpinMessage(messageId: Long) {
        val prefs = context.getSharedPreferences("chat_prefs", android.content.Context.MODE_PRIVATE)
        val count = prefs.getInt("pinned_count", 0)

        var foundIndex = -1
        for (i in 0 until count) {
            if (prefs.getLong("pinned_id_$i", -1) == messageId) {
                foundIndex = i
                break
            }
        }

        if (foundIndex >= 0) {
            with(prefs.edit()) {
                // Shift remaining messages down by one
                for (i in foundIndex until count - 1) {
                    putLong("pinned_id_$i", prefs.getLong("pinned_id_${i + 1}", -1))
                    putString("pinned_role_$i", prefs.getString("pinned_role_${i + 1}", ""))
                    putString("pinned_content_$i", prefs.getString("pinned_content_${i + 1}", ""))
                    putLong("pinned_timestamp_$i", prefs.getLong("pinned_timestamp_${i + 1}", 0L))
                }
                // Clear the last entry
                putLong("pinned_id_${count - 1}", -1)
                putString("pinned_role_${count - 1}", "")
                putString("pinned_content_${count - 1}", "")
                putLong("pinned_timestamp_${count - 1}", 0L)
                putInt("pinned_count", count - 1)
                apply()
            }
        }
    }
    
    /**
     * Request runtime permissions from the host activity.
     */
    fun requestPermissions(activity: ComponentActivity) {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                1001
            )
        }
    }
}
