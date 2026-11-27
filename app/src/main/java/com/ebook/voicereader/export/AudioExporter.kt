package com.ebook.voicereader.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ebook.voicereader.cache.CacheManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 把转换完成的 PCM 分段缓存导出成可直接播放的音频文件。
 *
 * 三种格式：
 * - WAV：无损，直接拼接 PCM，体积最大
 * - M4A：系统 MediaCodec 编码 AAC-LC，64kbps，体积约为 WAV 的 1/10
 * - MP3：内置 LAME 编码器（静态链接），64kbps，兼容性最广
 *
 * 保存位置：
 * - Android 10（API 29）及以上：写入系统媒体库「音乐/EbookVoiceReader」，其它播放器直接可见
 * - 更低版本：通过 SAF 由用户选择保存位置
 *
 * 全程按段处理，不会把整本书的音频载入内存。
 */
object AudioExporter {

    enum class Format(val extension: String, val mimeType: String) {
        WAV("wav", "audio/wav"),
        M4A("m4a", "audio/mp4"),
        MP3("mp3", "audio/mpeg"),
    }

    data class Result(
        val uri: Uri,
        /** 给用户看的位置描述 */
        val displayPath: String,
        val bytes: Long,
        val durationSeconds: Float,
    )

    class ExportException(message: String) : IOException(message)

    /** 清理文件名中的非法字符并补上对应后缀 */
    fun safeFileName(title: String, format: Format): String {
        val cleaned = title.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim()
        return (if (cleaned.isBlank()) "audiobook" else cleaned).take(80) + "." + format.extension
    }

    /** 计算可导出的 PCM 总字节数 */
    private fun measure(cache: CacheManager, meta: CacheManager.Meta): Long {
        var total = 0L
        for (i in 0 until meta.paragraphCount) {
            val f = cache.chunkFile(meta.bookId, i)
            if (f.exists()) total += f.length()
        }
        return total
    }

    /**
     * 执行导出。
     *
     * @param safUri 低版本（API < 29）由 SAF 提供的目标；高版本传 null，自动写入媒体库。
     * @param onProgress (已处理段数, 总段数)，在调用线程回调（IO 线程）。
     */
    fun export(
        context: Context,
        cache: CacheManager,
        meta: CacheManager.Meta,
        format: Format,
        safUri: Uri? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        if (meta.paragraphCount <= 0) throw ExportException("这本书没有可导出的音频")
        val pcmBytes = measure(cache, meta)
        if (pcmBytes <= 0L) throw ExportException("没有找到音频缓存，请先完成转换")
        if (format == Format.WAV && pcmBytes > 0xFFFF_FF00L) {
            // 标准 WAV 的大小字段是 32 位，超过 4GB 需要 RF64；压缩格式没有此限制
            throw ExportException("音频超过 4GB，请改用 M4A 或 MP3 压缩格式导出")
        }
        if (format == Format.MP3 && !LameEncoder.isAvailable()) {
            throw ExportException("当前设备不支持 MP3 编码，请改用 M4A 或 WAV")
        }

        val fileName = safeFileName(meta.title, format)
        val resolver = context.contentResolver
        val useMediaStore = Build.VERSION.SDK_INT >= 29 && safUri == null

        val targetUri: Uri
        if (useMediaStore) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/EbookVoiceReader",
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            targetUri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw ExportException("无法在音乐目录创建文件")
        } else {
            targetUri = safUri ?: throw ExportException("未选择保存位置")
        }

        var success = false
        try {
            val pfd = resolver.openFileDescriptor(targetUri, "w")
                ?: throw ExportException("无法打开输出文件")
            pfd.use { descriptor ->
                when (format) {
                    Format.WAV -> {
                        bufferedOut(descriptor).use { out ->
                            writeWavHeader(out, meta.sampleRate, pcmBytes)
                            forEachChunk(cache, meta, onProgress) { out.write(it) }
                            out.flush()
                        }
                    }
                    Format.MP3 -> {
                        bufferedOut(descriptor).use { out ->
                            val encoder = LameEncoder(meta.sampleRate)
                            try {
                                forEachChunk(cache, meta, onProgress) { pcm ->
                                    val encoded = encoder.encode(pcm, pcm.size)
                                    if (encoded.isNotEmpty()) out.write(encoded)
                                }
                                val tail = encoder.flush()
                                if (tail.isNotEmpty()) out.write(tail)
                                out.flush()
                            } finally {
                                encoder.close()
                            }
                        }
                    }
                    Format.M4A -> {
                        if (Build.VERSION.SDK_INT >= 26) {
                            encodeAac(cache, meta, AacEncoder(meta.sampleRate, descriptor.fileDescriptor), onProgress)
                        } else {
                            // MediaMuxer(FileDescriptor) 需要 API 26，低版本先写临时文件再复制
                            val tmp = File.createTempFile("export_", ".m4a", context.cacheDir)
                            try {
                                encodeAac(cache, meta, AacEncoder(meta.sampleRate, tmp.absolutePath), onProgress)
                                bufferedOut(descriptor).use { out ->
                                    tmp.inputStream().buffered().use { it.copyTo(out) }
                                }
                            } finally {
                                tmp.delete()
                            }
                        }
                    }
                }
            }
            success = true
        } finally {
            // 失败清理半成品，避免音乐目录里留下打不开的音频
            if (!success && useMediaStore) {
                runCatching { resolver.delete(targetUri, null, null) }
            }
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
        return Result(targetUri, displayPath, pcmBytes, pcmBytes / 2f / meta.sampleRate)
    }

    private fun bufferedOut(descriptor: android.os.ParcelFileDescriptor): BufferedOutputStream =
        BufferedOutputStream(FileOutputStream(descriptor.fileDescriptor), 256 * 1024)

    private fun encodeAac(
        cache: CacheManager,
        meta: CacheManager.Meta,
        encoder: AacEncoder,
        onProgress: (Int, Int) -> Unit,
    ) {
        try {
            forEachChunk(cache, meta, onProgress) { encoder.encode(it, it.size) }
            encoder.finish()
        } finally {
            encoder.close()
        }
    }

    /** 按顺序遍历所有 PCM 分段，空段自动跳过 */
    private inline fun forEachChunk(
        cache: CacheManager,
        meta: CacheManager.Meta,
        onProgress: (Int, Int) -> Unit,
        action: (ByteArray) -> Unit,
    ) {
        for (i in 0 until meta.paragraphCount) {
            val f = cache.chunkFile(meta.bookId, i)
            if (f.exists() && f.length() > 0) {
                action(f.readBytes())
            }
            if ((i and 7) == 0 || i == meta.paragraphCount - 1) {
                onProgress(i + 1, meta.paragraphCount)
            }
        }
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
