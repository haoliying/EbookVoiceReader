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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
        /** 系统重建服务或任务被移除后，恢复未完成的转换 */
        const val ACTION_RESUME = "resume"
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

        /**
         * 用户在书库删除书籍时登记。
         * 正在转换的循环检测到后会立刻放弃这本，避免删掉的目录被重新写回来。
         */
        private val deletedBooks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        /** 每本书的暂停标记：暂停不取消协程，只在段落之间挂起等待，进度完整保留。 */
        private val pausedBooks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        /** 当前真正有转换协程在跑的书籍，用于判断“继续转换”是否需要重新拉起服务。 */
        private val runningBooks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        /** 删除书籍时可立即取消对应协程，不必等当前段合成结束。 */
        private val bookJobs = ConcurrentHashMap<String, Job>()

        /** 用户主动暂停的书：服务重启后不能自动继续，必须等用户点“继续转换”。 */
        private val userPausedBooks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        fun skipBook(bookId: String) {
            deletedBooks.add(bookId)
            pausedBooks.remove(bookId)
            userPausedBooks.remove(bookId)
            runningBooks.remove(bookId)
            registry.remove(bookId)
            bookJobs.remove(bookId)?.cancel()
        }

        fun isBookSkipped(bookId: String): Boolean = bookId in deletedBooks

        /** 重新转换同一本书时清除删除标记。 */
        fun clearSkip(bookId: String) { deletedBooks.remove(bookId) }

        /** 暂停某本书的转换，已转换的片段与断点全部保留。 */
        fun pauseBook(bookId: String) {
            pausedBooks.add(bookId)
            userPausedBooks.add(bookId)
            registry.update(bookId) { it.copy(status = TaskStatus.PAUSED) }
        }

        /** 继续转换（协程还在时直接续跑，否则由界面重新拉起服务）。 */
        fun resumeBook(bookId: String) {
            pausedBooks.remove(bookId)
            userPausedBooks.remove(bookId)
            registry.update(bookId) { it.copy(status = TaskStatus.CONVERTING) }
        }

        fun isBookPaused(bookId: String): Boolean = bookId in pausedBooks

        fun isBookRunning(bookId: String): Boolean = bookId in runningBooks

        /** 用户主动暂停、不希望应用自动续转。 */
        fun isUserPaused(bookId: String): Boolean = bookId in userPausedBooks

        /** 是否真的有转换协程在工作（暂停等待中的也算）。 */
        fun hasRunningJobs(): Boolean = runningBooks.isNotEmpty() || bookJobs.isNotEmpty()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelState = CancelState()
    private val activeJobs = AtomicInteger(0)

    /**
     * 同时推进的书籍数上限。语音推理本身已被 TtsManager.engineMutex 串行化，
     * 这里限流主要是避免多本大 PDF 同时解析把内存吃满。
     */
    private val convertSlots = Semaphore(2)

    /**
     * 解析阶段单独串行：大 PDF 解析的内存峰值远高于合成，
     * 两本同时解析很容易被系统判定 OOM 并杀掉进程，表现为“转一点就停了”。
     */
    private val parseSlots = Semaphore(1)

    private lateinit var cache: CacheManager
    private lateinit var ttsManager: TtsManager
    private lateinit var readers: List<com.ebook.voicereader.reader.DocumentReader>
    private var manager: NotificationManager? = null
    private var lbm: LocalBroadcastManager? = null

    /**
     * 前台服务只保证进程不被轻易回收，屏幕熄灭后 CPU 仍会休眠。
     * 转换期间持有 PARTIAL_WAKE_LOCK，才能退出应用、锁屏后继续合成。
     */
    private var wakeLock: PowerManager.WakeLock? = null
    /** onDestroy 期间会取消任务，此时不能把 pending 标记清掉，否则无法自动续转 */
    @Volatile private var destroying = false

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

        // 进程被系统重建时拿不到原始 Intent（START_STICKY 重启），
        // 此时从 meta 恢复未完成的转换，而不是直接结束服务。
        if (intent == null) {
            App.traceSafe("service:intent=null，尝试恢复未完成任务")
            return handleResume()
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
        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_RESUME) {
            return handleResume()
        }
        if (intent.getStringExtra(EXTRA_ACTION) == ACTION_CANCEL) {
            cancelState.cancel()
            registry.markAllCancelled()
            App.instance.prefs.pendingConversion = false
            ConversionWatchdog.cancel(this)
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
        if (uris.isEmpty()) {
            App.traceSafe("service:uris 为空，结束服务")
            stopSelf()
            return START_NOT_STICKY
        }
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

        // 并发转换：每本书一个协程，互不影响。点开正在转换的书可以直接听，
        // 暂停其中一本也不会拖住其他书。
        for ((idx, uri) in uris.withIndex()) {
            val displayName = names.getOrNull(idx) ?: "文件${idx + 1}"
            launchBook(uri, displayName, mode)
        }

        // 用 START_STICKY：用户退出应用或进程被系统回收后，服务会被重建，
        // 重建时会走 handleResume() 从断点继续转换，而不是停在前台通知里。
        return START_STICKY
    }

    /**
     * 服务被系统重建（START_STICKY）或用户划掉最近任务后调用。
     * 已有任务在跑就不重复启动，否则从 meta 中找到未完成的转换继续。
     */
    private fun handleResume(): Int {
        if (hasRunningJobs()) {
            val active = registry.all().any {
                it.status == TaskStatus.CONVERTING || it.status == TaskStatus.PENDING
            }
            if (!active) {
                // 全部是用户主动暂停的任务：不要再持锁、也不要继续排看门狗。
                App.traceSafe("service:任务均已暂停，本次唤醒不再续期")
                return START_STICKY
            }
            App.traceSafe("service:收到恢复请求，任务仍在运行")
            // 续上下一分钟的看门狗，保证进程被杀后还能再次被拉起。
            ensureWakeLock()
            App.instance.prefs.pendingConversion = true
            ConversionWatchdog.schedule(this)
            return START_STICKY
        }
        resumeInterruptedFromCache()
        return START_STICKY
    }

    /** 从 meta 恢复被中断的转换；用户主动暂停的书不会自动继续。 */
    private fun resumeInterruptedFromCache() {
        scope.launch {
            val metas = runCatching { cache.listAll() }.getOrDefault(emptyList())
            val candidates = metas.filter {
                (it.status == CacheManager.Status.CONVERTING ||
                    it.status == CacheManager.Status.PAUSED) &&
                    !it.pausedByUser &&
                    it.paragraphCount > 0 &&
                    it.convertedUpTo < it.paragraphCount - 1 &&
                    it.sourceUri.isNotBlank()
            }
            if (candidates.isEmpty()) {
                App.traceSafe("service:没有需要恢复的转换任务，结束服务")
                App.instance.prefs.pendingConversion = false
                ConversionWatchdog.cancel(this@ConvertService)
                startBroadcast(ACTION_CONVERT_ALL_DONE)
                stopSelf()
                return@launch
            }
            App.traceSafe("service:恢复 ${candidates.size} 本未完成的转换")
            App.instance.prefs.pendingConversion = true
            ConversionWatchdog.schedule(this@ConvertService)
            val needChinese = candidates.any { it.mode.equals("chinese", ignoreCase = true) }
            ttsManager.init(if (needChinese) TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED)
            for (m in candidates) {
                val uri = runCatching { Uri.parse(m.sourceUri) }.getOrNull() ?: continue
                val mode = if (m.mode.equals("chinese", ignoreCase = true)) {
                    TtsManager.Mode.CHINESE
                } else {
                    TtsManager.Mode.MIXED
                }
                launchBook(uri, m.title, mode)
            }
        }
    }

    /**
     * 用户从最近任务划掉应用时，部分系统会连带停止服务。
     * 这里主动尝试再拉起一次前台服务；即使失败，START_STICKY 也会在进程存活时重建。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        App.traceSafe("service:任务被移除，尝试保持后台转换")
        ensureWakeLock()
        // 服务本身仍在前台运行，用 startService 只是补一次恢复指令，
        // 不会触发 Android 12+ 对“后台启动前台服务”的限制。
        runCatching {
            val intent = Intent(this, ConvertService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_RESUME)
            startService(intent)
        }.onFailure {
            App.traceSafe("service:任务移除后重启服务失败 ${it.javaClass.simpleName}")
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "EbookVoiceReader:convert",
            ).apply {
                setReferenceCounted(false)
                // 30 分钟自动过期，防止异常情况下长期占用；
                // 转换过程中会周期性续期。
                acquire(30 * 60 * 1000L)
            }
            App.traceSafe("service:已获取转换唤醒锁")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }
    }

    /** 为一本书启动独立转换协程。 */
    private fun launchBook(uri: Uri, displayName: String, mode: TtsManager.Mode) {
        ensureWakeLock()
        // 记录“有未完成转换”，进程被杀后下次启动会自动恢复；同时启动看门狗定时唤醒。
        App.instance.prefs.pendingConversion = true
        ConversionWatchdog.schedule(this)
        val bookId = CacheManager.idOf(uri.toString() + ":" + mode.name)
        clearSkip(bookId)
        pausedBooks.remove(bookId)
        userPausedBooks.remove(bookId)
        runningBooks.add(bookId)
        // 同一本书重复点击时先取消旧任务，避免两个协程同时写同一本书。
        bookJobs.remove(bookId)?.cancel()
        activeJobs.incrementAndGet()

        bookJobs[bookId] = scope.launch {
            try {
                // 首次启动引擎要加载约 190MB 模型，低端机可能需要几十秒。
                // 之前只等 10 秒，很多设备会直接判失败，看起来就是“转一点就停”。
                var wait = 0
                while (!ttsManager.isReady() && wait < 300) {
                    if (cancelState.isCancelled) break
                    delay(200); wait++
                }
                App.traceSafe("service:引擎就绪=${ttsManager.isReady()}，等待 ${wait * 200}ms")
                if (!ttsManager.isReady()) {
                    pauseWithReason(bookId, displayName, uri, mode, "语音引擎加载超时，可稍后继续转换")
                    return@launch
                }
                App.traceSafe("service:开始转换 $displayName")
                convertOne(uri, displayName, mode)
                App.traceSafe("service:结束转换 $displayName")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val reason = e.message ?: e.javaClass.simpleName
                App.traceSafe("service:转换异常 ${e.javaClass.name}: $reason")
                runCatching { failBook(bookId, displayName, uri, mode, reason) }
            } finally {
                // 删除可能发生在原生合成或解析尚未返回时；最后再清一次，
                // 确保删除后写回的 PCM/meta 不会把书籍重新带回书架。
                if (isBookSkipped(bookId)) {
                    runCatching { cache.deleteBook(bookId) }
                    registry.remove(bookId)
                    App.traceSafe("convert:已清理删除任务 $bookId")
                }
                runningBooks.remove(bookId)
                bookJobs.remove(bookId)
                if (activeJobs.decrementAndGet() == 0) finishBatch()
            }
        }
    }

    /** 所有书的协程都结束后收尾。被暂停的书协程仍在等待，不会走到这里。 */
    private fun finishBatch() {
        if (destroying) return
        releaseWakeLock()
        val hasPaused = registry.all().any { it.status == TaskStatus.PAUSED }
        // 正常结束或用户主动暂停：不再需要后台唤醒。
        App.instance.prefs.pendingConversion = false
        ConversionWatchdog.cancel(this)
        if (!cancelState.isCancelled && !hasPaused) notifyDone()
        App.traceSafe("service:全部任务结束 paused=$hasPaused")
        startBroadcast(ACTION_CONVERT_ALL_DONE)
        stopSelf()
    }

    override fun onDestroy() {
        destroying = true
        cancelState.cancel()
        // 服务被系统回收（内存紧张、后台限制）时，正在转换的书不能永远停在“转换中”。
        // 统一落成“已暂停”，并把断点写回 meta，下次进入书库会自动接着转换。
        val interrupted = registry.all().filter {
            it.status == TaskStatus.CONVERTING || it.status == TaskStatus.PENDING
        }
        for (task in interrupted) {
            registry.update(task.bookId) { it.copy(status = TaskStatus.PAUSED) }
            runCatching {
                cache.metaOf(task.bookId)?.let { m ->
                    if (m.status == CacheManager.Status.CONVERTING ||
                        m.status == CacheManager.Status.NONE
                    ) {
                        cache.saveMeta(m.copy(status = CacheManager.Status.PAUSED))
                    }
                }
            }
        }
        if (interrupted.isNotEmpty()) {
            App.traceSafe("service:服务停止，${interrupted.size} 本转为暂停，可自动续转")
            // 进程/服务即将结束，立刻安排一分钟后的唤醒，避免退出后长时间没人续转。
            App.instance.prefs.pendingConversion = true
            ConversionWatchdog.schedule(this)
        }
        bookJobs.values.forEach { it.cancel() }
        bookJobs.clear()
        runningBooks.clear()
        releaseWakeLock()
        // 注意：不能 release() 共享的 ttsManager，否则会连带卸载正在播放的引擎
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private suspend fun convertOne(
        uri: Uri, displayName: String, mode: TtsManager.Mode
    ) {
        val bookId = CacheManager.idOf(uri.toString() + ":" + mode.name)
        // 这是一次新的转换请求，清除这本书之前的删除标记。
        clearSkip(bookId)
        val ext = DocumentReaderFactory.extensionOf(this, uri, displayName)
        val reader = readers.firstOrNull { it.supports(ext) } ?: run {
            failBook(bookId, displayName, uri, mode, "不支持的格式：$displayName")
            return
        }

        val existing = cache.metaOf(bookId)
        // 扫描版 PDF 的 OCR 可能耗时几十分钟。服务重启后续转时直接复用已解析正文，
        // 不再重新解析整本书，这是“退出后继续转换”场景的最大提速点。
        val cachedParagraphs = cache.loadParagraphs(bookId)
        val reuseParsed = cachedParagraphs != null && cachedParagraphs.isNotEmpty() &&
            existing != null && existing.paragraphCount == cachedParagraphs.size

        val book: Book = if (reuseParsed) {
            App.traceSafe("convert:复用已解析正文 ${cachedParagraphs!!.size} 段，跳过解析")
            Book(
                title = existing!!.title.ifBlank { displayName },
                paragraphs = cachedParagraphs,
                sourceFormat = existing.format.ifBlank { ext },
            )
        } else {
            val parseStartAt = android.os.SystemClock.elapsedRealtime()
            try {
                App.traceSafe("convert:解析开始 $displayName ext=$ext")
                parseSlots.withPermit {
                    reader.read(this, uri, displayName) { /* no progress */ }
                }.also {
                    App.traceSafe(
                        "convert:解析完成 段数=${it.paragraphs.size} " +
                            "耗时=${android.os.SystemClock.elapsedRealtime() - parseStartAt}ms " +
                            "OCR=${it.usedOcr}"
                    )
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
        }

        if (book.paragraphs.isEmpty()) {
            failBook(bookId, displayName, uri, mode, "没有可朗读的文本")
            return
        }

        // 解析期间用户可能已经删除了这本书，此时不能再写缓存。
        if (isBookSkipped(bookId)) return

        // 正文落盘，下次续转（包括进程被杀、服务重启）直接读取。
        if (!reuseParsed) cache.saveParagraphs(bookId, book.paragraphs)

        val title = book.title.ifBlank { displayName }
        val total = book.paragraphs.size

        // 继续转换时注册表里可能没有这本书（进程重启后从书库续转），先补一条任务。
        registry.ensure(
            TaskInfo(
                bookId = bookId, title = title, uri = uri.toString(),
                mode = mode.name.lowercase(), total = total, done = -1,
                status = TaskStatus.CONVERTING, totalSeconds = 0f,
            )
        )
        registry.update(bookId) { it.copy(
            title = title, total = total, status = TaskStatus.CONVERTING,
        ) }
        startBroadcast(ACTION_CONVERT_START, bookId, title, uri, mode.name, -1, total, 0f, null)

        // 已经转换出的片段一律复用：中途崩溃、暂停、取消或被系统中断后重新转换，
        // 都从上次的进度继续，而不是从头再来。
        val startFrom = existing?.let {
            if (it.convertedUpTo >= 0 && it.paragraphCount == total) {
                max(0, it.convertedUpTo + 1)
            } else 0
        } ?: 0

        val meta = CacheManager.Meta(
            bookId = bookId, sourceUri = uri.toString(), title = title, format = book.sourceFormat,
            paragraphCount = total, totalSeconds = existing?.totalSeconds ?: 0f,
            sampleRate = existing?.sampleRate ?: 22050, mode = mode.name.lowercase(),
            convertedUpTo = startFrom - 1,
            status = CacheManager.Status.CONVERTING,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastPlayedIndex = existing?.lastPlayedIndex ?: -1,
        )
        if (isBookSkipped(bookId)) return
        cache.saveMeta(meta)

        var totalSec = meta.totalSeconds
        var rate = meta.sampleRate
        var done = startFrom - 1
        var failed = false
        var failReason: String? = null
        val synthStartAt = android.os.SystemClock.elapsedRealtime()

        // 把当前断点与状态写回 meta，暂停/继续都靠它跨进程恢复。
        fun persist(status: CacheManager.Status) {
            if (isBookSkipped(bookId)) return
            runCatching {
                cache.saveMeta(meta.copy(
                    convertedUpTo = done, totalSeconds = totalSec,
                    sampleRate = rate, status = status,
                ))
            }
        }

        // 每段都重写 meta.json、发广播、刷新通知会明显拖慢转换速度。
        // 改成按时间/段数节流，保证性能的同时不丢失断点语义。
        var lastPersistAt = 0L
        var lastPersistDone = startFrom - 1
        var lastUiAt = 0L

        fun persistIfDue() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPersistAt < 2000L && done - lastPersistDone < 20) return
            lastPersistAt = now
            lastPersistDone = done
            persist(CacheManager.Status.CONVERTING)
        }

        fun reportIfDue() {
            // 30 分钟超时的唤醒锁在这里顺手续期，锁屏后转换不会因 CPU 休眠而暂停。
            ensureWakeLock()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastUiAt < 700L) return
            lastUiAt = now
            registry.update(bookId) { it.copy(done = done, total = total, totalSeconds = totalSec) }
            startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                done, total, totalSec, null)
            refreshNotification()
        }

        for (i in startFrom until total) {
            // 用户删除这本书：立刻放弃，保留删除结果。
            if (isBookSkipped(bookId)) {
                App.traceSafe("convert:书籍已被删除，中止转换 $bookId")
                return
            }
            if (cancelState.isCancelled) {
                persist(CacheManager.Status.PAUSED)
                registry.update(bookId) { it.copy(
                    done = done, total = total, status = TaskStatus.CANCELLED,
                    totalSeconds = totalSec
                ) }
                startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                    done, total, totalSec, null, TaskStatus.CANCELLED)
                return
            }

            // 暂停：不取消协程，只在这里等待，已转换的片段与断点全部保留。
            if (isBookPaused(bookId)) {
                persist(CacheManager.Status.PAUSED)
                registry.update(bookId) { it.copy(
                    done = done, total = total, status = TaskStatus.PAUSED,
                    totalSeconds = totalSec
                ) }
                startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                    done, total, totalSec, null, TaskStatus.PAUSED)
                refreshNotification()
                App.traceSafe("convert:已暂停 $title @${done + 1}/$total")
                // 所有书都暂停时释放唤醒锁并停止看门狗，避免无谓耗电。
                if (runningBooks.all { isBookPaused(it) }) {
                    releaseWakeLock()
                    ConversionWatchdog.cancel(this)
                    App.instance.prefs.pendingConversion = false
                }
                while (isBookPaused(bookId)) {
                    if (cancelState.isCancelled || isBookSkipped(bookId)) break
                    delay(300)
                }
                ensureWakeLock()
                App.instance.prefs.pendingConversion = true
                ConversionWatchdog.schedule(this)
                if (cancelState.isCancelled) {
                    registry.update(bookId) { it.copy(status = TaskStatus.CANCELLED) }
                    return
                }
                if (isBookSkipped(bookId)) return
                persist(CacheManager.Status.CONVERTING)
                registry.update(bookId) { it.copy(status = TaskStatus.CONVERTING) }
                refreshNotification()
                App.traceSafe("convert:继续转换 $title @${done + 1}/$total")
            }

            val text = book.paragraphs[i]
            if (text.isBlank()) {
                cache.writeChunk(bookId, i, ShortArray(0))
                done = i
                persistIfDue()
                reportIfDue()
                continue
            }
            // 单段合成失败多为内存抖动或引擎瞬时不可用，重试几次通常能成功。
            // 直接放弃整本书会让用户看到“转一点就停”。
            var result: Pair<ShortArray, Int>? = null
            var attempt = 0
            while (attempt < 3 && result == null) {
                if (attempt > 0) delay(500L * attempt)
                if (isBookSkipped(bookId) || cancelState.isCancelled) return
                result = convertSlots.withPermit { ttsManager.synthesizeParagraph(text, mode) }
                if (result == null) {
                    App.traceSafe(
                        "convert:合成失败 段=$i 第${attempt + 1}次 " +
                            "可用内存=${App.instance.availableMemoryMB()}MB"
                    )
                }
                attempt++
            }
            // 原生合成可能耗时数秒，删除往往就发生在这段时间内。
            // 返回后必须再检查一次，不能把结果写回已删除的书籍目录。
            if (isBookSkipped(bookId)) {
                App.traceSafe("convert:合成返回时书籍已删除，丢弃结果 $bookId")
                return
            }
            if (result == null) {
                App.traceSafe("convert:合成连续失败 ${attempt} 次 段=$i，转为暂停待续")
                failed = true
                failReason = "语音合成失败（已重试 $attempt 次），可稍后继续"
                break
            }
            if (i < 3 || i % 20 == 0) {
                App.traceSafe("convert:合成第 $i 段完成 · 可用内存 ${App.instance.availableMemoryMB()}MB")
            }
            val (pcm, r) = result
            rate = r
            totalSec += pcm.size.toFloat() / r
            cache.writeChunk(bookId, i, pcm)
            done = i
            persistIfDue()
            reportIfDue()
        }

        // 便于排查真实耗时：解析多久、合成多久、平均每段多少毫秒。
        val synthMs = android.os.SystemClock.elapsedRealtime() - synthStartAt
        val count = (done - startFrom + 1).coerceAtLeast(1)
        App.traceSafe(
            "convert:$title 合成 $count 段 用时=${synthMs}ms " +
                "平均=${synthMs / count}ms/段 失败=$failed"
        )

        if (failed) {
            // 失败也保留断点并落为“已暂停”，下次可继续，而不是每次从头再来。
            val reason = failReason ?: "语音合成失败"
            runCatching {
                cache.saveMeta(meta.copy(
                    convertedUpTo = done, totalSeconds = totalSec,
                    sampleRate = rate, status = CacheManager.Status.PAUSED,
                    errorMessage = reason,
                ))
            }
            registry.update(bookId) { it.copy(
                done = done, total = total, status = TaskStatus.PAUSED,
                totalSeconds = totalSec, message = reason
            ) }
            startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
                done, total, totalSec, reason, TaskStatus.PAUSED)
            refreshNotification()
            return
        }

        done = total - 1
        persist(CacheManager.Status.DONE)
        registry.update(bookId) { it.copy(
            done = total, total = total, status = TaskStatus.DONE, totalSeconds = totalSec
        ) }
        startBroadcast(ACTION_CONVERT_DONE, bookId, title, uri, mode.name,
            total, total, totalSec, null)
    }

    /**
     * 因引擎未就绪等原因暂停（不是用户主动暂停）。
     * 落为 PAUSED 而不是 FAILED，进入书库时会自动接着转换。
     */
    private fun pauseWithReason(
        bookId: String, title: String, uri: Uri, mode: TtsManager.Mode, reason: String,
    ) {
        registry.ensure(
            TaskInfo(
                bookId = bookId, title = title, uri = uri.toString(),
                mode = mode.name.lowercase(), total = 0, done = -1,
                status = TaskStatus.PAUSED, totalSeconds = 0f,
            )
        )
        registry.update(bookId) { it.copy(status = TaskStatus.PAUSED, message = reason) }
        runCatching {
            val existing = cache.metaOf(bookId)
            if (existing != null) {
                cache.saveMeta(existing.copy(
                    status = CacheManager.Status.PAUSED, errorMessage = reason,
                ))
            } else {
                cache.saveMeta(CacheManager.Meta(
                    bookId = bookId, sourceUri = uri.toString(), title = title, format = "",
                    paragraphCount = 0, totalSeconds = 0f, sampleRate = 22050,
                    mode = mode.name.lowercase(), convertedUpTo = -1,
                    status = CacheManager.Status.PAUSED,
                    createdAt = System.currentTimeMillis(), errorMessage = reason,
                ))
            }
        }
        startBroadcast(ACTION_CONVERT_PROGRESS, bookId, title, uri, mode.name,
            -1, 0, 0f, reason, TaskStatus.PAUSED)
    }

    private fun failBook(
        bookId: String,
        title: String,
        uri: Uri,
        mode: TtsManager.Mode,
        reason: String,
    ) {
        // 失败回调也可能晚于用户的删除操作，不能借此重建书籍。
        if (isBookSkipped(bookId)) return
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
            if (isBookSkipped(bookId)) continue
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) builder.setProgress(total, done, false)
        return builder.build()
    }

    private fun updateNotification(title: String, text: String, done: Int, total: Int) {
        runCatching { manager?.notify(NOTIFY_ID, buildNotification(title, text, done, total)) }
    }

    /** 并发转换时通知栏展示汇总进度，而不是被多本书来回覆盖。 */
    private fun refreshNotification() {
        val running = registry.all().filter {
            it.status == TaskStatus.CONVERTING ||
                it.status == TaskStatus.PAUSED ||
                it.status == TaskStatus.PENDING
        }
        if (running.isEmpty()) return
        val total = running.sumOf { it.total }
        val done = running.sumOf { (it.done + 1).coerceAtLeast(0) }
        val paused = running.count { it.status == TaskStatus.PAUSED }
        val title = when {
            paused == running.size -> "转换已暂停"
            paused > 0 -> "正在转换 ${running.size - paused} 本 · 暂停 $paused 本"
            else -> "正在转换 ${running.size} 本书"
        }
        val text = if (total > 0) "已完成 $done/$total 段 · 退出应用后仍会继续" else "正在解析…"
        updateNotification(title, text, done, total)
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

enum class TaskStatus { PENDING, CONVERTING, PAUSED, DONE, FAILED, CANCELLED }

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
        TaskStatus.PAUSED -> "已暂停 · ${done + 1}/$total 段"
        TaskStatus.DONE -> "已完成 · ${totalSeconds.toInt()} 秒"
        TaskStatus.FAILED -> "失败${message?.let { "：$it" } ?: ""}"
        TaskStatus.CANCELLED -> "已取消 · ${done}/$total 段"
    }
}

