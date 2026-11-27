package com.ebook.voicereader.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.io.IOException

/**
 * 用系统自带的 MediaCodec 把 16-bit 单声道 PCM 编成 AAC-LC，
 * 再用 MediaMuxer 封装成 .m4a。不需要任何第三方库，Android 5.0+ 即可。
 *
 * 用法：encode() 反复喂 PCM → finish() 写入 EOS 并收尾 → close()。
 */
class AacEncoder private constructor(
    private val sampleRate: Int,
    private val muxer: MediaMuxer,
    bitRate: Int,
) {
    /** 直接写入文件描述符（Android 8.0+） */
    constructor(sampleRate: Int, muxerFd: FileDescriptor, bitRate: Int = 64_000) : this(
        sampleRate,
        MediaMuxer(muxerFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        bitRate,
    )

    /** 写入文件路径（用于低版本先落临时文件再复制） */
    constructor(sampleRate: Int, outputPath: String, bitRate: Int = 64_000) : this(
        sampleRate,
        MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        bitRate,
    )

    private val codec: MediaCodec
    private var trackIndex = -1
    private var samplesSubmitted = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            // 明确告诉编码器输入是 16-bit PCM，否则部分 ROM 会当成 float 处理
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** 喂入一段 PCM 数据（little-endian 16-bit mono） */
    fun encode(pcm: ByteArray, length: Int) {
        if (length <= 0) {
            drainOutput(endOfStream = false)
            return
        }
        var offset = 0
        var remaining = length
        while (remaining > 0) {
            val inputIndex = codec.dequeueInputBuffer(20_000)
            if (inputIndex < 0) {
                drainOutput(endOfStream = false)
                continue
            }
            val inputBuffer = codec.getInputBuffer(inputIndex)
                ?: throw IOException("AAC 编码器输入缓冲区不可用")
            inputBuffer.clear()
            val chunk = minOf(remaining, inputBuffer.capacity())
            inputBuffer.put(pcm, offset, chunk)
            val samples = chunk / 2
            val pts = samplesSubmitted * 1_000_000L / sampleRate
            codec.queueInputBuffer(inputIndex, 0, chunk, pts, 0)
            samplesSubmitted += samples
            offset += chunk
            remaining -= chunk
            drainOutput(endOfStream = false)
        }
    }

    /** 全部 PCM 已喂完，写入结束标记并把编码器排空 */
    fun finish() {
        var sent = false
        var attempts = 0
        while (!sent && attempts < 200) {
            val inputIndex = codec.dequeueInputBuffer(20_000)
            if (inputIndex >= 0) {
                val pts = samplesSubmitted * 1_000_000L / sampleRate
                codec.queueInputBuffer(
                    inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                sent = true
            } else {
                drainOutput(endOfStream = false)
                attempts++
            }
        }
        drainOutput(endOfStream = true)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drainOutput(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 20_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // EOS 阶段允许短暂空转，但必须有上限，避免编码器异常时死循环
                    if (++idleRounds > 200) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex >= 0) return
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    idleRounds = 0
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        // codec config（AAC 头）由 muxer 通过 outputFormat 写入，不作为采样写出
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && trackIndex >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }
}
