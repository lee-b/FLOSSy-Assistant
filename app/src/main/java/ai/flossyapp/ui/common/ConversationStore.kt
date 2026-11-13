package ai.flossyapp.ui.common

import ai.flossyapp.domain.model.ChatMessage
import ai.flossyapp.domain.model.VoiceCallPhase
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
 *
 * **Conversation lifetime.** A conversation does not end when a call does:
 * while either surface (the fullscreen app or the mini popup) still shows
 * it, the transcript stays and a new voice call resumes the same
 * conversation instead of erasing it. The conversation truly ends only
 * when
 *
 * - the model ends the call itself ("end_call"), or
 * - every surface has closed and no call is live,
 *
 * and only then does the next wake phrase or mic button start a NEW
 * conversation (the old turns are cleared). The store also persists the
 * transcript to disk, so a process death - or the future conversation
 * history sidebar - can pick the conversation back up.
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
     * The conversation has truly ended (see the class doc): the next call
     * starts a new one. Not a flow - only the engine's call bookkeeping
     * reads it, and it changes at most once per lifecycle event.
     */
    private var ended = false

    // Which surfaces currently render the conversation. Booleans, not
    // counts: each surface is one unit, and idempotent open/close pairs
    // cannot drift the way increment/decrement pairs do (a close without a
    // matching open must never push a counter negative).
    private var appSurfaceOpen = false
    private var popupSurfaceOpen = false
    // The legacy always-listening assistant service is a surface too: while
    // its foreground notification is live, a hang-up ends the call but not
    // the conversation.
    private var assistantSurfaceOpen = false

    // Disk persistence. Attached once from the Application; a null context
    // (plain unit tests) disables it. Serialization and disk writes run on
    // their own thread so a mutation on the main thread never janks.
    @Volatile
    private var prefs: android.content.SharedPreferences? = null
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ConversationStore-persist").apply { isDaemon = true }
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the current conversation has truly ended. */
    val conversationEnded: Boolean
        get() = synchronized(this) { ended }

    /**
     * Attach persistence and restore a saved transcript, once per process.
     * Called from [ai.flossyapp.FlossyApplication.onCreate]; in unit tests
     * it is never called and the store stays memory-only.
     */
    fun attach(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(
                "conversation_store", Context.MODE_PRIVATE
            )
            val saved = prefs?.getString(KEY_MESSAGES, null) ?: return
            if (_messages.value.isEmpty()) {
                runCatching {
                    _messages.value = json.decodeFromString(saved)
                }
            }
        }
    }

    /**
     * The fullscreen app surface opened (or came back). Going to the
     * settings pages and back does not close it - only the app leaving the
     * foreground (home, task removal) does.
     */
    fun setAppSurfaceOpen(open: Boolean) {
        val change = synchronized(this) {
            val changed = appSurfaceOpen != open
            appSurfaceOpen = open
            changed
        }
        if (change) evaluateEnded()
    }

    /** The mini popup surface opened (wake card raised) or closed. */
    fun setPopupSurfaceOpen(open: Boolean) {
        val change = synchronized(this) {
            val changed = popupSurfaceOpen != open
            popupSurfaceOpen = open
            changed
        }
        if (change) evaluateEnded()
    }

    /** The always-listening assistant service's surface opened or closed. */
    fun setAssistantSurfaceOpen(open: Boolean) {
        val change = synchronized(this) {
            val changed = assistantSurfaceOpen != open
            assistantSurfaceOpen = open
            changed
        }
        if (change) evaluateEnded()
    }

    /**
     * The model ended the call itself: the conversation is over at once,
     * even with a surface still on screen.
     */
    fun markConversationEndedByModel() {
        synchronized(this) { ended = true }
    }

    /** A new conversation has begun; clear the ended mark. */
    fun markConversationOpen() {
        synchronized(this) { ended = false }
    }

    /**
     * Both surfaces gone and no call live: the conversation is over. The
     * transcript itself stays (it is only cleared when a NEW conversation
     * starts), so a future history view can still show what was said.
     */
    private fun evaluateEnded() {
        val mark = synchronized(this) {
            if (!appSurfaceOpen && !popupSurfaceOpen && !assistantSurfaceOpen && !_callActive.value) {
                ended = true
                true
            } else false
        }
        if (mark) android.util.Log.d("ConversationStore", "Conversation ended (all surfaces closed)")
    }

    /**
     * Replace the whole list (used by the summarizer and the clear action).
     */
    fun setMessages(newMessages: List<ChatMessage>) {
        _messages.value = newMessages
        persist()
    }

    fun addMessage(message: ChatMessage) {
        _messages.update { current -> current + listOf(message) }
        persist()
    }

    fun clear() {
        _messages.value = emptyList()
        persist()
    }

    fun setCallActive(active: Boolean) {
        _callActive.value = active
        // A call that finished on its own (the close clock, say) with every
        // surface already gone is a conversation over - there will be no
        // surface unregister to re-evaluate the ended mark.
        if (!active) evaluateEnded()
    }

    fun setPhase(phase: VoiceCallPhase) {
        _phase.value = phase
    }

    /**
     * Serialize the transcript off the calling thread: a chat turn arrives
     * on the main thread, and JSON on a long transcript is not free.
     */
    private fun persist() {
        val p = prefs ?: return
        val snapshot = _messages.value
        persistExecutor.execute {
            runCatching {
                p.edit().putString(KEY_MESSAGES, json.encodeToString(snapshot)).apply()
            }
        }
    }

    private const val KEY_MESSAGES = "messages_json"
}
