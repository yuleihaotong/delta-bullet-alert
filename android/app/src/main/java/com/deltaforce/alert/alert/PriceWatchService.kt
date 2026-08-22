package com.deltaforce.alert.alert

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.deltaforce.alert.R
import com.deltaforce.alert.data.PriceApi
import com.deltaforce.alert.data.WatchlistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 价格监控前台服务：
 * - 每 pollSeconds 轮询 moligod 实时价
 * - 自选子弹相对 30 天参考均值的偏离超过阈值 → 强提醒（QQ 通话式），每子弹冷却 30 分钟
 * - 参考均值每天刷新一次（历史接口 720 小时 → 均值）
 */
class PriceWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RingHelper.ensureChannels(this)
        startForeground(RingHelper.NOTIF_SERVICE, buildServiceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = WatchlistStore.get(this)
        store.monitoring = true
        // 保证时段闹钟已排上
        AlertScheduler.scheduleDaily(this, store.buyHour, store.buyMinute, AlertScheduler.REQ_BUY, AlertScheduler.ACTION_BUY)
        AlertScheduler.scheduleDaily(this, store.sellHour, store.sellMinute, AlertScheduler.REQ_SELL, AlertScheduler.ACTION_SELL)

        if (pollJob?.isActive != true) {
            pollJob = scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    private suspend fun watchLoop() {
        val store = WatchlistStore.get(this)
        while (true) {
            try {
                val prices = withContext(Dispatchers.IO) { PriceApi.fetchCurrentPrices() }
                store.cachePrices(prices)
                maybeRefreshRefs(store)
                checkThresholds(store, prices)
            } catch (e: Exception) {
                // 网络/限流/接口变化：静默重试
            }
            delay(store.pollSeconds * 1000L)
        }
    }

    /** 参考均值每天刷一次（对自选子弹拉 720h 历史求均值） */
    private suspend fun maybeRefreshRefs(store: WatchlistStore) {
        val now = System.currentTimeMillis()
        if (now - store.lastRefUpdateTs < 24 * 3600_000L) return
        val watched = store.getWatched()
        if (watched.isEmpty()) return
        val refs = store.getRefMeans().toMutableMap()
        for (w in watched) {
            try {
                val avg = PriceApi.fetchHistoryAvg(w.name, "1h", 720)
                if (avg.isNotEmpty()) refs[w.name] = avg.average()
            } catch (_: Exception) {
            }
            delay(2_000)
        }
        store.setRefMeans(refs)
        store.lastRefUpdateTs = now
    }

    /** 偏离阈值检测 → 触发提醒（冷却 30 分钟） */
    private fun checkThresholds(store: WatchlistStore, prices: List<com.deltaforce.alert.data.BulletPrice>) {
        val refs = store.getRefMeans()
        if (refs.isEmpty()) return
        val byName = prices.associateBy { it.name }
        val now = System.currentTimeMillis()
        val cooldown = 30 * 60_000L
        for (w in store.getWatched()) {
            val ref = refs[w.name] ?: continue
            val p = byName[w.name] ?: continue
            if (ref <= 0 || p.price <= 0) continue
            val dev = (p.price - ref) / ref
            val triggered = when {
                dev <= w.buyDev -> "买入"
                dev >= w.sellDev -> "卖出"
                else -> null
            }
            if (triggered != null && now - w.lastAlertTs > cooldown) {
                RingHelper.fullScreenAlert(
                    this,
                    "${triggered}提醒：${w.name}",
                    "现价 ${p.price}，偏离 30 天均值 ${"%.1f".format(dev * 100)}%（阈值 ${"%.1f".format(if (triggered == "买入") w.buyDev else w.sellDev)}%）",
                    if (triggered == "买入") "buy" else "sell"
                )
                store.updateLastAlertTs(w.name, now)
            }
        }
    }

    private fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(this, RingHelper.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("三角洲子弹提醒 · 监控中")
            .setContentText("正在轮询行情，买入/卖出触发时提醒")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        WatchlistStore.get(this).monitoring = false
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, PriceWatchService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PriceWatchService::class.java))
        }
    }
}
