package com.ebook.voicereader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 离线语音合成管理器。
 *
 * 基于 Android 系统 TextToSpeech 引擎。系统 TTS 引擎（如 Google 文字转语音、
 * 各厂商语音引擎）在下载对应语言包后可完全离线使用，无需网络。
 *
 * 实现要点：
 * - 使用 QUEUE_ADD + UtteranceProgressListener 实现段落间链式朗读；
 * - 每个段落分配唯一 utteranceId，在 onDone 中推进到下一段；
 * - 暂停通过 stop + 记录当前索引实现，恢复时从当前索引继续。
 */
class TtsManager(private val context: Context) {

    interface Listener {
        fun onInitSuccess()
        fun onInitFailed()
        fun onParagraphStart(index: Int)
        fun onParagraphDone(index: Int)
        fun onSpeakingChanged(speaking: Boolean)
    }

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var listener: Listener? = null

    private var paragraphs: List<String> = emptyList()
    private var currentIndex = 0
    private var isPaused = false
    private var speed = 1.0f

    @Volatile
    private var autoAdvance = true

    fun setListener(l: Listener?) {
        listener = l
    }

    fun init() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("para_")?.toIntOrNull()
                        if (idx != null) listener?.onParagraphStart(idx)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onDone(utteranceId)
                    }

                    override fun onDone(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("para_")?.toIntOrNull()
                        if (idx != null) listener?.onParagraphDone(idx)
                        if (autoAdvance && idx != null && idx == currentIndex) {
                            currentIndex++
                            if (currentIndex < paragraphs.size && !isPaused) {
                                speakCurrent()
                            } else if (currentIndex >= paragraphs.size) {
                                listener?.onSpeakingChanged(false)
                            }
                        }
                    }
                })
                listener?.onInitSuccess()
            } else {
                initialized = false
                listener?.onInitFailed()
            }
        }
    }

    /** 设置朗读语速 (0.5 ~ 2.0) */
    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speed)
    }

    /** 设置语言，中文优先 */
    fun setLanguage(locale: Locale = Locale.CHINESE) {
        tts?.language = locale
    }

    /**
     * 开始朗读段落列表。
     */
    fun speak(paragraphs: List<String>, startIndex: Int = 0) {
        if (!initialized || paragraphs.isEmpty()) return
        this.paragraphs = paragraphs
        this.currentIndex = startIndex.coerceIn(0, paragraphs.size - 1)
        this.isPaused = false
        this.autoAdvance = true
        tts?.stop()
        tts?.setSpeechRate(speed)
        listener?.onSpeakingChanged(true)
        speakCurrent()
    }

    private fun speakCurrent() {
        if (currentIndex >= paragraphs.size || currentIndex < 0) return
        val text = paragraphs[currentIndex]
        if (text.isBlank()) {
            currentIndex++
            speakCurrent()
            return
        }
        val params = HashMap<String, String>().apply {
            put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "para_$currentIndex")
        }
        // QUEUE_ADD 保证语流连续，onDone 负责推进
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params)
    }

    /** 暂停：停止当前朗读，保留索引 */
    fun pause() {
        if (!initialized) return
        isPaused = true
        tts?.stop()
        listener?.onSpeakingChanged(false)
    }

    /** 恢复：从当前索引继续 */
    fun resume() {
        if (!initialized || paragraphs.isEmpty()) return
        isPaused = false
        autoAdvance = true
        listener?.onSpeakingChanged(true)
        speakCurrent()
    }

    /** 停止并重置 */
    fun stop() {
        if (!initialized) return
        isPaused = true
        autoAdvance = false
        tts?.stop()
        currentIndex = 0
        listener?.onSpeakingChanged(false)
    }

    /** 上一段 */
    fun previous() {
        if (paragraphs.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        if (!isPaused) {
            tts?.stop()
            speakCurrent()
        }
    }

    /** 下一段 */
    fun next() {
        if (paragraphs.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(paragraphs.size - 1)
        if (!isPaused) {
            tts?.stop()
            speakCurrent()
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun isPaused(): Boolean = isPaused

    fun currentIndex(): Int = currentIndex

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }
}
