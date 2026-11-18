package com.ebook.voicereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 离线语音合成管理器（系统 TextToSpeech 引擎）。
 *
 * 关键点：
 * 1. 必须显式设置语言。若不设置，部分设备默认语言为英文，中文文本会被静默跳过 → 完全无声。
 * 2. 必须检查 setLanguage 返回值，LANG_MISSING_DATA / LANG_NOT_SUPPORTED 说明系统缺少离线语音包。
 * 3. 一次只投递一个段落（QUEUE_FLUSH），在 onDone 中推进下一段，避免部分引擎在 stop() 后丢弃队列。
 */
class TtsManager(private val context: Context) {

    interface Listener {
        /** 引擎就绪，且语言可用。languageName 为实际使用的语言名 */
        fun onEngineReady(languageName: String)

        /** 引擎不可用 / 缺少语音数据，reason 用于提示用户 */
        fun onEngineUnavailable(reason: String)

        fun onParagraphStart(index: Int)

        /** 播放状态变化（true=正在朗读） */
        fun onSpeakingChanged(speaking: Boolean)

        /** 全部段落读完 */
        fun onFinished()

        /** 朗读过程中的错误 */
        fun onError(message: String)
    }

    private var tts: TextToSpeech? = null
    private var listener: Listener? = null

    @Volatile
    private var ready = false

    @Volatile
    private var languageOk = false

    private var paragraphs: List<String> = emptyList()
    private var currentIndex = 0
    private var paused = false
    private var speed = 1.0f
    private var currentLocale: Locale? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    fun init() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                listener?.onEngineUnavailable(
                    "语音引擎初始化失败，设备可能未安装文字转语音（TTS）服务"
                )
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setPitch(1.0f)

