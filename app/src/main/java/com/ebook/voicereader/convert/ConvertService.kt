package com.ebook.voicereader.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 后台批量转换服务。
 *
 * 除了通知栏进度，服务还通过 LocalBroadcastManager 广播进度到 UI：
 *   ACTION_CONVERT_PROGRESS   每段合成完
 *   ACTION_CONVERT_START      某本书开始解析
 *   ACTION_CONVERT_DONE       某本书转换完成
 *   ACTION_CONVERT_ERROR      某本书失败
 *   ACTION_CONVERT_ALL_DONE   全部完成（或被取消）
 *
 * 同时暴露一个 [StatusRegistry] 静态注册表，Activity 可以直接查询所有任务状态，
 * 也支持点击某本已完成或部分完成的书直接进入播放（TtsManager 会优先读缓存）。
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

        const val ACTION_CONVERT_PROGRESS = "ebook.voicereader.CONVERT_PROGRESS"
        const val ACTION_CONVERT_START = "ebook.voicereader.CONVERT_START"
        const val ACTION_CONVERT_DONE = "ebook.voicereader.CONVERT_DONE"
        const val ACTION_CONVERT_ERROR = "ebook.voicereader.CONVERT_ERROR"
        const val ACTION_CONVERT_ALL_DONE = "ebook.voicereader.CONVERT_ALL_DONE"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URI = "uri"
        const val EXTRA_MODE_NAME = "mode_name"  // 广播里的模式字段（避免与启动参数 EXTRA_MODE 冲突）
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_TOTAL_SECONDS = "total_seconds"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATUS = "status"

        /** 所有批量转换任务的共享注册表，Activity 可直接查询 */
        val registry = StatusRegistry()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val cancelState = CancelState()

    private lateinit var cache: CacheManager
    private lateinit var ttsManager: TtsManager
    private lateinit var readers: List<com.ebook.voicereader.reader.DocumentReader>
    private var manager: NotificationManager? = null
    private var lbm: LocalBroadcastManager? = null

    override fun onCreate() {
        super.onCreate()
        cache = CacheManager(this)
        ttsManager = TtsManager(this)
        ttsManager.setCache(cache)
        ttsManager.init()
        readers = DocumentReaderFactory.create(OcrEngine())
        manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        lbm = LocalBroadcastManager.getInstance(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_CANCEL -> {
                cancelState.cancel()
                registry.markAllCancelled()
                startBroadcast(ACTION_CONVERT_ALL_DONE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val uris = intent?.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: run {
            stopSelf(); return START_NOT_STICKY
        }
        val names = intent.getStringArrayListExtra(EXTRA_DISPLAY_NAMES) ?: List(uris.size) { "" }
        val modeStr = intent?.getStringExtra(EXTRA_MODE) ?: "mixed"
        val mode = if (modeStr == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED

        startForeground(NOTIFY_ID, buildNotification("准备中…", "加载语音引擎", 0, 0))

        // 预先在 registry 里登记所有任务为 PENDING
        registry.registerNewBatch(uris.mapIndexed { i, uri ->
            val displayName = names.getOrNull(i) ?: "文件${i + 1}"
            TaskInfo(
                bookId = CacheManager.idOf(uri.toString() + ":" + mode.name),
                title = displayName,
                uri = uri.toString(),
                mode = mode.name.lowercase(),
                total = 0, done = -1,
                status = TaskStatus.PENDING,
                totalSeconds = 0f,
            )
        })

        job = scope.launch {
            try {
                var wait = 0
                while (!ttsManager.isReady() && wait < 50) {
                    if (cancelState.isCancelled) break
                    kotlinx.coroutines.delay(200); wait++
                }
                if (!ttsManager.isReady()) {
                    registry.markAllFailed("语音引擎加载失败")
                    notifyError("语音引擎加载失败")
                    startBroadcast(ACTION_CONVERT_ALL_DONE)
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
                startBroadcast(ACTION_CONVERT_ALL_DONE)
            } catch (e: CancellationException) {
                startBroadcast(ACTION_CONVERT_ALL_DONE)
            } catch (e: Throwable) {
                notifyError(e.message ?: e.javaClass.simpleName)
                startBroadcast(ACTION_CONVERT_ALL_DONE)
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
        uri: Uri, displayName: String, mode: TtsManager.Mode,
        bookIndex: Int, bookCount: Int
    ) {
        val ext = DocumentReaderFactory.extensionOf(displayName)
        val reader = readers.firstOrNull { it.supports(ext) } ?: run {
            registry.markFailed(uri, mode.name, displayName)
            notifyError("不支持的格式：$displayName"); return
        }

        val book: Book = try {
            reader.read(this, uri, displayName) { /* no progress */ }
        } catch (e: Throwable) {
            registry.markFailed(uri, mode.name, displayName)
            notifyError("解析失败 $displayName: ${e.message}"); return
        }

        if (book.paragraphs.isEmpty()) {
            registry.markFailed(uri, mode.name, "$displayName 没有可朗读的文本")
            return
        }

        val bookId = CacheManager.idOf(uri.toString() + ":" + mode.name)
        val title = book.title.ifBlank { displayName }
        val total = book.paragraphs.size

        registry.update(bookId) { it.copy(
            title = title, total = total, status = TaskStatus.CONVERTING,
        ) }
        startBroadcast(ACTION_CONVERT_START, bookId, title, uri, mode.name, -1, total, 0f, null)

        val existing = cache.metaOf(bookId)
        val startFrom = existing?.let {
            if (it.status == CacheManager.Status.CONVERTING) max(0, it.convertedUpTo + 1) else 0
        } ?: 0

        val meta = CacheManager.Meta(
            bookId = bookId, title = title, format = book.sourceFormat,
            paragraphCount = total, totalSeconds = 0f,
            sampleRate = 22050, mode = mode.name.lowercase(),
            convertedUpTo = startFrom - 1,
            status = CacheManager.Status.CONVERTING,
            createdAt = System.currentTimeMillis(),
        )
        cache.saveMeta(meta)

        var totalSec = 0f
        var rate = meta.sampleRate
        var failed = false
        var failReason: String? = null

        for (i in startFrom until total) {
            if (cancelState.isCancelled) {
                val partial = meta.copy(
                    status = CacheManager.Status.FAILED, totalSeconds = totalSec,
                    convertedUpTo = i - 1, sampleRate = rate
                )
                cache.saveMeta(partial)
                registry.update(bookId) { it.copy(
                    done = i - 1, total = total, status = TaskStatus.CANCELLED,
                    totalSeconds = totalSec
                ) }
                startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                    i - 1, total, totalSec, null)
                return
            }
            val text = book.paragraphs[i]
            if (text.isBlank()) {
                cache.writeChunk(bookId, i, ShortArray(0))
                cache.saveMeta(meta.copy(convertedUpTo = i))
                registry.update(bookId) { it.copy(done = i, total = total, totalSeconds = totalSec) }
                startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                    i, total, totalSec, null)
                continue
            }
            val result = ttsManager.synthesizeParagraph(text, mode)
            if (result == null) {
                failed = true; failReason = "语音合成失败"; break
            }
            val (pcm, r) = result
            rate = r
            totalSec += pcm.size.toFloat() / r
            cache.writeChunk(bookId, i, pcm)
            cache.saveMeta(meta.copy(convertedUpTo = i, totalSeconds = totalSec, sampleRate = rate))

            registry.update(bookId) { it.copy(done = i, total = total, totalSeconds = totalSec) }
            startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                i, total, totalSec, null)

            val overallDone = (bookIndex * 100f / bookCount) + ((i + 1) * 100f / total / bookCount)
            updateNotification(
                "$title · ${i + 1}/$total 段",
                "总进度 ${overallDone.toInt()}% · 已合成 ${totalSec.toInt()} 秒",
                i + 1, total
            )
        }

        if (failed) {
            val partial = meta.copy(
                status = CacheManager.Status.FAILED, totalSeconds = totalSec,
                convertedUpTo = max(0, startFrom - 1), sampleRate = rate
            )
            cache.saveMeta(partial)
            registry.update(bookId) { it.copy(
                done = max(0, startFrom - 1), total = total,
                status = TaskStatus.FAILED, totalSeconds = totalSec
            ) }
            startBroadcast(ACTION_CONVERT_ERROR, bookId, title, uri, mode.name,
                max(0, startFrom - 1), total, totalSec, failReason)
            return
        }

        val finalMeta = meta.copy(
            convertedUpTo = total - 1, totalSeconds = totalSec,
            sampleRate = rate, status = CacheManager.Status.DONE,
        )
        cache.saveMeta(finalMeta)
        registry.update(bookId) { it.copy(
            done = total, total = total, status = TaskStatus.DONE, totalSeconds = totalSec
        ) }
        startBroadcast(ACTION_CONVERT_DONE, bookId, title, uri, mode.name,
            total, total, totalSec, null)
    }

    // ------------------------------------------------------------------
    // 广播 + Notification
    // ------------------------------------------------------------------

    private fun startBroadcast(
        action: String, bookId: String? = null, title: String? = null,
        uri: Uri? = null, mode: String? = null,
        done: Int = -1, total: Int = 0, totalSeconds: Float = 0f,
        message: String? = null, status: TaskStatus? = null
    ) {
        val i = Intent(action).apply {
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_TITLE, title)
            if (uri != null) putExtra(EXTRA_URI, uri.toString())
            putExtra(EXTRA_MODE_NAME, mode)
            putExtra(EXTRA_DONE, done)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_TOTAL_SECONDS, totalSeconds)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_STATUS, status?.name)
        }
        lbm?.sendBroadcast(i)
    }

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
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) builder.setProgress(total, done, false)
        return builder.build()
    }

    private fun updateNotification(title: String, text: String, done: Int, total: Int) {
        runCatching { manager?.notify(NOTIFY_ID, buildNotification(title, text, done, total)) }
    }

    private fun notifyError(message: String) {
        runCatching {
            manager?.notify(NOTIFY_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("转换失败").setContentText(message)
                    .setOngoing(false).setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build()
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
                    .setOngoing(false).setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            )
        }
    }

    private class CancelState {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true }
        val isCancelled get() = cancelled
    }
}

