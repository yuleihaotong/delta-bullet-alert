package com.deltaforce.alert.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deltaforce.alert.data.WatchlistStore

/** 开机/更新后：恢复监控服务与时段闹钟。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = WatchlistStore.get(ctx)
        if (store.monitoring) {
            PriceWatchService.start(ctx)
        }
        AlertScheduler.scheduleDaily(ctx, store.buyHour, store.buyMinute, AlertScheduler.REQ_BUY, AlertScheduler.ACTION_BUY)
        AlertScheduler.scheduleDaily(ctx, store.sellHour, store.sellMinute, AlertScheduler.REQ_SELL, AlertScheduler.ACTION_SELL)
    }
}
