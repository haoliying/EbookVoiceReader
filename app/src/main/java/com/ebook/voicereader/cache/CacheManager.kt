package com.ebook.voicereader.cache

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 已转换书籍的持久化存储。
 *
 * 每本书一个目录：cache/books/{sha1(uri)}/
 *   meta.json          —— 书名、格式、段数、sampleRate、语音模式、转换状态
 *   00000.pcm          —— 第 0 段的 PCM 片段（ShortArray，16bit MONO）
 *   00001.pcm
 *   ...
 *
 * 设计说明：
 * - 用 PCM 分片而不是整个合成 WAV：播放时可以跳段，也支持"部分转换"（转换到第 10 段就够听了）
 * - 不引入 Room/SQLite：整个 repo 用 JSON 管理足够，简单、零依赖
 */
class CacheManager(private val context: Context) {

    private val root: File = File(context.filesDir, "books")

    init {
        root.mkdirs()
    }

    fun bookDir(bookId: String): File = File(root, bookId).also { it.mkdirs() }

    fun metaFile(bookId: String): File = File(bookDir(bookId), "meta.json")

    fun metaOf(bookId: String): Meta? {
        val f = metaFile(bookId)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            Meta(
                bookId = bookId,
                sourceUri = o.optString("sourceUri", ""),
                title = o.optString("title", bookId),
                format = o.optString("format", ""),
                paragraphCount = o.optInt("paragraphCount", 0),
                totalSeconds = o.optDouble("totalSeconds", 0.0).toFloat(),
                sampleRate = o.optInt("sampleRate", 22050),
                mode = o.optString("mode", "mixed"),
                convertedUpTo = o.optInt("convertedUpTo", -1),
                status = Status.valueOf(o.optString("status", "NONE")),
                createdAt = o.optLong("createdAt", 0L),
                errorMessage = o.optString("errorMessage", ""),
                lastPlayedIndex = o.optInt("lastPlayedIndex", -1),
            )
        }.getOrNull()
    }

    fun saveMeta(meta: Meta) {
        val o = JSONObject()
            .put("sourceUri", meta.sourceUri)
            .put("title", meta.title)
            .put("format", meta.format)
            .put("paragraphCount", meta.paragraphCount)
            .put("totalSeconds", meta.totalSeconds.toDouble())
            .put("sampleRate", meta.sampleRate)
            .put("mode", meta.mode)
            .put("convertedUpTo", meta.convertedUpTo)
            .put("status", meta.status.name)
            .put("createdAt", meta.createdAt)
            .put("errorMessage", meta.errorMessage)
            .put("lastPlayedIndex", meta.lastPlayedIndex)
        metaFile(meta.bookId).writeText(o.toString())
    }

    fun listAll(): List<Meta> {
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { metaOf(it.name) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** 某个段落的 PCM 文件路径 */
    fun chunkFile(bookId: String, paraIndex: Int): File =
        File(bookDir(bookId), "%05d.pcm".format(paraIndex))

    /**
     * 进程崩溃/被杀后，未完成任务不能永远显示“转换中”。
     * 统一落成 PAUSED，保留 convertedUpTo，下次长按书籍即可从中断处继续转换。
     */
    fun resetStaleConverting() {
        runCatching {
            for (meta in listAll()) {
                when (meta.status) {
                    Status.CONVERTING -> saveMeta(meta.copy(
                        status = Status.PAUSED,
                        errorMessage = "上次转换被中断，长按书籍可继续转换",
                    ))
                    Status.NONE -> saveMeta(meta.copy(
                        status = Status.PAUSED,
                        errorMessage = "尚未开始转换，长按书籍可继续转换",
                    ))
                    else -> {}
                }
            }
        }
    }

    /** 记住上次播放到的段落，下次打开同一本书从该段继续。 */
    fun updateProgress(bookId: String, index: Int) {
        val meta = metaOf(bookId) ?: return
        if (meta.lastPlayedIndex == index) return
        saveMeta(meta.copy(lastPlayedIndex = index))
    }

    fun hasChunk(bookId: String, paraIndex: Int): Boolean = chunkFile(bookId, paraIndex).exists()

    fun readChunk(bookId: String, paraIndex: Int): ShortArray? {
        val f = chunkFile(bookId, paraIndex)
        if (!f.exists()) return null
        val bytes = f.readBytes()
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            shorts[i] = ((bytes[i * 2 + 1].toInt() shl 8) or
                (bytes[i * 2].toInt() and 0xFF)).toShort()
        }
        return shorts
    }

    fun writeChunk(bookId: String, paraIndex: Int, data: ShortArray) {
        val bytes = ByteArray(data.size * 2)
        for (i in data.indices) {
            bytes[i * 2] = (data[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((data[i].toInt() shr 8) and 0xFF).toByte()
        }
        chunkFile(bookId, paraIndex).writeBytes(bytes)
    }

    /** 总缓存大小（MB） */
    fun totalSizeMB(): Long {
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total / (1024 * 1024)
    }

    fun deleteBook(bookId: String) {
        bookDir(bookId).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    companion object {
        fun idOf(uriOrPath: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val raw = uriOrPath.toByteArray()
            val hex = digest.digest(raw).joinToString("") { "%02x".format(it) }
            return hex.substring(0, 16)
        }
    }

    data class Meta(
        val bookId: String,
        /** 原文档的 URI（持久化存储，下次打开同一本书时用） */
        val sourceUri: String,
        val title: String,
        val format: String,
        val paragraphCount: Int,
        val totalSeconds: Float,
        val sampleRate: Int,
        /** "mixed" / "chinese" */
        val mode: String,
        /** 已转换到的段落 index（-1 = 未开始） */
        val convertedUpTo: Int,
        val status: Status,
        val createdAt: Long,
        val errorMessage: String = "",
        /** 上次播放到的段落（-1 = 未播放过） */
        val lastPlayedIndex: Int = -1,
    )

    enum class Status { NONE, CONVERTING, PAUSED, DONE, FAILED }
}
