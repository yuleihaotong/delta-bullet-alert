# 跨平台提醒服务（iOS / Windows / Linux）

Android 那套是**客户端自己轮询**，iOS 做不到（系统不允许常驻后台轮询）。这里换个方向：
**一台常驻的机器负责轮询，把结果推到你的手机/微信**。于是

- iOS：装 [Bark](https://apps.apple.com/app/bark-customed-notifications/id1403753865)，填个 key，就能收系统通知——不用苹果开发者账号、不用上架审核；
- Windows / Linux / NAS：跑这个服务，或者把服务放在任意一台常开的机器上；
- 浏览器：打开网页就能改自选和阈值、看行情。

## 怎么跑

### 本机直接跑（最快）

```bash
cd server
pip install -r requirements.txt
python app.py                    # → http://127.0.0.1:8710
```

### Docker / NAS

```bash
cd server
WATCHER_TOKEN=随便设一个口令 docker compose up -d
# → http://<机器IP>:8710/?token=随便设一个口令
```

### 常用参数

```bash
python app.py --host 0.0.0.0 --port 8710 --token s3cret --poll 60
python app.py --db /data/watcher.db          # 数据库位置
python app.py --no-poller                    # 只起网页（调试 UI 用）
```

> 监听 `0.0.0.0` 且不设 `--token` 时，同网段任何人都能改你的推送配置。脚本会警告，别忽略。

## iOS 收通知（Bark）

1. App Store 装 Bark，打开，允许通知；
2. 首页那串 `https://api.day.app/xxxxxxxx/...` 里 `xxxxxxxx` 就是 key；
3. 网页 →「推送」→ 填 key → 保存 → 点「发一条测试」；
4. 手机收到「测试提醒」就通了。

之后价格触发阈值、或者到了 07:00 / 20:00 的时段，都会推一条。

> 想更吵一点：Bark 里可以把这条通知的铃声设成 `alarm`（服务端已经在买入提醒里带了 `sound=alarm`），
> 也可以在系统设置里把 Bark 的通知权限调成「时效性通知」。

## 微信 / 钉钉

不装 Bark 也行：

- 企业微信群 → 添加机器人 → 复制 webhook → 网页里格式选「企业微信机器人」；
- 钉钉群 → 智能群助手 → 自定义机器人 → 格式选「钉钉机器人」。

## 它做了什么

| | 说明 |
|---|---|
| 轮询 | 每 60 秒拉一次实时价（全站一次，不是每个用户一次，对上游更客气） |
| 参考均值 | 每 24 小时对自选子弹拉 720 小时历史求均值 |
| 阈值 | 和 Android 端一致：`-3` = 低于均值 3% 提醒买入，`6` = 高于均值 6% 提醒卖出 |
| 冷却 | 同一颗子弹默认 30 分钟内不重复提醒 |
| 时段 | 默认 07:00 买入 / 20:00 卖出（这两个数字来自 30 天数据分析，见仓库根目录报告） |
| 记录 | 所有提醒落库，网页上能看「当时推送成功没有」 |

## 测试

```bash
cd server
python -m unittest discover -s tests -v
```

单测覆盖阈值单位（和 Kotlin 端同一套用例）、冷却、时段只响一次、拉取失败不清空健康度。
依赖外网的端到端验收需要你自己跑一次 `python app.py` 然后点「发一条测试」。

## 已知限制

- **单用户**：没有账号体系，一个人一套部署。想让朋友也用，让他自己 `docker compose up`。
- **没有 Web Push**：iOS 的网页推送要先装到主屏、再申请权限，链路更长；Bark 更稳，所以先没做。
- **接口是第三方的**：moligod.com 改格式就得改 `watcher/api.py`，和 Android 端同一个地方。
- **服务器要常开**：关机/断网期间不会提醒，但记录不会丢。
