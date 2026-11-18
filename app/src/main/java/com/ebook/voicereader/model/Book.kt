package com.ebook.voicereader.model

/**
 * 解析后的电子书内容
 * @param title 书名
 * @param paragraphs 段落列表（每段为一个朗读单元）
 * @param sourceFormat 原始格式（txt/md/pdf/docx/epub/image）
 * @param usedOcr 是否经过 OCR 识别
 */
data class Book(
    val title: String,
    val paragraphs: List<String>,
    val sourceFormat: String,
    val usedOcr: Boolean = false
) {
    val fullText: String get() = paragraphs.joinToString("\n\n")
}
