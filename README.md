# 三角洲子弹提醒（Delta Bullet Alert）

《三角洲行动》交易行子弹 **价格预警 / 定时提醒** 工具。

- **Android App（Kotlin）**：到点/触发时发系统通知，并支持 **QQ 通话式强提醒**（全屏来电界面 + 响铃 + 震动，锁屏也响）。
- **行情分析（Python）**：拉取 30 天逐小时价格，分析日内最佳买卖时段。
- **数据源**：moligod.com 实时价/历史接口（第三方数据站，非游戏官方）。

> ⚠️ 本工具**不做游戏内自动操作**，只是提醒你"该买/该卖"；是否在游戏内交易、是否承担倒卖风险，由使用者自行决定。行情数据来自第三方网站，接口可能随时变动或限流。

## 核心结论（30 天数据实测，55 种子弹 × 720 小时，北京时间）

- **最佳买入时段：07:00–08:00**（日内最低，低于均值约 4%）
- **最佳卖出时段：16:00–17:00**（日内最高，高于均值约 5%）
- 买卖价差约 9 个百分点，**低于交易行 10% 的卖出手续费下限**——固定时段按均价硬买硬卖覆盖不了手续费，
  利润只能来自「低价时段抢低价挂单、高价时段挂高价」，这也正是提醒工具的价值点。
- 完整报告：`output/intraday_report.md`

> **⚠️ 该报告尚未用修复后的脚本重跑。** v1.4.0 及更早版本的 `analyze_intraday.py` 在 §4 的
> `A.avg-to-avg` 行用了「卖出小时最高价」而不是均价（`lambda x: x[3]` 应为 `x[1]`），
> 所以那一行偏高（合成数据上应为 -0.63%，旧版算出 +0.47%）。
> 由于数据缓存不入库，这里保留原报告原文，**数字以重跑结果为准**：
> `python tools/fetch_history.py && python tools/analyze_intraday.py`。
> 重跑后 §2 会多出一段「按日聚类 bootstrap 置信区间」：55 颗子弹同一天高度相关，
> 有效样本是 ~30 个交易日，不是 1600 个子弹×日。

## 目录结构

```
delta-bullet-alert/
├── android/                  # Android App 源码（Kotlin）
│   └── app/src/
│       ├── main/java/com/deltaforce/alert/
│       │   ├── data/         # moligod 接口 + 解密 + 本地存储 + 阈值判定
│       │   ├── alert/        # 时段闹钟 / 前台监控 / 全屏来电强提醒
│       │   └── MainActivity.kt   # 自选列表 + 设置
│       └── test/java/…       # JVM 单测（阈值单位）
├── tools/
│   ├── fetch_history.py      # 拉取 55 种子弹 30 天历史（Python）
│   ├── analyze_intraday.py   # 日内买卖时机分析
│   └── selftest_analyze.py   # 合成数据自检（不打真实接口）
├── output/                   # 分析报告与图表
├── data/history/             # 历史数据缓存（不入库，可随时重新拉）
└── 方案.md                    # 技术方案（含开源/盈利策略）
```

## Android App 构建

```bash
# 环境：JDK 17 + Android SDK 34 + Gradle 8.7
cd android
echo "sdk.dir=$HOME/Android" > local.properties
gradle assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
gradle test                   # JVM 单测（阈值单位回归，不需要模拟器）
```

> 仓库没有提交 Gradle Wrapper，用系统 `gradle` 或 `android/build_apk.sh`（后者可配 `BUILD_DIR` / `ANDROID_SDK_ROOT`）。
> 依赖仓库使用阿里云镜像（maven.google.com 部分地区无法直连）。

## 使用

1. 安装 APK，打开 App。
2. 授予通知权限（Android 13+）；Android 14+ 在"通知 → 全屏通知"里允许本 App（强提醒需要）。
3. 自选要监控的子弹，设置买入/卖出偏离阈值（**相对 30 天均值的百分比**，如 `-3` = 低于均值 3% 提醒买入）。
4. 点"开始监控"：前台服务轮询实时价（默认 30s，可设 30–600s），跌破/涨过阈值即触发提醒；
   同时每天按设定时段（默认 07:00 买入、16:00 卖出）发强提醒。
   顶部会显示 **"行情 N 分钟前"**，拉取失败时显示失败原因——不然"没触发"和"拉不到数据"无法区分。
5. "测试提醒"可预览 QQ 通话式响铃效果。

