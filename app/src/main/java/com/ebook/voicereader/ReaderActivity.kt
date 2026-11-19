package com.ebook.voicereader

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.databinding.ActivityReaderBinding
import com.ebook.voicereader.model.Book
import com.ebook.voicereader.reader.DocumentReaderFactory
import com.ebook.voicereader.tts.TtsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 朗读页：展示正文 + 播放控件。
 * 使用 App.instance.ttsManager（全局单例），退出后音频继续，
 * 回到 LibraryActivity 可点「继续播放」重新进来。
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_FALLBACK_TITLE = "title"
    }

    private lateinit var binding: ActivityReaderBinding
    private val tts by lazy { App.instance.ttsManager }
    private val cache by lazy { App.instance.cache }
    private val readers by lazy { DocumentReaderFactory.create(App.instance.ocrEngine) }

    private var book: Book? = null
    private var bookId: String? = null
    private var paragraphOffsets: List<Pair<Int, Int>> = emptyList()
    private var highlightedSpan: BackgroundColorSpan? = null

    private var isPlaying = false
    private var ttsReady = false
    private var engineError: String? = null
    private var lastUri: Uri? = null
    private var pendingAutoPlay = false
    private var seekDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater); setContentView(binding.root)

        ttsReady = tts.isReady()
        binding.tvEngineHint.text = if (ttsReady) "就绪" else getString(R.string.engine_loading)

        setupTtsListener()
        setupViews()
        tts.init()

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val bookIdFromIntent = intent.getStringExtra(EXTRA_BOOK_ID)
        val fallbackTitle = intent.getStringExtra(EXTRA_FALLBACK_TITLE) ?: ""

        lastUri = Uri.parse(uriStr)
        bookId = bookIdFromIntent ?: CacheManager.idOf("$uriStr:${tts.currentMode().name}")

        // 注意：即使这本书还在后台播放，也必须重新加载正文。
        // 否则 book 为 null，再次点播放按钮时 togglePlay 无从下手，看起来就是“不播放”。
        loadDocument(Uri.parse(uriStr), fallbackTitle)
    }

    private fun setupTtsListener() {
        tts.setListener(object : TtsManager.Listener {
            override fun onEngineReady(info: String) {
                ttsReady = true; engineError = null
                runOnUiThread {
                    binding.tvEngineHint.text = info
                    if (pendingAutoPlay && book != null) togglePlay()
                }
            }
            override fun onEngineUnavailable(reason: String) {
                ttsReady = false; engineError = reason; pendingAutoPlay = false
                runOnUiThread {
                    binding.tvEngineHint.text = reason
                    binding.btnPlayPause.isEnabled = true
                    binding.btnPlayPause.alpha = 1f
                }
            }
            override fun onPreparationProgress(doneParagraphs: Int, estimatedSeconds: Int) {
                runOnUiThread {
                    binding.btnPlayPause.isEnabled = false; binding.btnPlayPause.alpha = 0.6f
                    binding.tvEngineHint.text = getString(R.string.preparing_voice, doneParagraphs, estimatedSeconds)
                }
            }
            override fun onPreparationComplete(paragraphsReady: Int) {
                runOnUiThread {
                    binding.btnPlayPause.isEnabled = true; binding.btnPlayPause.alpha = 1f
                    binding.tvEngineHint.text = getString(R.string.preparation_done, paragraphsReady)
                }
            }
            override fun onCacheAvailable(convertedParagraphs: Int) {
                runOnUiThread {
                    binding.tvEngineHint.text = getString(R.string.cache_available_msg, convertedParagraphs)
                }
            }
            override fun onParagraphStart(index: Int) {
                runOnUiThread {
                    val total = book?.paragraphs?.size ?: 0
                    if (total > 0 && !seekDragging) {
                        binding.seekParagraph.progress = index.coerceIn(0, total - 1)
                        binding.tvPosition.text = "${index + 1} / $total"
                    }
                    highlightParagraph(index)
                }
            }
            override fun onSpeakingChanged(speaking: Boolean) { runOnUiThread { updatePlayState(speaking) } }
            override fun onFinished() {
                runOnUiThread { Toast.makeText(this@ReaderActivity, R.string.finished, Toast.LENGTH_SHORT).show() }
            }
            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@ReaderActivity, message, Toast.LENGTH_LONG).show()
                    binding.btnPlayPause.isEnabled = true; binding.btnPlayPause.alpha = 1f
                }
            }
        })
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnManual.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.manual).setMessage(R.string.manual_content)
                .setPositiveButton(R.string.got_it, null).show()
        }
        binding.btnChangelog.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.changelog).setMessage(R.string.changelog_content)
                .setPositiveButton(R.string.got_it, null).show()
        }
        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnStop.setOnClickListener { tts.stop(); clearHighlight() }
        binding.btnPrev.setOnClickListener { tts.previous() }
        binding.btnNext.setOnClickListener { tts.next() }
        binding.tvVoiceMode.setOnClickListener { showVoiceModeDialog() }

        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + progress * 0.1f
                binding.tvSpeedValue.text = String.format("%.1fx", speed)
                tts.setSpeed(speed)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekParagraph.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = book?.paragraphs?.size ?: 0
                    if (total > 0) binding.tvPosition.text = "${progress + 1} / $total"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                val b = book ?: return
                val total = b.paragraphs.size
                if (total <= 0) return
                val target = (sb?.progress ?: 0).coerceIn(0, total - 1)
                tts.seekTo(target)
                highlightParagraph(target)
            }
        })
    }

    private fun loadDocument(uri: Uri, fallbackTitle: String) {
        val displayName = getDisplayName(uri)
        val ext = DocumentReaderFactory.extensionOf(this, uri, displayName)
        val reader = readers.firstOrNull { it.supports(ext) } ?: run {
            Toast.makeText(this, R.string.file_open_failed, Toast.LENGTH_LONG).show(); finish()
            return
        }

        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoading.text = getString(R.string.loading)

        lifecycleScope.launch {
            try {
                val b = reader.read(this@ReaderActivity, uri, displayName) { progress ->
                    runOnUiThread { binding.tvLoading.text = "${getString(R.string.loading)} ${(progress * 100).toInt()}%" }
                }
                book = b
                runOnUiThread { showBook(b, fallbackTitle) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                runOnUiThread {
                    val reason = e.message ?: e.javaClass.simpleName
                    binding.tvLoading.text = "${getString(R.string.file_open_failed)}：$reason"
                    Toast.makeText(this@ReaderActivity, "${getString(R.string.file_open_failed)}: $reason", Toast.LENGTH_LONG).show()
                    binding.loadingOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun showBook(b: Book, fallbackTitle: String) {
        binding.loadingOverlay.visibility = View.GONE
        binding.tvTitle.text = b.title.ifBlank { fallbackTitle }
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = buildString {
            append(b.sourceFormat.uppercase()); append(" · ").append(b.paragraphs.size).append(" 段")
            if (b.usedOcr) { append(" · "); append(getString(R.string.ocr_hint)) }
        }
        val full = b.paragraphs.joinToString("\n\n")
        binding.tvContent.text = SpannableStringBuilder(full)
        binding.scrollView.scrollTo(0, 0)

        val offsets = mutableListOf<Pair<Int, Int>>(); var pos = 0
        for (p in b.paragraphs) {
            val start = pos; val end = pos + p.length; offsets.add(start to end); pos = end + 2
        }
        paragraphOffsets = offsets

        // 自动开始播放（用户从书库点进来就是想读）
        binding.btnPlayPause.isEnabled = true; binding.btnPlayPause.alpha = 1f
        tts.setCurrentBookTitle(b.title.ifBlank { fallbackTitle })

        // 同步进度条：正在后台播放/暂停时以引擎真实位置为准，否则读取上次记忆的位置。
        val resumeAt = if (tts.currentBookId() == bookId && (tts.isPlaying() || tts.isPaused())) {
            tts.currentIndex().coerceIn(0, b.paragraphs.size - 1)
        } else {
            startIndexFor(b)
        }
        binding.seekParagraph.max = (b.paragraphs.size - 1).coerceAtLeast(0)
        binding.seekParagraph.progress = resumeAt
        binding.tvPosition.text = "${resumeAt + 1} / ${b.paragraphs.size}"

        if (tts.currentBookId() == bookId && tts.isPlaying()) {
            // 这本书正在后台播放，接上状态即可，不要从上次位置重新开始。
            pendingAutoPlay = false
            updatePlayState(true)
            binding.tvEngineHint.text = getString(R.string.engine_ready_short)
        } else {
            pendingAutoPlay = true
            togglePlay()
        }
    }

    /** 上次播放到的段落；没有记录或段落数变化时从第一段开始。 */
    private fun startIndexFor(b: Book): Int {
        if (b.paragraphs.isEmpty()) return 0
        val bid = bookId ?: return 0
        val meta = cache.metaOf(bid) ?: return 0
        if (meta.paragraphCount != b.paragraphs.size) return 0
        return meta.lastPlayedIndex.coerceIn(0, b.paragraphs.size - 1)
    }

    private fun togglePlay() {
        val b = book ?: run {
            // 没书：要么正文还在解析，要么是"继续播放"进来的 → 直接 resume
            if (tts.isPaused()) tts.resume()
            else if (!tts.isPlaying()) Toast.makeText(this, "书籍正在加载，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (b.paragraphs.isEmpty()) {
            pendingAutoPlay = false
            Toast.makeText(this, R.string.empty_content, Toast.LENGTH_SHORT).show()
            return
        }
        if (!ttsReady) {
            pendingAutoPlay = true
            binding.tvEngineHint.text = engineError ?: getString(R.string.engine_loading)
            tts.init()
            return
        }
        pendingAutoPlay = false
        val isCurrentBook = tts.currentBookId() == bookId
        if (isPlaying && isCurrentBook) tts.pause()
        else {
            ensureVolume()
            if (isCurrentBook && tts.isPaused()) tts.resume()
            else tts.speak(b.paragraphs, startIndexFor(b), bookId)
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

    private fun updatePlayState(speaking: Boolean) {
        isPlaying = speaking
        binding.btnPlayPause.setImageResource(if (speaking) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun showVoiceModeDialog() {
        val modes = arrayOf(TtsManager.Mode.MIXED, TtsManager.Mode.CHINESE)
        val labels = arrayOf(
            "${getString(R.string.mode_mixed)} · ${getString(R.string.mode_mixed_desc)}",
            "${getString(R.string.mode_chinese)} · ${getString(R.string.mode_chinese_desc)}"
        )
        val checked = modes.indexOf(tts.currentMode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(R.string.voice_mode_title)
            .setSingleChoiceItems(labels, checked) { d, w ->
                tts.setMode(modes[w])
                Toast.makeText(this, getString(R.string.mode_switched, getString(if (modes[w] == TtsManager.Mode.CHINESE) R.string.mode_chinese else R.string.mode_mixed)), Toast.LENGTH_SHORT).show()
                d.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
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

    private fun getDisplayName(uri: Uri): String =
        DocumentReaderFactory.displayName(this, uri)

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        intent?.getStringExtra(EXTRA_URI)?.let { loadDocument(Uri.parse(it), intent.getStringExtra(EXTRA_FALLBACK_TITLE) ?: "") }
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回时重新接管引擎回调
        setupTtsListener()
        updatePlayState(tts.isPlaying())
    }
}
