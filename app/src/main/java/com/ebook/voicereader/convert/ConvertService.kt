package com.ebook.voicereader.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.model.Book
import com.ebook.voicereader.ocr.OcrEngine
import com.ebook.voicereader.reader.DocumentReaderFactory
import com.ebook.voicereader.tts.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 后台批量转换服务。
 *
 * 用法：
 *   Intent(ctx, ConvertService::class.java)
 *     .putParcelableArrayListExtra(ConvertService.EXTRA_URIS, arrayListOf(uri1, uri2, ...))
 *     .putStringExtra(ConvertService.EXTRA_MODE, "mixed")
 *     .putStringExtra(ConvertService.EXTRA_ACTION, ConvertService.ACTION_START)
 *   ctx.startForegroundService(intent)
 *
 * 服务启动后会：
 * 1. 用 DocumentReaderFactory 逐个解析每个 URI
 * 2. 逐段调用 TtsManager.synthesizeParagraph()，把 ShortArray 写进 CacheManager
 * 3. 实时更新 Notification 进度（书名 / 段数 / 已用秒数 / 总文件大小）
 * 4. 完成后写 meta.json，下次用 TtsManager.speak() 就直接播缓存、零等待
 *
 * 也支持 ACTION_CANCEL 中途取消：正在转换的书会标记为 FAILED，已经完成的段落仍保留。
 */