class StatusRegistry {
    private val tasks = ConcurrentHashMap<String, TaskInfo>()

    fun registerNewBatch(newTasks: List<TaskInfo>) {
        synchronized(tasks) {
            // 先移除旧的 DONE/FAILED/CANCELLED 任务，保留还在转换或已暂停的
            tasks.entries.removeIf {
                it.value.status != TaskStatus.CONVERTING && it.value.status != TaskStatus.PAUSED
            }
            for (t in newTasks) tasks[t.bookId] = t
        }
    }

    /** 从书库续转时注册表里可能没有这本书，补一条任务状态。 */
    fun ensure(task: TaskInfo) {
        synchronized(tasks) { tasks.putIfAbsent(task.bookId, task) }
    }

    fun all(): List<TaskInfo> = tasks.values.sortedBy {
        when (it.status) {
            TaskStatus.CONVERTING -> 0
            TaskStatus.PAUSED -> 1
            TaskStatus.PENDING -> 2
            TaskStatus.DONE -> 3
            TaskStatus.FAILED -> 4
            TaskStatus.CANCELLED -> 5
        }
    }

    fun update(bookId: String, block: (TaskInfo) -> TaskInfo) {
        synchronized(tasks) {
            tasks[bookId] = tasks[bookId]?.let { block(it) } ?: return
        }
    }

    /** 书籍被删除后从列表移除，避免书库里残留一张打不开的卡片。 */
    fun remove(bookId: String) {
        synchronized(tasks) { tasks.remove(bookId) }
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
                if (v.status == TaskStatus.CONVERTING || v.status == TaskStatus.PENDING ||
                    v.status == TaskStatus.PAUSED
                ) {
                    tasks[k] = v.copy(status = TaskStatus.CANCELLED)
                }
            }
        }
    }

    fun isRunning(): Boolean = tasks.values.any {
        it.status == TaskStatus.CONVERTING || it.status == TaskStatus.PENDING ||
            it.status == TaskStatus.PAUSED
    }

    fun clear() { tasks.clear() }
}
