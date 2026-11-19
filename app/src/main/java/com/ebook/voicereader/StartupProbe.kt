package com.ebook.voicereader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动探针。
 *
 * ContentProvider 的 onCreate 在 Application.onCreate **之前**执行，
 * 因此它可以记录到那些发生在 Application 初始化阶段的崩溃。
 * 记录直接写到公共「下载」目录，应用打不开也能取到。
 *
 * 另外每次启动都会把上一轮的运行轨迹（trace.log）导出到
 * 「朗读时光-上次运行.txt」，这样即使原生层崩溃、来不及写堆栈，
 * 也能知道崩溃前最后执行到了哪一步。
 */
class StartupProbe : ContentProvider() {

    override fun onCreate(): Boolean {
        // 这里绝对不能抛异常：抛了就变成“打开就闪退”。
        runCatching {
            val ctx = context ?: return true
            dumpPreviousRun(ctx)
            writeLaunchRecord(ctx)
        }
        return true
    }

    private fun dumpPreviousRun(ctx: android.content.Context) {
        runCatching {
            val trace = File(ctx.filesDir, "trace.log")
            if (!trace.exists() || trace.length() == 0L) return
            val tail = trace.readText().takeLast(6000)
            PublicLog.write(
                ctx,
                "朗读时光-上次运行.txt",
                "上一次运行的最后步骤（如果应用闪退，请看这里停在哪一步）：\n\n$tail"
            )
        }
    }

    private fun writeLaunchRecord(ctx: android.content.Context) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val text = """
            |朗读时光 启动记录
            |时间：$time
            |版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |设备：${Build.MANUFACTURER} ${Build.MODEL}
            |系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）
            |阶段：ContentProvider 已创建（应用进程启动成功）
            |说明：如果本文件停在“ContentProvider 已创建”，说明崩溃发生在
            |Application 初始化或之后，请看同目录的「朗读时光-崩溃日志.txt」。
        """.trimMargin()
        PublicLog.write(ctx, "朗读时光-启动.txt", text)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
