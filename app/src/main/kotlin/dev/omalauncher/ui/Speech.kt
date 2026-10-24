package dev.omalauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Android speech recognition for the prompt bar. The guest has no microphone,
 * so speech becomes text here and only text reaches Linux. Prefers the
 * on-device recognizer (offline, fast) and falls back to the regular one when
 * it has no model for the language. Phone build: or when it can't at all, as
 * on One UI 9, which names Android System Intelligence, not a recognition
 * service, so every try fails with 10. The regular one is asked for its
 * offline model too, and goes online only when it has none for the language.
 * Main thread only, like SpeechRecognizer.
 */
class Speech(
    private val context: Context,
) {
    var listening by mutableStateOf(false)
        private set

    /** What's been recognized so far: finished segments plus the current partial result. */
    var text by mutableStateOf("")
        private set

    /** Input loudness, 0..1. */
    var level by mutableFloatStateOf(0f)
        private set

    /** Why recognition failed; the UI clears it once shown. */
    var error by mutableStateOf<String?>(null)

    private var recognizer: SpeechRecognizer? = null

    /** Phone build: whether [recognizer] is the on-device one. */
    private var recognizerOnDevice = false

    /** Phone build: whether the on-device recognizer is still to be tried, where there is one; off once it failed. */
    private var onDevice = true

    /**
     * Phone build: whether the regular recognizer is asked to stay offline. Each [start] asks, so a model downloaded
     * since is used; it goes online only when there's none for the language.
     */
    private var offline = true

    /** Phone build: [stop] was asked for: whatever goes wrong then ends it, without trying another recognizer. */
    private var stopping = false
    private var finished = ""
    private var onDone: ((String) -> Unit)? = null

    /** While held, pauses don't end recognition: a new segment starts until [stop]. */
    private var held = false

    private val main = Handler(Looper.getMainLooper())

    /**
     * Phone build: a start the system dropped gets no answer at all, which would leave the bar listening for good. The
     * on-device recognizer's gives way to the regular one's, as its errors do.
     */
    private val unanswered =
        Runnable {
            if (recognizerOnDevice && !stopping) {
                useRegular("the on-device recognizer didn't answer")
            } else {
                Log.w(TAG, "the speech recognizer didn't answer")
                if (text.isBlank()) error = "the speech recognizer didn't answer"
                finish()
            }
        }

    private val onDeviceAvailable get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available get() = onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening; [onDone] gets the full text (maybe empty) once recognition ends.
     * With [hold], it keeps listening across pauses until [stop].
     */
    fun start(
        hold: Boolean,
        onDone: (String) -> Unit,
    ) {
        cancel()
        error = null
        if (!available) {
            error = "no speech recognizer on this phone"
            return
        }
        held = hold
        stopping = false
        offline = true
        this.onDone = onDone
        finished = ""
        text = ""
        listening = true
        listen()
    }

    /** Ends recognition now; the text so far goes to onDone. */
    fun stop() {
        held = false
        stopping = true
        recognizer?.stopListening() ?: finish()
    }

    /** Ends recognition without calling onDone. */
    fun cancel() {
        held = false
        onDone = null
        main.removeCallbacks(unanswered)
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
    }

    /**
     * The language to recognize: the phone's, or the closest one the recognizer
     * has a model for. Recognizers only know common language-region pairs, so a
     * phone set to English (Germany) needs en-US or en-GB instead. Null until checked.
     */
    private var language: String? = null

    /**
     * Starts a segment. Phone build: on the recognizer already bound when it's the kind wanted: one destroyed and made
     * again at once binds to a service still being torn down, and its start is dropped without a word.
     */
    private fun listen() {
        main.removeCallbacks(unanswered)
        main.postDelayed(unanswered, ANSWER_MS)
        val wanted = onDevice && onDeviceAvailable
        val r = recognizer?.takeIf { recognizerOnDevice == wanted } ?: newRecognizer(wanted)
        if (language != null || Build.VERSION.SDK_INT < 33) return begin(r)
        guarded {
            r.checkRecognitionSupport(
                intent(),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    // Speech Services by Google answers twice; listening starts once.
                    private var started = false

                    override fun onSupportResult(support: RecognitionSupport) {
                        if (r !== recognizer || started) return
                        language = closestLanguage(support)
                        Log.i(TAG, "recognizing ${language ?: "the default language"}")
                        start()
                    }

                    override fun onError(error: Int) {
                        if (r === recognizer && !started) start()
                    }

                    private fun start() {
                        started = true
                        begin(r)
                    }
                },
            )
        }
    }

    private fun begin(r: SpeechRecognizer) = guarded { r.startListening(intent()) }

    /**
     * Runs [call] on the recognizer. The phone's default one can be another app's that won't let
     * others bind to it (ChatGPT's voice assistant): that throws instead of reporting an error.
     */
    private fun guarded(call: () -> Unit) {
        try {
            call()
        } catch (e: SecurityException) {
            Log.w(TAG, "the speech recognizer refused", e)
            if (text.isBlank()) error = "the phone's speech recognizer doesn't let other apps use it"
            finish()
        }
    }

    /** The regular recognizer from now on, the on-device one having failed ([why]). */
    private fun useRegular(why: String) {
        Log.i(TAG, "$why, using the regular recognizer")
        onDevice = false
        // The regular recognizer may know other languages than the on-device one.
        language = null
        listen()
    }

    private fun newRecognizer(useOnDevice: Boolean): SpeechRecognizer {
        recognizer?.destroy()
        val r =
            if (useOnDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        recognizer = r
        recognizerOnDevice = useOnDevice
        r.setRecognitionListener(Listener(r))
        return r
    }

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .apply { language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }

    /**
     * The phone's language-region if supported, else the same language in another
     * region: installed models first, then the language's home region (de-DE),
     * then en-US style defaults, then any.
     */
    @androidx.annotation.RequiresApi(33)
    private fun closestLanguage(support: RecognitionSupport): String? {
        val locale = Locale.getDefault()
        val wanted = Locale(locale.language, locale.country).toLanguageTag()
        val pools =
            if (recognizerOnDevice) {
                listOf(support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages, support.onlineLanguages)
            } else {
                listOf(support.onlineLanguages, support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages)
            }
        pools.flatten().firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
        val home = "${locale.language}-${locale.language}"
        for (pool in pools) {
            val same = pool.filter { it.substringBefore('-').equals(locale.language, ignoreCase = true) }
            same
                .minByOrNull {
                    when {
                        it.equals(home, ignoreCase = true) -> 0
                        it.endsWith("-US", ignoreCase = true) -> 1
                        else -> 2
                    }
                }?.let { return it }
        }
        return null
    }

    private fun finish() {
        main.removeCallbacks(unanswered)
        recognizer?.destroy()
        recognizer = null
        listening = false
        level = 0f
        val done = onDone
        onDone = null
        done?.invoke(text.trim())
    }

    private fun join(
        a: String,
        b: String,
    ) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

    private fun Bundle.best() = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** Listens to one recognizer; callbacks from one that's been replaced are ignored. */
    private inner class Listener(
        private val r: SpeechRecognizer,
    ) : RecognitionListener {
        private val current get() = r === recognizer

        override fun onPartialResults(partial: Bundle) {
            if (current) text = join(finished, partial.best())
        }

        override fun onResults(results: Bundle) {
            if (!current) return
            main.removeCallbacks(unanswered)
            finished = join(finished, results.best())
            text = finished
            if (held) listen() else finish()
        }

        override fun onError(code: Int) {
            if (!current) return
            main.removeCallbacks(unanswered)
            when (code) {
                // Silence or nothing understood: fine while the button is held.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (held) listen() else finish()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("microphone permission needed")
                // Phone build: anything else the on-device recognizer can't do, the regular one may, for as long as
                // this lasts; and the regular one without an offline model may online. Not once stopped: it would
                // start listening again.
                else ->
                    if (recognizerOnDevice && !stopping) {
                        useRegular("on-device recognition failed ($code)")
                    } else if (offline && !stopping && code in NO_OFFLINE_MODEL) {
                        Log.i(TAG, "offline recognition failed ($code), recognizing online")
                        offline = false
                        listen()
                    } else {
                        fail(
                            when (code) {
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "speech isn't available in this language"
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "speech recognition needs a network connection"
                                else -> "speech recognition failed ($code)"
                            },
                        )
                    }
            }
        }

        private fun fail(message: String) {
            Log.w(TAG, message)
            // Keep what was already recognized; the error is only worth showing when there's nothing.
            if (text.isBlank()) error = message
            finish()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (current) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (current) main.removeCallbacks(unanswered)
        }

        override fun onBeginningOfSpeech() {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) {}
    }

    companion object {
        private const val TAG = "speech"

        /** How long a start may go unanswered: a cold Speech Services takes about 300 ms. */
        private const val ANSWER_MS = 5000L

        /**
         * What the regular recognizer says, asked to stay offline, when it has no model for the language: Speech
         * Services by Google has said both 13 and 5 for a language pack it hasn't downloaded.
         */
        private val NO_OFFLINE_MODEL =
            setOf(SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    }
}
