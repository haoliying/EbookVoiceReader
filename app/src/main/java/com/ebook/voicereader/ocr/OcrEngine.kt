package com.ebook.voicereader.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 引擎：基于 Google ML Kit 文本识别。
 *
 * 说明：ML Kit 文本识别模型在首次使用时自动下载，下载完成后完全离线可用。
 * 同时使用中文与拉丁识别器，覆盖中英文混排场景。
 */
class OcrEngine {

    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 识别单张图片中的文字。
     * 先尝试中文识别器，若结果为空再尝试拉丁识别器。
     */
    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        chineseRecognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text
                if (text.isNotBlank()) {
                    cont.resume(text)
                } else {
                    // 中文识别无结果，尝试拉丁
                    latinRecognizer.process(image)
                        .addOnSuccessListener { latin ->
                            cont.resume(latin.text)
                        }
                        .addOnFailureListener { e ->
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                }
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    fun close() {
        runCatching { chineseRecognizer.close() }
        runCatching { latinRecognizer.close() }
    }
}
