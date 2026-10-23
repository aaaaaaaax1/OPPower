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

## `chargeVal` 写入入口核查

截至 2026-10-23，未发现可直接写入 `getCurThermalPolicy.chargeVal` 的普通设置项或公开 setter。该值来自热控 XML 中当前分类的 `gear_config charge` 属性；本机当前短视频分类示例为 `tempGear 2 → 12`、`3/4 → 9`、`5 → 6`、`6 → 4`、`7 → 3`。`12` 是当前样例中的最高观测值，尚未证明是跨设备、跨策略的绝对上限。

可实现充电控制写入的路径是：

```text
dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel N
→ ThermalControlUtils.D1(N, normalCooldown, packageName)
→ android.os.OplusBatteryManager.setChargingLevel()
→ IGuardElfThermalControl.setChargeLevel()
```

直接调用底层 Binder 需要 `com.oplus.permission.safe.READ_COMMON`，OPPower 与 Shizuku shell 不能获得；`OplusBatteryService` 的 `setChargingLevel` 调试入口由系统进程代执行，因此普通 Shizuku 用户可以调用。它在未接 AC 时对 `N = 0/3/6/9/12` 均返回执行成功，但没有改变 `getCurThermalPolicy.chargeVal`。所以 `chargeVal` 是策略诊断字段，`setChargingLevel N` 是充电控制写入字段，二者不能仅凭回读值判断是否相同。

全局设置 `sys_thermal_control_list` 也已核验。该设置最大允许 32768 字符，完整热控 XML 无法直接写入；高版本最小 XML 可以写入，但 Cosa 监听只把全局字符串作为更新触发，实际策略数据仍从受 `oplus.permission.OPLUS_COMPONENT_SAFE` / `oplus.permission.RUS_WRITE_DATABASE` 保护的 ROM 更新 Provider 读取。因此直接写全局设置或补发 `oplus.intent.action.ROM_UPDATE_CONFIG_SUCCESS` 不会改变 `chargeVal`，全局设置随后已删除恢复。`com.oplus.romupdate.provider.db` 与旧 authority `com.nearme.romupdate.provider.db` 对 shell 都返回权限拒绝。

`tempGear` 是 XML 中的静态阈值行，当前分类的 `charge` 属性挂在对应 `tempGear` 行上；运行时修改该行需要替换 ROM 更新 Provider 数据，不能通过普通设置完成。隐藏调试入口 `setThermalControlTest` 和 `setTempLevel` 受 `sys.deepsleep.allow.debug` 控制，当前属性为空且 `setprop` 被 SELinux 拒绝，两个命令都返回 `cmd not allow`。设备报告 `ro.debuggable=0` 且不存在 `su`，因此当前无 root、无工程签名权限时无法执行 `tempGear charge=0` 实测。

禁止再用 `cmd thermalservice inject-temperature` 代替 `tempGear` 测试。2026-10-23 实测向电池类型注入 `SHUTDOWN` 状态后设备立即关机；`cmd thermalservice reset` 只解除框架状态覆盖，不清除已注入的温度样本，必须重启才能清空。该接口也不改变 OPlus `Thermal Levels[0]`，因此对目标无效且会触发系统安全关机。

`setChargingLevel 0` 只保留为已核实可调用的对照入口，不作为 `tempGear` 修改的替代实现。只有在获得可写 Provider、工程调试属性或 root 后，才能继续自然策略、`tempGear charge=0`、仅 `setChargingLevel 0`、两者同时开启的四组逐秒功率对照。

## 新的充电功率门控候选（2026-10-23）

`chargeVal` 本身仍不可直接写入。源码链路已经确认它是活动 `ThermalPolicy.charge` 的诊断值，再由 `rb.b.e(policy)` 原样交给 `ThermalControlUtils.D1()`。真正决定功率的是 `D1()` 收到的充电 level 与 `speedChargeAdd`，以及当前是否处于 custom/single-speed charge 路径。因此后续应调整这些下发门控，而不是尝试修改 `getCurThermalPolicy` 的只读显示值。

已用 40605 端口和 Shizuku/shell 相同身份确认以下入口：