            val (ok, name) = applyBestLanguage(engine)
            languageOk = ok
            // 语速必须在设置语言之后再设置，部分引擎切换语言会重置语速
            engine.setSpeechRate(speed)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val idx = indexOf(utteranceId) ?: return
                    listener?.onParagraphStart(idx)
                }

                override fun onDone(utteranceId: String?) {
                    val idx = indexOf(utteranceId) ?: return
                    if (paused || idx != currentIndex) return
                    currentIndex++
                    if (currentIndex < paragraphs.size) {
                        speakCurrent()
                    } else {
                        listener?.onSpeakingChanged(false)
                        listener?.onFinished()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    listener?.onError("语音合成出错，请检查系统语音引擎")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    listener?.onError(errorMessage(errorCode))
                    listener?.onSpeakingChanged(false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    // 主动 stop 时无需处理
                }
            })

            ready = true
            if (ok) {
                listener?.onEngineReady(name)
            } else {
                listener?.onEngineUnavailable(
                    "系统缺少可用的中文离线语音包，请在「系统设置 → 文字转语音」中安装后重试"
                )
            }
        }
    }

    /**
     * 应用可用语言，优先简体中文（本应用主要面向中文书籍）。
     * 返回 (是否可用, 语言显示名)。
     */
    private fun applyBestLanguage(engine: TextToSpeech): Pair<Boolean, String> {
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.getDefault(),
            Locale.CHINESE,
            Locale.US
        )
        for (locale in candidates) {
            if (trySetLocale(engine, locale)) {
                currentLocale = locale
                val display = locale.displayName.ifBlank { locale.toString() }
                return true to display
            }
        }
        return false to ""
    }

    private fun trySetLocale(engine: TextToSpeech, locale: Locale): Boolean {
        val result = try {
            engine.setLanguage(locale)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        return result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 根据文本内容切换中/英语音。
     * 中文书籍配英文引擎时会静默无声，因此朗读前必须检查。
     */
    private fun ensureLanguageForText(sampleText: String): Boolean {
        val engine = tts ?: return false
        val hasCjk = sampleText.any { it.code in 0x4E00..0x9FFF }
        val wantZh = hasCjk
        val current = currentLocale

        if (wantZh) {
            if (current?.language == "zh") return true
            for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE)) {
                if (trySetLocale(engine, locale)) {
                    currentLocale = locale
                    engine.setSpeechRate(speed)
                    return true
                }
            }
            listener?.onError("系统缺少中文语音包，请在「设置 → 文字转语音」中下载")
            return false
        } else {
            if (current?.language == "en") return true
            for (locale in listOf(Locale.getDefault(), Locale.US, Locale.UK)) {
                if (trySetLocale(engine, locale)) {
                    currentLocale = locale
                    engine.setSpeechRate(speed)
                    return true
                }
            }
            // 没有英语语音包时保留当前语言继续朗读
            return true
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "语音合成失败，请检查语音包是否完整"
        TextToSpeech.ERROR_INVALID_REQUEST -> "无效的朗读请求"
        TextToSpeech.ERROR_NETWORK -> "语音引擎需要联网，请安装离线语音包"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "语音引擎网络超时"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "语音数据尚未安装完成"
        TextToSpeech.ERROR_OUTPUT -> "音频输出失败，请检查媒体音量"
        TextToSpeech.ERROR_SERVICE -> "语音服务异常"
        else -> "语音合成出错（错误码 $code）"
    }

    /** 引擎是否可用（初始化完成且语言可用） */
    fun isReady(): Boolean = ready && languageOk

    fun isEngineInitialized(): Boolean = ready

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speed)
    }

    fun getSpeed(): Float = speed

    /**
     * 开始/重新开始朗读整本书。
     */
    fun speak(allParagraphs: List<String>, startIndex: Int = 0) {
        if (!isReady() || allParagraphs.isEmpty()) return
        // 根据内容切换语音，避免中文配英文引擎导致无声
        val sample = allParagraphs.take(3).joinToString(" ")
        if (!ensureLanguageForText(sample)) return
        paragraphs = allParagraphs
        currentIndex = startIndex.coerceIn(0, allParagraphs.size - 1)
        paused = false
        tts?.stop()
        tts?.setSpeechRate(speed)
        listener?.onSpeakingChanged(true)
        speakCurrent()
    }

    private fun speakCurrent() {
        if (currentIndex !in paragraphs.indices) return
        val engine = tts ?: return
        val text = paragraphs[currentIndex].trim()
        if (text.isEmpty()) {
            // 跳过空段落
            currentIndex++
            if (currentIndex < paragraphs.size) speakCurrent()
            else {
                listener?.onSpeakingChanged(false)
                listener?.onFinished()
            }
            return
        }
        val utteranceId = "p$currentIndex"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        // 单段投递，避免队列被 stop() 清空导致无声
        val ret = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (ret == TextToSpeech.ERROR) {
            listener?.onError("语音播放失败，请确认媒体音量与语音包")
            listener?.onSpeakingChanged(false)
        }
    }

    fun pause() {
        if (!ready) return
        paused = true
        tts?.stop()
        listener?.onSpeakingChanged(false)
    }

    fun resume() {
        if (!isReady() || paragraphs.isEmpty()) return
        paused = false
        listener?.onSpeakingChanged(true)
        speakCurrent()
    }

    fun stop() {
        if (!ready) return
        paused = true
        tts?.stop()
        currentIndex = 0
        listener?.onSpeakingChanged(false)
    }

    fun previous() {
        if (paragraphs.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        if (!paused) {
            tts?.stop()
            speakCurrent()
        }
    }

    fun next() {
        if (paragraphs.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(paragraphs.size - 1)
        if (!paused) {
            tts?.stop()
            speakCurrent()
        }
    }

    fun isPaused(): Boolean = paused

    fun currentIndex(): Int = currentIndex

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        languageOk = false
    }

    private fun indexOf(utteranceId: String?): Int? =
        utteranceId?.removePrefix("p")?.toIntOrNull()
}
