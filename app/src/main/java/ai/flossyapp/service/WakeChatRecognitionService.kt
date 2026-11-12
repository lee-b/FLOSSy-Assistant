package ai.flossyapp.service

import android.speech.RecognitionService
import android.content.Intent

/**
 * The recognition service the voice-interaction contract must name.
 *
 * VoiceInteractionServiceInfo refuses to describe a voice service at all while its
 * resource omits "recognitionService" - it answers "No recognitionService
 * specified" and the platform marks the whole voice-interaction path invalid - so
 * the app has to name a class here even though it never lets the platform do the
 * listening: the wake pipeline records the microphone itself and sends the clip to
 * the local STT endpoint, which is where transcripts actually come from.
 *
 * This class therefore only acknowledges the calls the platform makes. It is not
 * declared as a manifest <service>, so the app does not advertise itself in the
 * separate "default speech recognition" picker - it cannot answer that one, and
 * claiming to would be a lie: this Kotlin/Android toolchain exposes
 * [RecognitionService] and its three entry points, but the callback interface's
 * members (onResult, onNoMatch, onAudioStart, onAudioEnd, onError) and the
 * hypothesis type it carries are all unresolved, so a class that returned a
 * transcript cannot be written here at all.
 */
class WakeChatRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent, callback: RecognitionService.Callback) {
        android.util.Log.w("FLOSSyAssistant", "onStartListening: recognition is not served here - the wake pipeline transcribes through the app's own STT endpoint")
    }

    override fun onCancel(callback: RecognitionService.Callback) {}

    override fun onStopListening(callback: RecognitionService.Callback) {}
}
