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
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 内置离线语音合成引擎。
 *
 * 完全不依赖手机系统 TTS，两个引擎都随 APK 打包：
 * - 引擎：sherpa-onnx（Apache-2.0，ONNX Runtime）
 * - 中文女声：vits-piper-zh_CN-xiao_ya-medium-int8（约 20MB）
 * - 中英双语女声：vits-melo-tts-zh_en（约 170MB，中英文混读，英文按单词朗读）
 *
 * 两种语音模式：
 * - [Mode.MIXED]：全部文本交给双语引擎，中文、英文自然混读
 * - [Mode.CHINESE]：中文段用「小雅」，英文段自动改用双语引擎，避免英文被忽略
 *
 * 实现要点：
 * - 模型加载耗时较长，放在 IO 线程，完成后回调 onEngineReady
 * - 长段落按标点切成 80 字以内的短句逐句合成，首句出声更快
 * - 每次用户操作递增 session，用于丢弃过期的合成结果
 */
class TtsManager(private val context: Context) {

    enum class Mode {
        /** 中英混读：双语女声，英文按整词朗读 */
        MIXED,

        /** 中文女声：中文用「小雅」，英文自动改用双语声 */
        CHINESE,
    }

    interface Listener {
        fun onEngineReady(info: String)
        fun onEngineUnavailable(reason: String)
        fun onParagraphStart(index: Int)
        fun onSpeakingChanged(speaking: Boolean)
        fun onFinished()
        fun onError(message: String)
    }

    companion object {
        private const val DIR_ZH = "tts/zh_CN-xiao_ya"
        private const val DIR_MIXED = "tts/zh_en-melo"
        private const val MAX_CHUNK = 80
        private const val SAMPLE_CHUNK = 4096

        /** 模型输出音量偏小，统一把峰值归一到该电平 */
        private const val TARGET_PEAK = 0.9f
        private const val MAX_GAIN = 6f
    }