| 门控 | 当前值 | 读回/写入门径 | 结论 |
| --- | ---: | --- | --- |
| `speed_charge_state` | `3` | `settings get/put system` | ColorOS 原生手动快充状态，当前已在 mode 3 |
| `single_speed_charge_state` | `0` | `settings get/put system` | 仅为入口核查临时写 `1`，已恢复 `0`；会启用 XML `speedChargeAdd` 并可能影响 gear 回读，按用户真机对比收益不如 `cosa`，列为排除项 |
| `customize_charge_switch_state` | 未设置 | `settings get/put/delete system` | 写 `1` 后回读成功，已删除恢复；是否实际提高瓦数待接电验证 |
| `getSmartChgMode()`（GuardElf 事务 19） | `1` | `service call guardelfthermalcontrol 19` | 可读 |
| `setSmartChgMode(0)`（GuardElf 事务 20） | `1 → 0 → 1` | `service call guardelfthermalcontrol 20 s16 0` | 写后事务 19 回读 `0`，随后已恢复 `1` |
| `getCustomSelectChgMode()`（GuardElf 事务 23） | `3` | `service call guardelfthermalcontrol 23` | 当前已是 custom mode 3 |
| `setCustomSelectChgMode(3,true)`（GuardElf 事务 24） | 返回 `0` | Binder 参数 `i32 3 i32 1` | 调用成功；该 getter 会返回框架当前 policy id，不能把单次 getter 回读当成 setter 成功条件 |

热控应用还注册了 `oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM`。当 extra 为 `name=set_charge`、`level=N`、`speedChargeAdd=M`、`foregroundPkgName=<pkg>` 时会进入 `ThermalControlUtils.D1(N, M, pkg)`。通过当前 ADB/shell 身份发送测试广播返回 `Broadcast completed: result=0`，但当时设备未接电，不能据此宣称功率变化。这个入口可以在保持目标 gear 的同时覆盖策略下发值，是下一轮接电对照实验的首选。

旧版亮屏快充工程还记录了一条完整组合链：`speed_charge_state=3`、`single_speed_charge_state=1`、`customize_charge_switch_state=1`、清除 `SmartChgMode` bit 0、`setCustomSelectChgMode(3,true)`、`setChargeLevel 0`、`setPsySlowChgEnable(60,33,0)`。这只能证明调用面可达到，不代表每项都有独立增益。其中 `single_speed_charge_state=1` 已因真机收益不如 `cosa` 且会干扰 gear 回读而排除。下一轮只比较不依赖 single-speed 的路径，并至少测量实际输入功率、电池电流、`Thermal Levels[0]`、计算 gear、policy `chargeVal` 与 `speedChargeAdd`。

V12 `ICharger` 的 `ChgFuncFlag.SET_CHG_UP_LIMIT_VALUE` 名字容易被误认为功率上限，但实际语义是电池充电上限百分比（配套状态项 `SET_CHG_UP_LIMIT_STATE`），不能用于突破瓦数。`/proc/charging_limit_current` 在当前系统不存在；旧路径中的 `cool_down` 节点对 shell 返回权限拒绝，不能作为 OPPower 的可靠提功率入口。

截至本轮 A/B 测试结束，设备仍接 AC，`cosa=0`，基础档 8，真实充电功率约 6.2W，电池温度约 40.8°C；OPPower 与 `llpower` 均未运行，避免继续回写 `cosa`。
## 接电 A/B 真机结果（2026-10-23，RMX8899 / ColorOS 17）

测试条件：真实亮屏，`stay_on_while_plugged_in` 临时设为 7，测试结束已恢复 0；`speed_charge_state=3`、`single_speed_charge_state=0`、`customize_charge_switch_state` 未设置、`SmartChgMode=1`、`CustomSelectChgMode=3`。先停止 OPPower 和 `com.lele.llpower`，确认 30 秒内无人回写 `cosa`。功率均按电池侧 `|Battery current| × Battery voltage` 计算，仅用于同机短时对照。