> 时段和内置模型都按**北京时间**：手机在其他时区时，提醒仍落在北京时间的 07:00 / 16:00。

## 数据复现

```bash
pip install pycryptodome                 # fetch_history.py 需要
python tools/fetch_history.py            # → data/history/（默认 30 天，720 小时点）
python tools/analyze_intraday.py         # → output/intraday_report.md / .csv / .png
python tools/selftest_analyze.py         # 合成数据自检，不联网
```

两个脚本都支持 `--data-dir` / `--out-dir`，不再硬编码作者机器上的路径。
`analyze_intraday.py` 另有 `--bootstrap N`（置信区间重采样次数，0 = 跳过）、`--min-days`、`--year`。

## 发布（盈利分发，打包版不开源）

```bash
# 1. 生成你自己的签名密钥（一次性，务必妥善保管，勿提交仓库）
keytool -genkeypair -v -keystore ~/release.keystore -alias delta -keyalg RSA -keysize 2048 -validity 3650

# 2. 在 android/app/build.gradle 的 buildTypes.release 中配置 signingConfig 指向该密钥

# 3. 打包 release
cd android
~/android-build/gradle-8.7/bin/gradle assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

- 仓库只含源码，**不发布 APK**；APK 走你的分发渠道（网盘/闲鱼/QQ 群），凭激活码解锁高级功能。
- 高级功能（多子弹批量、自定义铃声、去广告、走势图等）用独立模块实现，不入库。

## 版本规范

| 类型 | 版本号 | 示例 |
|---|---|---|
| **新增功能 / 新增模块 / 重构 / 界面大改** | 主版本 +1，次版本归 0（X.0.0） | 多主题、可折叠列表 → 2.0.0 |
| 小功能增强 / UI 微调 | 次版本 +1，补丁归 0（X.Y.0） | 行情健康度显示 → 1.5.0 |
| Bug 修复 / 文案调整 | 补丁 +1（X.Y.Z） | 阈值单位修复 → 1.4.1 |

> 当前版本：**1.4.1**（6 套主题、搜索/排序/分组折叠、行情核对与参考均值内置；本版修复阈值单位、
> 闹钟取消失效、行情静默失败、分析脚本 A 行口径等问题）

**APK 归档规范**：`dist/` 是**本地**归档目录（`*.apk` 已进 `.gitignore`，不会进仓库），
每个版本只增不覆盖；旧版本要找回只能靠本地归档或 git 历史重建，别指望 `git` 里有。

## 已知限制

- 报警依赖前台服务常驻：国产 ROM 的省电策略仍可能杀掉它，建议加白名单。
- Android 14+ 的 `USE_FULL_SCREEN_INTENT` 默认不给（本 App 不是通话/闹钟类），必须用户手动允许，
  否则退化成普通通知；`startActivity` 那条兜底路径在 Android 10+ 的后台限制下基本不生效。
- 参考均值每天最多刷新一次，日内剧烈波动时会有滞后。
- 交易行手续费随挂单价偏离基准价上浮，按高价卖出时实际费率高于 10%，报告里的收益估算仍偏乐观。

## 风险提示

- 数据接口是第三方站点的**私有加密接口**（响应体 AES-GCM + 自定义头密钥），本仓库包含其解密逻辑。
  这类做法通常被视为绕过对方的技术措施，对方改接口/封 IP/主张权利都在其选择范围内；**商用会放大风险**。
- 用游戏名做产品名并售卖 APK，涉及商标与游戏内经济数据，属于与大厂 IP 打交道的场景，自行评估。
- 源码 Apache-2.0 意味着任何人都可以 fork、改、闭源分发（包括和你竞争）；open-core 的护城河只有
  "高级功能不在这个仓库里"这一条。

## 开源与授权

- 源码：**Apache-2.0**（见 `LICENSE`）。
- **打包版（APK）为闭源商业产品**，不入库、不在本仓库分发；高级功能（多子弹批量、自定义铃声、去广告、走势图等）为付费解锁。需要 APK 或商业授权请联系作者。
- 数据接口 moligod.com 有防爬设计，本工具低频调用（默认 30s 起、可调更慢）；如接口变更请自行更新 `PriceApi.kt`。

## 免责声明

本项目仅供学习交流。游戏内倒卖行为、账号风险、以及因行情数据不准导致的交易损失均由使用者自行承担。数据接口版权归 moligod.com 所有。
