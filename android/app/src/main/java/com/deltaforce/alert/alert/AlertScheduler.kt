package com.deltaforce.alert.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 每天固定时段的精确闹钟调度（07:00 买入 / 16:00 卖出）。 */
object AlertScheduler {
    const val REQ_BUY = 2001
    const val REQ_SELL = 2002
    const val ACTION_BUY = "com.deltaforce.alert.BUY_WINDOW"
    const val ACTION_SELL = "com.deltaforce.alert.SELL_WINDOW"

    fun scheduleDaily(ctx: Context, hour: Int, minute: Int, requestCode: Int, action: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, TimeWindowReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            // 未授权精确闹钟：用窗口闹钟（±5 分钟内触发）
            am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 5 * 60 * 1000L, pi)
        }
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        listOf(REQ_BUY, REQ_SELL).forEach { code ->
            val pi = PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, TimeWindowReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let { am.cancel(it) }
        }
    }
}