| 对照 | 稳定条件 | 结果 | 结论 |
| --- | --- | --- | --- |
| 服务隔离 | `cosa=0`，两个应用无进程，电量约 42%，温度 37.2°C | `cosa` 连续 30 秒保持 0；功率约 10.7–12W | `llpower` 是测试期间唯一在运行的外部充电监控实例；强制停止后不再回写 `cosa` |
| `customize_charge_switch_state=1` | 写入 `cosa=-6` 后保持亮屏；随后写 `customize=1` | 写入后约 27 秒功率从约 11.7W 升到约 40W，但日志同期出现 `ThermalControlUtils: onGameCustomTempChanged value:-6`；删除 `customize` 后高功率未立即退出 | 因果被 `cosa` 写入触发的热控路径混入，不能证明 `customize` 有独立增益，列为无效对照 |
| customize_charge_switch_state=1 专项复测 | OPPower 前台，cosa=-7、基础档 7、计算 gear=0；屏幕全程 Awake，温度约 40.1–40.9°C | 写入前约 35.66–35.83W；写入后约 34.53–35.83W，未出现跃升；删除后恢复未设置 | 独立对照没有功率增益，并略有下降；不纳入提速路径 |
| `SmartChgMode=0` 单独测试 | `cosa=-6`，基础档 7，计算 gear 1，`customize` 未设置 | 前后均约 38.1–38.9W；`chargeVal=0`、`speedChargeAdd=-2` 不变；恢复后回读 `SmartChgMode=1` | 该条件下没有立即观察到独立增益 |
| `customize=1` + `SmartChgMode=0` 组合 | gear0；`customize=1` 保持生效 | 2026-10-23 15:59 用户实测写入 `SmartChgMode=0` 后功率约提高 1–3W，实际增益可能更高；写入后回读为 0，当时电量 81%、温度约 39.5°C、电池侧约 14.2W | 组合路径有正收益，作为候选保留；尚不能把增益全部归因于 `SmartChgMode` 单独作用 |
| `set_charge(level=0, speedChargeAdd=0)` | 同一 `cosa`、同一前台包 `com.android.launcher` | 广播返回 `result=0`；发送前约 38.2–39.5W，发送后约 39.0–39.3W；`chargeVal` 和 `speedChargeAdd` 不变 | 没有独立功率增益。源码确认该路径最终调用 `setSmartCoolDown(0,0,pkg)` |
| `set_charge(level=0, speedChargeAdd=-2)` | beta12 使用该系统广播 | 广播返回 `result=0`，但接电日志中没有对应的 `setSmartCoolDown`，HAL 未变化 | 排除广播下发；`result=0` 不能证明接收器执行 |
| `OplusBatteryService setChargingLevel 0` | 2026-10-23 16:03，OPPower 前台，gear0，`SmartChgMode=0`，`customize=null` | 命令返回 `set charging level = 0`；当时电量 84%、温度约 38.0°C、电池侧约 16.3W；用户实测确认有效提升，实际幅度可能更高 | 纳入组合验证候选；与之前的 `set_charge` 广播结果不同，说明当前组合状态和直接下发路径仍需继续对照 |
| `setPsySlowChgEnable(60,33,0)` | GuardElf 事务 38，参数 `i32 60 i32 33 i32 0` | Binder 返回异常：`com.oplus.permission.safe.READ_COMMON`；ADB/Shizuku shell uid 2000 无权调用，未发生状态变化 | 普通 shell/Shizuku 路径不可用；需要系统签名或拥有 `READ_COMMON` 的进程，不能作为 OPPower 当前权限下的提速入口 |
| 降温恢复 | `cosa=0`，调用 `setChargingLevel 2` | 功率从约 36.9W 在约 10 秒内降到 6.8W，随后稳定约 6.2W；热控温度从 42.2°C 开始回落 | 充电控制通道可实际压低功率；策略缓存 `gearVal` 仍为 1，不能把它当作真实下发状态 |

高功率阶段还确认了一个缓存现象：`cosa` 已恢复为 0、基础档为 8，但 `getCurThermalPolicy.gearVal` 仍缓存为 1，功率保持约 36–37W；直到 `setChargingLevel 2` 下发后功率才降到约 6W。该现象说明缓存 `gearVal` 不能用于控制器反馈或验收，也不能据此推断当前实际功率。OPPower 仍按 `Thermal Levels[0] + cosa` 计算目标；是否到达控制目标不能以滞后的 `policy.gear` 判断。

