"""阈值单位回归测试：和 Android 的 ThresholdsTest.kt 逐条对齐。"""
import unittest

from watcher import thresholds


class ThresholdsTest(unittest.TestCase):
    def test_deviation_is_percent_not_fraction(self):
        self.assertAlmostEqual(thresholds.deviation_percent(970, 1000.0), -3.0)
        self.assertAlmostEqual(thresholds.deviation_percent(586, 541.0), 8.318, places=2)

    def test_buy_triggers_at_minus_3(self):
        self.assertEqual(thresholds.evaluate(970, 1000.0, -3.0, 6.0), "buy")
        self.assertEqual(thresholds.evaluate(940, 1000.0, -3.0, 6.0), "buy")

    def test_buy_does_not_trigger_just_above(self):
        self.assertIsNone(thresholds.evaluate(971, 1000.0, -3.0, 6.0))
        self.assertIsNone(thresholds.evaluate(1000, 1000.0, -3.0, 6.0))

    def test_sell_triggers_at_plus_6(self):
        self.assertEqual(thresholds.evaluate(1060, 1000.0, -3.0, 6.0), "sell")
        self.assertIsNone(thresholds.evaluate(1059, 1000.0, -3.0, 6.0))

    def test_realistic_bullet(self):
        # 上一轮报告里的 5.56x45mm M855：均值 541，现价 586（+8.3%）
        self.assertEqual(thresholds.evaluate(586, 541.0, -3.0, 6.0), "sell")
        self.assertEqual(thresholds.evaluate(520, 541.0, -3.0, 6.0), "buy")

    def test_missing_data_never_triggers(self):
        self.assertIsNone(thresholds.evaluate(0, 541.0, -3.0, 6.0))
        self.assertIsNone(thresholds.evaluate(586, 0.0, -3.0, 6.0))
        self.assertIsNone(thresholds.evaluate(586, -1.0, -3.0, 6.0))

    def test_describe_units_are_consistent(self):
        title, body = thresholds.describe("buy", "5.56x45mm M855", 520, 541.0, -3.0)
        self.assertIn("买入", title)
        self.assertIn("-3.9%", body)      # 偏离是百分比
        self.assertIn("-3.0%", body)      # 阈值也是百分比，同单位


if __name__ == "__main__":
    unittest.main()
