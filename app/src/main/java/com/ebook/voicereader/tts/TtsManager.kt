package com.ebook.voicereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 内置离线语音合成引擎。
 *
 * 完全自带引擎与模型，不依赖手机系统 TTS：
 * - 引擎：sherpa-onnx（Apache-2.0，ONNX Runtime）
 * - 模型：vits-piper-zh_CN-xiao_ya-medium-int8（中文女声，约 20MB，随 APK 打包）
 * - 合成结果通过 AudioTrack 直接播放
 *
 * 实现要点：
 * - 模型加载耗时较长，放在 IO 线程，完成后回调 onEngineReady
 * - 长段落按标点切成 80 字以内的短句逐句合成，首句出声更快
 * - 每次用户操作递增 session，用于丢弃过期的合成结果
 */
class TtsManager(private val context: Context) {

    interface Listener {
        fun onEngineReady(info: String)
        fun onEngineUnavailable(reason: String)
        fun onParagraphStart(index: Int)
        fun onSpeakingChanged(speaking: Boolean)
        fun onFinished()
        fun onError(message: String)
    }

    companion object {
        private const val MODEL_DIR = "tts/zh_CN-xiao_ya"
        private const val MAX_CHUNK = 80
        private const val SAMPLE_CHUNK = 4096
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: Listener? = null
    private var engine: OfflineTts? = null

    @Volatile
    private var ready = false

    @Volatile
    private var session = 0

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = true

    @Volatile
    private var speed = 1.0f

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var job: Job? = null

    private var paragraphs: List<String> = emptyList()
    private var currentIndex = 0

    fun setListener(l: Listener?) {
        listener = l
    }

    /** 加载引擎与模型（耗时操作，完成后回调 onEngineReady） */
    fun init() {
        scope.launch {
            try {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "$MODEL_DIR/model.onnx",
                            lexicon = "$MODEL_DIR/lexicon.txt",
                            tokens = "$MODEL_DIR/tokens.txt",
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                    // 数字、日期、电话号码的中文读法
                    ruleFsts = "$MODEL_DIR/date.fst,$MODEL_DIR/number.fst,$MODEL_DIR/phone.fst",
                    maxNumSentences = 1,
                    silenceScale = 0.2f,
                )
                val t = OfflineTts(assetManager = context.assets, config = config)
                engine = t
                ready = true
                notify { it.onEngineReady("内置离线语音 · 小雅（中文女声）") }
            } catch (e: Throwable) {
                ready = false
                notify { it.onEngineUnavailable("内置语音引擎加载失败：${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun isReady(): Boolean = ready

    fun isPaused(): Boolean = paused

    fun currentIndex(): Int = currentIndex

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2.0f)
    }

    /** 开始朗读整本书 */
    fun speak(all: List<String>, startIndex: Int = 0) {
        if (!ready) {
            notify { it.onError("语音引擎正在加载，请稍候再试") }
            return
        }
        if (all.isEmpty()) return

        cancelPlayback()
        session++
        paragraphs = all
        currentIndex = startIndex.coerceIn(0, all.size - 1)
        paused = false
        stopRequested = false
        notify { it.onSpeakingChanged(true) }
        startCurrent()
    }

    fun pause() {
        if (!ready) return
        paused = true
        runCatching { currentTrack?.pause() }
        notify { it.onSpeakingChanged(false) }
    }

    fun resume() {
        if (!ready || paragraphs.isEmpty() || !paused) return
        paused = false
        val track = currentTrack
        if (track != null) {
            runCatching { track.play() }
        }
        notify { it.onSpeakingChanged(true) }
    }

    fun stop() {
        if (!ready) return
        session++
        cancelPlayback()
        paused = false
        currentIndex = 0
        notify { it.onSpeakingChanged(false) }
    }

    fun previous() {
        if (paragraphs.isEmpty()) return
        jumpTo((currentIndex - 1).coerceAtLeast(0))
    }

    fun next() {
        if (paragraphs.isEmpty()) return
        jumpTo((currentIndex + 1).coerceAtMost(paragraphs.size - 1))
    }

    fun release() {
        session++
        cancelPlayback()
        scope.launch { runCatching { engine?.release() } }
    }

    // ------------------------------------------------------------------

    private fun jumpTo(index: Int) {
        if (!ready) return
        val wasPlaying = !paused
        session++
        cancelPlayback()
        currentIndex = index
        if (wasPlaying) {
            stopRequested = false
            notify { it.onSpeakingChanged(true) }
            startCurrent()
        } else {
            notify { it.onParagraphStart(index) }
        }
    }

    private fun startCurrent() {
        if (currentIndex !in paragraphs.indices) {
            finishAll()
            return
        }
        val mySession = session
        job = scope.launch {
            try {
                val t = engine ?: return@launch
                val text = paragraphs[currentIndex]
                if (text.isBlank()) {
                    advance(mySession)
                    return@launch
                }
                notify { it.onParagraphStart(currentIndex) }

                for (chunk in splitChunks(text)) {
                    if (mySession != session || stopRequested) return@launch
                    val audio = t.generate(chunk, 0, speed)
                    if (mySession != session || stopRequested) return@launch
                    if (audio.samples.isEmpty()) continue
                    playSamples(audio.samples, audio.sampleRate, mySession)
                    if (mySession != session || stopRequested) return@launch
                }
                advance(mySession)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notify { it.onError("朗读出错：${e.message ?: e.javaClass.simpleName}") }
                notify { it.onSpeakingChanged(false) }
            }
        }
    }

    private fun advance(mySession: Int) {
        if (mySession != session) return
        currentIndex++
        if (currentIndex < paragraphs.size) {
            startCurrent()
        } else {
            finishAll()
        }
    }

    private fun finishAll() {
        notify { it.onSpeakingChanged(false) }
        notify { it.onFinished() }
    }

    /** 把文本切成短句，首句更快出声 */
    private fun splitChunks(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[。！？；!?;\\n])"))
        val out = mutableListOf<String>()
        var buf = StringBuilder()
        for (raw in sentences) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.length > MAX_CHUNK) {
                if (buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf = StringBuilder()
                }
                var i = 0
                while (i < s.length) {
                    out.add(s.substring(i, min(i + MAX_CHUNK, s.length)))
                    i += MAX_CHUNK
                }
                continue
            }
            if (buf.length + s.length > MAX_CHUNK && buf.isNotEmpty()) {
                out.add(buf.toString())
                buf = StringBuilder()
            }
            buf.append(s)
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out.ifEmpty { listOf(text) }
    }

    /** 将合成出的 PCM 浮点数据写入 AudioTrack 播放 */
    private suspend fun playSamples(samples: FloatArray, sampleRate: Int, mySession: Int) {
        // 暂停状态下先等待，避免恢复时错过开头
        while (paused && mySession == session && !stopRequested) delay(50)
        if (mySession != session || stopRequested) return

        val total = samples.size
        val shorts = ShortArray(total)
        for (i in 0 until total) {
            shorts[i] = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, 16384)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentTrack = track
        try {
            track.play()
            var offset = 0
            while (offset < total && mySession == session && !stopRequested) {
                if (paused) {
                    delay(50)
                    continue
                }
                val n = track.write(
                    shorts, offset, min(SAMPLE_CHUNK, total - offset), AudioTrack.WRITE_BLOCKING
                )
                if (n <= 0) break
                offset += n
            }
            // 等待缓冲区播完
            while (mySession == session && !stopRequested &&
                track.playbackHeadPosition < total
            ) {
                delay(50)
            }
        } catch (e: IllegalStateException) {
            // 被停止时 track 可能已释放
        } finally {
            if (currentTrack === track) currentTrack = null
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun cancelPlayback() {
        stopRequested = true
        job?.cancel()
        job = null
        val track = currentTrack
        currentTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun notify(block: (Listener) -> Unit) {
        val l = listener ?: return
        mainHandler.post { block(l) }
    }
}