本轮没有找到可直接替代 `chargeVal` 的写入入口，`chargeVal` 仍是只读诊断字段。`setSmartCoolDown(0,0)` 单独测试没有额外瓦数；但用户后续在 `customize_charge_switch_state=1` 保持生效、计算 gear=0 的条件下写入 `SmartChgMode=0`，实测功率约提高 1–3W，说明该组合路径存在正收益，实际增益可能更高。此前独立 A/B 结束时曾恢复 `cosa=0`、`stay_on_while_plugged_in=0`、`customize_charge_switch_state` 未设置和 `SmartChgMode=1`，设备回到约 6W；随后用户继续组合复测：`customize_charge_switch_state=1` 条件下的 `SmartChgMode=0` 获得约 1–3W 正收益；`customize` 恢复默认后，继续在 OPPower 前台、`cosa=-6`、基础档 6、计算 gear=0、`SmartChgMode=0` 状态直接调用 `setChargingLevel 0`，用户再次确认有效提升。当前电量约 84%，继续验证这一组合路径。
## 闭环控制验收

- 控制目标是规则选出的计算 gear：`计算 gear = Thermal Levels[0] + cosa_ctc_value`。
- 目标 `cosa = 目标 gear - Thermal Levels[0]`；基础档变化时立即重新计算，得到新的计算 gear。
- `getCurThermalPolicy` 的 `gearVal` 缓存不参与控制、显示或验收，代码只读取 `chargeVal` 作独立诊断。
- 目标变化、首次开启或检测到外部 `cosa` 漂移时，直接覆盖动态计算出的目标值并回读 `cosa`，不先写 `0`。
- 写入后只触发一次系统热控重算：把 `oplus_camera_3rd_activity` 的同一整数在 `0/00` 或 `1/01` 之间切换。数值不变，监听回调调用 `m2()`；系统内部安排约 5 秒后的计算。
- 同一目标保持且基础档未变化时不重复写 `cosa`、不重排 `m2()`，也不重复下发原生 `setChargingLevel`。应用继续每秒读取基础档和 `cosa`；`SmartChgMode` 每个充电会话只确认一次。
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

- `1.0.0-beta9` 增加原生充电级双通道：继续写入 `cosa` 并触发热控重算，同时通过受限的 `set_charge` 系统广播直接下发映射后的原生充电级，解决 OPPower 前台时 `getCurThermalPolicy=null` 导致实际充电状态停留在旧值的问题。原生级按目标映射为 `0..1→0`、`2..4→7`、`5→5`、`6→3`、`7+→2`；同一原生级不重复下发，暂停/关闭/拔电/充满回退到安全级 `2`。33 项单测全部通过，`lintDebug` 构建成功。真机首轮验证在 OPPower 前台、未切应用时显示“原生充电级=0”，`cosa=-6`、基础档 6、计算 gear 0；当时电量 88%、约 7.9 W，功率受高 SOC 限制。

- `1.0.0-beta10` 在控制会话生效时自动确保 `system.customize_charge_switch_state=1` 并将 GuardElf `SmartChgMode` 写为 `0`，进入会话前保存原值；暂停、关闭、拔电和充满时恢复。两项每会话只应用一次，稳定期间不重复写。34 项单测全部通过，覆盖应用、单次写入和拔电恢复。真机安装 beta10 后只读确认 `system.customize_charge_switch_state=1`、GuardElf `SmartChgMode=0`，且 OPPower 控制仍在运行。

- `1.0.0-beta11` 移除 `customize_charge_switch_state` 的读取、写入、恢复和 Shizuku 命令白名单，保留 `cosa`、原生充电级直发与 GuardElf `SmartChgMode=0`。36 项单测、`lintDebug` 和 `assembleDebug` 全部通过；APK 位于 `release/1.0.0-beta11/OPPower-1.0.0-beta11-debug.apk`，安装后 versionCode 11、versionName 1.0.0-beta11。设备上的 `customize_charge_switch_state` 已删除并回读为 null。

- `1.0.0-beta12` 将 gear 0 的原生下发从 `set_charge(0,0)` 改为 `set_charge(0,-2)`，目标稳定时每 3 秒重新确认一次；档位或 `cosa` 变化仍立即处理。源码与 Shizuku 白名单只增加 level 0 对应的 speed -2。设备采样时已拔电，因此功率效果尚待真机接电验收。

