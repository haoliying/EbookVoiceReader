package com.ebook.voicereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.ebook.voicereader.App
import com.ebook.voicereader.cache.CacheManager
import com.ebook.voicereader.cache.Prefs
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 内置离线语音合成 + 播放引擎。
 *
 * 两个引擎都随 APK 打包，完全不依赖系统 TTS：
 * - 中文女声：vits-piper-zh_CN-xiao_ya-medium-int8（约 20MB）
 * - 中英双语：vits-melo-tts-zh_en（约 170MB，英文按单词朗读）
 *
 * 三种播放模式：
 * 1. **缓存播放**：如果某本书已被后台转换（CacheManager 里有 PCM 分片），
 *    直接播放 PCM，完全跳过合成，打开就能听。
 * 2. **实时预热播放**：未转换的书走实时模式，打开后先预热 N 秒（默认 15 秒，
 *    在设置里可调 5–120 秒），播放时后台持续预合成。
 * 3. **后台转换**：ConverterService 调用 [synthesizeParagraph] 把全书逐段合成、
 *    写进 CacheManager，下次打开就变成模式 1。
 *
 * ONNX Runtime 非线程安全，所有 engine.generate() 都经 [engineMutex] 串行。
 */
class TtsManager(private val context: Context) {

    enum class Mode {
        /** 中英混读：双语女声，英文按整词朗读 */
        MIXED,
        /** 中文女声：中文段用「小雅」，英文自动换双语 */
        CHINESE,
    }

    interface Listener {
        fun onEngineReady(info: String)
        fun onEngineUnavailable(reason: String)
        /** 预热阶段进度 */
        fun onPreparationProgress(doneParagraphs: Int, estimatedSeconds: Int)
        fun onPreparationComplete(paragraphsReady: Int)
        /** 检测到有缓存可以直接播放 */
        fun onCacheAvailable(convertedParagraphs: Int) {}
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
        private const val TARGET_PEAK = 0.9f
        private const val MAX_GAIN = 6f
        private const val DEFAULT_SAMPLE_RATE = 22050
    }

    private class PreparedChunk(
        val samples: ShortArray,
        val sampleRate: Int,
        val durationSec: Float,
    )

