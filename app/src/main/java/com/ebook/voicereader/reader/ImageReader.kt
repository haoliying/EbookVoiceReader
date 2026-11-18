package com.ebook.voicereader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.ebook.voicereader.model.Book
import com.ebook.voicereader.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片读取器：直接对图片进行 OCR 识别。
 * 支持 png / jpg / jpeg / bmp / webp 等常见图片格式。
 */
class ImageReader(private val ocrEngine: OcrEngine) : DocumentReader {

    override fun supports(extension: String): Boolean =
        extension in setOf("png", "jpg", "jpeg", "bmp", "webp", "gif")

    override suspend fun read(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Float) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        onProgress(0.2f)
        val bitmap = decodeSampledBitmap(context, uri)
        onProgress(0.5f)
        val text = if (bitmap != null) ocrEngine.recognize(bitmap) else ""
        bitmap?.recycle()
        onProgress(1f)
        val paragraphs = splitParagraphs(text)
        Book(
            title = displayName,
            paragraphs = paragraphs,
            sourceFormat = "image",
            usedOcr = true
        )
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 先读取尺寸
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                val maxDim = 1500
                var sample = 1
                while ((options.outWidth / sample) > maxDim || (options.outHeight / sample) > maxDim) {
                    sample *= 2
                }
                // 再次读取实际位图
                context.contentResolver.openInputStream(uri)?.use { input2 ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(input2, null, opts)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun splitParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return blocks.ifEmpty { listOf(normalized.trim()) }
    }
}
