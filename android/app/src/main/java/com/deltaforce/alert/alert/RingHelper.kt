package com.deltaforce.alert.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deltaforce.alert.R

/** 提醒通知与强提醒（QQ 通话式全屏）的入口。 */
object RingHelper {
    const val CHANNEL_ALERT = "alert_channel"
    const val CHANNEL_SERVICE = "service_channel"
    const val NOTIF_ALERT = 1001
    const val NOTIF_SERVICE = 1002

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT, "交易提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "买入/卖出时机与价格预警（QQ 通话式强提醒）"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "监控状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "前台价格监控常驻通知" }
        )
    }

    /** Android 14+ 是否已授权全屏通知 */
    fun canUseFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            return ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }
        return true
    }

    /** 强提醒：全屏来电界面 + 通知栏消息（铃声由 IncomingCallActivity 播放）。type: "buy" | "sell" */
    fun fullScreenAlert(ctx: Context, title: String, text: String, type: String = "buy") {
        ensureChannels(ctx)
        val intent = Intent(ctx, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TYPE, type)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 600, 300, 600, 300, 600))
            .setFullScreenIntent(pi, true)
        // 双保险：先尽力直接拉起全屏界面（后台启动受限时静默失败），
        // 同时发带 fullScreenIntent 的通知（Android 14+ 授权后由系统拉起，锁屏也能显示）。
        try {
            ctx.startActivity(
                Intent(ctx, IncomingCallActivity::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_TYPE, type)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } catch (_: Exception) {
        }
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ALERT, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** 普通消息提醒（非强提醒） */
    fun notifyPlain(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ALERT, notif)
        } catch (_: SecurityException) {
        }
    }

    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_TEXT = "extra_text"
    const val EXTRA_TYPE = "extra_type"
}
