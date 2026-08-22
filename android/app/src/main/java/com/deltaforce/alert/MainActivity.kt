package com.deltaforce.alert

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.deltaforce.alert.alert.AlertScheduler
import com.deltaforce.alert.alert.PriceWatchService
import com.deltaforce.alert.alert.RingHelper
import com.deltaforce.alert.data.BulletPrice
import com.deltaforce.alert.data.HourModel
import com.deltaforce.alert.data.WatchItem
import com.deltaforce.alert.data.WatchlistStore
import com.deltaforce.alert.databinding.ActivityMainBinding
import com.deltaforce.alert.databinding.ItemBulletBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val store by lazy { WatchlistStore.get(this) }
    private var bullets: List<BulletPrice> = emptyList()
    private val hourlyModel: List<HourModel> by lazy { loadHourlyModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRefresh.setOnClickListener { refreshPrices() }
        binding.btnMonitor.setOnClickListener {
            if (store.monitoring) {
                PriceWatchService.stop(this)
                AlertScheduler.cancelAll(this)
                Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show()
            } else {
                saveSettings()
                ensurePermissions()
                PriceWatchService.start(this)
                Toast.makeText(this, "监控已开始", Toast.LENGTH_SHORT).show()
            }
            updateMonitorUi()
        }
        binding.btnTest.setOnClickListener {
            ensurePermissions()
            RingHelper.fullScreenAlert(this, "测试强提醒", "QQ 通话式响铃预览 · 锁屏也会响", "buy")
        }
        binding.btnSave.setOnClickListener { saveSettings(); Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show() }

        loadSettings()
        updateMonitorUi()
        showCurrentHour()
        ensurePermissions()
        refreshPrices()
    }

    /** 渲染自选子弹列表（LinearLayout，随外层 ScrollView 一起滚动） */
    private fun renderList() {
        binding.list.removeAllViews()
        val refs = store.getRefMeans()
        for (p in bullets) {
            val b = ItemBulletBinding.inflate(layoutInflater, binding.list, false)
            b.name.text = p.name
            b.price.text = p.price.toString()
            val dev = refs[p.name]?.let { r -> if (r > 0) (p.price - r) / r else null }
            b.dev.text = if (dev != null) "%.1f%%".format(dev * 100) else "--"
            val chipBg: Int
            val chipColor: Int
            when {
                dev == null -> { chipBg = R.drawable.bg_chip_normal; chipColor = R.color.text_secondary }
                dev <= -0.03 -> { chipBg = R.drawable.bg_chip_buy; chipColor = R.color.buy }
                dev >= 0.06 -> { chipBg = R.drawable.bg_chip_sell; chipColor = R.color.sell }
                else -> { chipBg = R.drawable.bg_chip_normal; chipColor = R.color.text_secondary }
            }
            b.dev.background = ContextCompat.getDrawable(this, chipBg)
            b.dev.setTextColor(ContextCompat.getColor(this, chipColor))
            val watched = store.isWatched(p.name)
            b.watch.setOnCheckedChangeListener(null)
            b.watch.isChecked = watched
            b.watch.setOnCheckedChangeListener { _, checked ->
                store.toggleWatch(
                    WatchItem(name = p.name, buyDev = store.buyDevInput, sellDev = store.sellDevInput),
                    checked
                )
            }
            binding.list.addView(b.root)
        }
    }

    private fun refreshPrices() {
        binding.status.text = "刷新中…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                bullets = withContext(Dispatchers.IO) { com.deltaforce.alert.data.PriceApi.fetchCurrentPrices() }
                store.cachePrices(bullets)
                renderList()
                val t = Calendar.getInstance()
                binding.status.text = "已更新 ${bullets.size} 种子弹 · ${"%02d:%02d".format(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))}"
            } catch (e: Exception) {
                bullets = store.cachedPrices()
                renderList()
                binding.status.text = "刷新失败（${e.message ?: "网络错误"}），显示上次缓存"
            }
        }
    }

    private fun saveSettings() {
        store.buyHour = binding.etBuyHour.text.toString().toIntOrNull() ?: 7
        store.buyMinute = 0
        store.sellHour = binding.etSellHour.text.toString().toIntOrNull() ?: 16
        store.sellMinute = 0
        store.buyDevInput = binding.etBuyDev.text.toString().toDoubleOrNull() ?: -3.0
        store.sellDevInput = binding.etSellDev.text.toString().toDoubleOrNull() ?: 6.0
        store.pollSeconds = binding.etPoll.text.toString().toIntOrNull() ?: 30
    }

    private fun loadSettings() {
        binding.etBuyHour.setText(store.buyHour.toString())
        binding.etSellHour.setText(store.sellHour.toString())
        binding.etBuyDev.setText(store.buyDevInput.toString())
        binding.etSellDev.setText(store.sellDevInput.toString())
        binding.etPoll.setText(store.pollSeconds.toString())
    }

    private fun updateMonitorUi() {
        val mon = store.monitoring
        binding.btnMonitor.text = if (mon) "停止监控" else "开始监控"
        binding.btnMonitor.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (mon) R.color.danger_bg else R.color.gold)
        )
        binding.btnMonitor.strokeColor = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (mon) R.color.danger_stroke else android.R.color.transparent)
        )
        binding.btnMonitor.strokeWidth = if (mon) (resources.displayMetrics.density).toInt() else 0
        binding.btnMonitor.setTextColor(
            ContextCompat.getColor(this, if (mon) R.color.danger else R.color.on_gold)
        )
        val dot = if (mon) R.drawable.bg_dot_on else R.drawable.bg_dot
        binding.liveText.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
        binding.liveText.text = if (mon) "监控中 · 每 ${store.pollSeconds}s" else "已停止"
    }

    private fun showCurrentHour() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val m = hourlyModel.firstOrNull { it.hour == h }
        if (m != null) {
            val zone: String
            val tag: String
            val tagColor: Int
            val tagBg: Int
            when {
                m.meanDev <= -0.03 -> {
                    zone = "低价区（适合买入）"; tag = "低价区"; tagColor = R.color.buy; tagBg = R.drawable.bg_chip_buy
                }
                m.meanDev >= 0.06 -> {
                    zone = "高价区（适合卖出）"; tag = "高价区"; tagColor = R.color.sell; tagBg = R.drawable.bg_chip_sell
                }
                else -> {
                    zone = "正常区"; tag = "正常区"; tagColor = R.color.text_secondary; tagBg = R.drawable.bg_chip_normal
                }
            }
            binding.hourInfo.text = "现在 ${"%02d:00".format(h)} · 相对均值 ${"%.1f%%".format(m.meanDev * 100)} · $zone（30 天规律）"
            binding.hourTag.text = tag
            binding.hourTag.background = ContextCompat.getDrawable(this, tagBg)
            binding.hourTag.setTextColor(ContextCompat.getColor(this, tagColor))
        } else {
            binding.hourInfo.text = "当前 ${"%02d:00".format(h)} · 无模型数据"
            binding.hourTag.text = "无数据"
        }
    }

    private fun loadHourlyModel(): List<HourModel> {
        return try {
            val json = assets.open("hourly_model.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HourModel(
                            hour = o.getInt("hour"),
                            meanDev = o.optDouble("mean", 0.0),
                            minDev = o.optDouble("min", 0.0),
                            maxDev = o.optDouble("max", 0.0),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent().not()
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
