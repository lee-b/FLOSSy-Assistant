package ai.flossyapp.domain.model

/**
 * The dialog's mode: the app is either plain text messaging or a hands-free
 * voice call, never both at once. The mic tap opens a call, the End Call
 * button closes it, and every other concept (mic button appearance, spoken
 * prompts, re-listening after replies) follows this state.
 */
enum class DialogMode {
    TEXT_CHAT,
    VOICE_CALL
}

/**
 * What a voice call is doing right now. The phases follow one turn of the
 * pipeline: hear the user ([LISTENING], [HEARD]), understand them
 * ([TRANSCRIBING], [THINKING]), answer ([SPEAKING]).
 *
 * [HEARD] is the VAD hand-off: speech matched the energy threshold and a clip
 * is being kept for transcription, but the transcription has not started.
 * [IDLE] inside a call means the call is open but the microphone is not armed
 * between turns.
 */
enum class VoiceCallPhase {
    IDLE,
    LISTENING,
    HEARD,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    /**
     * Wake-only: the service is coming alive but the capture is not open yet.
     * A transient state between launch and the listening loop's first pass, so
     * the notification never says a live state the pipeline has not reached.
     */
    STARTUP,
    /**
     * Wake-only: the service is alive but the listening loop is stopped - the
     * assistant is present and hearing nothing. Distinct from [IDLE] so a
     * stopped loop cannot read as one that is running.
     */
    SLEEPING
}

/**
 * One typed snapshot of the dialog state, replacing the stringly "idle /
 * listening / processing / speaking" flags that used to live in four
 * independent variables which could disagree with each other.
 *
 * The mode is the whole dialog; the phase is only meaningful when the mode is
 * [DialogMode.VOICE_CALL] - in text-chat mode the phase is always
 * [VoiceCallPhase.IDLE].
 */
data class DialogState(
    val mode: DialogMode = DialogMode.TEXT_CHAT,
    val phase: VoiceCallPhase = VoiceCallPhase.IDLE
) {
    val isVoiceCall: Boolean get() = mode == DialogMode.VOICE_CALL
    val isListening: Boolean get() = mode == DialogMode.VOICE_CALL && phase == VoiceCallPhase.LISTENING
    val isThinking: Boolean get() = mode == DialogMode.VOICE_CALL && (phase == VoiceCallPhase.THINKING || phase == VoiceCallPhase.TRANSCRIBING || phase == VoiceCallPhase.HEARD)
    val isSpeaking: Boolean get() = mode == DialogMode.VOICE_CALL && phase == VoiceCallPhase.SPEAKING
    val isBusy: Boolean get() = isThinking || isSpeaking
}