// ----------------------------------------------------------------------
// 共享状态注册表
// ----------------------------------------------------------------------

enum class TaskStatus { PENDING, CONVERTING, DONE, FAILED, CANCELLED }

data class TaskInfo(
    val bookId: String,
    val title: String,
    val uri: String,
    val mode: String,
    val total: Int,
    val done: Int,
    val status: TaskStatus,
    val totalSeconds: Float,
    val message: String? = null,
) {
    fun progressText(): String = when (status) {
        TaskStatus.PENDING -> "等待中"
        TaskStatus.CONVERTING -> if (total > 0) "转换中 ${done + 1}/$total" else "解析中…"
        TaskStatus.DONE -> "已完成 · ${totalSeconds.toInt()} 秒"
        TaskStatus.FAILED -> "失败${message?.let { "：$it" } ?: ""}"
        TaskStatus.CANCELLED -> "已取消 · ${done}/$total 段"
    }
}

class StatusRegistry {
    private val tasks = ConcurrentHashMap<String, TaskInfo>()

    fun registerNewBatch(newTasks: List<TaskInfo>) {
        synchronized(tasks) {
            // 先移除旧的 DONE/FAILED/CANCELLED 任务，保留还在 CONVERTING 的
            tasks.entries.removeIf { it.value.status != TaskStatus.CONVERTING }
            for (t in newTasks) tasks[t.bookId] = t
        }
    }

