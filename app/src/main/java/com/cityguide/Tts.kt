package com.cityguide

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Голосові підказки українською. Якщо голосу немає в телефоні, мовчить (краще, ніж спотворена вимова). */
class Tts(context: Context) : TextToSpeech.OnInitListener {

    private var ready = false
    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = engine.setLanguage(Locale("uk", "UA"))
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