    private data class CacheChunk(
        val samples: ShortArray,
        val sampleRate: Int,
    ) {
        fun durationSec(): Float = samples.size.toFloat() / sampleRate
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            speaking = false
            notify { it.onError("语音任务出错：${error.message ?: error.javaClass.simpleName}") }
            notify { it.onSpeakingChanged(false) }
        }
    )
    private val engineMutex = Mutex()
    private val initMutex = Mutex()

    private val prefs = Prefs(context)
    private var cache: CacheManager? = null

    private var listener: Listener? = null
    private var zhEngine: OfflineTts? = null
    private var mixedEngine: OfflineTts? = null

    @Volatile private var ready = false
    @Volatile private var mode = Mode.MIXED
    @Volatile private var session = 0
    @Volatile private var paused = false
    @Volatile private var stopRequested = true
    @Volatile private var speaking = false
    @Volatile private var speed = 1.0f
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var job: Job? = null
    private val precomputed = LinkedHashMap<Int, List<PreparedChunk>>()
    @Volatile private var prefetchJob: Job? = null

    private var paragraphs: List<String> = emptyList()
    private var currentIndex = 0
    private var currentBookId: String? = null
    private var currentBookTitle: String? = null

    fun setListener(l: Listener?) { listener = l }

    fun setCache(c: CacheManager) { cache = c }

    /**
     * 按需加载模型：默认只加载双语模型；中文女声模式才额外加载小雅模型。
     * initMutex 防止阅读页与转换服务同时重复加载同一模型。
     */
    fun init(requestedMode: Mode = mode) {
        scope.launch {
            initMutex.withLock {
                var mixedError: String? = null
                var zhError: String? = null
                if (mixedEngine == null) {
                    App.traceSafe("tts:开始加载双语模型 · 可用内存 ${App.instance.availableMemoryMB()}MB")
                    try {
                        mixedEngine = buildEngine(DIR_MIXED, 600)
                        App.traceSafe("tts:双语模型加载成功 · 可用内存 ${App.instance.availableMemoryMB()}MB")
                    } catch (e: Throwable) {
                        mixedError = e.message ?: e.javaClass.simpleName
                        App.traceSafe("tts:双语模型加载失败 ${e.javaClass.name}: ${e.message}")
                    }
                }
                // 中文模式需要双语模型兜底英文；双语模型不可用时不再浪费内存加载另一套。
                if (requestedMode == Mode.CHINESE && mixedEngine != null && zhEngine == null) {
                    try {
                        zhEngine = buildEngine(DIR_ZH, 300)
                        App.traceSafe("tts:中文模型加载成功")
                    } catch (e: Throwable) {
                        zhError = e.message ?: e.javaClass.simpleName
                        App.traceSafe("tts:中文模型加载失败 ${e.javaClass.name}: ${e.message}")
                    }
                }

                ready = mixedEngine != null
                if (ready) {
                    val info = if (zhEngine != null) {
                        "内置离线语音已就绪 · 中英混读 / 中文女声"
                    } else {
                        "内置离线语音已就绪 · 中英混读"
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
    }

    fun isReady() = ready
    fun isPaused() = paused
    fun isPlaying() = speaking && !paused
    fun currentIndex() = currentIndex
    fun currentMode() = mode
    fun currentBookId() = currentBookId
    fun currentBookTitle() = currentBookTitle
    fun setCurrentBookTitle(t: String?) { currentBookTitle = t }
    fun setSpeed(s: Float) { speed = s.coerceIn(0.5f, 2.0f) }

    /** 暴露给 ConverterService 用：单独合成一段文本 */
    suspend fun synthesizeParagraph(text: String, mode: Mode = this.mode): Pair<ShortArray, Int>? {
        if (!ready || text.isBlank()) return null
        val chunks = synthesize(text, mode, session)
        if (chunks.isEmpty()) return null
        val rate = chunks.first().sampleRate
        val out = ShortArray(chunks.sumOf { it.samples.size })
        var off = 0
        for (c in chunks) {
            System.arraycopy(c.samples, 0, out, off, c.samples.size)
            off += c.samples.size
        }
        return out to rate
    }

    /** 切换语音模式；正在朗读时从当前段落用新音色继续 */
    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        // Application 恢复默认模式时不能提前加载模型；只有引擎已启用才补充新模式模型。
        if (ready) init(m)
        currentBookId = null // 模式变了缓存不可用
        clearPrecomputed()
        if (speaking && ready) {
            session++
            cancelPlayback()
            stopRequested = false
            paused = false
            notify { it.onSpeakingChanged(true) }
            scope.launch {
                prepareLookaheadFrom(currentIndex, prefs.lookaheadSeconds, incrementSession = false)
                startPlaybackLoop(currentIndex)
            }
        }
    }

    /**
     * 开始朗读一本书。
     * 优先尝试 CacheManager 里的缓存：如果缓存完整直接进入播放循环，零等待；
     * 否则走实时预热，预热秒数从 Prefs 读取（默认 15 秒）。
     */
    fun speak(all: List<String>, startIndex: Int = 0, bookId: String? = null) {
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
        speaking = false
        currentBookId = bookId

        val mySession = session
        App.traceSafe("tts:speak 段数=${all.size} 起始=$startIndex 模式=$mode ready=$ready")
        job = scope.launch {
            try {
                // 1) 缓存路径：只要已经有可用片段就直接播，边播边补
                val cache = cache
                val bid = bookId
                val meta = cache?.metaOf(bid ?: "")
                if (cache != null && bid != null && meta != null &&
                    meta.paragraphCount == all.size &&
                    (mode.name.lowercase() == meta.mode || meta.mode.isEmpty())
                ) {
                    val converted = (0 until all.size).count { cache.hasChunk(bid, it) }
                    // 后台还在转换时也必须复用已转好的部分，不能从头重新合成。
                    if (converted > 0) {
                        App.traceSafe("tts:走缓存播放 已转=$converted/${all.size} 采样率=${meta.sampleRate}")
                        notify { it.onCacheAvailable(converted) }
                        speaking = true
                        notify { it.onPreparationComplete(converted) }
                        notify { it.onSpeakingChanged(true) }
                        startCachedPlaybackLoop(bid, all.size, meta.sampleRate)
                        return@launch
                    }
                }

                // 2) 实时预热路径
                App.traceSafe("tts:走实时合成 起始=$currentIndex")
                val warmup = prefs.warmupSeconds
                val done = prepareWarmup(currentIndex, warmup, mySession)
                if (mySession != session || stopRequested) return@launch
                notify { it.onPreparationComplete(done) }
                speaking = true
                notify { it.onSpeakingChanged(true) }
                startPlaybackLoop(currentIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                speaking = false
                notify { it.onError("朗读出错：${e.message ?: e.javaClass.simpleName}") }
                notify { it.onSpeakingChanged(false) }
            }
        }
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
        val active = job
        if (active != null && active.isActive) {
            // 普通暂停：播放循环还在，继续写音频即可。
            speaking = true
            runCatching { currentTrack?.play() }
            notify { it.onSpeakingChanged(true) }
            return
        }
        // 播放循环已因拖拽定位 / 切段被取消，需要从当前位置重新起播。
        stopRequested = false
        speaking = true
        session++
        notify { it.onSpeakingChanged(true) }
        launchPlaybackFromCurrent()
    }

    fun stop() {
        session++
        cancelPlayback()
        clearPrecomputed()
        paused = false
        speaking = false
        currentIndex = 0
        // 停止视为回到开头，下次播放从第一段开始。
        rememberPosition()
        notify { it.onSpeakingChanged(false) }
    }

    fun previous() {
        if (paragraphs.isEmpty()) return
        seekTo((currentIndex - 1).coerceAtLeast(0))
    }

    fun next() {
        if (paragraphs.isEmpty()) return
        seekTo((currentIndex + 1).coerceAtMost(paragraphs.size - 1))
    }

    fun release() {
        session++
        cancelPlayback()
        clearPrecomputed()
        scope.launch {
            runCatching { zhEngine?.release() }
            runCatching { mixedEngine?.release() }
        }
    }

    // ------------------------------------------------------------------
    // 播放循环
    // ------------------------------------------------------------------

    private suspend fun startCachedPlaybackLoop(bookId: String, paraCount: Int, sampleRate: Int) {
        var idx = currentIndex
        while (idx in 0 until paraCount) {
            if (stopRequested) return
            val mySession = session
            currentIndex = idx
            rememberPosition()
            notify { it.onParagraphStart(idx) }
            // 提前合成后面还没转好的段落，让“边播边转”不出现明显停顿。
            prefetchJob?.cancel()
            prefetchJob = scope.launch { prefetchMissing(bookId, idx + 1, prefs.lookaheadSeconds) }
            val chunk = cache?.readChunk(bookId, idx)
            if (chunk != null && chunk.isNotEmpty()) {
                val dur = chunk.size.toFloat() / sampleRate
                playRawParagraph(listOf(CacheChunk(chunk, sampleRate)), dur, mySession)
            } else if (idx < paragraphs.size) {
                // 后台还没转到这里：先实时合成这一段，播完继续往下走。
                val audio = precomputed.remove(idx) ?: prepareParagraph(paragraphs[idx], mySession)
                if (audio.isNotEmpty()) playPreparedParagraph(audio, mySession)
            }
            if (stopRequested) return
            idx++
            currentIndex = idx
        }
        finishAll()
    }

    /** 只合成尚未落盘的段落，已转换好的直接跳过，避免重复劳动。 */
    private suspend fun prefetchMissing(bookId: String, fromIndex: Int, targetSeconds: Int) {
        val mySession = session
        var seconds = 0f
        var idx = fromIndex
        while (idx < paragraphs.size && seconds < targetSeconds) {
            if (mySession != session || stopRequested) return
            if (cache?.hasChunk(bookId, idx) != true && !precomputed.containsKey(idx)) {
                val audio = prepareParagraph(paragraphs[idx], mySession)
                precomputed[idx] = audio
                seconds += audio.fold(0f) { a, c -> a + c.durationSec }
                while (precomputed.size > 5) {
                    precomputed.entries.firstOrNull()?.let { precomputed.remove(it.key) }
                }
            }
            idx++
        }
    }

    private suspend fun startPlaybackLoop(startIndex: Int) {
        var idx = startIndex
        while (idx in paragraphs.indices) {
            if (stopRequested) return
            val mySession = session
            currentIndex = idx
            rememberPosition()
            prefetchJob?.cancel()
            prefetchJob = scope.launch {
                // 注意：预合成属于当前播放会话，绝对不能自增 session，
                // 否则正在播放的段落会被判定为过期，导致整本书静音。
                prepareLookaheadFrom(idx + 1, prefs.lookaheadSeconds, incrementSession = false)
            }
            notify { it.onParagraphStart(idx) }
            val audio = precomputed.remove(idx)
                ?: prepareParagraph(paragraphs[idx], mySession)
            if (audio.isNotEmpty()) playPreparedParagraph(audio, mySession)
            if (stopRequested) return
            idx++
            currentIndex = idx
        }
        finishAll()
    }

    /**
     * 跳转到任意段落。
     * 播放中会立刻从该段继续；暂停时只移动位置，恢复播放时从新位置起播。
     */
    fun seekTo(index: Int) {
        if (!ready || paragraphs.isEmpty()) return
        val target = index.coerceIn(0, paragraphs.size - 1)
        val wasPlaying = speaking && !paused
        session++
        cancelPlayback()
        clearPrecomputed()
        currentIndex = target
        rememberPosition()
        notify { it.onParagraphStart(target) }
        if (wasPlaying) {
            stopRequested = false
            notify { it.onSpeakingChanged(true) }
            launchPlaybackFromCurrent()
        }
    }

    /** 用当前 currentIndex 起播；缓存可用就走缓存，否则实时合成。 */
    private fun launchPlaybackFromCurrent() {
        job = scope.launch {
            try {
                val bid = currentBookId
                val meta = bid?.let { cache?.metaOf(it) }
                if (bid != null && meta != null &&
                    meta.paragraphCount == paragraphs.size &&
                    (mode.name.lowercase() == meta.mode || meta.mode.isEmpty())
                ) {
                    startCachedPlaybackLoop(bid, meta.paragraphCount, meta.sampleRate)
                } else {
                    prepareLookaheadFrom(currentIndex, prefs.lookaheadSeconds, incrementSession = false)
                    startPlaybackLoop(currentIndex)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                speaking = false
                notify { it.onError("朗读出错：${e.message ?: e.javaClass.simpleName}") }
                notify { it.onSpeakingChanged(false) }
            }
        }
    }

    /** 把当前段落写回 meta，下次打开同一本书从该段继续。 */
    private fun rememberPosition() {
        val bid = currentBookId ?: return
        val c = cache ?: return
        val idx = currentIndex
        scope.launch { runCatching { c.updateProgress(bid, idx) } }
    }

    // ------------------------------------------------------------------
    // 预热 + 预合成
    // ------------------------------------------------------------------

    private suspend fun prepareWarmup(startIndex: Int, targetSeconds: Int, mySession: Int): Int {
        var doneSeconds = 0f
        var done = 0
        for (idx in startIndex until paragraphs.size) {
            if (mySession != session || stopRequested) break
            val audio = prepareParagraph(paragraphs[idx], mySession)
            if (audio.isEmpty()) continue
            precomputed[idx] = audio
            done++
            doneSeconds += audio.fold(0f) { a, c -> a + c.durationSec }
            notify { it.onPreparationProgress(done, doneSeconds.toInt()) }
            if (doneSeconds >= targetSeconds) break
        }
        return done
    }

    private suspend fun prepareLookaheadFrom(
        fromIndex: Int, targetSeconds: Int, incrementSession: Boolean = true
    ) {
        val mySession = if (incrementSession) ++session else session
        var doneSeconds = precomputed.entries
            .filter { it.key >= fromIndex }
            .fold(0f) { acc, e -> acc + e.value.fold(0f) { a, c -> a + c.durationSec } }
        var idx = max(fromIndex, currentIndex + precomputed.size)
        while (idx < paragraphs.size && doneSeconds < targetSeconds) {
            if (mySession != session || stopRequested) return
            if (precomputed.containsKey(idx)) { idx++; continue }
            val audio = prepareParagraph(paragraphs[idx], mySession)
            precomputed[idx] = audio
            doneSeconds += audio.fold(0f) { a, c -> a + c.durationSec }
            idx++
            while (precomputed.size > 5) {
                precomputed.entries.firstOrNull()?.let { precomputed.remove(it.key) }
            }
        }
    }

    /** 内部合成：切句 → 语言分段 → engine.generate → 归一 */
    private suspend fun synthesize(
        text: String, modeOverride: Mode, mySession: Int
    ): List<PreparedChunk> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<PreparedChunk>()
        for (chunk in splitChunks(text)) {
            for ((chinese, part) in segmentsOf(chunk, modeOverride)) {
                val engine = engineFor(modeOverride, chinese)
                    ?: throw IllegalStateException("所选语音模式的模型尚未加载")
                val audio = engineMutex.withLock {
                    withContext(Dispatchers.Default) { engine.generate(part, 0, speed) }
                }
                if (audio.samples.isNotEmpty()) {
                    out.add(normalize(audio.samples, audio.sampleRate))
                }
            }
        }
        return out
    }

    private suspend fun prepareParagraph(text: String, mySession: Int) =
        synthesize(text, mode, mySession)

    private fun engineFor(m: Mode, chinese: Boolean): OfflineTts? = when (m) {
        Mode.MIXED -> mixedEngine ?: if (chinese) zhEngine else null
        Mode.CHINESE -> if (chinese) zhEngine ?: mixedEngine else mixedEngine
    }

    private fun segmentsOf(chunk: String, selectedMode: Mode): List<Pair<Boolean, String>> =
        if (selectedMode == Mode.MIXED) listOf(false to chunk)
        else splitByLanguage(chunk).ifEmpty { listOf(true to chunk) }

    private fun splitByLanguage(text: String): List<Pair<Boolean, String>> {
        val parts = mutableListOf<Pair<Boolean, StringBuilder>>()
        var curChinese = true
        for (c in text) {
            val chinese = when {
                isChinese(c) -> true
                c.isLetter() -> false
                else -> curChinese
            }
            curChinese = chinese
            if (parts.isEmpty() || parts.last().first != chinese) {
                parts.add(chinese to StringBuilder().append(c))
            } else {
                parts.last().second.append(c)
            }
        }
        return parts.map { it.first to it.second.toString().trim() }.filter { it.second.isNotEmpty() }
    }

    private fun isChinese(c: Char): Boolean {
        if (c in '\u4e00'..'\u9fff') return true
        if (c in '\u3400'..'\u4dbf') return true
        return c in "，。！？；：、“”‘’（）《》〈〉【】…—～·「」『』"
    }

    private fun splitChunks(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[。！？；!?;\\n])"))
        val out = mutableListOf<String>()
        var buf = StringBuilder()
        for (raw in sentences) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.length > MAX_CHUNK) {
                if (buf.isNotEmpty()) { out.add(buf.toString()); buf = StringBuilder() }
                var i = 0
                while (i < s.length) { out.add(s.substring(i, min(i + MAX_CHUNK, s.length))); i += MAX_CHUNK }
                continue
            }
            if (buf.length + s.length > MAX_CHUNK && buf.isNotEmpty()) {
                out.add(buf.toString()); buf = StringBuilder()
            }
            buf.append(s)
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out.ifEmpty { listOf(text) }
    }

    private fun normalize(samples: FloatArray, sampleRate: Int): PreparedChunk {
        var peak = 0f
        for (v in samples) { val a = abs(v); if (a > peak) peak = a }
        val gain = if (peak > 0.01f) (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN) else 1f
        val shorts = ShortArray(samples.size)
        for (i in samples.indices) {
            shorts[i] = (samples[i] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return PreparedChunk(shorts, sampleRate, shorts.size.toFloat() / sampleRate)
    }

    private suspend fun playPreparedParagraph(chunks: List<PreparedChunk>, mySession: Int) {
        if (chunks.isEmpty()) return
        val sampleRate = chunks.first().sampleRate
        val raw = chunks.map { CacheChunk(it.samples, it.sampleRate) }
        val dur = chunks.fold(0f) { a, c -> a + c.durationSec }
        playRawParagraph(raw, dur, mySession)
    }

    private suspend fun playRawParagraph(
        chunks: List<CacheChunk>, totalDurationSec: Float, mySession: Int
    ) {
        if (chunks.isEmpty()) return
        val sampleRate = chunks.first().sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, 65536).coerceAtMost(sampleRate * 4 /* 最多 4 秒缓冲 */)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            // 采样率异常或音频资源被占用时，给出可读提示而不是静默无声。
            App.traceSafe("tts:创建播放器失败 rate=$sampleRate ${e.javaClass.name}: ${e.message}")
            notify { it.onError("无法创建音频播放器：${e.message ?: e.javaClass.simpleName}") }
            return
        }

        currentTrack = track
        try {
            track.play()
            var written = 0
            for (chunk in chunks) {
                if (mySession != session || stopRequested) return
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
                    off += n; written += n
                }
            }
            while (mySession == session && !stopRequested &&
                track.playbackHeadPosition < written
            ) { delay(30) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            App.traceSafe("tts:写入音频失败 ${e.javaClass.name}: ${e.message}")
            notify { it.onError("播放失败：${e.message ?: e.javaClass.simpleName}") }
        } finally {
            if (currentTrack === track) currentTrack = null
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun buildEngine(dir: String, minFreeMB: Long): OfflineTts {
        // 双语模型约 170MB，加载时还需要额外的推理内存。
        // 可用内存过低时直接给出可读错误，避免原生层 OOM 直接杀进程（表现为闪退）。
        val avail = runCatching { App.instance.availableMemoryMB() }.getOrDefault(-1L)
        if (avail in 1 until minFreeMB) {
            throw IllegalStateException(
                "可用内存不足（${avail}MB，加载该语音需要 ${minFreeMB}MB），请关闭其他应用后重试"
            )
        }
        // 先确认资产真的能读出来。缺失/损坏时给中文提示，而不是在原生层直接崩溃。
        for (name in listOf("model.onnx", "lexicon.txt", "tokens.txt")) {
            try {
                context.assets.open("$dir/$name").use { it.read() }
            } catch (e: Throwable) {
                throw IllegalStateException("语音模型文件缺失或损坏：$dir/$name", e)
            }
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/model.onnx",
                    lexicon = "$dir/lexicon.txt",
                    tokens = "$dir/tokens.txt",
                ),
                numThreads = 2, debug = false, provider = "cpu",
            ),
            ruleFsts = "$dir/date.fst,$dir/number.fst,$dir/phone.fst",
            maxNumSentences = 1, silenceScale = 0.15f,
        )
        return OfflineTts(assetManager = context.assets, config = config)
    }

    private fun finishAll() {
        clearPrecomputed()
        speaking = false
        notify { it.onSpeakingChanged(false) }
        notify { it.onFinished() }
    }

    private fun clearPrecomputed() {
        prefetchJob?.cancel(); prefetchJob = null; precomputed.clear()
    }

    private fun cancelPlayback() {
        clearPrecomputed()
        stopRequested = true
        job?.cancel(); job = null
        val track = currentTrack; currentTrack = null
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
