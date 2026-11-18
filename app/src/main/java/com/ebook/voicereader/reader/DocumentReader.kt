package com.ebook.voicereader.reader

import android.content.Context
import android.net.Uri
import com.ebook.voicereader.model.Book

/**
 * 文档读取器接口：根据 Uri 解析出文本段落
 */
interface DocumentReader {
    /** 该读取器支持的扩展名（小写，不含点） */
    fun supports(extension: String): Boolean

    /**
     * 读取文档。
     * @param onProgress 进度回调 (0..1)
     */
    suspend fun read(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Float) -> Unit = {}
    ): Book
}
