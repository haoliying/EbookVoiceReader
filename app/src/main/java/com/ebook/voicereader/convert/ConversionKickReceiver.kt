package com.ebook.voicereader.convert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ebook.voicereader.App

/**
 * 看门狗定时广播 / 开机广播的接收器。
 * 只负责把转换服务重新拉起来，真正的断点判断在服务内完成。
 */
class ConversionKickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        App.traceSafe("kick:收到 ${intent.action}")
        runCatching {
            val serviceIntent = Intent(context, ConvertService::class.java)
                .putExtra(ConvertService.EXTRA_ACTION, ConvertService.ACTION_RESUME)
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure {
            App.traceSafe("kick:启动转换服务失败 ${it.javaClass.simpleName}")
        }
        // 继续排下一轮，形成持续守护；没有任务时服务会自行 stopSelf 并取消。
        ConversionWatchdog.schedule(context)
    }
}
