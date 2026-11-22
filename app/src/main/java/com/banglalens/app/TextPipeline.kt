package com.banglalens.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class NetworkNeededException(cause: Throwable) : Exception(cause)

/** OCR -> language decision -> Bengali. */
class TextPipeline {

    data class Outcome(val original: String, val bengali: String)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
    )

    /** Reads text from the already-cropped selection. */
    suspend fun recognize(bitmap: Bitmap): String {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.joinToString("\n") { block ->
            block.lines.joinToString(" ") { it.text }
        }.trim()
    }

    suspend fun toBengali(raw: String): Outcome {
        val text = raw.trim()

        // Already Bengali script: nothing to translate, just show and read it.
        if (BanglishTransliterator.containsBengaliScript(text)) return Outcome(text, text)

        val ratio = BanglishTransliterator.knownRatio(text)
        val tag = try { langId.identifyLanguage(text).await() } catch (e: Exception) { "und" }

        val banglish = ratio >= 0.34 || tag == "bn-Latn" || (tag == "und" && ratio > 0.0)
        if (banglish) return Outcome(text, BanglishTransliterator.convert(text))

        // English (or another language ML Kit can translate)
        val source = TranslateLanguage.fromLanguageTag(tag.substringBefore('-')) ?: TranslateLanguage.ENGLISH
        if (source == TranslateLanguage.BENGALI) return Outcome(text, text)
        return Outcome(text, translate(text, source))
    }

    private suspend fun translate(text: String, source: String): String {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.BENGALI)
                .build()
        )
        try {
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                throw NetworkNeededException(e)
            }
            return translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    fun close() {
        recognizer.close()
        langId.close()
    }
}
