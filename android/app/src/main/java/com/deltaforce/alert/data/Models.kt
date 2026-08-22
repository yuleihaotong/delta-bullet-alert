package com.deltaforce.alert.data

/** 一颗子弹的实时价格 */
data class BulletPrice(
    val objectId: String,
    val name: String,
    val price: Int,
    val updatedAtMs: Long,
)

/** 自选子弹 + 提醒阈值（偏离 30 天均值百分比，如 -3 表示低于均值 3% 提醒买入） */
data class WatchItem(
    val name: String,
    val buyDev: Double = -3.0,   // 触发买入提醒的偏离阈值 %
    val sellDev: Double = 6.0,   // 触发卖出提醒的偏离阈值 %
    val lastAlertTs: Long = 0L,  // 冷却用
)

/** 子弹当前状态（UI 用） */
data class BulletStatus(
    val name: String,
    val price: Int?,
    val refMean: Double?,     // 参考均值（30 天）
    val deviation: Double?,   // (price - ref)/ref
) {
    val tag: String
        get() = when {
            deviation == null -> "无数据"
            deviation <= -0.03 -> "低点"
            deviation >= 0.06 -> "高点"
            else -> "正常"
        }
}

/** 日内模型：每小时相对均值偏离（来自 30 天分析） */
data class HourModel(val hour: Int, val meanDev: Double, val minDev: Double, val maxDev: Double)
