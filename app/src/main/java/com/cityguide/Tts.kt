package com.cityguide

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Голосові підказки мовою інтерфейсу (рядки tts_locale у ресурсах збігаються з перекладами підказок).
 * Якщо голосу такою мовою немає в телефоні, мовчить: краще, ніж спотворена вимова.
 */
class Tts(context: Context) : TextToSpeech.OnInitListener {

    private val locale = Locale.forLanguageTag(context.getString(R.string.tts_locale))
    private var ready = false
    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = engine.setLanguage(locale)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun speak(text: String) {
        if (ready) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }
}