- `1.0.0-beta13` 修复 beta12 的未生效广播：接电日志确认 `set_charge` 只返回广播成功，没有进入 `D1()`；改为 `OplusBatteryService setChargingLevel` 后，logcat 明确出现 `setSmartCoolDown(0,0)` 和 HAL `set cooldown:0`。37 项单测、`lintDebug` 和 `assembleDebug` 通过。APK 位于 `release/1.0.0-beta13/OPPower-1.0.0-beta13-debug.apk`，SHA-256 为 `9968DFA7E4312A710916827CF2194DA6A17527096123D189D3C694EB36D597C9`。真机保留微信及 `TYPE_APPLICATION_OVERLAY` 浮窗时，25 秒内 8 次 level 0 都到达 HAL，但 88% SOC 下功率保持约 11.10–11.53 W；浮窗复杂场景和当前协商状态仍占主导。

- `1.0.0-beta14` 移除稳定期每 3 秒重发原生 level 的设计。beta8 没有主动下发 `setChargingLevel`；beta14 仅在首次进入目标或原生 level / speedAdd 变化时下发一次。`SmartChgMode` 改为每个充电会话只确认一次，避免控制循环每秒重复执行 Binder 读取。38 项单测、`lintDebug` 和 `assembleDebug` 全部通过；APK 位于 `release/1.0.0-beta14/OPPower-1.0.0-beta14-debug.apk`。

## beta11 参数面复核（2026-10-23）

- 应用已移除 `customize_charge_switch_state` 全部读写和命令白名单，保留 `SmartChgMode=0`。
- GuardElf `QuickModeGain`（事务 27）返回 `com.oplus.permission.safe.READ_COMMON` 拒绝；不能作为反馈参数。
- Charger V12 接口中的 `setChgConfig`、`setChargerCoolDown`、慢充与充电配置入口在 shell/Shizuku 身份下权限拒绝；直接 Binder 事务出现 DeadObject。
- `speedChargeAdd` 在 `ThermalControlUtils.D1()` 中只于 single-speed 条件成立时加到原生 level，不是独立倍率；负值可能产生无效 level。
- 新增两项自定义规则回归测试后共 36 项单测通过。测试证明开启自定义后默认温度阈值与默认 80% 暂停不再参与。
- 因设备未接电，移除 `customize=1` 后的 `SmartChgMode` 独立功率 A/B 尚未执行。

## 后续真机验收

1. 安装当前 APK，启动 Shizuku 并授权。
2. 接电并开启总开关，保持 OPPower 前台，不切换应用；确认控制循环自动读取 `Thermal Levels[0]` 和 `cosa` 并计算 gear。
3. 选择“最大输出”，每 200–300 ms 采样 `cosa_ctc_value` 和 `Thermal Levels`。确认 `基础档 + cosa = 目标 gear`；例如基础档为 6 时必须写 `cosa=-6`。同时故意保留旧 `policy.gear`，确认它不影响计算结果。
4. 开启强制亮屏快充后首次选择规则，保持 OPPower 前台并确认无需切换应用即可写入；切换基础档或自定义规则时确认写入序列不包含中间 `cosa=0`，且每个目标只触发一次重算。
5. 外部或系统把 `cosa` 改错后，确认控制器在下一周期按最新基础档重新计算并修正一次；稳定到目标后不重复写 `cosa`，也不重复下发 `setChargingLevel`；仅在目标或原生映射变化时检查一次 cooldown 调用。
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

## 第二轮充电功率发现（2026-10-23）

本轮继续只使用 ADB/Shizuku shell，不注入温度、不伪造亮屏状态。当前设备在部分只读/场景核查时未接 AC；因此“策略字段未变化”只记作待接电 A/B，不作为接口无效结论。

