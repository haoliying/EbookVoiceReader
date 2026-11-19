package com.ebook.voicereader.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ebook.voicereader.ocr.OcrEngine

/**
 * 根据扩展名选择对应的文档读取器。
 */
object DocumentReaderFactory {

    fun create(ocrEngine: OcrEngine): List<DocumentReader> = listOf(
        TextReader(),
        PdfReader(ocrEngine),
        DocxReader(),
        EpubReader(),
        ImageReader(ocrEngine)
    )

    /**
     * 从文件名中提取扩展名（小写）。
     */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot >= 0 && dot < fileName.length - 1) {
            fileName.substring(dot + 1).lowercase()
        } else {
            ""
        }
    }

    /** 文件管理器可能不给 DISPLAY_NAME，查询失败也不能让导入流程崩溃。 */
    fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.trim().orEmpty()
        if (queried.isNotEmpty()) return queried

        val fromPath = runCatching {
            Uri.decode(uri.lastPathSegment.orEmpty())
                .substringAfterLast('/')
                .substringAfterLast(':')
                .trim()
        }.getOrDefault("")
        return fromPath.ifEmpty { "未命名书籍" }
    }

    /** 扩展名缺失或被改成 document 时，用 Uri 路径和 MIME 类型兜底。 */
    fun extensionOf(context: Context, uri: Uri, displayName: String): String {
        val fromName = extensionOf(displayName)
        if (fromName in supportedExtensions()) return fromName

        val pathName = runCatching { Uri.decode(uri.lastPathSegment.orEmpty()) }.getOrDefault("")
        val fromPath = extensionOf(pathName)
        if (fromPath in supportedExtensions()) return fromPath

        val mime = runCatching {
            context.contentResolver.getType(uri)?.lowercase().orEmpty()
        }.getOrDefault("")
        return when (mime) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/msword" -> "doc"
            "text/markdown", "text/x-markdown" -> "md"
            "text/plain" -> "txt"
            else -> if (mime.startsWith("image/")) "jpg" else fromName
        }
    }

    /**
     * 获取所有支持的扩展名，供文件选择器过滤使用。
     */
    fun supportedExtensions(): Set<String> = setOf(
        "txt", "md", "markdown", "text", "log",
        "pdf",
        "docx", "doc",
        "epub",
        "png", "jpg", "jpeg", "bmp", "webp", "gif"
    )
}
