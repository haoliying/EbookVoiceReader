package com.ebook.voicereader.reader

import android.content.Context
import android.net.Uri
import com.ebook.voicereader.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/**
 * DOCX 读取器。
 * DOCX 本质是 ZIP，正文在 word/document.xml 中。
 * 解析 <w:p>（段落）与 <w:t>（文本节点）提取纯文本。
 */
class DocxReader : DocumentReader {

    override fun supports(extension: String): Boolean =
        extension in setOf("docx", "doc")

    override suspend fun read(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Float) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        val xml = readDocumentXml(context, uri)
        onProgress(0.6f)
        val paragraphs = parseParagraphs(xml)
        onProgress(1f)
        Book(
            title = displayName,
            paragraphs = paragraphs,
            sourceFormat = "docx"
        )
    }

    private fun readDocumentXml(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        return zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return ""
    }

    private fun parseParagraphs(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        // 按 <w:p> 分段
        val paragraphRegex = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
        val textRegex = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        val tabRegex = Regex("<w:tab\\s*/?>")
        val brRegex = Regex("<w:br\\s*/?>")

        paragraphRegex.findAll(xml).forEach { match ->
            var p = match.value
            // 制表符与换行替换
            p = tabRegex.replace(p, "\t")
            p = brRegex.replace(p, "\n")
            val texts = textRegex.findAll(p).map { it.groupValues[1] }.joinToString("")
            val decoded = decodeXmlEntities(texts).trim()
            if (decoded.isNotBlank()) {
                // 段内过长再切分
                if (decoded.length <= 300) {
                    result.add(decoded)
                } else {
                    val sentences = decoded.split(Regex("(?<=[。！？!?\\.])")).map { it.trim() }
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
        }
        return result
    }

    private fun decodeXmlEntities(s: String): String {
        return s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
