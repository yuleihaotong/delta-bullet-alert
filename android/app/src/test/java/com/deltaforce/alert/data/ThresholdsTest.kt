package com.deltaforce.alert.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 阈值判定的单位回归测试：百分比阈值必须和百分比偏离比较。 */
class ThresholdsTest {

    @Test
    fun deviationPercentIsPercentNotFraction() {
        assertEquals(-3.0, Thresholds.deviationPercent(970, 1000.0)!!, 1e-9)
        assertEquals(8.3, Thresholds.deviationPercent(586, 541.0)!!, 0.05)
    }

    @Test
    fun buyTriggersAtDefaultMinus3Percent() {
        // 默认阈值 -3%：970 vs 1000 恰好触发买入
        assertEquals(Thresholds.BUY, Thresholds.evaluate(970, 1000.0, -3.0, 6.0))
        assertEquals(Thresholds.BUY, Thresholds.evaluate(940, 1000.0, -3.0, 6.0))
    }

    @Test
    fun buyDoesNotTriggerJustAboveThreshold() {
        assertNull(Thresholds.evaluate(971, 1000.0, -3.0, 6.0))
        assertNull(Thresholds.evaluate(1000, 1000.0, -3.0, 6.0))
    }

    @Test
    fun sellTriggersAtDefaultPlus6Percent() {
        assertEquals(Thresholds.SELL, Thresholds.evaluate(1060, 1000.0, -3.0, 6.0))
        assertNull(Thresholds.evaluate(1059, 1000.0, -3.0, 6.0))
    }

    @Test
    fun realisticBulletPrice() {
        // 报告里 5.56x45mm M855：30 天均值 541，当前 586 → +8.3%，应触发卖出
        assertEquals(Thresholds.SELL, Thresholds.evaluate(586, 541.0, -3.0, 6.0))
        // 同一颗子弹跌到 520（-3.9%）→ 应触发买入
        assertEquals(Thresholds.BUY, Thresholds.evaluate(520, 541.0, -3.0, 6.0))
    }

    @Test
    fun missingDataNeverTriggers() {
        assertNull(Thresholds.evaluate(0, 541.0, -3.0, 6.0))
        assertNull(Thresholds.evaluate(586, 0.0, -3.0, 6.0))
        assertNull(Thresholds.evaluate(586, -1.0, -3.0, 6.0))
    }
}
