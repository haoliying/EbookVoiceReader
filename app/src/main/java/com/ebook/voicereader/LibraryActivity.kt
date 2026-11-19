package com.ebook.voicereader

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.ebook.voicereader.convert.ConvertService
import com.ebook.voicereader.convert.TaskInfo
import com.ebook.voicereader.convert.TaskStatus
import com.ebook.voicereader.databinding.ActivityLibraryBinding
import com.ebook.voicereader.databinding.ItemBookCoverBinding
import com.ebook.voicereader.reader.DocumentReaderFactory
import com.ebook.voicereader.tts.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Launcher: 书库网格。
 * - 点「选择要转换的书籍」多文件选择 → 后台 ForegroundService 逐本转换
 * - 点任一封面卡片 → ReaderActivity（朗读页）
 * - 正在播放时顶部显示"继续播放"条，点它回到 ReaderActivity
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var cache: CacheManager
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsManager

    private val openMultiple = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onPickedForConvert(uris.toList()) }

    private val convertReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // 必须同时更新空状态，否则“书库里还没有书”会一直盖住书籍网格。
            refreshLibrary()
        }
    }

    private lateinit var adapter: LibraryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动阶段任何异常都不能直接闪退：改为展示可读错误和导出入口。
        try {
            initLibrary()
        } catch (e: Throwable) {
            App.traceSafe("library:onCreate 失败 ${e.javaClass.name}: ${e.message}")
            runCatching {
                java.io.File(filesDir, "crash.log").appendText(
                    "\n===== LibraryActivity.onCreate =====\n" +
                        android.util.Log.getStackTraceString(e) + "\n"
                )
            }
            showStartupFailure(e)
        }
    }

    /** 启动失败时的保底界面：不依赖任何布局资源，一定能显示出来。 */
    private fun showStartupFailure(e: Throwable) {
        val detail = "${e.javaClass.name}: ${e.message}"
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        val text = android.widget.TextView(this).apply {
            this.text = "应用启动失败\n\n$detail\n\n请点下方按钮把日志保存到「下载」目录，" +
                "然后把 朗读时光-诊断日志.txt 发给开发者。"
            textSize = 15f
            setTextIsSelectable(true)
        }
        val button = android.widget.Button(this).apply {
            this.text = "保存错误日志到下载目录"
            setOnClickListener { saveDiagnosticsToDownloads() }
        }
        container.addView(text)
        container.addView(button)
        setContentView(container)
    }

    private fun initLibrary() {
        binding = ActivityLibraryBinding.inflate(layoutInflater); setContentView(binding.root)

        cache = App.instance.cache
        prefs = App.instance.prefs
        tts = App.instance.ttsManager

        binding.tvEngineHint.text = getString(
            if (tts.isReady()) R.string.engine_ready_short else R.string.engine_lazy
        )

        setupViews()
        setupTtsListener()
        adapter = LibraryAdapter(
            onClick = { item -> openReader(item) },
            onLongClick = { item -> confirmDelete(item) },
        )
        binding.rvLibrary.layoutManager = GridLayoutManager(this, 2)
        binding.rvLibrary.adapter = adapter
        refreshLibrary()

        val filter = IntentFilter().apply {
            addAction(ConvertService.ACTION_CONVERT_PROGRESS)
            addAction(ConvertService.ACTION_CONVERT_START)
            addAction(ConvertService.ACTION_CONVERT_DONE)
            addAction(ConvertService.ACTION_CONVERT_ERROR)
            addAction(ConvertService.ACTION_CONVERT_ALL_DONE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(convertReceiver, filter)

        // 书库只展示数据，不加载大型语音模型；模型在阅读或转换时按需加载。
        handleIncoming(intent)
        if (intent?.action != Intent.ACTION_VIEW) {
            binding.root.post { showPendingCrash() }
        }
    }

    private fun setupViews() {
        binding.btnPickConvert.setOnClickListener { pickForConvert() }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnResumePlay.setOnClickListener {
            val current = tts.currentBookId()
            if (current != null) openReaderByBookId(current)
        }
    }

    private fun setupTtsListener() {
        tts.setListener(object : TtsManager.Listener {
            override fun onEngineReady(info: String) {
                runOnUiThread { binding.tvEngineHint.text = info }
            }
            override fun onEngineUnavailable(reason: String) {
                runOnUiThread { binding.tvEngineHint.text = reason }
            }
            override fun onSpeakingChanged(speaking: Boolean) {
                runOnUiThread { updatePlayingBar() }
            }
            override fun onParagraphStart(index: Int) {}
            override fun onPreparationProgress(doneParagraphs: Int, estimatedSeconds: Int) {}
            override fun onPreparationComplete(paragraphsReady: Int) {}
            override fun onCacheAvailable(convertedParagraphs: Int) {}
            override fun onFinished() {}
            override fun onError(message: String) {
                runOnUiThread { Toast.makeText(this@LibraryActivity, message, Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun updatePlayingBar() {
        val visible = tts.isPlaying() || tts.isPaused()
        binding.playingBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvPlayingTitle.text = tts.currentBookTitle() ?: getString(R.string.resume_reading)
    }

    private fun refreshLibrary() {
        val items = buildLibraryList()
        adapter.refreshAll(items)
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 长按书籍卡片：确认后删除书籍及其语音缓存。 */
    private fun confirmDelete(item: LibraryItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除《${item.title}》")
            .setMessage("会同时删除已转换的语音文件，删除后需要重新转换才能后台朗读。")
            .setPositiveButton("删除") { _, _ -> deleteBook(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteBook(item: LibraryItem) {
        // 正在播这本书就先停掉，避免继续读已删除的缓存。
        if (tts.currentBookId() == item.bookId) tts.stop()
        // 通知转换服务立刻放弃这本书，防止删掉的目录又被写回来。
        ConvertService.skipBook(item.bookId)
        cache.deleteBook(item.bookId)
        refreshLibrary()
        Toast.makeText(this, "已删除《${item.title}》", Toast.LENGTH_SHORT).show()
    }

    /** 合并 CacheManager 和 ConvertService.registry → 统一展示列表 */
    private fun buildLibraryList(): List<LibraryItem> {
        val map = LinkedHashMap<String, LibraryItem>()

        // 已持久化的缓存（包括 DONE 和 FAILED）
        for (meta in cache.listAll()) {
            map[meta.bookId] = LibraryItem(
                bookId = meta.bookId,
                sourceUri = meta.sourceUri,
                title = meta.title,
                format = meta.format,
                paragraphCount = meta.paragraphCount,
                convertedUpTo = meta.convertedUpTo,
                totalSeconds = meta.totalSeconds,
                mode = meta.mode,
                errorMessage = meta.errorMessage,
                status = when (meta.status) {
                    CacheManager.Status.DONE -> ItemStatus.DONE
                    CacheManager.Status.CONVERTING -> ItemStatus.CONVERTING
                    CacheManager.Status.FAILED -> ItemStatus.FAILED
                    CacheManager.Status.NONE -> ItemStatus.PENDING
                },
                createdAt = meta.createdAt,
            )
        }

        // 当前正在进行的转换（可能还没持久化 meta）
        for (task in ConvertService.registry.all()) {
            val existing = map[task.bookId]
            val item = if (existing != null) {
                existing.copy(
                    convertedUpTo = if (task.done >= 0) task.done else existing.convertedUpTo,
                    paragraphCount = task.total.coerceAtLeast(existing.paragraphCount),
                    title = task.title.ifBlank { existing.title },
                    totalSeconds = task.totalSeconds.takeIf { it > 0f } ?: existing.totalSeconds,
                    sourceUri = task.uri.ifBlank { existing.sourceUri },
                    errorMessage = task.message ?: existing.errorMessage,
                    status = when (task.status) {
                        TaskStatus.CONVERTING -> ItemStatus.CONVERTING
                        TaskStatus.PENDING -> ItemStatus.PENDING
                        TaskStatus.DONE -> ItemStatus.DONE
                        TaskStatus.FAILED -> ItemStatus.FAILED
                        TaskStatus.CANCELLED -> if (existing.convertedUpTo >= 0) ItemStatus.PARTIAL else ItemStatus.FAILED
                    }
                )
            } else {
                LibraryItem(
                    bookId = task.bookId,
                    sourceUri = task.uri,
                    title = task.title,
                    format = "",
                    paragraphCount = task.total,
                    convertedUpTo = if (task.done >= 0) task.done else -1,
                    totalSeconds = task.totalSeconds,
                    mode = task.mode,
                    errorMessage = task.message.orEmpty(),
                    status = when (task.status) {
                        TaskStatus.CONVERTING -> ItemStatus.CONVERTING
                        TaskStatus.PENDING -> ItemStatus.PENDING
                        TaskStatus.DONE -> ItemStatus.DONE
                        TaskStatus.FAILED -> ItemStatus.FAILED
                        TaskStatus.CANCELLED -> ItemStatus.PARTIAL
                    },
                    createdAt = System.currentTimeMillis()
                )
            }
            map[task.bookId] = item
        }

        return map.values.sortedWith(
            compareBy<LibraryItem> {
                when (it.status) {
                    ItemStatus.CONVERTING -> 0
                    ItemStatus.PENDING -> 1
                    ItemStatus.DONE -> 2
                    ItemStatus.PARTIAL -> 3
                    ItemStatus.FAILED -> 4
                }
            }.thenByDescending { it.createdAt }
        )
    }

    private fun pickForConvert() {
        // 只启动文件选择器；通知权限留到真正开始转换时再申请，
        // 避免权限弹窗与文件选择器同时打开导致 ActivityResult 竞态闪退。
        runCatching {
            openMultiple.launch(arrayOf(
                "text/plain", "text/markdown", "application/pdf", "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword", "image/*", "*/*"
            ))
        }.onFailure {
            Toast.makeText(this, R.string.file_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onPickedForConvert(uris: List<Uri>) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.convert_pick_mode)
            .setSingleChoiceItems(
                arrayOf(
                    "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
                    "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
                ), 0
            ) { dialog, which ->
                val mode = if (which == 1) "chinese" else "mixed"
                runCatching { startConvertService(uris, mode) }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "导入失败：${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startConvertService(uris: List<Uri>, mode: String) {
        App.traceSafe("library:选择 ${uris.size} 个文件，模式=$mode")
        val names = uris.map { getDisplayName(it) }
        val tasks = uris.mapIndexed { i, uri ->
            TaskInfo(
                bookId = CacheManager.idOf(uri.toString() + ":" + mode.uppercase()),
                title = names.getOrNull(i) ?: "文件${i + 1}",
                uri = uri.toString(),
                mode = mode,
                total = 0, done = -1,
                status = TaskStatus.PENDING,
                totalSeconds = 0f,
            )
        }

        // 先持久化书籍，再启动服务。即使服务被系统拒绝或进程随后退出，书库也不会丢书。
        for ((i, task) in tasks.withIndex()) {
            val uri = uris[i]
            if (uri.scheme == "content") {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            cache.saveMeta(CacheManager.Meta(
                bookId = task.bookId,
                sourceUri = task.uri,
                title = task.title,
                format = DocumentReaderFactory.extensionOf(this, uri, task.title),
                paragraphCount = 0,
                totalSeconds = 0f,
                sampleRate = 22050,
                mode = mode,
                convertedUpTo = -1,
                status = CacheManager.Status.NONE,
                createdAt = System.currentTimeMillis(),
            ))
        }
        ConvertService.registry.registerNewBatch(tasks)
        refreshLibrary()

        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_ACTION, ConvertService.ACTION_START)
            putParcelableArrayListExtra(ConvertService.EXTRA_URIS, ArrayList(uris))
            putStringArrayListExtra(ConvertService.EXTRA_DISPLAY_NAMES, ArrayList(names))
            putExtra(ConvertService.EXTRA_MODE, mode)
        }
        val foregroundAttempt = runCatching { ContextCompat.startForegroundService(this, intent) }
        val started = foregroundAttempt.isSuccess || runCatching {
            startService(intent) != null
        }.getOrDefault(false)
        App.traceSafe(
            "library:启动服务 started=$started " +
                "error=${foregroundAttempt.exceptionOrNull()?.javaClass?.simpleName}"
        )

        if (!started) {
            val reason = foregroundAttempt.exceptionOrNull()?.message ?: "系统拒绝启动后台转换"
            for (task in tasks) {
                ConvertService.registry.update(task.bookId) {
                    it.copy(status = TaskStatus.FAILED, message = reason)
                }
                cache.metaOf(task.bookId)?.let { meta ->
                    cache.saveMeta(meta.copy(
                        status = CacheManager.Status.FAILED,
                        errorMessage = reason,
                    ))
                }
            }
            refreshLibrary()
            Toast.makeText(this, "后台转换启动失败：$reason", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, getString(R.string.convert_start) + "（${uris.size} 本）", Toast.LENGTH_LONG).show()
        scheduleRefreshWhileRunning()
    }

    /** 转换进行中时定时刷新书库，保证进度和"没有显示书"的问题不再出现 */
    private fun scheduleRefreshWhileRunning() {
        binding.rvLibrary.removeCallbacks(refreshTick)
        binding.rvLibrary.postDelayed(refreshTick, 1000)
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshLibrary()
            if (ConvertService.registry.isRunning()) {
                binding.rvLibrary.postDelayed(this, 1000)
            }
        }
    }

    private fun openReader(item: LibraryItem) {
        if (item.sourceUri.isBlank()) {
            Toast.makeText(this, "原文件不可用：${item.title}", Toast.LENGTH_LONG).show()
            return
        }
        val uri = runCatching { Uri.parse(item.sourceUri) }.getOrNull() ?: return
        openReaderFor(uri, item.bookId, item.title)
    }

    private fun openReaderByBookId(bookId: String) {
        val meta = cache.metaOf(bookId)
        if (meta != null && meta.sourceUri.isNotBlank()) {
            openReaderFor(Uri.parse(meta.sourceUri), bookId, meta.title)
            return
        }
        // fallback：用 registry 里的 uri
        val reg = ConvertService.registry.all().firstOrNull { it.bookId == bookId }
        if (reg != null && reg.uri.isNotBlank()) {
            openReaderFor(Uri.parse(reg.uri), bookId, reg.title)
        }
    }

    private fun openReaderFor(uri: Uri, bookId: String, fallbackTitle: String) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_URI, uri.toString())
            putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
            putExtra(ReaderActivity.EXTRA_FALLBACK_TITLE, fallbackTitle)
        }
        startActivity(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                val name = getDisplayName(uri)
                val id = CacheManager.idOf(uri.toString() + ":" + tts.currentMode().name)
                openReaderFor(uri, id, name)
            }
        }
    }

    private fun showSettings() {
        val warmup = prefs.warmupSeconds
        val lookahead = prefs.lookaheadSeconds
        val defaultMode = prefs.defaultMode
        val cacheMB = cache.totalSizeMB()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setItems(arrayOf(
                getString(R.string.warmup_label) + "：${warmup} 秒",
                getString(R.string.lookahead_label) + "：${lookahead} 秒",
                getString(R.string.default_mode_label) + "：" + getString(if (defaultMode == "chinese") R.string.mode_chinese else R.string.mode_mixed),
                getString(R.string.cache_size_label) + "：${cacheMB} MB",
                getString(R.string.cache_clean),
                getString(R.string.diagnostics),
            )) { _, which ->
                when (which) {
                    0 -> editWarmup()
                    1 -> editLookahead()
                    2 -> editDefaultMode()
                    4 -> cleanCache()
                    5 -> showDiagnostics()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun editWarmup() {
        val values = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
        val labels = values.map { "${it} 秒" }.toTypedArray()
        val checked = values.indexOf(prefs.warmupSeconds).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.warmup_label).setMessage(R.string.warmup_hint)
            .setSingleChoiceItems(labels, checked) { d, w -> prefs.warmupSeconds = values[w]; d.dismiss(); showSettings() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun editLookahead() {
        val values = intArrayOf(10, 20, 30, 45, 60, 90, 120)
        val labels = values.map { "${it} 秒" }.toTypedArray()
        val checked = values.indexOf(prefs.lookaheadSeconds).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.lookahead_label)
            .setSingleChoiceItems(labels, checked) { d, w -> prefs.lookaheadSeconds = values[w]; d.dismiss(); showSettings() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun editDefaultMode() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.default_mode_label)
            .setSingleChoiceItems(
                arrayOf(
                    "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
                    "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
                ), if (prefs.defaultMode == "chinese") 1 else 0
            ) { d, w ->
                val mode = if (w == 1) "chinese" else "mixed"
                prefs.defaultMode = mode
                if (!tts.isPlaying()) tts.setMode(if (mode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED)
                d.dismiss(); showSettings()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun cleanCache() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.cache_clean).setMessage(R.string.cache_clean_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cache.clearAll(); Toast.makeText(this, R.string.cache_clean_done, Toast.LENGTH_SHORT).show(); refreshLibrary()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun getDisplayName(uri: Uri): String =
        DocumentReaderFactory.displayName(this, uri)

    private fun showPendingCrash() {
        if (isFinishing || isDestroyed) return
        val report = App.instance.consumeCrashReport() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.last_crash_title)
            .setMessage(getString(R.string.last_crash_message) + "\n\n" + report.takeLast(5000))
            .setPositiveButton(R.string.copy_diagnostics) { _, _ -> copyDiagnostics(report) }
            .setNeutralButton(R.string.save_to_downloads) { _, _ -> saveDiagnosticsToDownloads() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showDiagnostics() {
        val report = App.instance.diagnosticReport()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnostics)
            .setMessage(report)
            .setPositiveButton(R.string.copy_diagnostics) { _, _ -> copyDiagnostics(report) }
            .setNeutralButton(R.string.save_to_downloads) { _, _ -> saveDiagnosticsToDownloads() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** 用户不需要 root 或数据线，也能把真实错误发给开发者。 */
    private fun saveDiagnosticsToDownloads() {
        val result = runCatching { App.instance.exportDiagnostics() }
            .getOrElse { "保存失败：${it.message ?: it.javaClass.simpleName}" }
        Toast.makeText(this, result, Toast.LENGTH_LONG).show()
    }

    private fun copyDiagnostics(report: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("朗读时光诊断", report))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // 启动失败走保底界面时，binding / tts 尚未初始化，直接跳过。
        if (!::binding.isInitialized || !::tts.isInitialized) return
        // 从 ReaderActivity 返回时重新接管引擎回调（之前被阅读页覆盖了）
        setupTtsListener()
        if (tts.isReady()) binding.tvEngineHint.setText(R.string.engine_ready_short)
        updatePlayingBar()
        refreshLibrary()
        if (ConvertService.registry.isRunning()) scheduleRefreshWhileRunning()
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.rvLibrary.removeCallbacks(refreshTick)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { LocalBroadcastManager.getInstance(this).unregisterReceiver(convertReceiver) }
        super.onDestroy()
    }
}

// ----------------------------------------------------------------------
// 类型
// ----------------------------------------------------------------------

enum class ItemStatus { CONVERTING, PENDING, DONE, PARTIAL, FAILED }

data class LibraryItem(
    val bookId: String,
    val sourceUri: String,
    val title: String,
    val format: String,
    val paragraphCount: Int,
    val convertedUpTo: Int,
    val totalSeconds: Float,
    val mode: String,
    val status: ItemStatus,
    val createdAt: Long,
    val errorMessage: String = "",
) {
    fun coverInitial(): String = title.trim().take(1).ifBlank { "?" }
    fun percent(): Int = if (paragraphCount > 0 && convertedUpTo >= 0) ((convertedUpTo + 1) * 100f / paragraphCount).toInt().coerceIn(0, 100) else 0
    fun metaText(): String = buildString {
        if (status == ItemStatus.FAILED && errorMessage.isNotBlank()) {
            append(errorMessage)
            return@buildString
        }
        if (paragraphCount > 0) append("${paragraphCount} 段")
        if (totalSeconds > 0f) { append(" · ${totalSeconds.toInt()}s") }
        if (mode.isNotBlank()) append(" · ").append(if (mode == "chinese") "中文" else "双语")
    }
    fun statusText(): String = when (status) {
        ItemStatus.CONVERTING -> "${percent()}%"
        ItemStatus.PENDING -> "等待中"
        ItemStatus.DONE -> App.instance.getString(R.string.library_done)
        ItemStatus.PARTIAL -> "部分 · ${percent()}%"
        ItemStatus.FAILED -> App.instance.getString(R.string.library_failed)
    }
}

// ----------------------------------------------------------------------
// Adapter
// ----------------------------------------------------------------------

class LibraryAdapter(
    private val onClick: (LibraryItem) -> Unit,
    private val onLongClick: (LibraryItem) -> Unit = {},
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    private var items: List<LibraryItem> = emptyList()

    fun refreshAll(newItems: List<LibraryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBookCoverBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick, onLongClick)
    }

    class VH(private val b: ItemBookCoverBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: LibraryItem, onClick: (LibraryItem) -> Unit, onLongClick: (LibraryItem) -> Unit) {
            b.coverInitial.text = item.coverInitial()
            b.bookTitle.text = item.title
            b.bookMeta.text = item.metaText()

            val badgeColor = when (item.status) {
                ItemStatus.CONVERTING -> Color.parseColor("#1976D2")
                ItemStatus.PENDING -> Color.parseColor("#9E9E9E")
                ItemStatus.DONE -> Color.parseColor("#388E3C")
                ItemStatus.PARTIAL -> Color.parseColor("#F57C00")
                ItemStatus.FAILED -> Color.parseColor("#D32F2F")
            }
            b.coverStatus.text = item.statusText()
            b.coverStatus.setTextColor(badgeColor)

            if (item.status == ItemStatus.CONVERTING || item.status == ItemStatus.PENDING) {
                b.coverProgress.visibility = View.VISIBLE
                if (item.status == ItemStatus.CONVERTING && item.paragraphCount > 0) {
                    b.coverProgress.isIndeterminate = false
                    b.coverProgress.progress = item.percent()
                } else {
                    b.coverProgress.isIndeterminate = true
                }
            } else {
                b.coverProgress.visibility = View.GONE
            }

            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item); true }
        }
    }
}
