package com.ebook.voicereader.convert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 后台转换看门狗。
 *
 * 前台服务在多数系统上能持续运行，但应用被划掉后部分厂商会直接杀进程。
 * 这里每隔一分钟用 AlarmManager 直接把转换服务拉回前台：
 * 用 PendingIntent.getForegroundService 而不是广播，避免 Android 12+
 * “后台不允许启动前台服务”的限制让唤醒静默失败。
 * 转换全部结束或用户主动暂停时会取消，避免无意义唤醒。
 */
object ConversionWatchdog {

    /** 一分钟检查一次：进程被杀后最多一分钟就能续上，而不是干等十分钟。 */
    private const val INTERVAL_MS = 60 * 1000L
    private const val REQUEST_CODE = 1007
    const val ACTION_KICK = "ebook.voicereader.CONVERT_KICK"

    fun schedule(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            val pi = pendingIntent(context)
            val exact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            trace("看门狗已排期 1 分钟后唤醒 exact=$exact")
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pendingIntent(context))
            trace("看门狗已取消")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConvertService::class.java)
            .setAction(ACTION_KICK)
            .putExtra(ConvertService.EXTRA_ACTION, ConvertService.ACTION_RESUME)
        return PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun trace(tag: String) {
        com.ebook.voicereader.App.traceSafe("watchdog:$tag")
    }
}
