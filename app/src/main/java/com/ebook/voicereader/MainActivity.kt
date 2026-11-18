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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private val readers by lazy { DocumentReaderFactory.create(ocrEngine) }

    private var currentBook: Book? = null
    private var paragraphOffsets: List<Pair<Int, Int>> = emptyList()
    private var highlightedSpan: BackgroundColorSpan? = null

    private var ttsReady = false
    private var isPlaying = false
    private var engineError: String? = null

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { loadDocument(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ocrEngine = OcrEngine()
        binding.tvHeaderHint.text = getString(R.string.engine_loading)
        setupTts()
        setupViews()

        handleIncomingIntent(intent)
    }

    private fun setupTts() {
        ttsManager = TtsManager(this)
        ttsManager.setListener(object : TtsManager.Listener {
            override fun onEngineReady(info: String) {
                ttsReady = true
                engineError = null
                runOnUiThread {
                    binding.tvHeaderHint.text = info
                }
            }

            override fun onEngineUnavailable(reason: String) {
                ttsReady = false
                engineError = reason
                runOnUiThread {
                    binding.tvHeaderHint.text = reason
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                }
            }

            override fun onParagraphStart(index: Int) {
                runOnUiThread { highlightParagraph(index) }
            }

            override fun onSpeakingChanged(speaking: Boolean) {
                runOnUiThread { updatePlayState(speaking) }
            }

            override fun onFinished() {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.finished, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        })
        ttsManager.init()
    }

    private fun setupViews() {
        binding.btnOpenFile.setOnClickListener {
            openDocument.launch(
                arrayOf(
                    "text/plain", "text/markdown",
                    "application/pdf",
                    "application/epub+zip",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/msword",
                    "image/*",
                    "*/*"
                )
            )
        }

        binding.btnPlayPause.setOnClickListener { togglePlay() }

        binding.btnStop.setOnClickListener {
            ttsManager.stop()
            clearHighlight()
        }

        binding.btnPrev.setOnClickListener { ttsManager.previous() }
        binding.btnNext.setOnClickListener { ttsManager.next() }

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

    private fun togglePlay() {
        val book = currentBook
        if (book == null || book.paragraphs.isEmpty()) {
            Toast.makeText(this, R.string.empty_content, Toast.LENGTH_SHORT).show()
            return
        }
        if (!ttsReady) {
            Toast.makeText(
                this,
                engineError ?: getString(R.string.tts_not_ready),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (isPlaying) {
            ttsManager.pause()
        } else {
            ensureVolume()
            if (ttsManager.isPaused()) {
                ttsManager.resume()
            } else {
                ttsManager.speak(book.paragraphs, ttsManager.currentIndex())
            }
        }
    }

    /** 媒体音量为 0 时提示用户，避免“点了没声音” */
    private fun ensureVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.volume_zero_title)
            .setMessage(R.string.volume_zero_msg)
            .setPositiveButton(R.string.raise_volume) { _, _ ->
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updatePlayState(speaking: Boolean) {
        isPlaying = speaking
        binding.btnPlayPause.setImageResource(
            if (speaking) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.contentDescription =
            getString(if (speaking) R.string.pause else R.string.play)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { loadDocument(it) }
        }
    }

    private fun loadDocument(uri: Uri) {
        val displayName = getDisplayName(uri)
        val ext = DocumentReaderFactory.extensionOf(displayName)
        val reader = readers.firstOrNull { it.supports(ext) }
        if (reader == null) {
            Toast.makeText(this, R.string.file_open_failed, Toast.LENGTH_LONG).show()
            return
        }

        ttsManager.stop()
        clearHighlight()
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoading.text = getString(R.string.loading)
        binding.emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val book = reader.read(this@MainActivity, uri, displayName) { progress ->
                    runOnUiThread {
                        binding.tvLoading.text =
                            "${getString(R.string.loading)} ${(progress * 100).toInt()}%"
                    }
                }
                showBook(book)
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE
                    Toast.makeText(
                        this@MainActivity,
                        "${getString(R.string.file_open_failed)}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
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
                append(book.sourceFormat.uppercase())
                append(" · ")
                append(book.paragraphs.size)
                append(" 段")
                if (book.usedOcr) {
                    append(" · ")
                    append(getString(R.string.ocr_hint))
                }
            }

            val fullText = book.paragraphs.joinToString("\n\n")
            binding.tvContent.text = SpannableStringBuilder(fullText)
            binding.scrollView.scrollTo(0, 0)

            val offsets = mutableListOf<Pair<Int, Int>>()
            var pos = 0
            for (p in book.paragraphs) {
                val start = pos
                val end = pos + p.length
                offsets.add(start to end)
                pos = end + 2
            }
            paragraphOffsets = offsets

            if (book.paragraphs.isEmpty()) {
                binding.tvContent.text = getString(R.string.no_text_hint)
            }
        }
    }

    /** 高亮当前朗读段落 */
    private fun highlightParagraph(index: Int) {
        val ssb = SpannableStringBuilder(binding.tvContent.text)
        highlightedSpan?.let { ssb.removeSpan(it) }
        highlightedSpan = null

        if (index in paragraphOffsets.indices) {
            val (start, end) = paragraphOffsets[index]
            if (end <= ssb.length) {
                val span = BackgroundColorSpan(
                    ContextCompat.getColor(this, R.color.highlight)
                )
                ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                highlightedSpan = span
                binding.tvContent.text = ssb
                scrollToParagraph(start)
            }
        }
    }

    private fun clearHighlight() {
        highlightedSpan?.let {
            val ssb = SpannableStringBuilder(binding.tvContent.text)
            ssb.removeSpan(it)
            highlightedSpan = null
            binding.tvContent.text = ssb
        }
    }

    private fun scrollToParagraph(charOffset: Int) {
        binding.tvContent.post {
            val layout = binding.tvContent.layout ?: return@post
            if (charOffset > binding.tvContent.text.length) return@post
            val line = layout.getLineForOffset(charOffset)
            val y = layout.getLineTop(line)
            binding.scrollView.smoothScrollTo(0, (y - binding.scrollView.height / 3).coerceAtLeast(0))
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "document"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
        return name
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.release()
        ocrEngine.close()
    }
}
