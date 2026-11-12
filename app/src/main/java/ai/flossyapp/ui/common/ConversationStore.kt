package ai.flossyapp.ui.common

import ai.flossyapp.domain.model.ChatMessage
import ai.flossyapp.domain.model.VoiceCallPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The one conversation, shared by every surface that renders it.
 *
 * A call is one session no matter which window shows it: the wake popup, the
 * fullscreen chat during a call, and the plain chat history are all views of
 * this store, not separate logs. The wake service appends its exchanges here,
 * the chat screen reads and writes the same list, so a conversation started
 * by the wake word is visible the moment the fullscreen app is opened, and a
 * reply typed on screen is part of the same history the popup is drawing.
 *
 * [callActive] is the single source of truth for "a voice call is in
 * progress", whichever surface opened it - the wake service sets it when a
 * wake conversation opens and the screen sets it when the mic button opens a
 * call. Mic ownership itself is arbitrated by [VoiceCallEngine]; this flag
 * is only what the UI reads to decide what to draw.
 */
object ConversationStore {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** The whole conversation, newest last. */
    val messages: StateFlow<List<ChatMessage>> = _messages

    /**
     * A call is live - opened by the wake word, the mic button, or a handoff
     * between the two. The avatar and the call layout read this; outside an
     * active call nothing of the call UI is drawn.
     */
    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive

    /**
     * The live phase of the active call, whoever owns it - the popup and the
     * fullscreen avatar both render their poses from this.
     */
    private val _phase = MutableStateFlow(VoiceCallPhase.IDLE)
    val phase: StateFlow<VoiceCallPhase> = _phase

    /**
     * Replace the whole list (used by the summarizer and the clear action).
     */
    fun setMessages(newMessages: List<ChatMessage>) {
        _messages.value = newMessages
    }

    fun addMessage(message: ChatMessage) {
        _messages.update { current -> current + listOf(message) }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    fun setCallActive(active: Boolean) {
        _callActive.value = active
    }

    fun setPhase(phase: VoiceCallPhase) {
        _phase.value = phase
    }
}
