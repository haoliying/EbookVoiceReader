package com.ebook.voicereader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.lifecycleScope
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.ebook.voicereader.convert.ConvertService
import com.ebook.voicereader.databinding.ActivityMainBinding
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

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadDocument(it) } }

    private val openMultipleDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) startBatchConvert(uris.toList())
    }

    private val requestNotifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不关心结果，服务启动会自动降级 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = CacheManager(this)
        prefs = Prefs(this)
        ocrEngine = OcrEngine()
        binding.tvHeaderHint.text = getString(R.string.engine_loading)

        // 首次启动时应用默认语音模式
        val defaultMode = if (prefs.defaultMode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED

        setupTts()
        ttsManager.setMode(defaultMode)
        setupViews()
        updateVoiceModeUi()

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
                    binding.tvHeaderHint.text = getString(
                        R.string.preparing_voice, doneParagraphs, estimatedSeconds
                    )
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
                        .setPositiveButton(R.string.got_it, null)
                        .show()
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
        binding.btnConvert.setOnClickListener { startPickForConvert() }
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
        if (book.paragraphs.isEmpty()) {
            Toast.makeText(this, R.string.empty_content, Toast.LENGTH_SHORT).show(); return
        }
        if (!ttsReady) {
            Toast.makeText(this, engineError ?: getString(R.string.tts_not_ready), Toast.LENGTH_SHORT).show()
            return
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
            }
            .setNegativeButton(R.string.cancel, null).show()
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
                    runOnUiThread {
                        binding.tvLoading.text = "${getString(R.string.loading)} ${(progress * 100).toInt()}%"
                    }
                }
                currentBookId = CacheManager.idOf(uri.toString() + ":" + ttsManager.currentMode().name.lowercase())
                showBook(book)
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
            binding.emptyState.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
            binding.tvTitle.visibility = View.VISIBLE
            binding.tvTitle.text = book.title
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = buildString {
                append(book.sourceFormat.uppercase()); append(" · ").append(book.paragraphs.size).append(" 段")
                if (book.usedOcr) { append(" · "); append(getString(R.string.ocr_hint)) }
            }
            binding.tvContent.text = SpannableStringBuilder(book.paragraphs.joinToString("\n\n"))
            binding.scrollView.scrollTo(0, 0)

            val offsets = mutableListOf<Pair<Int, Int>>(); var pos = 0
            for (p in book.paragraphs) {
                val start = pos; val end = pos + p.length
                offsets.add(start to end); pos = end + 2
            }
            paragraphOffsets = offsets
            if (book.paragraphs.isEmpty()) binding.tvContent.text = getString(R.string.no_text_hint)
        }
    }

    // ------------------------------------------------------------------
    // 批量转换
    // ------------------------------------------------------------------

    private fun startPickForConvert() {
        // Android 13+ 请求通知权限
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
        Toast.makeText(
            this, getString(R.string.convert_start) + "（${uris.size} 本）", Toast.LENGTH_LONG
        ).show()
    }

    // ------------------------------------------------------------------
    // 设置 dialog
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
                getString(R.string.default_mode_label) + "：" +
                    getString(if (defaultMode == "chinese") R.string.mode_chinese else R.string.mode_mixed),
                getString(R.string.cache_size_label) + "：${cacheMB} MB",
                getString(R.string.cache_clean),
            )) { _, which ->
                when (which) {
                    0 -> editWarmup()
                    1 -> editLookahead()
                    2 -> editDefaultMode()
                    4 -> cleanCache()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editWarmup() {
        val current = prefs.warmupSeconds
        val values = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
        val labels = values.map { "${it} 秒" }.toTypedArray()
        val checked = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.warmup_label)
            .setMessage(R.string.warmup_hint)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.warmupSeconds = values[which]
                dialog.dismiss(); showSettingsDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editLookahead() {
        val current = prefs.lookaheadSeconds
        val values = intArrayOf(10, 20, 30, 45, 60, 90, 120)
        val labels = values.map { "${it} 秒" }.toTypedArray()
        val checked = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lookahead_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.lookaheadSeconds = values[which]
                dialog.dismiss(); showSettingsDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editDefaultMode() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_mode_label)
            .setSingleChoiceItems(
                arrayOf(
                    "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
                    "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
                ), if (prefs.defaultMode == "chinese") 1 else 0
            ) { dialog, which ->
                val mode = if (which == 1) "chinese" else "mixed"
                prefs.defaultMode = mode
                if (!isPlaying) ttsManager.setMode(if (mode == "chinese") TtsManager.Mode.CHINESE else TtsManager.Mode.MIXED)
                dialog.dismiss(); showSettingsDialog(); updateVoiceModeUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cleanCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cache_clean)
            .setMessage(R.string.cache_clean_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cache.clearAll()
                Toast.makeText(this, R.string.cache_clean_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_mode_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ttsManager.setMode(modes[which])
                updateVoiceModeUi()
                Toast.makeText(
                    this, getString(R.string.mode_switched, getString(if (modes[which] == TtsManager.Mode.CHINESE) R.string.mode_chinese else R.string.mode_mixed)),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showTextDialog(titleRes: Int, contentRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes).setMessage(contentRes).setPositiveButton(R.string.got_it, null).show()
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
                highlightedSpan = span; binding.tvContent.text = ssb
                scrollToParagraph(start)
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
    override fun onDestroy() { super.onDestroy(); ttsManager.release(); ocrEngine.close() }
}
