package com.deltaforce.alert

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

        binding.list.adapter = adapter
        binding.btnRefresh.setOnClickListener { refreshPrices() }
        binding.btnStart.setOnClickListener {
            saveSettings()
            ensurePermissions()
            PriceWatchService.start(this)
            Toast.makeText(this, "监控已开始", Toast.LENGTH_SHORT).show()
            updateMonitorUi()
        }
        binding.btnStop.setOnClickListener {
            PriceWatchService.stop(this)
            AlertScheduler.cancelAll(this)
            Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show()
            updateMonitorUi()
        }
        binding.btnTest.setOnClickListener {
            ensurePermissions()
            RingHelper.fullScreenAlert(this, "📞 测试强提醒", "这是 QQ 通话式提醒效果预览")
        }
        binding.btnSave.setOnClickListener { saveSettings(); Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show() }

        loadSettings()
        updateMonitorUi()
        showCurrentHour()
        ensurePermissions()
        refreshPrices()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = bullets.size
        override fun getItem(pos: Int) = bullets[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView != null) ItemBulletBinding.bind(convertView)
            else ItemBulletBinding.inflate(LayoutInflater.from(this@MainActivity), parent, false)
            val p = bullets[pos]
            b.name.text = p.name
            b.price.text = p.price.toString()
            val refs = store.getRefMeans()
            val dev = refs[p.name]?.let { r -> if (r > 0) (p.price - r) / r else null }
            b.dev.text = if (dev != null) "%.1f%%".format(dev * 100) else "--"
            b.dev.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    when {
                        dev == null -> android.R.color.darker_gray
                        dev <= -0.03 -> android.R.color.holo_green_dark
                        dev >= 0.06 -> android.R.color.holo_red_dark
                        else -> android.R.color.darker_gray
                    }
                )
            )
            val watched = store.isWatched(p.name)
            b.watch.setOnCheckedChangeListener(null)
            b.watch.isChecked = watched
            b.watch.setOnCheckedChangeListener { _, checked ->
                store.toggleWatch(
                    WatchItem(name = p.name, buyDev = store.buyDevInput, sellDev = store.sellDevInput),
                    checked
                )
            }
            return b.root
        }
    }

    private fun refreshPrices() {
        binding.status.text = "刷新中…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                bullets = withContext(Dispatchers.IO) { com.deltaforce.alert.data.PriceApi.fetchCurrentPrices() }
                store.cachePrices(bullets)
                adapter.notifyDataSetChanged()
                val t = Calendar.getInstance()
                binding.status.text = "已更新 ${bullets.size} 种子弹 · ${"%02d:%02d".format(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))}"
            } catch (e: Exception) {
                bullets = store.cachedPrices()
                adapter.notifyDataSetChanged()
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
        binding.btnStart.isEnabled = !store.monitoring
        binding.btnStop.isEnabled = store.monitoring
    }

    private fun showCurrentHour() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val m = hourlyModel.firstOrNull { it.hour == h }
        binding.hourInfo.text = if (m != null) {
            val zone = when {
                m.meanDev <= -0.03 -> "低价区（适合买入）"
                m.meanDev >= 0.06 -> "高价区（适合卖出）"
                else -> "正常区"
            }
            "当前 ${"%02d:00".format(h)} · 相对均值 ${"%.1f%%".format(m.meanDev * 100)} · $zone（30 天规律）"
        } else {
            "当前 ${"%02d:00".format(h)} · 无模型数据"
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
