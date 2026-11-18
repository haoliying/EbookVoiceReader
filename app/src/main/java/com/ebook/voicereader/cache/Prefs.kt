package com.ebook.voicereader.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * 用户偏好：预热秒数（实时模式）、语音模式默认值、是否自动检测缓存。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /** 实时模式预热秒数（默认 15） */
    var warmupSeconds: Int
        get() = sp.getInt(KEY_WARMUP, 15).coerceIn(5, 120)
        set(v) = sp.edit().putInt(KEY_WARMUP, v.coerceIn(5, 120)).apply()

    /** 播放过程中持续预合成的目标领先秒数 */
    var lookaheadSeconds: Int
        get() = sp.getInt(KEY_LOOKAHEAD, 30).coerceIn(10, 120)
        set(v) = sp.edit().putInt(KEY_LOOKAHEAD, v.coerceIn(10, 120)).apply()

    /** 默认语音模式 "mixed" / "chinese" */
    var defaultMode: String
        get() = sp.getString(KEY_MODE, "mixed") ?: "mixed"
        set(v) = sp.edit().putString(KEY_MODE, v).apply()

    companion object {
        private const val KEY_WARMUP = "warmup_seconds"
        private const val KEY_LOOKAHEAD = "lookahead_seconds"
        private const val KEY_MODE = "default_mode"
    }
}
