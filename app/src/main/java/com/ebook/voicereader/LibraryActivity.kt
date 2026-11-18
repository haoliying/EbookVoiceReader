package com.ebook.voicereader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
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

    private val requestNotifyPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 忽略结果，服务会降级 */ }

    private val convertReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            adapter.refreshAll(buildLibraryList())
        }
    }

    private lateinit var adapter: LibraryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater); setContentView(binding.root)

        cache = App.instance.cache
        prefs = App.instance.prefs
        tts = App.instance.ttsManager

        binding.tvEngineHint.text = getString(R.string.engine_loading)

        setupViews()
        setupTtsListener()
        adapter = LibraryAdapter(onClick = { item -> openReader(item) })
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

        // 引擎未初始化时在后台启动（与 App.onCreate 分离以便拿到 UI 回调）
        if (!tts.isReady()) tts.init()

        // 检查 incoming ACTION_VIEW（文件管理器直接打开）
        handleIncoming(intent)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) requestNotifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        openMultiple.launch(arrayOf(
            "text/plain", "text/markdown", "application/pdf", "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword", "image/*", "*/*"
        ))
    }

    private fun onPickedForConvert(uris: List<Uri>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.convert_pick_mode)
            .setSingleChoiceItems(
                arrayOf(
                    "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
                    "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
                ), 0
            ) { dialog, which ->
                val mode = if (which == 1) "chinese" else "mixed"
                startConvertService(uris, mode)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startConvertService(uris: List<Uri>, mode: String) {
        val names = uris.map { getDisplayName(it) }
        val intent = Intent(this, ConvertService::class.java).apply {
            putExtra(ConvertService.EXTRA_ACTION, ConvertService.ACTION_START)
            putParcelableArrayListExtra(ConvertService.EXTRA_URIS, ArrayList(uris))
            putStringArrayListExtra(ConvertService.EXTRA_DISPLAY_NAMES, ArrayList(names))
            putExtra(ConvertService.EXTRA_MODE, mode)
        }
        runCatching { startForegroundService(intent) }
        Toast.makeText(this, getString(R.string.convert_start) + "（${uris.size} 本）", Toast.LENGTH_LONG).show()
        // 让新任务立刻出现在列表（服务还没来得及 registerNewBatch，手动触发一次刷新）
        binding.rvLibrary.postDelayed({ refreshLibrary() }, 500)
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
                openMultiple.launch(arrayOf("*/*")) // ignore
                // 直接进入阅读器
                val name = getDisplayName(uri)
                val id = CacheManager.idOf(uri.toString() + ":" + tts.currentMode().name.lowercase())
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
            )) { _, which ->
                when (which) {
                    0 -> editWarmup()
                    1 -> editLookahead()
                    2 -> editDefaultMode()
                    4 -> cleanCache()
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

    private fun getDisplayName(uri: Uri): String {
        var name = "document"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name
    }

    override fun onResume() {
        super.onResume()
        updatePlayingBar()
        refreshLibrary()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(convertReceiver)
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
) {
    fun coverInitial(): String = title.trim().take(1).ifBlank { "?" }
    fun percent(): Int = if (paragraphCount > 0 && convertedUpTo >= 0) ((convertedUpTo + 1) * 100f / paragraphCount).toInt().coerceIn(0, 100) else 0
    fun metaText(): String = buildString {
        if (paragraphCount > 0) append("${paragraphCount} 段")
        if (totalSeconds > 0f) { append(" · ${totalSeconds.toInt()}s") }
        if (mode.isNotBlank()) append(" · ").append(if (mode == "chinese") "中文" else "双语")
    }
    fun statusText(): String = when (status) {
        ItemStatus.CONVERTING -> "${percent()}%"
        ItemStatus.PENDING -> App.instance.getString(R.string.library_converting)
        ItemStatus.DONE -> App.instance.getString(R.string.library_done)
        ItemStatus.PARTIAL -> "部分 · ${percent()}%"
        ItemStatus.FAILED -> App.instance.getString(R.string.library_failed)
    }
}

// ----------------------------------------------------------------------
// Adapter
// ----------------------------------------------------------------------

class LibraryAdapter(private val onClick: (LibraryItem) -> Unit) :
    RecyclerView.Adapter<LibraryAdapter.VH>() {

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
        holder.bind(items[position], onClick)
    }

    class VH(private val b: ItemBookCoverBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: LibraryItem, onClick: (LibraryItem) -> Unit) {
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
        }
    }
}

// ----------------------------------------------------------------------
// 为 LibraryItem.getStringSafe 需要 context — 用 object 代替
// ----------------------------------------------------------------------

private val contextRef: android.content.Context
    get() = App.instance
