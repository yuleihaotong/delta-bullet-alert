package com.deltaforce.alert.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences 存储：自选子弹、阈值、设置、参考均值、冷却。 */
class WatchlistStore private constructor(ctx: Context) {
    private val sp = ctx.getSharedPreferences("delta_alert", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var inst: WatchlistStore? = null
        fun get(ctx: Context): WatchlistStore =
            inst ?: synchronized(this) {
                inst ?: WatchlistStore(ctx.applicationContext).also { inst = it }
            }
    }

    // ---------------- 设置 ----------------
    var buyHour: Int
        get() = sp.getInt("buy_hour", 7)
        set(v) = sp.edit().putInt("buy_hour", v).apply()
    var buyMinute: Int
        get() = sp.getInt("buy_minute", 0)
        set(v) = sp.edit().putInt("buy_minute", v).apply()
    var sellHour: Int
        get() = sp.getInt("sell_hour", 16)
        set(v) = sp.edit().putInt("sell_hour", v).apply()
    var sellMinute: Int
        get() = sp.getInt("sell_minute", 0)
        set(v) = sp.edit().putInt("sell_minute", v).apply()
    var pollSeconds: Int
        get() = sp.getInt("poll_sec", 30)
        set(v) = sp.edit().putInt("poll_sec", v.coerceIn(15, 600)).apply()
    var buyDevInput: Double
        get() = sp.getFloat("buy_dev", -3.0f).toDouble()
        set(v) = sp.edit().putFloat("buy_dev", v.toFloat()).apply()
    var sellDevInput: Double
        get() = sp.getFloat("sell_dev", 6.0f).toDouble()
        set(v) = sp.edit().putFloat("sell_dev", v.toFloat()).apply()
    var monitoring: Boolean
        get() = sp.getBoolean("monitoring", false)
        set(v) = sp.edit().putBoolean("monitoring", v).apply()
    var themeIndex: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v.coerceIn(0, 5)).apply()
    var listCollapsed: Boolean
        get() = sp.getBoolean("list_collapsed", false)
        set(v) = sp.edit().putBoolean("list_collapsed", v).apply()

    // ---------------- 自选子弹 ----------------
    private fun watchKey(): String = "watched"

    fun getWatched(): List<WatchItem> {
        val raw = sp.getString(watchKey(), "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        WatchItem(
                            name = o.getString("name"),
                            buyDev = o.optDouble("buy_dev", -3.0),
                            sellDev = o.optDouble("sell_dev", 6.0),
                            lastAlertTs = o.optLong("last_alert_ts", 0L),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setWatched(list: List<WatchItem>) {
        val arr = JSONArray()
        for (w in list) {
            arr.put(
                JSONObject()
                    .put("name", w.name)
                    .put("buy_dev", w.buyDev)
                    .put("sell_dev", w.sellDev)
                    .put("last_alert_ts", w.lastAlertTs)
            )
        }
        sp.edit().putString(watchKey(), arr.toString()).apply()
    }

    fun isWatched(name: String): Boolean = getWatched().any { it.name == name }

    fun toggleWatch(item: WatchItem, on: Boolean) {
        val list = getWatched().toMutableList()
        if (on) {
            if (list.none { it.name == item.name }) list.add(item)
        } else {
            list.removeAll { it.name == item.name }
        }
        setWatched(list)
    }

    fun updateLastAlertTs(name: String, ts: Long) {
        val list = getWatched().map {
            if (it.name == name) it.copy(lastAlertTs = ts) else it
        }
        setWatched(list)
    }

    // ---------------- 参考均值（30 天，每日刷新） ----------------
    private fun refKey(): String = "ref_means"

    fun getRefMeans(): Map<String, Double> {
        val raw = sp.getString(refKey(), "{}") ?: "{}"
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optDouble(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setRefMeans(map: Map<String, Double>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        sp.edit().putString(refKey(), o.toString()).apply()
    }

    var lastRefUpdateTs: Long
        get() = sp.getLong("ref_update_ts", 0L)
        set(v) = sp.edit().putLong("ref_update_ts", v).apply()

    /** 若参考均值缺失或过期（>7 天），用内置资产数据填充（离线也能显示偏离%） */
    fun ensureRefMeansFromBundle(map: Map<String, Double>, maxAgeMs: Long = 7L * 24 * 3600_000L) {
        val now = System.currentTimeMillis()
        val refs = getRefMeans()
        val stale = now - lastRefUpdateTs > maxAgeMs
        if ((refs.isEmpty() || stale) && map.isNotEmpty()) {
            setRefMeans(map)
            lastRefUpdateTs = now
        }
    }

    // ---------------- 上次行情缓存（离线展示用） ----------------
    fun cachePrices(list: List<BulletPrice>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("object_id", p.objectId)
                    .put("name", p.name)
                    .put("price", p.price)
                    .put("updated_at_ms", p.updatedAtMs)
            )
        }
        sp.edit().putString("price_cache", arr.toString()).apply()
    }

    fun cachedPrices(): List<BulletPrice> {
        val raw = sp.getString("price_cache", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BulletPrice(
                            objectId = o.optString("object_id"),
                            name = o.getString("name"),
                            price = o.optInt("price"),
                            updatedAtMs = o.optLong("updated_at_ms"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