| 候选/入口 | 可写性 | 已知信息 | 第二轮状态 | 接电后的首轮动作 |
| --- | --- | --- | --- | --- |
| `send_pkg(pkgName)` 热控广播 | shell 广播返回成功 | `ThermalControlUtils.s1()` 会把包名送入 Horae 场景层；可指定高 `charge`/`speedChargeAdd` 的包模板 | **保留待测**：发送 `com.tencent.qqlive` 并触发 `m2()` 后，策略字段仍显示抖音；这不排除 Horae 内部状态已改变，需看实际功率 | 固定 gear0/电量/温度，依次比较抖音、腾讯视频、B 站、桌面包名，再恢复真实包名 |
| `send_scene(sceneName)` | shell 广播返回成功 | `scene_16` 在 XML 中有 `charge="0"` 条目；广播会进入 `u1(25, sceneName)` | **保留待测**：只读策略未变化，不能据此否定 | 先测 `scene_16`、空场景和当前真实场景，至少各观察 20 秒实际功率 |
| `send_thermal_mode_scene(state)` | shell 广播返回成功 | XML 的 `app_type` 将游戏映射为 `type=4`；状态通过 `u1(27, state)` 送入 Horae | **保留待测**：`state=4 -> 0` 后策略字段未变化 | 接电后比较 `state=4` 与 `state=0`；若温度或基础档变化明显立即恢复 |
| `set_charge(level, speedChargeAdd, pkg)` | shell 广播返回成功 | `D1()` 在 `level=0` 时固定调用 `setSmartCoolDown(0,0,pkg)`，因此 `speedChargeAdd` 会被忽略；`level>0` 才可能叠加正 speedAdd | **保留待测**：已有 `level=0/speedAdd=0` 无独立增益；尚未测正 `speedChargeAdd` | 在 `speed_charge_state=3` 且充电流程处于 mode 3 时，比较 `level=0,speedAdd=0` 与保守组合 `level=4,speedAdd=4,pkg=com.tencent.qqlive` |
| `setLongChargeProtectionChargeWattage` | BatteryService 命令接受 | `P0(N)` 写智能充电保护的 `mChargeWattage`；仅当值 `<65` 时关闭“支持智能慢充”判断，不是硬件功率 setter | **保留待测、低优先级**：可影响慢充保护门控，不会直接写充电电流 | 在测试模式下比较 `<65` 与真实 wattage；结束后用 `resetLongChargeProtectionTestMode` 恢复 |
| `oplus_battery_settings_charge_wattage` | Settings 可读/可写 | 是 UI/状态镜像，当前未接电为 `0`；未发现它直接驱动充电 HAL | **只作观测** | 每次实际功率采样时同步记录，不能把它作为功率控制字段 |
| `SmartChgMode` 非 bit0 值 | GuardElf 事务 20 可写 | 当前 bitfield 为 `1`；已确认 `0` 在 `customize=1` 组合中有约 1–3W 正收益，其他 bit 语义未确认 | **暂不盲写**：2/3 等值只列候选 | 仅在确认 bit 定义后测试；任何测试前记录原值并恢复 `1` |
| `setPsySlowChgEnable` | shell 直调拒绝 | 返回 `com.oplus.permission.safe.READ_COMMON` | 当前权限不可用 | 不纳入应用路径；除非出现系统代执行广播 |
| `setChargerCycle` / `setChgOlcConfig` / `setChgRusConfig` / `setChgConfig` | shell 直调拒绝 | GuardElf 事务 34/35/43/44 均要求系统权限；工程模式里可见 `en808`/`dis808` 等充电满测入口，但无法确认长期恢复 | **阻塞，不盲测** | 获得可审计的系统代执行入口后再评估 |
| `setChargingModeInGsmCall` / `setTbattPwrOff` | shell 直调拒绝 | GuardElf 事务 41/42 返回 `com.oplus.permission.safe.POWER`；后者涉及电池供电关闭 | **阻塞且高风险** | 不作为常规提速入口 |
| `setLongChargeProtectionTestMode` + `setLongChargeProtectionBatteryLevel` | BatteryService 命令接受 | 可模拟慢充 AI 条件和电量，但不会修改 thermal `chargeVal` | 次选 | 仅在已确认慢充保护实际进入时测试 |

GuardElf 权限边界补充：事务 34–39 对 shell 返回 `READ_COMMON` 拒绝；事务 40–44 返回 `POWER` 拒绝。热控调试命令 `thermalscene`、`setAmbientTemperature`、`sendChargeModeChanged` 仍受 `sys.deepsleep.allow.debug` 门控，当前均为 `cmd not allow`。

