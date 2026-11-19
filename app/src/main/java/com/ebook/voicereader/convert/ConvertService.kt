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
import com.ebook.voicereader.App
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.model.Book
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
        // 复用 App 的全局单例：绝不在这里再 new 一个 TtsManager。
        // 两个 TtsManager 会各加载一份 ~190MB 的 ONNX 模型，低内存设备直接 OOM 闪退。
        cache = App.instance.cache
        ttsManager = App.instance.ttsManager
        readers = DocumentReaderFactory.create(App.instance.ocrEngine)
        manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        lbm = LocalBroadcastManager.getInstance(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第一件事就是进入前台态：Android 12+ 如果 5 秒内没进入，
        // 系统会抛 ForegroundServiceDidNotStartInTimeException 并杀掉整个应用。
        val foregroundError = runCatching {
            startForeground(NOTIFY_ID, buildNotification("准备中…", "加载语音引擎", 0, 0))
        }.exceptionOrNull()
        App.traceSafe(
            "service:onStartCommand action=${intent?.getStringExtra(EXTRA_ACTION)} " +
                "foreground=${foregroundError == null} 可用内存=${App.instance.availableMemoryMB()}MB"
        )

        // 进程被系统重建时可能拿不到原始 Intent，此时没有任务可做。
        if (intent == null) {
            App.traceSafe("service:intent=null，结束服务")
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            handleStart(intent, foregroundError)
        } catch (e: Throwable) {
            // 服务运行在主线程，任何抛出都会直接杀死整个应用；这里统一兜住并留下轨迹。
            val reason = "后台服务异常：${e.message ?: e.javaClass.simpleName}"
            App.traceSafe("service:未捕获异常 $reason")
            runCatching { registry.markAllFailed(reason) }
            runCatching { notifyError(reason) }
            runCatching { startBroadcast(ACTION_CONVERT_ALL_DONE) }
            runCatching { stopSelf() }
            START_NOT_STICKY
        }
    }

    private fun handleStart(intent: Intent, foregroundError: Throwable?): Int {
        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_CANCEL) {
            cancelState.cancel()
            registry.markAllCancelled()
            startBroadcast(ACTION_CONVERT_ALL_DONE)
            stopSelf()
            return START_NOT_STICKY
        }

        val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        } ?: run {
            App.traceSafe("service:没有 uris，结束服务")
            stopSelf()
            return START_NOT_STICKY
        }
        val names = intent.getStringArrayListExtra(EXTRA_DISPLAY_NAMES) ?: List(uris.size) { "" }
        val modeStr = intent.getStringExtra(EXTRA_MODE) ?: "mixed"
        val mode = if (modeStr == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED
        App.traceSafe("service:收到 ${uris.size} 个文件 mode=$modeStr")

        if (foregroundError != null) {
            val reason = "后台服务启动失败：${foregroundError.message ?: foregroundError.javaClass.simpleName}"
            App.traceSafe("service:startForeground 失败 $reason")
            persistBatchFailure(uris, mode, reason)
            registry.markAllFailed(reason)
            notifyError(reason)
            startBroadcast(ACTION_CONVERT_ALL_DONE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 引擎在 IO 线程加载，不在主线程阻塞。
        ttsManager.init(mode)
        App.traceSafe("service:已请求加载语音引擎")

        job = scope.launch {
            try {
                var wait = 0
                while (!ttsManager.isReady() && wait < 50) {
                    if (cancelState.isCancelled) break
                    kotlinx.coroutines.delay(200); wait++
                }
                App.traceSafe("service:引擎就绪=${ttsManager.isReady()}，等待 ${wait * 200}ms")
                if (!ttsManager.isReady()) {
                    val reason = "语音引擎加载失败，请打开书库后重试"
                    persistBatchFailure(uris, mode, reason)
                    registry.markAllFailed(reason)
                    notifyError(reason)
                    startBroadcast(ACTION_CONVERT_ALL_DONE)
                    stopSelf(); return@launch
                }

                for ((idx, uri) in uris.withIndex()) {
                    if (cancelState.isCancelled) break
                    val displayName = names.getOrNull(idx) ?: "文件${idx + 1}"
                    App.traceSafe("service:开始转换 [${idx + 1}/${uris.size}] $displayName")
                    convertOne(uri, displayName, mode, idx, uris.size)
                    App.traceSafe("service:结束转换 [${idx + 1}/${uris.size}] $displayName")
                }
                if (!cancelState.isCancelled) {
                    notifyDone()
                }
                App.traceSafe("service:全部完成")
                startBroadcast(ACTION_CONVERT_ALL_DONE)
            } catch (e: CancellationException) {
                App.traceSafe("service:已取消")
                startBroadcast(ACTION_CONVERT_ALL_DONE)
            } catch (e: Throwable) {
                val reason = e.message ?: e.javaClass.simpleName
                App.traceSafe("service:批处理异常 $reason")
                persistBatchFailure(uris, mode, reason)
                registry.markAllFailed(reason)
                notifyError(reason)
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
        // 注意：不能 release() 共享的 ttsManager，否则会连带卸载正在播放的引擎
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private suspend fun convertOne(
        uri: Uri, displayName: String, mode: TtsManager.Mode,
        bookIndex: Int, bookCount: Int
    ) {
        val bookId = CacheManager.idOf(uri.toString() + ":" + mode.name)
        val ext = DocumentReaderFactory.extensionOf(this, uri, displayName)
        val reader = readers.firstOrNull { it.supports(ext) } ?: run {
            failBook(bookId, displayName, uri, mode, "不支持的格式：$displayName")
            return
        }

        val book: Book = try {
            App.traceSafe("convert:解析开始 $displayName ext=$ext")
            reader.read(this, uri, displayName) { /* no progress */ }.also {
                App.traceSafe("convert:解析完成 段数=${it.paragraphs.size}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            App.traceSafe("convert:解析失败 ${e.javaClass.name}: ${e.message}")
            failBook(
                bookId, displayName, uri, mode,
                "解析失败：${e.message ?: e.javaClass.simpleName}",
            )
            return
        }

        if (book.paragraphs.isEmpty()) {
            failBook(bookId, displayName, uri, mode, "没有可朗读的文本")
            return
        }

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
            bookId = bookId, sourceUri = uri.toString(), title = title, format = book.sourceFormat,
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
                App.traceSafe("convert:合成返回空 段=$i")
                failed = true; failReason = "语音合成失败"; break
            }
            if (i < 3 || i % 20 == 0) {
                App.traceSafe("convert:合成第 $i 段完成 · 可用内存 ${App.instance.availableMemoryMB()}MB")
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
            failBook(bookId, title, uri, mode, failReason ?: "语音合成失败")
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

    private fun failBook(
        bookId: String,
        title: String,
        uri: Uri,
        mode: TtsManager.Mode,
        reason: String,
    ) {
        val current = cache.metaOf(bookId)
        val meta = current?.copy(
            title = title,
            status = CacheManager.Status.FAILED,
            errorMessage = reason,
        ) ?: CacheManager.Meta(
            bookId = bookId,
            sourceUri = uri.toString(),
            title = title,
            format = DocumentReaderFactory.extensionOf(this, uri, title),
            paragraphCount = 0,
            totalSeconds = 0f,
            sampleRate = 22050,
            mode = mode.name.lowercase(),
            convertedUpTo = -1,
            status = CacheManager.Status.FAILED,
            createdAt = System.currentTimeMillis(),
            errorMessage = reason,
        )
        runCatching { cache.saveMeta(meta) }
        registry.update(bookId) {
            it.copy(
                title = title,
                done = meta.convertedUpTo,
                total = meta.paragraphCount,
                totalSeconds = meta.totalSeconds,
                status = TaskStatus.FAILED,
                message = reason,
            )
        }
        startBroadcast(
            ACTION_CONVERT_ERROR, bookId, title, uri, mode.name,
            meta.convertedUpTo, meta.paragraphCount, meta.totalSeconds, reason,
        )
        notifyError("$title：$reason")
    }

    private fun persistBatchFailure(uris: List<Uri>, mode: TtsManager.Mode, reason: String) {
        for (uri in uris) {
            val bookId = CacheManager.idOf(uri.toString() + ":" + mode.name)
            val meta = cache.metaOf(bookId) ?: continue
            if (meta.status != CacheManager.Status.DONE) {
                runCatching {
                    cache.saveMeta(meta.copy(
                        status = CacheManager.Status.FAILED,
                        errorMessage = reason,
                    ))
                }
            }
        }
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
