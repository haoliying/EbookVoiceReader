package com.ebook.voicereader.reader

import android.content.Context
import android.net.Uri
import com.ebook.voicereader.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 纯文本读取器（支持 TXT / MD）
 * 自动尝试 UTF-8 / GBK 编码
 */
class TextReader : DocumentReader {

    override fun supports(extension: String): Boolean =
        extension in setOf("txt", "md", "markdown", "text", "log")

    override suspend fun read(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Float) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        val text = readText(context, uri)
        onProgress(1f)
        val paragraphs = splitParagraphs(text)
        val fmt = if (displayName.endsWith(".md", true) ||
            displayName.endsWith(".markdown", true)
        ) "md" else "txt"
        Book(
            title = displayName,
            paragraphs = paragraphs,
            sourceFormat = fmt
        )
    }

    private fun readText(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // 先尝试 UTF-8
            val bytes = input.readBytes()
            val utf8 = try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
            // UTF-8 解码出现大量替换字符则尝试 GBK
            val replacementCount = utf8.count { it == '\uFFFD' }
            return if (replacementCount > bytes.size / 50) {
                String(bytes, charset("GBK"))
            } else {
                utf8
            }
        }
        return ""
    }

    /**
     * 将文本切分为段落。
     * 规则：空行分段；段内过长按句号/换行再切，便于朗读。
     */
    private fun splitParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // 统一换行
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()
        for (block in blocks) {
            // 单段过长（>300字）按句切分
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
