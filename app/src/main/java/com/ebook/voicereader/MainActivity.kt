package com.ebook.voicereader

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.ebook.voicereader.databinding.ActivityMainBinding
import com.ebook.voicereader.model.Book
import com.ebook.voicereader.ocr.OcrEngine
import com.ebook.voicereader.reader.DocumentReaderFactory
import com.ebook.voicereader.tts.TtsManager
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
        ttsManager = TtsManager(this)
        ttsManager.setListener(object : TtsManager.Listener {
            override fun onInitSuccess() {
                ttsReady = true
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.app_name, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onInitFailed() {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.tts_not_ready, Toast.LENGTH_LONG).show()
                }
            }

            override fun onParagraphStart(index: Int) {
                runOnUiThread { highlightParagraph(index) }
            }

            override fun onParagraphDone(index: Int) {}

            override fun onSpeakingChanged(speaking: Boolean) {
                runOnUiThread {
                    isPlaying = speaking
                    binding.btnPlayPause.text = if (speaking) {
                        getString(R.string.pause)
                    } else {
                        getString(R.string.play)
                    }
                }
            }
        })
        ttsManager.init()

        setupViews()

        // 支持从外部应用打开文件
        handleIncomingIntent(intent)
    }

    private fun setupViews() {
        binding.btnOpenFile.setOnClickListener {
            val mimeTypes = arrayOf(
                "text/plain", "text/markdown",
                "application/pdf",
                "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword",
                "image/*",
                "*/*"
            )
            openDocument.launch(mimeTypes)
        }

        binding.btnPlayPause.setOnClickListener {
            val book = currentBook
            if (book == null || book.paragraphs.isEmpty()) {
                Toast.makeText(this, R.string.empty_content, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ttsReady) {
                Toast.makeText(this, R.string.tts_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isPlaying) {
                ttsManager.pause()
            } else if (ttsManager.isPaused()) {
                ttsManager.resume()
            } else {
                ttsManager.speak(book.paragraphs, ttsManager.currentIndex())
            }
        }

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

        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.loading)
        binding.tvContent.text = ""

        lifecycleScope.launch {
            try {
                val book = reader.read(this@MainActivity, uri, displayName) { progress ->
                    runOnUiThread {
                        binding.tvStatus.text =
                            "${getString(R.string.loading)} ${(progress * 100).toInt()}%"
                    }
                }
                showBook(book)
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.file_open_failed)
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
            binding.tvTitle.text = book.title
            binding.tvStatus.text = buildString {
                append("格式：").append(book.sourceFormat.uppercase())
                append("  |  段落：").append(book.paragraphs.size)
                if (book.usedOcr) append("  |  ").append(getString(R.string.ocr_hint))
            }

            val fullText = book.paragraphs.joinToString("\n\n")
            val ssb = SpannableStringBuilder(fullText)
            binding.tvContent.text = ssb

            // 预计算每段在全文中的偏移
            val offsets = mutableListOf<Pair<Int, Int>>()
            var pos = 0
            for ((i, p) in book.paragraphs.withIndex()) {
                val start = pos
                val end = pos + p.length
                offsets.add(start to end)
                pos = end + 2 // "\n\n"
            }
            paragraphOffsets = offsets

            binding.progressBar.visibility = View.GONE
        }
    }

    /** 高亮当前朗读段落 */
    private fun highlightParagraph(index: Int) {
        val ssb = SpannableStringBuilder(binding.tvContent.text)
        // 移除上一次高亮
        if (highlightedSpan != null) {
            ssb.removeSpan(highlightedSpan)
            highlightedSpan = null
        }
        if (index in paragraphOffsets.indices) {
            val (start, end) = paragraphOffsets[index]
            val span = BackgroundColorSpan(0x33FF6F00)
            ssb.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            highlightedSpan = span
            binding.tvContent.text = ssb
            // 滚动到当前段落
            scrollToParagraph(start)
        }
    }

    private fun clearHighlight() {
        if (highlightedSpan != null) {
            val ssb = SpannableStringBuilder(binding.tvContent.text)
            ssb.removeSpan(highlightedSpan)
            highlightedSpan = null
            binding.tvContent.text = ssb
        }
    }

    private fun scrollToParagraph(charOffset: Int) {
        val layout = binding.tvContent.layout ?: return
        val line = layout.getLineForOffset(charOffset)
        val y = layout.getLineTop(line)
        binding.scrollView.smoothScrollTo(0, y - binding.scrollView.height / 3)
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
