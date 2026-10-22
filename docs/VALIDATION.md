# OPPower 真机标定与验证记录

记录日期：2026-10-22

设备：realme Neo8 RMX8899，ColorOS 17

条件：真实亮屏、AC 在线、外接功率计。除特别注明外，测试时未发送 LV，未冻结 Athena/Battery。

## `cosa_ctc_value` 条件样例

下表来自本机真机采样，仅对应采样时的热控基础档 8 和其他场景影响为 0。当前实现不使用缓存 `gearVal`；`gear` 越小表示系统允许的输出越高，但功率不是 gear 的线性函数，最终仍需以外接功率计确认。

| `cosa_ctc_value` | 当时系统 gear | `chargeVal` | 效果 |
| ---: | ---: | ---: | --- |
| `0` | 8 | 2 | 原生约 5 W |
| `-3` | 5 | 4 | 开始提升 |
| `-4` | 4 | 6 | 约 10–14 W |
| `-6` | 3 | 7 | 更高 |
| `-7` | 2 | 9 | 更高 |
| `-8` | 1 | 9 | 接近最低 gear |
| `-9` | 0 | 12 | 实测约 15–30 W，跨应用保持 |
| `-10` 及以下 | 0 | -2 | 策略字段进入无效哨兵状态，禁止使用 |

曾观察到抖音极速版前台、真实亮屏、无 LV 条件下的约 20 W 实际功率；gear 0 仍可能受充电器、线材、电池温度、SOC、原生热策略和前台应用共同限制，因此 `gear=0` 不等于固定瓦数。`setChargingLevel 0` 虽然可返回成功，但实测不能稳定突破约 20 W，不作为提功率入口。

## 闭环控制验收

- 控制目标是规则选出的计算 gear：`计算 gear = Thermal Levels[0] + cosa_ctc_value`。
- 目标 `cosa = 目标 gear - Thermal Levels[0]`；基础档变化时立即重新计算，得到新的计算 gear。
- `getCurThermalPolicy` 的 `gearVal` 缓存不参与控制、显示或验收，代码只读取 `chargeVal` 作独立诊断。
- 目标变化、首次开启或检测到外部 `cosa` 漂移时，直接覆盖动态计算出的目标值并回读 `cosa`，不先写 `0`。
- 写入后只触发一次系统热控重算：把 `oplus_camera_3rd_activity` 的同一整数在 `0/00` 或 `1/01` 之间切换。数值不变，监听回调调用 `m2()`；系统内部安排约 5 秒后的计算。
- 同一目标保持且基础档未变化时不重复写 `cosa`、不重排 `m2()`，也不执行 3 秒重试。应用继续每秒读取基础档和 `cosa`。
- `基础档 + cosa == target` 即达到计算目标；`policy.gear` 缓存和 `chargeVal` 都不参与该判断。
- 温度下降 5 秒、恢复 8 秒的确认仍属于热安全规则；用户选择和档位切换没有防抖。
- 只有拔电、关闭总开关、电量暂停、充满或停止服务才恢复 `cosa=0`，并在实际写入时触发一次系统重算。软件不读取或写入 `single_speed_charge_state`。

## 软件验证

截至 2026-10-23：

