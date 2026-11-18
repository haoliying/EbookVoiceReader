package com.ebook.voicereader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.ebook.voicereader.convert.ConvertService
import com.ebook.voicereader.convert.TaskInfo
import com.ebook.voicereader.convert.TaskStatus
import com.ebook.voicereader.convert.StatusRegistry
import com.ebook.voicereader.databinding.ActivityMainBinding
import com.ebook.voicereader.databinding.ItemConvertTaskBinding
import com.ebook.voicereader.model.Book
import com.ebook.voicereader.ocr.OcrEngine
import com.ebook.voicereader.reader.DocumentReaderFactory
import com.ebook.voicereader.tts.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ttsManager: TtsManager
    private lateinit var ocrEngine: OcrEngine
    private lateinit var cache: CacheManager
    private lateinit var prefs: Prefs
    private val readers by lazy { DocumentReaderFactory.create(ocrEngine) }

    private var currentBook: Book? = null
    private var currentBookId: String? = null
    private var paragraphOffsets: List<Pair<Int, Int>> = emptyList()
    private var highlightedSpan: BackgroundColorSpan? = null

    private var ttsReady = false
    private var isPlaying = false
    private var engineError: String? = null

    private var queueDialog: AlertDialog? = null
    private var queueAdapter: ConvertQueueAdapter? = null

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadDocument(it) } }

    private val openMultipleDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) startBatchConvert(uris.toList()) }

    private val requestNotifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不关心结果 */ }

    // 从 Service 接收转换进度广播
    private val convertReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            queueAdapter?.notifyItemChangedByBookId(intent.getStringExtra(ConvertService.EXTRA_BOOK_ID))
            // 若全部完成，可考虑自动关闭 Dialog（用户可手动关，这里不强制）
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)

        cache = CacheManager(this); prefs = Prefs(this); ocrEngine = OcrEngine()
        binding.tvHeaderHint.text = getString(R.string.engine_loading)

        val defaultMode = if (prefs.defaultMode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED

        setupTts(); ttsManager.setMode(defaultMode); setupViews(); updateVoiceModeUi()

        // 注册广播
        val filter = IntentFilter().apply {
            addAction(ConvertService.ACTION_CONVERT_PROGRESS)
            addAction(ConvertService.ACTION_CONVERT_START)
            addAction(ConvertService.ACTION_CONVERT_DONE)
            addAction(ConvertService.ACTION_CONVERT_ERROR)
            addAction(ConvertService.ACTION_CONVERT_ALL_DONE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(convertReceiver, filter)

        // 如果上次 Activity 关闭后还有转换任务没结束，自动弹队列
        if (ConvertService.registry.isRunning()) {
            showConvertQueueDialog()
        }

        handleIncomingIntent(intent)
    }

    private fun setupTts() {
        ttsManager = TtsManager(this)
        ttsManager.setCache(cache)
        ttsManager.setListener(object : TtsManager.Listener {
            override fun onEngineReady(info: String) {
                ttsReady = true; engineError = null
                runOnUiThread { binding.tvHeaderHint.text = info }
            }
            override fun onEngineUnavailable(reason: String) {
                ttsReady = false; engineError = reason
                runOnUiThread {
                    binding.tvHeaderHint.text = reason
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                }
            }
            override fun onPreparationProgress(doneParagraphs: Int, estimatedSeconds: Int) {
                runOnUiThread {
                    binding.btnPlayPause.isEnabled = false; binding.btnPlayPause.alpha = 0.6f
                    binding.tvHeaderHint.text = getString(R.string.preparing_voice, doneParagraphs, estimatedSeconds)
                }
            }
            override fun onPreparationComplete(paragraphsReady: Int) {
                runOnUiThread {
                    binding.btnPlayPause.isEnabled = true; binding.btnPlayPause.alpha = 1f
                    binding.tvHeaderHint.text = getString(R.string.preparation_done, paragraphsReady)
                }
            }
            override fun onCacheAvailable(convertedParagraphs: Int) {
                runOnUiThread {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.cache_available_title)
                        .setMessage(getString(R.string.cache_available_msg, convertedParagraphs))
                        .setPositiveButton(R.string.got_it, null).show()
                }
            }
            override fun onParagraphStart(index: Int) { runOnUiThread { highlightParagraph(index) } }
            override fun onSpeakingChanged(speaking: Boolean) { runOnUiThread { updatePlayState(speaking) } }
            override fun onFinished() {
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.finished, Toast.LENGTH_SHORT).show() }
            }
            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    binding.btnPlayPause.isEnabled = true; binding.btnPlayPause.alpha = 1f
                }
            }
        })
        ttsManager.init()
    }

    private fun setupViews() {
        binding.btnOpenFile.setOnClickListener { openSingleDocument() }
        binding.btnConvert.setOnClickListener { showConvertQueueDialogOrStart() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnStop.setOnClickListener { ttsManager.stop(); clearHighlight() }
        binding.btnPrev.setOnClickListener { ttsManager.previous() }
        binding.btnNext.setOnClickListener { ttsManager.next() }

        binding.tvVoiceMode.setOnClickListener { showVoiceModeDialog() }
        binding.btnManual.setOnClickListener { showTextDialog(R.string.manual, R.string.manual_content) }
        binding.btnChangelog.setOnClickListener { showTextDialog(R.string.changelog, R.string.changelog_content) }

        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + progress * 0.1f
                binding.tvSpeedValue.text = String.format("%.1fx", speed)
                ttsManager.setSpeed(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ------------------------------------------------------------------
    // 打开 / 朗读
    // ------------------------------------------------------------------

    private fun openSingleDocument() {
        openDocument.launch(arrayOf(
            "text/plain", "text/markdown", "application/pdf", "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword", "image/*", "*/*"
        ))
    }

    private fun togglePlay() {
        val book = currentBook ?: run {
            Toast.makeText(this, R.string.empty_content, Toast.LENGTH_SHORT).show(); return
        }
        if (book.paragraphs.isEmpty()) { Toast.makeText(this, R.string.empty_content, Toast.LENGTH_SHORT).show(); return }
        if (!ttsReady) {
            Toast.makeText(this, engineError ?: getString(R.string.tts_not_ready), Toast.LENGTH_SHORT).show(); return
        }
        if (isPlaying) ttsManager.pause()
        else {
            ensureVolume()
            if (ttsManager.isPaused()) ttsManager.resume()
            else ttsManager.speak(book.paragraphs, 0, currentBookId)
        }
    }

    private fun ensureVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.volume_zero_title).setMessage(R.string.volume_zero_msg)
            .setPositiveButton(R.string.raise_volume) { _, _ ->
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { loadDocument(it) }
    }

    private fun loadDocument(uri: Uri) {
        val displayName = getDisplayName(uri)
        val ext = DocumentReaderFactory.extensionOf(displayName)
        val reader = readers.firstOrNull { it.supports(ext) } ?: run {
            Toast.makeText(this, R.string.file_open_failed, Toast.LENGTH_LONG).show(); return
        }
        ttsManager.stop(); clearHighlight()
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoading.text = getString(R.string.loading)
        binding.emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val book = reader.read(this@MainActivity, uri, displayName) { progress ->
                    runOnUiThread { binding.tvLoading.text = "${getString(R.string.loading)} ${(progress * 100).toInt()}%" }
                }
                currentBookId = CacheManager.idOf(uri.toString() + ":" + ttsManager.currentMode().name.lowercase())
                showBook(book)
                // 自动开始播放（从转换队列点开的书最符合用户预期）
                togglePlay()
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "${getString(R.string.file_open_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showBook(book: Book) {
        runOnUiThread {
            currentBook = book
            binding.emptyState.visibility = View.GONE; binding.loadingOverlay.visibility = View.GONE
            binding.tvTitle.visibility = View.VISIBLE; binding.tvTitle.text = book.title
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = buildString {
                append(book.sourceFormat.uppercase()); append(" · ").append(book.paragraphs.size).append(" 段")
                if (book.usedOcr) { append(" · "); append(getString(R.string.ocr_hint)) }
            }
            binding.tvContent.text = SpannableStringBuilder(book.paragraphs.joinToString("\n\n"))
            binding.scrollView.scrollTo(0, 0)

            val offsets = mutableListOf<Pair<Int, Int>>(); var pos = 0
            for (p in book.paragraphs) {
                val start = pos; val end = pos + p.length; offsets.add(start to end); pos = end + 2
            }
            paragraphOffsets = offsets
            if (book.paragraphs.isEmpty()) binding.tvContent.text = getString(R.string.no_text_hint)
        }
    }

    // ------------------------------------------------------------------
    // 批量转换队列 Dialog
    // ------------------------------------------------------------------

    /** 顶部「批量转换」按钮：有任务显示队列，否则开始新任务 */
    private fun showConvertQueueDialogOrStart() {
        if (ConvertService.registry.all().isNotEmpty()) {
            showConvertQueueDialog()
        } else {
            startPickForConvert()
        }
    }

    private fun showConvertQueueDialog() {
        if (queueDialog?.isShowing == true) { queueAdapter?.refreshAll(ConvertService.registry.all()); return }
        val view = layoutInflater.inflate(R.layout.dialog_convert_queue, null)
        val rv: RecyclerView = view.findViewById(R.id.rvTasks)
        val btnNew: TextView = view.findViewById(R.id.btnNewConvert)
        val btnCancelAll: TextView = view.findViewById(R.id.btnCancelAll)

        val adapter = ConvertQueueAdapter(
            onClick = { task ->
                val uri = runCatching { Uri.parse(task.uri) }.getOrNull() ?: run {
                    Toast.makeText(this, "无法定位原文件：${task.title}", Toast.LENGTH_LONG).show(); return@ConvertQueueAdapter
                }
                queueDialog?.dismiss(); loadDocument(uri)
            }
        )
        adapter.refreshAll(ConvertService.registry.all())
        queueAdapter = adapter
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnNew.setOnClickListener { queueDialog?.dismiss(); startPickForConvert() }
        btnCancelAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.convert_cancel)
                .setMessage("取消全部正在转换的任务？已完成的段落会保留。")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startService(Intent(this, ConvertService::class.java).apply {
                        putExtra(ConvertService.EXTRA_ACTION, ConvertService.ACTION_CANCEL)
                    })
                    // 短暂延迟后刷新列表（CANCEL 会走 onDestroy → registry markAllCancelled）
                    rv.postDelayed({ adapter.refreshAll(ConvertService.registry.all()) }, 400)
                }.setNegativeButton(R.string.cancel, null).show()
        }

        queueDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.convert_queue_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { queueDialog = null; queueAdapter = null }
            .create()
        queueDialog?.show()
    }

    private fun refreshQueueIfOpen() {
        queueAdapter?.refreshAll(ConvertService.registry.all())
    }

    private fun startPickForConvert() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        openMultipleDocuments.launch(arrayOf(
            "text/plain", "text/markdown", "application/pdf", "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword", "image/*", "*/*"
        ))
    }

    private fun startBatchConvert(uris: List<Uri>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.convert_pick_mode)
            .setSingleChoiceItems(
                arrayOf(
                    "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
                    "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
                ), 0
            ) { dialog, which ->
                val mode = if (which == 1) "chinese" else "mixed"
                startConvertService(uris, mode); dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
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
        // 弹出队列
        // 等 registry.registerNewBatch 更新一下再弹
        window?.decorView?.postDelayed({ showConvertQueueDialog() }, 200)
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    private fun showSettingsDialog() {
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
                    0 -> editWarmup(); 1 -> editLookahead()
                    2 -> editDefaultMode(); 4 -> cleanCache()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun editWarmup() {
        val values = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
        val labels = values.map { "${it} 秒" }.toTypedArray()
        val checked = values.indexOf(prefs.warmupSeconds).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.warmup_label)
            .setMessage(R.string.warmup_hint)
            .setSingleChoiceItems(labels, checked) { d, w -> prefs.warmupSeconds = values[w]; d.dismiss(); showSettingsDialog() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun editLookahead() {
        val values = intArrayOf(10, 20, 30, 45, 60, 90, 120)
        val labels = values.map { "${it} 秒" }.toTypedArray()
        val checked = values.indexOf(prefs.lookaheadSeconds).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.lookahead_label)
            .setSingleChoiceItems(labels, checked) { d, w -> prefs.lookaheadSeconds = values[w]; d.dismiss(); showSettingsDialog() }
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
                if (!isPlaying) ttsManager.setMode(if (mode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED)
                d.dismiss(); showSettingsDialog(); updateVoiceModeUi()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun cleanCache() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.cache_clean)
            .setMessage(R.string.cache_clean_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cache.clearAll(); Toast.makeText(this, R.string.cache_clean_done, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------

    private fun updateVoiceModeUi() {
        binding.tvVoiceMode.text = getString(
            R.string.voice_mode_chip,
            getString(if (ttsManager.currentMode() == TtsManager.Mode.CHINESE) R.string.mode_chinese else R.string.mode_mixed)
        )
    }

    private fun showVoiceModeDialog() {
        val modes = arrayOf(TtsManager.Mode.MIXED, TtsManager.Mode.CHINESE)
        val labels = arrayOf(
            "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
            "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
        )
        val checked = modes.indexOf(ttsManager.currentMode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.voice_mode_title)
            .setSingleChoiceItems(labels, checked) { d, w ->
                ttsManager.setMode(modes[w]); updateVoiceModeUi()
                Toast.makeText(this, getString(R.string.mode_switched, getString(if (modes[w] == TtsManager.Mode.CHINESE) R.string.mode_chinese else R.string.mode_mixed)), Toast.LENGTH_SHORT).show()
                d.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showTextDialog(titleRes: Int, contentRes: Int) {
        MaterialAlertDialogBuilder(this).setTitle(titleRes).setMessage(contentRes).setPositiveButton(R.string.got_it, null).show()
    }

    private fun updatePlayState(speaking: Boolean) {
        isPlaying = speaking
        binding.btnPlayPause.setImageResource(if (speaking) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlayPause.contentDescription = getString(if (speaking) R.string.pause else R.string.play)
    }

    private fun highlightParagraph(index: Int) {
        val ssb = SpannableStringBuilder(binding.tvContent.text)
        highlightedSpan?.let { ssb.removeSpan(it) }; highlightedSpan = null
        if (index in paragraphOffsets.indices) {
            val (start, end) = paragraphOffsets[index]
            if (end <= ssb.length) {
                val span = BackgroundColorSpan(ContextCompat.getColor(this, R.color.highlight))
                ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                highlightedSpan = span; binding.tvContent.text = ssb; scrollToParagraph(start)
            }
        }
    }

    private fun clearHighlight() {
        highlightedSpan?.let {
            val ssb = SpannableStringBuilder(binding.tvContent.text)
            ssb.removeSpan(it); highlightedSpan = null; binding.tvContent.text = ssb
        }
    }

    private fun scrollToParagraph(charOffset: Int) {
        binding.tvContent.post {
            val layout = binding.tvContent.layout ?: return@post
            if (charOffset > binding.tvContent.text.length) return@post
            val line = layout.getLineForOffset(charOffset)
            binding.scrollView.smoothScrollTo(0, (layout.getLineTop(line) - binding.scrollView.height / 3).coerceAtLeast(0))
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "document"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIncomingIntent(intent) }
    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(convertReceiver)
        queueDialog?.dismiss(); queueDialog = null
        super.onDestroy(); ttsManager.release(); ocrEngine.close()
    }
}

// ----------------------------------------------------------------------
// 队列 RecyclerView
// ----------------------------------------------------------------------

class ConvertQueueAdapter(
    private val onClick: (TaskInfo) -> Unit,
) : RecyclerView.Adapter<ConvertQueueAdapter.VH>() {

    private var tasks: MutableList<TaskInfo> = mutableListOf()

    fun refreshAll(all: List<TaskInfo>) {
        tasks = all.toMutableList()
        notifyDataSetChanged()
    }

    fun notifyItemChangedByBookId(bookId: String?) {
        if (bookId == null) return
        val pos = tasks.indexOfFirst { it.bookId == bookId }
        if (pos >= 0) {
            // 用 registry 最新数据更新（比广播的 Intent 完整）
            val updated = StatusRegistry().let { /* 从单例拿最新 */ }
            // 直接从单例 registry 拿
            ConvertService.registry.all().firstOrNull { it.bookId == bookId }?.let { info ->
                tasks[pos] = info; notifyItemChanged(pos)
            } ?: notifyItemChanged(pos)
        } else {
            // 可能是新任务
            val list = ConvertService.registry.all()
            refreshAll(list)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConvertTaskBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(tasks[position], onClick)
    }

    override fun getItemCount() = tasks.size

    class VH(private val b: ItemConvertTaskBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(task: TaskInfo, onClick: (TaskInfo) -> Unit) {
            b.tvItemTitle.text = task.title
            b.tvItemProgress.text = task.progressText()
            b.tvItemStatusBadge.text = when (task.status) {
                TaskStatus.CONVERTING -> "转换中"
                TaskStatus.PENDING -> "等待中"
                TaskStatus.DONE -> "可播放"
                TaskStatus.FAILED -> "失败"
                TaskStatus.CANCELLED -> "已取消"
            }
            b.tvItemStatusBadge.setTextColor(Color.parseColor(when (task.status) {
                TaskStatus.CONVERTING -> "#1976D2"
                TaskStatus.PENDING -> "#9E9E9E"
                TaskStatus.DONE -> "#388E3C"
                TaskStatus.FAILED -> "#D32F2F"
                TaskStatus.CANCELLED -> "#757575"
            }))
            b.progressItem.isIndeterminate = task.status == TaskStatus.CONVERTING || task.status == TaskStatus.PENDING
            if (task.total > 0) {
                b.progressItem.isIndeterminate = false
                val pct = ((task.done.toFloat() / task.total) * 100f).toInt().coerceIn(0, 100)
                b.progressItem.progress = pct
            }
            b.root.setOnClickListener { onClick(task) }
        }
    }
}
