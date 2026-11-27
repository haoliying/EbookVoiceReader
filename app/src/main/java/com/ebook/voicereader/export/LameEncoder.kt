package com.ebook.voicereader.export

import java.io.IOException

/**
 * LAME 的 Kotlin 封装：把 16-bit 小端单声道 PCM 编成 MP3。
 * 本地库在首次使用时加载，加载失败会返回不可用而不是崩溃。
 */
class LameEncoder(sampleRate: Int, bitRateKbps: Int = 64) {
    private var handle: Long = 0L

    init {
        if (!isAvailable()) throw IOException("设备不支持 MP3 编码")
        handle = nativeInit(sampleRate, 1, sampleRate, bitRateKbps)
        if (handle == 0L) throw IOException("MP3 编码器初始化失败")
    }

    /** 编码一段 PCM，返回需要写入文件的 MP3 字节（可能为空） */
    fun encode(pcm: ByteArray, length: Int): ByteArray {
        if (handle == 0L || length <= 0) return ByteArray(0)
        return nativeEncode(handle, pcm, length)
            ?: throw IOException("MP3 编码失败")
    }

    /** 冲刷编码器尾部数据 */
    fun flush(): ByteArray {
        if (handle == 0L) return ByteArray(0)
        return nativeFlush(handle) ?: ByteArray(0)
    }

    fun close() {
        if (handle != 0L) {
            runCatching { nativeClose(handle) }
            handle = 0L
        }
    }

    private external fun nativeInit(inRate: Int, channels: Int, outRate: Int, kbps: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ByteArray, length: Int): ByteArray?
    private external fun nativeFlush(handle: Long): ByteArray?
    private external fun nativeClose(handle: Long)

    companion object {
        @Volatile
        private var loaded: Boolean? = null

        /** 本地库是否可用（某些 ABI 未打包时返回 false） */
        fun isAvailable(): Boolean {
            loaded?.let { return it }
            val ok = runCatching {
                System.loadLibrary("mp3encoder")
                true
            }.getOrDefault(false)
            loaded = ok
            return ok
        }
    }
}
