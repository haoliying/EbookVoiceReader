package com.ebook.voicereader.reader

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
