package com.ebook.voicereader

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        trace("app:onCreate 开始 · 可用内存 ${availableMemoryMB()} MB")

        cache = CacheManager(this)
        cache.resetStaleConverting()
        prefs = Prefs(this)
        ocrEngine = OcrEngine()
        // PdfBox 只在真正读取 PDF 时初始化（见 PdfReader），避免启动阶段加载大量类。

        ttsManager = TtsManager(this).apply {
            setCache(cache)
            val defaultMode =
                if (prefs.defaultMode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED
            setMode(defaultMode)
        }
        trace("app:onCreate 完成")
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
        val logs = listOf("crash.log", "last-crash.log", "trace.log").mapNotNull { name ->
            val file = File(filesDir, name)
            if (file.exists()) runCatching { "===== $name =====\n" + file.readText().takeLast(20000) }.getOrNull() else null
        }
        return (listOf(header) + logs).joinToString("\n\n")
    }

    /**
     * 逐步写入运行轨迹。原生层崩溃（SIGABRT / 被系统杀进程）不会产生 Java 堆栈，
     * 这份文件可以告诉我们在崩溃前执行到了哪一步。
     */
    fun trace(tag: String) {
        runCatching {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
            val file = File(filesDir, "trace.log")
            if (file.exists() && file.length() > 512 * 1024) file.writeText("")
            file.appendText("$time  $tag\n")
        }
    }

    /** 可用内存（MB），用于判断加载大型模型是否会直接触发原生 OOM。 */
    fun availableMemoryMB(): Long = runCatching {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem / (1024 * 1024)
    }.getOrDefault(-1L)

    /**
     * 把诊断日志写到公共下载目录，用户无需 root 或数据线即可发送出来。
     * Android 10+ 走 MediaStore，不申请任何存储权限。
     */
    fun exportDiagnostics(): String {
        val name = "朗读时光-诊断日志.txt"
        val text = diagnosticReport() +
            "\n\n可用内存：${availableMemoryMB()} MB\n"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "无法创建下载文件"
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "已保存到「下载」目录：$name"
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(text)
        return "已保存到：${file.absolutePath}"
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                val text = "\n===== $time [${thread.name}] =====\n" +
                    Log.getStackTraceString(throwable) + "\n"
                File(filesDir, "crash.log").appendText(text)
                trace("crash:${throwable.javaClass.name}: ${throwable.message}")
                // 关键：启动就崩溃时应用内的诊断页永远打不开，
                // 所以崩溃时直接把日志写到公共下载目录，用户不打开应用也能发出来。
                writePublicCrashLog(
                    "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "设备 ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        text + "\n最后运行步骤：\n" +
                        runCatching { File(filesDir, "trace.log").readText().takeLast(3000) }.getOrDefault("")
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 把崩溃堆栈写到公共「下载」目录；失败也不影响崩溃流程。 */
    private fun writePublicCrashLog(text: String) {
        runCatching {
            val name = "朗读时光-崩溃日志.txt"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeText(text)
            }
        }
    }

    companion object {
        lateinit var instance: App
            private set

        /** 任何线程都能安全调用的轨迹记录，不会被未初始化的单例打断。 */
        fun traceSafe(tag: String) {
            runCatching { instance.trace(tag) }
        }
    }
}
