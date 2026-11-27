package com.ebook.voicereader.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ebook.voicereader.cache.CacheManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 把转换完成的 PCM 分段缓存导出为标准的 16-bit 单声道 WAV。
 *
 * - Android 10（API 29）及以上：写入系统媒体库「音乐/EbookVoiceReader」，
 *   不需要任何存储权限，其它播放器能直接扫描到。
 * - 更低版本：通过 SAF（CreateDocument）由用户选择保存位置。
 *
 * 全程流式读写，不会把整本书的音频载入内存。
 */
object AudioExporter {

    data class Result(
        val uri: Uri,
        /** 给用户看的位置描述 */
        val displayPath: String,
        val bytes: Long,
        val durationSeconds: Float,
    )

    class ExportException(message: String) : IOException(message)

    /** 清理文件名中的非法字符 */
    fun safeFileName(title: String): String {
        val cleaned = title.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim()
        return (if (cleaned.isBlank()) "audiobook" else cleaned).take(80) + ".wav"
    }

    /** 计算可导出的 PCM 总字节数，顺便统计缺失分段 */
    private fun measure(cache: CacheManager, meta: CacheManager.Meta): Pair<Long, Int> {
        var total = 0L
        var missing = 0
        for (i in 0 until meta.paragraphCount) {
            val f = cache.chunkFile(meta.bookId, i)
            if (f.exists()) total += f.length() else missing++
        }
        return total to missing
    }

    /**
     * 执行导出。
     *
     * @param safUri 低版本（API < 29）由 SAF 提供的目标；高版本传 null，自动写入媒体库。
     * @param onProgress (已处理段数, 总段数)，在调用线程回调（IO 线程）。
     */
    fun exportWav(
        context: Context,
        cache: CacheManager,
        meta: CacheManager.Meta,
        safUri: Uri? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        if (meta.paragraphCount <= 0) throw ExportException("这本书没有可导出的音频")
        val (pcmBytes, missing) = measure(cache, meta)
        if (pcmBytes <= 0L) throw ExportException("没有找到音频缓存，请先完成转换")
        // 标准 WAV 的大小字段是 32 位，超过 4GB 需要 RF64，这里直接拒绝并说明。
        if (pcmBytes > 0xFFFF_FF00L) {
            throw ExportException("音频超过 4GB，无法导出为单个 WAV 文件")
        }

        val fileName = safeFileName(meta.title)
        val resolver = context.contentResolver
        val useMediaStore = Build.VERSION.SDK_INT >= 29 && safUri == null

        val targetUri: Uri
        var relativePath: String? = null
        if (useMediaStore) {
            relativePath = Environment.DIRECTORY_MUSIC + "/EbookVoiceReader"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            targetUri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw ExportException("无法在音乐目录创建文件")
        } else {
            targetUri = safUri ?: throw ExportException("未选择保存位置")
        }

        try {
            resolver.openOutputStream(targetUri, "w")?.use { raw ->
                val out = BufferedOutputStream(raw, 256 * 1024)
                writeWavHeader(out, meta.sampleRate, pcmBytes)
                val buffer = ByteArray(256 * 1024)
                for (i in 0 until meta.paragraphCount) {
                    val f = cache.chunkFile(meta.bookId, i)
                    if (f.exists() && f.length() > 0) {
                        BufferedInputStream(f.inputStream(), 256 * 1024).use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    if ((i and 15) == 0 || i == meta.paragraphCount - 1) {
                        onProgress(i + 1, meta.paragraphCount)
                    }
                }
                out.flush()
            } ?: throw ExportException("无法打开输出文件")
        } catch (t: Throwable) {
            // 失败清理半成品，避免音乐目录里留下打不开的 WAV
            if (useMediaStore) runCatching { resolver.delete(targetUri, null, null) }
            throw t
        }

        if (useMediaStore) {
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            runCatching { resolver.update(targetUri, done, null, null) }
        }

        val displayPath = if (useMediaStore) {
            "音乐/EbookVoiceReader/$fileName"
        } else {
            targetUri.lastPathSegment ?: fileName
        }
        val seconds = pcmBytes / 2f / meta.sampleRate
        return Result(targetUri, displayPath, pcmBytes, seconds)
    }

    private fun writeWavHeader(out: OutputStream, sampleRate: Int, dataBytes: Long) {
        val sr = if (sampleRate > 0) sampleRate else 22050
        out.write('R'.code); out.write('I'.code); out.write('F'.code); out.write('F'.code)
        writeIntLE(out, 36 + dataBytes)
        out.write('W'.code); out.write('A'.code); out.write('V'.code); out.write('E'.code)
        out.write('f'.code); out.write('m'.code); out.write('t'.code); out.write(' '.code)
        writeIntLE(out, 16)              // fmt chunk 大小
        writeShortLE(out, 1)             // PCM
        writeShortLE(out, 1)             // 单声道
        writeIntLE(out, sr.toLong())     // 采样率
        writeIntLE(out, sr.toLong() * 2) // 字节率 = 采样率 * 2
        writeShortLE(out, 2)             // block align
        writeShortLE(out, 16)            // 位深
        out.write('d'.code); out.write('a'.code); out.write('t'.code); out.write('a'.code)
        writeIntLE(out, dataBytes)
    }

    private fun writeIntLE(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun writeShortLE(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
