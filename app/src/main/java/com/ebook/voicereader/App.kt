package com.ebook.voicereader

import android.app.Application
import android.util.Log
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.ebook.voicereader.ocr.OcrEngine
import com.ebook.voicereader.tts.TtsManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局单例容器：TtsManager / CacheManager / OcrEngine / Prefs
 * 让播放引擎跨 Activity 存活（切到书库不停止朗读）
 *
 * 同时注册未捕获异常处理器，把崩溃栈写入 files/crash.log，
 * 方便设备上复现问题后取日志定位。
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
        installCrashHandler()

        cache = CacheManager(this)
        cache.resetStaleConverting()
        prefs = Prefs(this)
        ocrEngine = OcrEngine()

        ttsManager = TtsManager(this).apply {
            setCache(cache)
            val defaultMode =
                if (prefs.defaultMode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED
            setMode(defaultMode)
        }
        // init() 由 LibraryActivity / ConvertService 幂等调用
    }

    override fun onTerminate() {
        runCatching { ttsManager.release() }
        runCatching { ocrEngine.close() }
        super.onTerminate()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val file = File(filesDir, "crash.log")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                file.appendText(
                    "\n===== $time [${thread.name}] =====\n" +
                        Log.getStackTraceString(throwable) + "\n"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