- `:app:compileDebugKotlin` 通过。
- `:app:testDebugUnitTest` 通过，共 32 项、0 失败。覆盖默认温度档、SOC 暂停、100%/FULL、禁止更高温反向升档、均衡档内部 gear、Shizuku 命令白名单、24 小时曲线记录序列化、LLPower 采样间隔、曲线聚合/平滑/断线/坐标轴，以及“只按基础档和 cosa 计算、不经过 0”“同一目标只触发一次刷新”“缓存 gear 不参与计算”“基础档变化立即重算 cosa”“外部 cosa 漂移单次修正”和“数值不变刷新别名”的控制器用例。
- `:app:assembleDebug` 通过并生成 debug key 签名 APK。
- 页面数据每秒刷新；曲线按 LLPower 节奏采样：充电亮屏每 3 秒、充电熄屏每 5 秒、未充电每 180 秒。控制服务在 AC 在线时每 1 秒执行一次控制循环。
- 设置多级页面、主题与比例、通知内容/频率、硬件修正、诊断字段、数据管理、悬浮窗与无障碍保活已接入源码。
- 设置主页、亮屏快充详情页和系统页提供 Shizuku 未安装、未运行、未授权、已授权四种状态及对应操作。
- 通知只在接电时显示；总开关关闭时显示“亮屏快充未启用”。Android 16 真机已确认使用 LLPower 同类的 `Notification.ProgressStyle`、`battery_live_update_v2` 通道和 `PROMOTED_ONGOING` 实时卡片；开启 ColorOS“流体云显示实时活动”后，顶部卡片使用系统透明样式，正文保留电量、电压、电流、温度和亮屏快充档位。
- `1.0.0-beta3` 使用 debug key 签名（versionCode 3），已通过上述单测和 `:app:assembleDebug`。APK 位于 `release/1.0.0-beta3/`，SHA-256 为 `DA8DA0D74546FA1E686BE61573542588EC39A2B1AAE2C1AC986A505613C11F35`；beta2 及其归档保持原样。各版本使用独立目录。
- `1.0.0-beta4` 使用 debug key 签名（versionCode `4`），已通过 `:app:lintDebug`（0 error）、上述单测和 `:app:assembleDebug`。APK 位于 `release/1.0.0-beta4/`，SHA-256 为 `43AD083730408C8C1FC1189C88DF7862FC1CE3C1F9B18EF52B8B9D4C0A1EE093`；beta1–beta3 保持原样。各版本使用独立目录。
- 1.0.0-beta5 修复 Android 16 / ColorOS 17 实时通知回归：实时分支恢复 setOngoing(true)，真机通知转储重新包含 PROMOTED_ONGOING 与 Notification.ProgressStyle，通知栏不再显示应用图标和展开箭头；关闭亮屏快充时闪电为白色，开启时为琥珀色 #FFC107。APK 位于 release/1.0.0-beta5/。
- 真机已安装验证冷启动、前台服务、通知和 Shizuku 授权路径。修复了 `ForegroundServiceDidNotStartInTimeException`；前台服务启动后持续运行，无新的 ANR 或崩溃记录。
- `1.0.0-beta6` 删除同一目标重复写入和 3 秒重试，改为固定映射单次写入、单次热控重算触发；实际档位只认 `policy.gear`。24 项单测全部通过，`lintDebug` 为 0 error / 20 warning，`:app:assembleDebug` 通过。APK 位于 `release/1.0.0-beta6/OPPower-1.0.0-beta6-debug.apk`，SHA-256 为 `D1A48BBE9FC52DC12C260DC5D7212118CF72C671FE4CD093E5722F038ED15AC3`。安装后已用无线 ADB 确认等价字符串写入会触发热控策略缓存变化；因手机当时未接电且前台切换到 QQ，目标 gear 的最终功率路径仍需在接电并保持 OPPower 前台时复验。
- `1.0.0-beta7` 将固定 `cosa` 映射改为实时公式 `gear = 热控基础档位 + 场景及其他修正 + cosa`。控制器读取 `Thermal Levels[0]`，按 `cosa = 目标 gear - 基础档 - 修正` 每秒重算，并在界面显示全部求和项与独立的 `chargeVal`。30 项单测全部通过，`lintDebug` 为 0 error / 20 warning，`:app:assembleDebug` 通过。APK 位于 `release/1.0.0-beta7/OPPower-1.0.0-beta7-debug.apk`，SHA-256 为 `78AB7D2183C3C5F47F1C1FF4FB0788D9250E9489FCA89A08E5F5469BFF0BDCA5`；真机只读验证结果为 `实际档位 7 = 基础档 7 + 修正 0 + cosa 0`，`chargeVal=2` 保持独立显示；动态公式的真机充电写入与功率验收仍需在接电、OPPower 前台条件下完成。
- `1.0.0-beta8` 将档位定义统一为 `计算 gear = Thermal Levels[0] + cosa_ctc_value`，目标 `cosa = 目标 gear - 基础档`。`policy.gear` 缓存不再解析、显示、参与校准或验收；热控策略只读取 `chargeVal`。32 项单测全部通过，`lintDebug` 为 0 error / 20 warning，`:app:assembleDebug` 通过。APK 位于 `release/1.0.0-beta8/OPPower-1.0.0-beta8-debug.apk`，SHA-256 为 `9D7DB495BFB4457983743B5DB846B7F0CF390AA0C5BE3BA91758B62564CA977B`。 真机只读验证显示 `计算档位 5 = Thermal Levels[0] 5 + cosa 0`，`chargeVal=4` 单独显示；接电写入与功率验收仍待完成。

## 后续真机验收

1. 安装当前 APK，启动 Shizuku 并授权。
2. 接电并开启总开关，保持 OPPower 前台，不切换应用；确认控制循环自动读取 `Thermal Levels[0]` 和 `cosa` 并计算 gear。
3. 选择“最大输出”，每 200–300 ms 采样 `cosa_ctc_value` 和 `Thermal Levels`。确认 `基础档 + cosa = 目标 gear`；例如基础档为 6 时必须写 `cosa=-6`。同时故意保留旧 `policy.gear`，确认它不影响计算结果。
4. 开启强制亮屏快充后首次选择规则，保持 OPPower 前台并确认无需切换应用即可写入；切换基础档或自定义规则时确认写入序列不包含中间 `cosa=0`，且每个目标只触发一次重算。
5. 外部或系统把 `cosa` 改错后，确认控制器在下一周期按最新基础档重新计算并修正一次；稳定到目标后不重复写。缓存 gear 未变化时确认应用不进行 3 秒重试。
6. 在抖音、B 站和桌面之间切换，每档至少观察 60 秒，确认稳定目标不重复写；只有基础档或 `cosa` 变化时重新计算。
7. 分别验证 41/42/43°C 默认温度条件、80% 电量暂停和 100% 恢复系统默认；温度降档持续 5 秒、恢复持续 8 秒。
8. 拔电后确认 `cosa=0`；再插电确认直接读取当前 `cosa` 和 gear 建立闭环，不执行“基础 gear 重校准”。
9. 强制终止应用、重启设备和 Shizuku 断开场景单独记录失败边界。

## 已排除方案

- 单次 LV0：只带来短时提升，切换应用后会回落；循环补发不是持久控制。
- 冻结 Athena/Battery：约十秒后系统因 freezer 冲突终止进程并重启，功率回落。
- 分屏、旁路充电、高性能磁贴、Doze/假亮屏、关闭智能充电：没有满足持久亮屏快充目标。
- 伪造温度、停止原生热服务、工程模式或签名权限绕过：不纳入实现。
- `single_speed_charge_state=1` 会遮蔽缓存档位回读，且不是持续功率的必要条件；正式控制路径只使用热控基础档和 `cosa_ctc_value`。