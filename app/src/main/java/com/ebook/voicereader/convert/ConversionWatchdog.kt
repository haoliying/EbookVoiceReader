package com.ebook.voicereader.convert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 后台转换看门狗。
 *
 * 前台服务在绝大多数系统上能持续运行，但部分厂商会在应用被划掉后直接杀进程。
 * 这里定时（10 分钟）发一个唤醒广播，尝试把转换服务重新拉起；
 * 若系统允许（电池优化白名单 / 启动广播豁免），转换会从断点继续。
 * 转换全部结束或用户主动暂停时会取消，避免无意义唤醒。
 */
object ConversionWatchdog {

    private const val INTERVAL_MS = 10 * 60 * 1000L
    private const val REQUEST_CODE = 1007
    const val ACTION_KICK = "ebook.voicereader.CONVERT_KICK"

    fun schedule(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pendingIntent(context),
            )
            trace("看门狗已排期 10 分钟后唤醒")
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
        val intent = Intent(context, ConversionKickReceiver::class.java).setAction(ACTION_KICK)
        return PendingIntent.getBroadcast(
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
