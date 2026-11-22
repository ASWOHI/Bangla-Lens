package com.banglalens.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Manual-only Bengali reader. Nothing is spoken until [play] is called.
 * Android TTS has no native pause, so text is spoken sentence by sentence and
 * pause/resume restarts from the current sentence.
 */
class SpeechController(
    context: Context,
    private val onChange: () -> Unit,
    private val onMessage: (String) -> Unit
) {
    enum class State { IDLE, SPEAKING, PAUSED }

    var state = State.IDLE
        private set

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var bengaliOk = false
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var gen = 0
    private var currentText: String? = null
    private var pendingText: String? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { main.post { advance(utteranceId) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { advance(utteranceId) } }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    bengaliOk = selectBengali()
                    tts?.setOnUtteranceProgressListener(listener)
                    ready = true
                    pendingText?.let { pendingText = null; play(it) }
                } else {
                    onMessage(appContext.getString(R.string.tts_failed))
                }
            }
        }
    }

    private fun selectBengali(): Boolean {
        val t = tts ?: return false
        for (tag in listOf("bn-BD", "bn-IN", "bn")) {
            val r = t.setLanguage(Locale.forLanguageTag(tag))
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return true
        }
        return false
    }

    /** Call only from a button press. */
    fun play(text: String) {
        if (text.isBlank()) return
        currentText = text
        if (!ready) {
            pendingText = text
            onMessage(appContext.getString(R.string.tts_loading))
            return
        }
        if (!bengaliOk) {
            onMessage(appContext.getString(R.string.tts_no_bengali))
            try {
                appContext.startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) { }
            return
        }
        gen++
        tts?.stop()
        chunks = text.split(Regex("(?<=[.।!?\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        index = 0
        state = State.SPEAKING
        onChange()
        speakCurrent()
    }

    fun pause() {
        if (state != State.SPEAKING) return
        gen++
        tts?.stop()
        state = State.PAUSED
        onChange()
    }

    fun resume() {
        if (state != State.PAUSED) return
        gen++
        state = State.SPEAKING
        onChange()
        speakCurrent()
    }

    fun stop() {
        gen++
        tts?.stop()
        state = State.IDLE
        index = 0
        onChange()
    }

    fun repeat() { currentText?.let { play(it) } }

    fun shutdown() {
        gen++
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun speakCurrent() {
        val c = chunks.getOrNull(index)
        if (c == null) { finish(); return }
        tts?.speak(c, TextToSpeech.QUEUE_FLUSH, null, "$gen:$index")
    }

    private fun advance(id: String?) {
        val g = id?.substringBefore(':')?.toIntOrNull()
        if (g != gen || state != State.SPEAKING) return
        index++
        if (index < chunks.size) speakCurrent() else finish()
    }

    private fun finish() {
        state = State.IDLE
        index = 0
        onChange()
    }
}