class ConvertService : Service() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_DISPLAY_NAMES = "display_names"
        const val EXTRA_MODE = "mode" // "mixed" / "chinese"
        const val EXTRA_ACTION = "action"
        const val ACTION_START = "start"
        const val ACTION_CANCEL = "cancel"
        const val CHANNEL_ID = "ebook_convert"
        const val NOTIFY_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val cancelState = CancelState()

    private lateinit var cache: CacheManager
    private lateinit var ttsManager: TtsManager
    private lateinit var readers: List<com.ebook.voicereader.reader.DocumentReader>
    private var manager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        cache = CacheManager(this)
        ttsManager = TtsManager(this)
        ttsManager.setCache(cache)
        ttsManager.init()
        readers = DocumentReaderFactory.create(OcrEngine())
        manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_CANCEL -> {
                cancelState.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val uris = intent?.getParcelableArrayListExtra<android.net.Uri>(EXTRA_URIS) ?: run {
            stopSelf(); return START_NOT_STICKY
        }
        val names = intent.getStringArrayListExtra(EXTRA_DISPLAY_NAMES) ?: List(uris.size) { "" }
        val modeStr = intent?.getStringExtra(EXTRA_MODE) ?: "mixed"
        val mode = if (modeStr == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED

        startForeground(NOTIFY_ID, buildNotification("准备中…", "加载语音引擎", 0, 0))

        job = scope.launch {
            try {
                // 等引擎 ready（最多等 10 秒，避免 ANR）
                var wait = 0
                while (!ttsManager.isReady() && wait < 50) {
                    if (cancelState.isCancelled) break
                    kotlinx.coroutines.delay(200); wait++
                }
                if (!ttsManager.isReady()) {
                    notifyError("语音引擎加载失败")
                    stopSelf(); return@launch
                }

                for ((idx, uri) in uris.withIndex()) {
                    if (cancelState.isCancelled) break
                    val displayName = names.getOrNull(idx) ?: "文件${idx + 1}"
                    convertOne(uri, displayName, mode, idx, uris.size)
                }
                if (!cancelState.isCancelled) {
                    notifyDone()
                }
            } catch (e: CancellationException) {
                // cancelled
            } catch (e: Throwable) {
                notifyError(e.message ?: e.javaClass.simpleName)
            } finally {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelState.cancel()
        job?.cancel()
        job = null
        runCatching { ttsManager.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private suspend fun convertOne(
        uri: android.net.Uri, displayName: String, mode: TtsManager.Mode,
        bookIndex: Int, bookCount: Int
    ) {
        val ext = DocumentReaderFactory.extensionOf(displayName)
        val reader = readers.firstOrNull { it.supports(ext) } ?: run {
            notifyError("不支持的格式：$displayName"); return
        }

        val book: Book = try {
            reader.read(this, uri, displayName) { /* no progress */ }
        } catch (e: Throwable) {
            notifyError("解析失败 $displayName: ${e.message}"); return
        }

        if (book.paragraphs.isEmpty()) {
            notifyError("$displayName 没有可朗读的文本"); return
        }

        val bookId = CacheManager.idOf(uri.toString() + ":" + mode.name)
        val title = book.title.ifBlank { displayName }

        // 初始化 meta
        val existing = cache.metaOf(bookId)
        val startFrom = existing?.let {
            if (it.status == CacheManager.Status.CONVERTING) max(0, it.convertedUpTo + 1) else 0
        } ?: 0

        val meta = CacheManager.Meta(
            bookId = bookId,
            title = title,
            format = book.sourceFormat,
            paragraphCount = book.paragraphs.size,
            totalSeconds = 0f,
            sampleRate = 22050,
            mode = mode.name.lowercase(),
            convertedUpTo = startFrom - 1,
            status = CacheManager.Status.CONVERTING,
            createdAt = System.currentTimeMillis(),
        )
        cache.saveMeta(meta)

        var totalSec = 0f
        var rate = meta.sampleRate

        for (i in startFrom until book.paragraphs.size) {
            if (cancelState.isCancelled) {
                val partial = meta.copy(status = CacheManager.Status.FAILED, totalSeconds = totalSec, convertedUpTo = i - 1, sampleRate = rate)
                cache.saveMeta(partial)
                return
            }
            val text = book.paragraphs[i]
            if (text.isBlank()) {
                cache.writeChunk(bookId, i, ShortArray(0))
                val updated = meta.copy(convertedUpTo = i)
                cache.saveMeta(updated)
                continue
            }
            val result = ttsManager.synthesizeParagraph(text, mode)
            if (result == null) {
                cache.writeChunk(bookId, i, ShortArray(0))
            } else {
                val (pcm, r) = result
                rate = r
                totalSec += pcm.size.toFloat() / r
                cache.writeChunk(bookId, i, pcm)
            }

            val updated = meta.copy(
                convertedUpTo = i,
                totalSeconds = totalSec,
                sampleRate = rate,
            )
            cache.saveMeta(updated)

            val overallDone = (bookIndex * 100f / bookCount) + ((i + 1) * 100f / book.paragraphs.size / bookCount)
            updateNotification(
                "$title · ${i + 1}/${book.paragraphs.size} 段",
                "总进度 ${overallDone.toInt()}% · 已合成 ${totalSec.toInt()} 秒",
                i + 1, book.paragraphs.size
            )
        }

        val finalMeta = meta.copy(
            convertedUpTo = book.paragraphs.size - 1,
            totalSeconds = totalSec,
            sampleRate = rate,
            status = CacheManager.Status.DONE,
        )
        cache.saveMeta(finalMeta)
    }

    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "离线语音转换", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台把书籍转成语音片段" }
            manager?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String, done: Int, total: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) {
            builder.setProgress(total, done, false)
        }
        return builder.build()
    }

    private fun updateNotification(title: String, text: String, done: Int, total: Int) {
        runCatching {
            manager?.notify(NOTIFY_ID, buildNotification(title, text, done, total))
        }
    }

    private fun notifyError(message: String) {
        runCatching {
            manager?.notify(NOTIFY_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("转换失败")
                    .setContentText(message)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            )
        }
    }

    private fun notifyDone() {
        runCatching {
            manager?.notify(NOTIFY_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("转换完成")
                    .setContentText("全部书籍已生成离线语音，打开即可收听")
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            )
        }
    }

    private class CancelState {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true }
        val isCancelled get() = cancelled
    }
}