以下默认/安全字段已经处于较有利状态，不应为了提高功率而反向修改：

| 字段 | 当前值 | 说明 |
| --- | ---: | --- |
| `speed_charge_state` | `3` | 已处于原生快充选择状态 |
| `single_speed_charge_state` | `0` | 已验证开启会干扰回读，且不是必要条件 |
| `oplus_battery_cool_charge` | `0` | 未开启额外降温充电限制 |
| `oplus_settings_hightemp_protect` | `0` | 未开启高温保护限制 |
| `oplus_thermal_outdoor_scene` | `0` | 未进入户外热控场景 |
| `thermal_safety_mode` | `0` | 当前安全模式最低 |
| `battery_charging_state_enforce_level` | `-1` | 未额外强制充电状态级别 |
| `battery_charging_state_update_delay` | `-1` | 未增加充电状态更新延迟 |

## 同 SOC 热控基础档抬高 A/B（2026-10-23）

测试条件：OPPower 前台、AC 接电；`system.customize_charge_switch_state=1`、`system.speed_charge_state=3`、`system.single_speed_charge_state=0`；`global.cosa_ctc_value` 由控制循环维护；`getCurThermalPolicy` 在 OPPower 前台返回 `null`。功率按电池侧 `Battery voltage × |Battery current|` 计算。

| 时点/状态 | 电量 | 热温度 | 基础档 | cosa | 计算 gear | 电压/电流 | 电池侧功率 | 结果 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 19:24:51，负载前 | 84% | 35.99°C | 5 | -5 | 0 | 4.397 V / 2.855 A | 12.55 W | 基线 |
| 19:27:40，约 135 秒真实 CPU 负载后 | 86% | 41.01°C | 8 | -8 | 0 | 4.437 V / 2.477 A | 10.99 W（停载瞬间的过渡样本） | 不计入稳定对照 |
| 19:27:49，停载后单次稳定读数 | 86% | 41.01°C | 8 | -8 | 0 | 4.472 V / 3.659 A | 16.36 W | 较基线约提升 3.8 W / 30% |

真机确认：在不切换应用的情况下，基础档从 5 自然升到 8，OPPower 自动把 `cosa` 从 -5 重算并写入 -8，计算 gear 始终保持 0。用户观察功率明显上升；单次稳定读数从约 12.6 W 到约 16.4 W。该组记录确认“同一计算 gear 下，基础档抬高可以带来可测功率增量”，但测试伴随热温度从约 36.0°C 升到 41.0°C、电量从 84% 升到 86%，因此它是自然升温 A/B，不能把 3.8 W 全部归因于基础档。

同日的既有日志还显示，gear 0 的功率层同时强依赖 SOC：43–44% 时约 31.8–38.7 W，68–70% 时约 34.5–35.8 W，79–80% 时约 15.4–15.6 W。当前 84–86% 的高 SOC 阶段只有约 12.6–16.4 W。因此后续控制逻辑应同时记录基础档、cosa、计算 gear、`chargeVal`、SOC 和温度；`gear=0` 不能单独代表最高功率。

`chargeVal=--` 的根因也已复核：OPPower 自身不在 ROM 热控 XML 的包名分类中，OPPower 前台时原生策略对象可以为 `null`，本周期不调用 `D1()`，实际充电控制可能保持上一条下发值。切回桌面后原生策略对象立即生成，读到的示例为 `gearVal=5, chargeVal=6, speedChargeAdd=1`。因此计算 gear 只表示 OPPower 根据基础档和 cosa 得到的目标；需要另行验证原生策略对象为空时是否必须通过系统代执行 `set_charge` 才能保证实际下发。

接电后的第二轮顺序应固定为：先记录 gear0 的自然基线；再测 `send_pkg`/场景广播；再测 `set_charge` 正 speedAdd；最后才评估长充保护 wattage。每组至少记录电池侧 `|current × voltage|`、输入侧功率计、`Thermal Levels[0]`、计算 gear、`chargeVal`、`speedChargeAdd`、电量和电池温度，并确认恢复真实包名、场景及 GuardElf 原值。
