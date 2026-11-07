// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import helium314.keyboard.latin.utils.Log

/** Listens to one spoken command in Portuguese for the launcher mode (long press on space). */
class LauncherSpeech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /**
     * Calls [onReady] when the microphone is listening, then back with the recognized text, or null. Calls from a
     * recognizer replaced by a newer listen (a late error of the old one, for instance) are ignored.
     */
    fun listen(onReady: () -> Unit, onText: (String?) -> Unit) {
        recognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        this.recognizer = recognizer
        fun current() = this.recognizer === recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (!current()) return
                onText(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                recognizer.destroy()
            }
            override fun onError(error: Int) {
                if (!current()) return
                Log.w("launcher-speech", "speech recognition error $error")
                onText(null)
                recognizer.destroy()
            }
            override fun onReadyForSpeech(params: Bundle?) {
                if (current()) onReady()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-PT"))
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
