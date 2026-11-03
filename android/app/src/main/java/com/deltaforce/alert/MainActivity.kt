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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
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
import com.deltaforce.alert.databinding.GroupHeaderBinding
import com.deltaforce.alert.databinding.ItemBulletBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val store by lazy { WatchlistStore.get(this) }
    private var bullets: List<BulletPrice> = emptyList()
    private val hourlyModel: List<HourModel> by lazy { loadHourlyModel() }
    private val gradeMap: Map<String, Int> by lazy { loadGradeMap() }
    private val collapsedGroups = mutableSetOf<String>()
    private val freshHandler = Handler(Looper.getMainLooper())
    private val freshTick = object : Runnable {
        override fun run() {
            updateMonitorUi()
            freshHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(ThemeIndex.styleOf(store.themeIndex))
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

        setupThemeSpinner()
        setupSearch()
        setupSortChips()
        setupGroupToggle()
        ensureRefMeans()
        loadSettings()
        updateMonitorUi()
        showCurrentHour()
        ensurePermissions()
        refreshPrices()
    }

    private fun setupThemeSpinner() {
        val themes = resources.getStringArray(R.array.themes)
        binding.themeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, themes
        )
        binding.themeSpinner.setSelection(store.themeIndex)
        binding.themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (store.themeIndex != pos) {
                    store.themeIndex = pos
                    recreate()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** 渲染子弹列表：搜索过滤 → 排序 → 分组（口径/品质）→ 组内可折叠 */
    private fun renderList() {
        binding.list.removeAllViews()
        val refs = store.getRefMeans()
        val watchedNames = store.getWatched().map { it.name }.toSet()
        val query = binding.searchBox.text.toString().trim()

        var filtered = bullets.filter { it.name.contains(query, ignoreCase = true) }
        binding.bulletCount.text = if (query.isNotEmpty()) {
            "找到 ${filtered.size} 种（${query}）"
        } else {
            "共 ${bullets.size} 种 · 监控 ${watchedNames.size} 种"
        }

        filtered = when (store.sortMode) {
            "price_asc" -> filtered.sortedBy { it.price }
            "price_desc" -> filtered.sortedByDescending { it.price }
            "grade" -> filtered.sortedWith(compareByDescending<BulletPrice> { gradeOf(it.name) }.thenByDescending { it.price })
            "return" -> filtered.sortedWith(compareBy<BulletPrice> {
                val r = refs[it.name]; if (r != null && r > 0) (it.price - r) / r else Double.MAX_VALUE
            })
            else -> filtered
        }

        val groups: List<Pair<String, List<BulletPrice>>> = if (store.groupByMode == "grade") {
            filtered.groupBy { "Lv.${gradeOf(it.name)} 弹" }.toList()
                .sortedWith(compareByDescending<Pair<String, List<BulletPrice>>> { it.first })
        } else {
            filtered.groupBy { caliberOf(it.name) }.toList().sortedBy { it.first }
        }

        for ((gname, gitems) in groups) {
            val gh = GroupHeaderBinding.inflate(layoutInflater, binding.list, false)
            gh.groupName.text = gname
            gh.groupCount.text = "${gitems.size} 种"
            val collapsed = collapsedGroups.contains(gname)
            gh.groupArrow.rotation = if (collapsed) 180f else 0f

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (collapsed) View.GONE else View.VISIBLE
            }
            for (p in gitems) container.addView(buildBulletRow(p, refs, watchedNames))

            gh.root.setOnClickListener {
                val nowCollapsed = !collapsedGroups.contains(gname)
                if (nowCollapsed) collapsedGroups.add(gname) else collapsedGroups.remove(gname)
                container.visibility = if (nowCollapsed) View.GONE else View.VISIBLE
                gh.groupArrow.animate().rotation(if (nowCollapsed) 180f else 0f).setDuration(180).start()
            }
            binding.list.addView(gh.root)
            binding.list.addView(container)
        }
    }

    private fun buildBulletRow(
        p: BulletPrice,
        refs: Map<String, Double>,
        watchedNames: Set<String>
    ): View {
        val b = ItemBulletBinding.inflate(layoutInflater, null, false)
        b.name.text = p.name
        b.price.text = p.price.toString()
        val dev = refs[p.name]?.let { r -> if (r > 0) (p.price - r) / r else null }
        b.dev.text = if (dev != null) "%.1f%%".format(dev * 100) else "--"
        val chipBg: Int
        val chipColorAttr: Int
        when {
            dev == null -> { chipBg = R.drawable.bg_chip_normal; chipColorAttr = R.attr.appTextSecondary }
            dev <= -0.03 -> { chipBg = R.drawable.bg_chip_buy; chipColorAttr = R.attr.appBuy }
            dev >= 0.06 -> { chipBg = R.drawable.bg_chip_sell; chipColorAttr = R.attr.appSell }
            else -> { chipBg = R.drawable.bg_chip_normal; chipColorAttr = R.attr.appTextSecondary }
        }
        b.dev.background = ContextCompat.getDrawable(this, chipBg)
        b.dev.setTextColor(attrColor(chipColorAttr))
        val watched = watchedNames.contains(p.name)
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

    /** 提取口径：如 5.56x45mm / 7.62x54R / 12 Gauge / .45 ACP / 箭矢 */
    private fun caliberOf(name: String): String {
        if (name.contains("箭矢")) return "箭矢"
        val clean = name.removePrefix("金").removePrefix("紫").removePrefix("蓝")
        val m = Regex(
            """^([0-9]+(?:\.[0-9]+)?(?:mm)?x[0-9]+[A-Za-z]*|\.[0-9]+[A-Za-z]+|\.[0-9]+ [A-Za-z]+|[0-9]+ Gauge|45-70 Govt)"""
        ).find(clean)
        return m?.value ?: clean.substringBefore(" ").ifEmpty { name }
    }

    private fun gradeOf(name: String): Int = gradeMap[name] ?: 1

    private fun loadGradeMap(): Map<String, Int> {
        return try {
            val json = assets.open("grade_map.json").bufferedReader().use { it.readText() }
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optInt(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun setupSearch() {
        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderList() }
        })
    }

    private fun setupSortChips() {
        val map = listOf(
            binding.sortDefault to "default",
            binding.sortPriceAsc to "price_asc",
            binding.sortPriceDesc to "price_desc",
            binding.sortGrade to "grade",
            binding.sortReturn to "return",
        )
        for ((pill, mode) in map) {
            pill.setOnClickListener {
                store.sortMode = mode
                refreshSortPills()
                renderList()
            }
        }
        refreshSortPills()
    }

    private fun refreshSortPills() {
        fun set(v: TextView, sel: Boolean) {
            v.background = ContextCompat.getDrawable(this, if (sel) R.drawable.bg_pill_sel else R.drawable.bg_pill)
            v.setTextColor(attrColor(if (sel) R.attr.appOnPrimary else R.attr.appTextSecondary))
        }
        set(binding.sortDefault, store.sortMode == "default")
        set(binding.sortPriceAsc, store.sortMode == "price_asc")
        set(binding.sortPriceDesc, store.sortMode == "price_desc")
        set(binding.sortGrade, store.sortMode == "grade")
        set(binding.sortReturn, store.sortMode == "return")
    }

    private fun setupGroupToggle() {
        binding.groupCaliber.setOnClickListener {
            store.groupByMode = "caliber"
            refreshGroupPills()
            renderList()
        }
        binding.groupGrade.setOnClickListener {
            store.groupByMode = "grade"
            refreshGroupPills()
            renderList()
        }
        refreshGroupPills()
    }

    private fun refreshGroupPills() {
        val caliber = store.groupByMode == "caliber"
        binding.groupCaliber.background = ContextCompat.getDrawable(this, if (caliber) R.drawable.bg_pill_sel else R.drawable.bg_pill)
        binding.groupCaliber.setTextColor(attrColor(if (caliber) R.attr.appOnPrimary else R.attr.appTextSecondary))
        binding.groupGrade.background = ContextCompat.getDrawable(this, if (!caliber) R.drawable.bg_pill_sel else R.drawable.bg_pill)
        binding.groupGrade.setTextColor(attrColor(if (!caliber) R.attr.appOnPrimary else R.attr.appTextSecondary))
    }

    override fun onResume() {
        super.onResume()
        // 监控服务可能在后台更新了行情缓存 → 回到界面时用最新数据重渲染
        if (store.monitoring) {
            val cached = store.cachedPrices()
            if (cached.isNotEmpty()) {
                bullets = cached
                renderList()
                updateStatusFromCache(cached)
            }
        }
        updateMonitorUi()
        freshHandler.removeCallbacks(freshTick)
        freshHandler.post(freshTick)
    }

    override fun onPause() {
        super.onPause()
        freshHandler.removeCallbacks(freshTick)
    }

    private fun updateStatusFromCache(prices: List<BulletPrice>) {
        val t = beijingTime(prices.firstOrNull()?.updatedAtMs)
        binding.status.text = "监控数据 ${prices.size} 种子弹 · 行情更新 $t（北京）"
    }

    private fun beijingTime(ms: Long?): String {
        if (ms == null || ms <= 0) return "--:--"
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply { timeInMillis = ms }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }


    private fun refreshPrices() {
        binding.status.text = "刷新中…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                bullets = withContext(Dispatchers.IO) { com.deltaforce.alert.data.PriceApi.fetchCurrentPrices() }
                store.cachePrices(bullets)
                renderList()
                val t = beijingTime(bullets.firstOrNull()?.updatedAtMs)
                binding.status.text = "已更新 ${bullets.size} 种子弹 · 行情更新 $t（北京）· 每发单价"
            } catch (e: Exception) {
                bullets = store.cachedPrices()
                renderList()
                val cached = bullets.firstOrNull()?.updatedAtMs
                val t = beijingTime(cached)
                binding.status.text = if (cached != null && cached > 0) {
                    "刷新失败（${e.message ?: "网络错误"}）· 显示缓存数据（行情 $t）"
                } else {
                    "刷新失败（${e.message ?: "网络错误"}）· 无缓存数据"
                }
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
            attrColor(if (mon) R.attr.appDangerBg else R.attr.appPrimary)
        )
        binding.btnMonitor.strokeColor = ColorStateList.valueOf(
            attrColor(if (mon) R.attr.appDangerStroke else android.R.color.transparent)
        )
        binding.btnMonitor.strokeWidth = if (mon) (resources.displayMetrics.density).toInt() else 0
        binding.btnMonitor.setTextColor(
            ColorStateList.valueOf(attrColor(if (mon) R.attr.appDanger else R.attr.appOnPrimary))
        )
        val dot = if (mon) R.drawable.bg_dot_on else R.drawable.bg_dot
        binding.liveText.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
        binding.liveText.text = if (mon) "监控中 · 每 ${store.pollSeconds}s${fetchHealthSuffix()}" else "已停止"
    }

    /** 行情健康度：让「拉不到数据」可见，否则和「没触发」看起来一样 */
    private fun fetchHealthSuffix(): String {
        val err = store.lastFetchError
        if (err.isNotEmpty()) return " · 拉取失败：${err.take(20)}"
        val ts = store.lastFetchOkTs
        if (ts <= 0L) return " · 等待首次行情"
        val minutes = (System.currentTimeMillis() - ts) / 60_000L
        return if (minutes < 1) " · 行情刚刚更新" else " · 行情 ${minutes} 分钟前"
    }

    private fun showCurrentHour() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val m = hourlyModel.firstOrNull { it.hour == h }
        if (m != null) {
            val zone: String
            val tag: String
            val tagColorAttr: Int
            val tagBg: Int
            when {
                m.meanDev <= -0.03 -> {
                    zone = "低价区（适合买入）"; tag = "低价区"; tagColorAttr = R.attr.appBuy; tagBg = R.drawable.bg_chip_buy
                }
                m.meanDev >= 0.06 -> {
                    zone = "高价区（适合卖出）"; tag = "高价区"; tagColorAttr = R.attr.appSell; tagBg = R.drawable.bg_chip_sell
                }
                else -> {
                    zone = "正常区"; tag = "正常区"; tagColorAttr = R.attr.appTextSecondary; tagBg = R.drawable.bg_chip_normal
                }
            }
            binding.hourInfo.text = "现在 ${"%02d:00".format(h)} · 相对均值 ${"%.1f%%".format(m.meanDev * 100)} · $zone（30 天规律）"
            binding.hourTag.text = tag
            binding.hourTag.background = ContextCompat.getDrawable(this, tagBg)
            binding.hourTag.setTextColor(attrColor(tagColorAttr))
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

    /** 从内置资产加载 55 种子弹的 30 天参考均值（离线可显示偏离%） */
    private fun loadRefMeansAsset(): Map<String, Double> {
        return try {
            val json = assets.open("ref_means.json").bufferedReader().use { it.readText() }
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.optDouble(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun ensureRefMeans() {
        store.ensureRefMeansFromBundle(loadRefMeansAsset())
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