    fun all(): List<TaskInfo> = tasks.values.sortedBy {
        when (it.status) {
            TaskStatus.CONVERTING -> 0
            TaskStatus.PENDING -> 1
            TaskStatus.DONE -> 2
            TaskStatus.FAILED -> 3
            TaskStatus.CANCELLED -> 4
        }
    }

    fun update(bookId: String, block: (TaskInfo) -> TaskInfo) {
        synchronized(tasks) {
            tasks[bookId] = tasks[bookId]?.let { block(it) } ?: return
        }
    }

    fun markFailed(uri: Uri, mode: String, reason: String) {
        val bookId = CacheManager.idOf(uri.toString() + ":" + mode)
        synchronized(tasks) {
            tasks[bookId]?.let { tasks[bookId] = it.copy(status = TaskStatus.FAILED, message = reason) }
        }
    }

    fun markAllFailed(reason: String) {
        synchronized(tasks) {
            tasks.entries.forEach { (k, v) ->
                if (v.status != TaskStatus.DONE) {
                    tasks[k] = v.copy(status = TaskStatus.FAILED, message = reason)
                }
            }
        }
    }

    fun markAllCancelled() {
        synchronized(tasks) {
            tasks.entries.forEach { (k, v) ->
                if (v.status == TaskStatus.CONVERTING || v.status == TaskStatus.PENDING) {
                    tasks[k] = v.copy(status = TaskStatus.CANCELLED)
                }
            }
        }
    }

    fun isRunning(): Boolean = tasks.values.any { it.status == TaskStatus.CONVERTING || it.status == TaskStatus.PENDING }

    fun clear() { tasks.clear() }
}
