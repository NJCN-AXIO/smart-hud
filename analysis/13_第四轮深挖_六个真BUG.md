# 13｜第四轮深挖：六个真 BUG（v0.9.9）

这轮不看界面、不找新功能，只顺着 v0.9.2–v0.9.8 改过的地方回查逻辑，找到 6 个真问题，
其中 2 个是"用户一定会遇到"的。

## BUG 1｜长按清零后里程会弹回去（严重）

**现象**：长按里程 → 弹窗点"清零" → 显示 0，下一秒又变回 19.1。

**根因**：`MainActivity` 只写了 SharedPreferences 和 `HudState.tripKm`；
而正在跑的服务手里攥着 `TripTracker`（内部 `TripIntegrator` 还是旧值），
tick 每秒 `HudState.tripKm = trip.km()`，直接把旧值写回来 ——
界面上是"清零后立刻回弹"，而且 5 秒一次的落盘还会把旧值又存回磁盘。

**修法**：清零改为**广播通知服务**（`ACTION_TRIP_RESET`，仅包内、不导出，API 33+ 用
`RECEIVER_NOT_EXPORTED`）。服务收到后：
- `trip.reset(now)` —— 内存、磁盘、tripStart 一起归零
- 电压哨点基准 `manualKm` 归零（推算不跑偏）
- 本箱电账本重锚：`cycleSoc = 当前电量`、`cycleKm = 0`（否则没电压时会跳回 100%）
- 清掉 `lastSummaryKmF`、`lastAnnounceBucket`、`lastEstKm` 三个计数器

**顺带修的关联问题**：`lastSummaryKmF` 不清，`trip summary` 要等里程重新超过旧值才写；
`lastAnnounceBucket` 不清，语音播报会停摆（见 BUG 3）。

## BUG 2｜停车等单时会凭空多出里程（严重）

**现象**：停在商场门口一小时，里程表自己涨了 3–6 公里。

**根因**：v0.9.2 的"停车降频"把 GPS 间隔从 1 秒改成 **3 秒**，正好等于
`GapFill.MIN_GAP_MS = 3000` 的门槛；而静止时 GPS 速度仍有 2–6km/h 噪声，
于是每个点都会被"隧道补里程"当成真的跑了 3 秒 → `3s × 5km/h ≈ 4m`，一小时 1200 个点。

**修法（两道保险）**：
1. `GapFill` 新增 `MIN_SPEED_KMH = 8f`：低于 8km/h 一律不补（真在隧道里骑不会低于这个速度）
2. 服务侧：`parkedMode` 期间直接不补（停车态本来就不可能"穿隧道"）
3. 单测锁死：3 秒 + 3/6km/h → 补 0 米；3 秒 + 15km/h → 有补

## BUG 3｜语音播报在清零后永久停摆

**根因**：旧实现存的是"上次播报的里程"（float）。清零后当前里程 0 永远小于旧值 19.1，
条件 `km - last < 5` 一直成立 → 再也不播。
**修法**：改成"第几个 5 公里"整数计数（`lastAnnounceBucket`），里程 <1km 时归零；
清零后自然从 5km 重新开始。另外演示模式下不再出声（合成里程不该播）。

## BUG 4｜低电在"按里程数电"时不会震动

**根因**：`maybeLowPowerAlert` 里有 `src == SRC_NONE` 就 return，
而"按里程账本兜底"时电压源正是 `SRC_NONE` → 电量已经 10% 也不震不报。
**修法**：改成"只要电量算得出来就允许提醒"，即 `src==SRC_NONE` 且 `!socFromKm` 才跳过。

## BUG 5｜停车状态机会抖（省电省了个寂寞）

**根因**：判定"动了"的门槛是 2.5km/h 或 8 米。停车时 GPS 噪声经常超过 8 米，
于是"降频→立刻升频→60 秒后再降频"反复，省电没省到，日志还刷屏。
**修法**：门槛提到 3.5km/h 或 15 米；真起步一两秒就超过 3.5km/h，不会漏。

## BUG 6｜链路重启后拿旧定位基准算里程

**根因**：`stopGps()` 只反注册监听，`lastLoc`/`trackDist` 的基准还留着。
改设置触发 restart 后，新起点会对着旧终点算出一段"位移"，甚至触发一次假隧道补里程。
**修法**：`stopGps()` 里清 `lastLoc`、`lastGpsKm`、`trackDist.reset()`、`speedStab.reset()`，
并把 `gpsKmh` 置 NaN（别把重启前时速留在屏幕上）。

## 顺手改的

- `trip summary` 不再写出 `avg=NaNkmh` / `dur=NaNmin`，没数据写 `-`
- `HudScreen` 删掉已经没人用的 `mapCandidate` 局部变量

## 查过但没问题的（有据可查，不是"感觉没事"）

- 8 秒电压过期 + 20 秒静默看门狗：判定条件和互斥都对
- 保活 ping 的 2 秒静默跳过：`lastNotifyMs` 在 `deliver()` 与 `onDeviceReady` 都重置了
- 防烧屏位移：只作用于数据区，按钮条固定
- 红线：`check_no_write.py` 通过，唯一写仍是 CCCD `01 00`
- 段错误类风险：`GapFill`、`PowerLedger`、`TrackDistance.rebind` 都有单测覆盖

## 验证

`:protocol` 11 条 + `:core` 55 条单测全过（本轮 +1 条）；自检 6 项全过；
APK vc36 / 0.9.9；`local=142 central=142`、`testzip=None`。

实车待验清单：清零后不再回弹、停车一小时里程不动、清零后仍会在 5 的整数倍播报、
低电（无电压时）也会震动。
