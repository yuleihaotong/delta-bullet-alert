package com.deltaforce.alert.data

/**
 * 阈值判定：买卖阈值用「相对 30 天参考均值的百分比」表示（-3.0 = 低于均值 3%），
 * 和输入框标签「≤ %」、WatchItem 注释保持同一单位。
 *
 * 这里单独成对象是为了能被 JVM 单测覆盖：之前服务里把小数偏离（-0.03）直接和百分比
 * （-3.0）比较，等价于要求价格跌到均值的 -200%，买入提醒永远不会触发。
 */
object Thresholds {
    const val BUY = "buy"
    const val SELL = "sell"

    /** 当前价相对参考均值的偏离（百分数）。价格或参考值不可用时返回 null。 */
    fun deviationPercent(price: Int, ref: Double): Double? {
        if (price <= 0 || ref <= 0.0) return null
        return (price - ref) / ref * 100.0
    }

    /** 判断触发哪一侧：BUY / SELL / null（不触发或数据不可用）。 */
    fun evaluate(price: Int, ref: Double, buyDev: Double, sellDev: Double): String? {
        val dev = deviationPercent(price, ref) ?: return null
        return when {
            dev <= buyDev -> BUY
            dev >= sellDev -> SELL
            else -> null
        }
    }
}
