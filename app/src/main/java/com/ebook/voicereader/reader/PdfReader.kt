package com.ebook.voicereader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ebook.voicereader.model.Book
import com.ebook.voicereader.ocr.OcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PDF 读取器。
 * 1) 先用 PdfBox 提取文本层；
 * 2) 若文本极少（扫描版 PDF），则用 PdfRenderer 逐页渲染为位图，再交给 OCR 识别。
 */
class PdfReader(private val ocrEngine: OcrEngine) : DocumentReader {

    companion object {
        private val pdfBoxReady = AtomicBoolean(false)

        /** PdfBox 只在真正读 PDF 时初始化，避免拖慢或拖垮应用启动。 */
        private fun ensurePdfBox(context: Context) {
            if (pdfBoxReady.compareAndSet(false, true)) {
                PDFBoxResourceLoader.init(context.applicationContext)
            }
        }
    }

    override fun supports(extension: String): Boolean = extension == "pdf"

    override suspend fun read(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Float) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        // 先尝试文本提取
        var paragraphs = extractText(context, uri)
        var usedOcr = false
        onProgress(0.3f)

        // 文本层为空或过少，走 OCR
        val totalChars = paragraphs.sumOf { it.length }
        if (totalChars < 50) {
            val ocrText = extractByOcr(context, uri) { p -> onProgress(0.3f + p * 0.7f) }
            paragraphs = splitParagraphs(ocrText)
            usedOcr = true
        } else {
            onProgress(1f)
        }

        Book(
            title = displayName,
            paragraphs = paragraphs,
            sourceFormat = "pdf",
            usedOcr = usedOcr
        )
    }

    private fun extractText(context: Context, uri: Uri): List<String> {
        return try {
            ensurePdfBox(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    val stripper = PDFTextStripper()
                    stripper.sortByPosition = true
                    val text = stripper.getText(doc)
                    splitParagraphs(text)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 用 PdfRenderer 渲染每一页为位图并 OCR。
     */
    private suspend fun extractByOcr(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit
    ): String {
        // 将 Uri 对应的内容复制到临时文件，PdfRenderer 需要 ParcelFileDescriptor
        val tempFile = File(context.cacheDir, "ocr_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }

        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val result = StringBuilder()
        PdfRenderer(pfd).use { renderer ->
            val pageCount = renderer.pageCount
            for (i in 0 until pageCount) {
                renderer.openPage(i).use { page ->
                    // 1280px 宽对书页文字识别足够，像素量比 1500px 少约 27%，
                    // OCR 单页耗时明显下降；RGB_565 还能再省一半渲染内存。
                    val width = page.width.coerceAtMost(1280)
                    val height = (page.height * (width.toFloat() / page.width)).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val text = ocrEngine.recognize(bitmap)
                    if (text.isNotBlank()) {
                        result.append(text).append("\n\n")
                    }
                    bitmap.recycle()
                }
                onProgress((i + 1).toFloat() / pageCount)
            }
        }
        pfd.close()
        tempFile.delete()
        return result.toString()
    }

    private fun splitParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        for (block in blocks) {
            if (block.length <= 300) {
                result.add(block)
            } else {
                val sentences = block.split(Regex("(?<=[。！？!?\\.])")).map { it.trim() }
                    .filter { it.isNotBlank() }
                var buf = StringBuilder()
                for (s in sentences) {
                    if (buf.length + s.length > 300 && buf.isNotEmpty()) {
                        result.add(buf.toString().trim())
                        buf = StringBuilder()
                    }
                    buf.append(s)
                }
                if (buf.isNotEmpty()) result.add(buf.toString().trim())
            }
        }
        return result
    }
}
