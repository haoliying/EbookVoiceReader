package com.ebook.voicereader

import android.app.Application
import android.os.Build
import android.util.Log
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.ebook.voicereader.ocr.OcrEngine
import com.ebook.voicereader.tts.TtsManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
        // PdfBox-Android 官方要求在首次读写 PDF 前初始化应用资源。
        PDFBoxResourceLoader.init(this)

        ttsManager = TtsManager(this).apply {
            setCache(cache)
            val defaultMode =
                if (prefs.defaultMode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED
            setMode(defaultMode)
        }
        // init() 由 ReaderActivity / ConvertService 按需调用，书库启动不加载模型。
    }

    override fun onTerminate() {
        runCatching { ttsManager.release() }
        runCatching { ocrEngine.close() }
        super.onTerminate()
    }

    /** 下次启动只提示一次；日志保留到 last-crash.log 供设置页复制。 */
    fun consumeCrashReport(): String? {
        val source = File(filesDir, "crash.log")
        if (!source.exists()) return null
        return runCatching {
            val report = source.readText().takeLast(12000)
            val previous = File(filesDir, "last-crash.log")
            if (previous.exists()) previous.delete()
            source.renameTo(previous)
            report
        }.getOrNull()
    }

    fun diagnosticReport(): String {
        val header = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "设备 ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val logs = listOf("crash.log", "last-crash.log").mapNotNull { name ->
            val file = File(filesDir, name)
            if (file.exists()) runCatching { "===== $name =====\n" + file.readText().takeLast(12000) }.getOrNull() else null
        }
        return (listOf(header) + logs).joinToString("\n\n")
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
