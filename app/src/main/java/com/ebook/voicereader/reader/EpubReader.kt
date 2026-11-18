package com.ebook.voicereader.reader

import android.content.Context
import android.net.Uri
import com.ebook.voicereader.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.zip.ZipInputStream

/**
 * EPUB 读取器。
 * EPUB 是 ZIP：
 * 1) META-INF/container.xml 指明 OPF 文件位置；
 * 2) OPF 的 <spine> 定义阅读顺序，<manifest> 给出每个 id 对应的 href；
 * 3) 逐个读取 spine 中的 XHTML，提取纯文本。
 */
class EpubReader : DocumentReader {

    override fun supports(extension: String): Boolean = extension == "epub"

    override suspend fun read(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Float) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        val entries = readZipEntries(context, uri)
        onProgress(0.2f)

        val opfPath = findOpfPath(entries["META-INF/container.xml"] ?: "")
        if (opfPath.isBlank()) {
            return@withContext Book(displayName, emptyList(), "epub")
        }
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfContent = entries[opfPath] ?: ""

        val (title, spineIds) = parseOpf(opfContent)
        onProgress(0.4f)

        // manifest: id -> href
        val manifest = parseManifest(opfContent)

        val paragraphs = mutableListOf<String>()
        val total = spineIds.size
        spineIds.forEachIndexed { index, id ->
            val href = manifest[id] ?: return@forEachIndexed
            val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
            val xhtml = entries[fullPath] ?: entries[href] ?: ""
            val text = extractTextFromXhtml(xhtml)
            paragraphs.addAll(splitParagraphs(text))
            onProgress(0.4f + (index + 1).toFloat() / total * 0.6f)
        }

        Book(
            title = title.ifBlank { displayName },
            paragraphs = paragraphs,
            sourceFormat = "epub"
        )
    }

    /** 读取 ZIP 中所有文本型条目到内存（EPUB 通常不大） */
    private fun readZipEntries(context: Context, uri: Uri): Map<String, String> {
        val map = mutableMapOf<String, String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (name.endsWith(".xhtml", true) ||
                            name.endsWith(".html", true) ||
                            name.endsWith(".htm", true) ||
                            name.endsWith(".opf", true) ||
                            name.endsWith(".xml", true) ||
                            name.endsWith(".ncx", true)
                        ) {
                            val bytes = zip.readBytes()
                            // 尝试 UTF-8，失败回退 GBK
                            val str = try {
                                String(bytes, Charsets.UTF_8)
                            } catch (e: Exception) {
                                String(bytes, charset("GBK"))
                            }
                            map[name] = str
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return map
    }

    private fun findOpfPath(containerXml: String): String {
        val match = Regex("full-path=\"([^\"]+)\"").find(containerXml)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun parseOpf(opf: String): Pair<String, List<String>> {
        val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.trim() ?: ""
        val spineIds = Regex("<itemref[^>]*idref=\"([^\"]+)\"")
            .findAll(opf)
            .map { it.groupValues[1] }
            .toList()
        return title to spineIds
    }

    private fun parseManifest(opf: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("<item[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"")
            .findAll(opf)
            .forEach { map[it.groupValues[1]] = it.groupValues[2] }
        // 也处理 href 在 id 前面的情况
        Regex("<item[^>]*href=\"([^\"]+)\"[^>]*id=\"([^\"]+)\"")
            .findAll(opf)
            .forEach { map[it.groupValues[2]] = it.groupValues[1] }
        return map
    }

    private fun extractTextFromXhtml(xhtml: String): String {
        if (xhtml.isBlank()) return ""
        return try {
            val doc = Jsoup.parse(xhtml, "", org.jsoup.parser.Parser.xmlParser())
            // 移除 script/style
            doc.select("script, style").remove()
            doc.text()
        } catch (e: Exception) {
            // 回退：用正则去标签
            xhtml.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private fun splitParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val blocks = text.split(Regex("\\n\\s*\\n"))
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