    /** 已合成并做过峰值归一化的 PCM 片段，随时可送 AudioTrack 播放 */
    private class PreparedChunk(val samples: ShortArray, val sampleRate: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: Listener? = null

    private var zhEngine: OfflineTts? = null
    private var mixedEngine: OfflineTts? = null

    @Volatile
    private var ready = false

    @Volatile
    private var mode = Mode.MIXED

    @Volatile
    private var session = 0

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = true

    @Volatile
    private var speaking = false

    @Volatile
    private var speed = 1.0f

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var job: Job? = null

    /** 预合成好的段落音频：paragraph index → 片段列表 */
    private val precomputed = LinkedHashMap<Int, List<PreparedChunk>>()

    /**
     * 正在后台预合成下一段的协程。cancel 时需要把它也停掉，避免生成过期音频。
     */
    @Volatile
    private var prefetchJob: Job? = null

    private var paragraphs: List<String> = emptyList()
    private var currentIndex = 0

    fun setListener(l: Listener?) {
        listener = l
    }

    /** 加载两个引擎（耗时操作，完成后回调 onEngineReady） */
    fun init() {
        scope.launch {
            var mixedError: String? = null
            var zhError: String? = null

            try {
                mixedEngine = buildEngine(DIR_MIXED)
            } catch (e: Throwable) {
                mixedError = e.message ?: e.javaClass.simpleName
            }
            try {
                zhEngine = buildEngine(DIR_ZH)
            } catch (e: Throwable) {
                zhError = e.message ?: e.javaClass.simpleName
            }

            ready = mixedEngine != null || zhEngine != null
            if (ready) {
                val info = when {
                    mixedEngine != null && zhEngine != null ->
                        "内置离线语音已就绪 · 中英混读 / 中文女声"
                    mixedEngine != null -> "内置离线语音已就绪 · 仅中英混读"
                    else -> "内置离线语音已就绪 · 仅中文女声"
                }
                notify { it.onEngineReady(info) }
            } else {
                notify {
                    it.onEngineUnavailable(
                        "内置语音引擎加载失败：${mixedError ?: zhError ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun isReady(): Boolean = ready

    fun isPaused(): Boolean = paused

    fun currentIndex(): Int = currentIndex

    fun currentMode(): Mode = mode

    fun setSpeed(s: Float) {
        speed = s.coerceIn(0.5f, 2.0f)
    }

    /** 切换语音模式；正在朗读时从当前段落用新音色继续 */
    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        clearPrecomputed()
        if (speaking && ready) {
            session++
            cancelPlayback()
            stopRequested = false
            paused = false
            notify { it.onSpeakingChanged(true) }
            startCurrent()
        }
    }

    /** 开始朗读整本书 */
    fun speak(all: List<String>, startIndex: Int = 0) {
        if (!ready) {
            notify { it.onError("语音引擎正在加载，请稍候再试") }
            return
        }
        if (all.isEmpty()) return

        cancelPlayback()
        clearPrecomputed()
        session++
        paragraphs = all
        currentIndex = startIndex.coerceIn(0, all.size - 1)
        paused = false
        stopRequested = false
        speaking = true
        notify { it.onSpeakingChanged(true) }
        startCurrent()
    }

    fun pause() {
        if (!ready) return
        paused = true
        speaking = false
        runCatching { currentTrack?.pause() }
        notify { it.onSpeakingChanged(false) }
    }

    fun resume() {
        if (!ready || paragraphs.isEmpty() || !paused) return
        paused = false
        speaking = true
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
        speaking = false
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
        scope.launch {
            runCatching { zhEngine?.release() }
            runCatching { mixedEngine?.release() }
        }
    }

    // ------------------------------------------------------------------

    private fun buildEngine(dir: String): OfflineTts {
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/model.onnx",
                    lexicon = "$dir/lexicon.txt",
                    tokens = "$dir/tokens.txt",
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            // 数字、日期、电话号码的中文读法
            ruleFsts = "$dir/date.fst,$dir/number.fst,$dir/phone.fst",
            maxNumSentences = 1,
            silenceScale = 0.15f,
        )
        return OfflineTts(assetManager = context.assets, config = config)
    }

    private fun jumpTo(index: Int) {
        if (!ready) return
        val wasPlaying = !paused && speaking
        session++
        cancelPlayback()
        clearPrecomputed()
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
                notify { it.onParagraphStart(currentIndex) }

                // 先启动后台预合成下一段；当前段若已经算好就直接拿，否则现场合成
                val prefetch = prefetchNext(currentIndex + 1, mySession)

                val audioList = precomputed.remove(currentIndex)
                    ?: prepareParagraph(paragraphs[currentIndex], mySession)

                if (audioList.isEmpty()) {
                    advance(mySession)
                    return@launch
                }

                // 所有片段用同一个 AudioTrack 无缝串起来，避免每段都重建 track
                playParagraph(audioList, mySession)

                // 等后台预合成结束，确保 precomputed 已经有下一段
                prefetch?.join()

                advance(mySession)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                speaking = false
                notify { it.onError("朗读出错：${e.message ?: e.javaClass.simpleName}") }
                notify { it.onSpeakingChanged(false) }
            }
        }
    }

    /**
     * 后台预合成下一段，完成后塞进 precomputed。
     * 返回的 Job 可 join 等待，null 表示没有下一段。
     */
    private fun prefetchNext(index: Int, mySession: Int): Job? {
        if (index >= paragraphs.size) return null
        if (precomputed.containsKey(index)) return null // 已有，跳过
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            if (mySession != session) return@launch
            val list = prepareParagraph(paragraphs[index], mySession)
            if (mySession == session && !stopRequested) {
                precomputed[index] = list
                // 控制内存，只保留最近两段预合成
                while (precomputed.size > 3) {
                    precomputed.entries.firstOrNull()?.let { precomputed.remove(it.key) }
                }
            }
        }
        return prefetchJob
    }

    /** 把一个段落切成若干合成片段并做峰值归一，返回随时可播的音频列表 */
    private suspend fun prepareParagraph(text: String, mySession: Int): List<PreparedChunk> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<PreparedChunk>()
        for (chunk in splitChunks(text)) {
            if (mySession != session || stopRequested) break
            for ((chinese, part) in segmentsOf(chunk)) {
                if (mySession != session || stopRequested) break
                val engine = engineFor(chinese) ?: continue
                // 合成本身是同步 native 调用，用 Default dispatcher 让出 IO 线程
                val audio = withContext(Dispatchers.Default) { engine.generate(part, 0, speed) }
                if (audio.samples.isNotEmpty()) {
                    out.add(normalize(audio.samples, audio.sampleRate))
                }
            }
        }
        return out
    }

