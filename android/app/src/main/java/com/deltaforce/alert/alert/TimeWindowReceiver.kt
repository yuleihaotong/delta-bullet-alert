package com.deltaforce.alert.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 时段提醒：闹钟到点 → 触发 QQ 通话式强提醒，并预约下一天。 */
class TimeWindowReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AlertScheduler.ACTION_BUY -> {
                RingHelper.fullScreenAlert(
                    ctx,
                    "📉 买入提醒",
                    "现在是最佳买入时段，适合上线扫低价子弹（30 天规律：此时低于日均约 4%）"
                )
                val s = WatchStoreAccess.get(ctx)
                AlertScheduler.scheduleDaily(ctx, s.buyHour, s.buyMinute, AlertScheduler.REQ_BUY, AlertScheduler.ACTION_BUY)
            }
            AlertScheduler.ACTION_SELL -> {
                RingHelper.fullScreenAlert(
                    ctx,
                    "📈 卖出提醒",
                    "现在是最佳卖出时段，适合挂单卖出子弹（30 天规律：此时高于日均约 5%）"
                )
                val s = WatchStoreAccess.get(ctx)
                AlertScheduler.scheduleDaily(ctx, s.sellHour, s.sellMinute, AlertScheduler.REQ_SELL, AlertScheduler.ACTION_SELL)
            }
        }
    }
}

/** 避免包名循环引用的存储访问（内部用）。 */
internal object WatchStoreAccess {
    private var cache: com.deltaforce.alert.data.WatchlistStore? = null
    fun get(ctx: Context): com.deltaforce.alert.data.WatchlistStore {
        val c = cache
        if (c != null) return c
        return com.deltaforce.alert.data.WatchlistStore.get(ctx).also { cache = it }
    }
}
