package com.ebook.voicereader

import android.app.Application
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.ebook.voicereader.convert.ConvertService
import com.ebook.voicereader.ocr.OcrEngine
import com.ebook.voicereader.tts.TtsManager

/**
 * 全局单例容器：TtsManager / CacheManager / OcrEngine / Prefs
 * 让播放引擎跨 Activity 存活（切到 Library 不停止朗读）
 */
class App : Application() {

    lateinit var cache: CacheManager
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var ttsManager: TtsManager
        private set
    lateinit var ocrEngine: OcrEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        cache = CacheManager(this)
        prefs = Prefs(this)
        ocrEngine = OcrEngine()

        ttsManager = TtsManager(this).apply {
            setCache(cache)
            val defaultMode = if (prefs.defaultMode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED
            setMode(defaultMode)
        }
        // init() 在 LibraryActivity 中调用，等 UI 准备好
    }

    override fun onTerminate() {
        runCatching { ttsManager.release() }
        runCatching { ocrEngine.close() }
        super.onTerminate()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