    /** 把合成出的 float PCM 做峰值归一化并转成 ShortArray */
    private fun normalize(samples: FloatArray, sampleRate: Int): PreparedChunk {
        var peak = 0f
        for (v in samples) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        val gain = if (peak > 0.01f) (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN) else 1f
        val shorts = ShortArray(samples.size)
        for (i in samples.indices) {
            shorts[i] = (samples[i] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return PreparedChunk(shorts, sampleRate)
    }

    /** 把整个段落的 PreparedChunk 列表用同一个 AudioTrack 无缝播放 */
    private suspend fun playParagraph(chunks: List<PreparedChunk>, mySession: Int) {
        if (chunks.isEmpty()) return
        val sampleRate = chunks.first().sampleRate

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 开大缓冲：段落切换时 AudioTrack 还能继续播，给预合成留时间
        val bufSize = max(minBuf, 65536).coerceAtMost(sampleRate * 2 * 2 /* 最多 2 秒 */)

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
            var written = 0
            for (chunk in chunks) {
                if (mySession != session || stopRequested) return
                // 暂停状态先等，恢复时从当前片段开头播
                while (paused && mySession == session && !stopRequested) delay(50)
                if (mySession != session || stopRequested) return

                var off = 0
                while (off < chunk.samples.size && mySession == session && !stopRequested) {
                    while (paused && mySession == session && !stopRequested) delay(50)
                    if (mySession != session || stopRequested) return
                    val n = track.write(
                        chunk.samples, off,
                        min(SAMPLE_CHUNK, chunk.samples.size - off),
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (n <= 0) break
                    off += n
                    written += n
                }
            }
            // 等 track 把所有已写入的样本播完
            while (mySession == session && !stopRequested &&
                track.playbackHeadPosition < written
            ) {
                delay(30)
            }
        } catch (e: IllegalStateException) {
            // 被 stop() 杀掉时 track 可能已释放
        } finally {
            if (currentTrack === track) currentTrack = null
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun clearPrecomputed() {
        prefetchJob?.cancel()
        prefetchJob = null
        precomputed.clear()
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
        clearPrecomputed()
        speaking = false
        notify { it.onSpeakingChanged(false) }
        notify { it.onFinished() }
    }

    private fun engineFor(chinese: Boolean): OfflineTts? = when (mode) {
        Mode.MIXED -> mixedEngine ?: if (chinese) zhEngine else null
        Mode.CHINESE -> if (chinese) zhEngine ?: mixedEngine else mixedEngine
    }

    /**
     * 按语言切分片段。
     * 混读模式整段交给双语引擎；中文模式再把中文段与英文段分开。
     */
    private fun segmentsOf(chunk: String): List<Pair<Boolean, String>> {
        if (mode == Mode.MIXED) return listOf(false to chunk)
        return splitByLanguage(chunk).ifEmpty { listOf(true to chunk) }
    }

    /**
     * 把文本切成「中文段 / 英文段」。
     * 数字与空格、标点跟随前一个语言，保证「2023年」整体读中文、「GPT-4」整体读英文。
     */
    private fun splitByLanguage(text: String): List<Pair<Boolean, String>> {
        val parts = mutableListOf<Pair<Boolean, StringBuilder>>()
        var currentChinese = true
        for (c in text) {
            val chinese = when {
                isChinese(c) -> true
                c.isLetter() -> false
                else -> currentChinese
            }
            currentChinese = chinese
            if (parts.isEmpty() || parts.last().first != chinese) {
                parts.add(chinese to StringBuilder().append(c))
            } else {
                parts.last().second.append(c)
            }
        }
        return parts
            .map { it.first to it.second.toString().trim() }
            .filter { it.second.isNotEmpty() }
    }

    private fun isChinese(c: Char): Boolean {
        if (c in '\u4e00'..'\u9fff') return true
        if (c in '\u3400'..'\u4dbf') return true
        return c in "，。！？；：、“”‘’（）《》〈〉【】…—～·「」『』"
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

    private fun cancelPlayback() {
        clearPrecomputed()
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
