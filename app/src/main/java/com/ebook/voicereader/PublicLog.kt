package com.ebook.voicereader

import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把日志写到公共「下载」目录。
 *
 * 为什么需要它：应用在启动阶段崩溃时，用户根本进不到应用里的诊断页面，
 * 只有写到公共目录的文件才能被取出来。所有写入都吞掉异常，绝不因为写日志失败而影响主流程。
 */
object PublicLog {

    fun write(context: Context, name: String, text: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(context, name, text)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeText(text)
            }
        }
    }

    private fun writeToDownloads(context: Context, name: String, text: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = runCatching {
            resolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
        }.getOrNull()

        val uri = existing ?: runCatching {
            resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            })
        }.getOrNull() ?: return

        runCatching {
            // "wt" 以截断方式打开，避免多次崩溃后内容不断叠加。
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        }.onFailure {
            resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) }
        }
    }
}
