package com.deltaforce.alert.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.TimeZone

/**
 * 每天固定时段的精确闹钟调度（07:00 买入 / 16:00 卖出）。
 *
 * 时段按北京时间排：内置模型是「北京时间日内规律」，手机在别的时区时也用同一套时刻，
 * 否则规律跟着手机时区漂移。
 */
object AlertScheduler {
    const val REQ_BUY = 2001
    const val REQ_SELL = 2002
    const val ACTION_BUY = "com.deltaforce.alert.BUY_WINDOW"
    const val ACTION_SELL = "com.deltaforce.alert.SELL_WINDOW"

    private val TZ = TimeZone.getTimeZone("Asia/Shanghai")

    private fun pendingIntent(ctx: Context, requestCode: Int, action: String, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, TimeWindowReceiver::class.java).setAction(action),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    fun scheduleDaily(ctx: Context, hour: Int, minute: Int, requestCode: Int, action: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = Calendar.getInstance(TZ)
        val cal = Calendar.getInstance(TZ).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pendingIntent(ctx, requestCode, action, PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            // 未授权精确闹钟：用窗口闹钟（±5 分钟内触发）
            am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 5 * 60 * 1000L, pi)
        }
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // PendingIntent 按 Intent 匹配，action 必须和排闹钟时一致，否则 FLAG_NO_CREATE 返回 null、取消失效
        listOf(REQ_BUY to ACTION_BUY, REQ_SELL to ACTION_SELL).forEach { (code, action) ->
            pendingIntent(ctx, code, action, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        }
    }
}
