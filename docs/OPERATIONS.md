# OPPower 真机操作书

本文件记录 RMX8899 / ColorOS 17 上已做过的测试、结论、恢复方法和下一轮操作顺序。每次真机实验前先读本文件，实验后立即补充日期、原值、动作、稳定读数、结论和恢复结果。

## 0. 现行控制约束（2026-11-23）

- 常规 gear 模式不得调用 `OplusBatteryService setChargingLevel`，也不得做持续原生充电级轮询。
- 用户显式开启的“高功率保活”（内部使用 `setChargingLevel 0`）是唯一例外，并且独立于“强制亮屏快充”总开关。只要该开关开启、AC 在线且未被 80%/充满/息屏保护暂停，就先清 `cosa=0`，再立即及最多每 3 秒一次下发完整固定命令 `setChargingLevel 0`；不进入普通 temperature/SOC 分档。总开关可以保持关闭。
- 历史冲突：原生 level 0 与目标 `cosa` 形成的 gear 0 作用重合；两者和 ColorOS 自行写回的 `cooldown=12/15` 同时存在时，会形成 `0 → 12 → 0` 的反向写，造成充电功率跳到 0 W 后恢复。因此 Level 0 只作为默认关闭的独立 opt-in，不能与普通 `cosa` 目标同时控制。
- 高功率保活的 80% 本次暂停、100%/FULL、拔电、关闭该模式且总开关也未开启、息屏暂停或一键恢复，都必须先下发 `setChargingLevel 2`，再恢复 `cosa=0`。关闭总开关本身不会停止或恢复该独立模式。白名单只允许 0/2 两条完整命令。
- 目标 gear 0 的普通模式仍只写入一次 `cosa` 并触发一次热控重算。`set_charge(0,-2)` 广播要求发送方持有 `oplus.permission.OPLUS_COMPONENT_SAFE`，Shizuku/ADB 发送后会失败，禁止再接入。
- `getCurThermalPolicy` 的 `chargeVal`、`speedChargeAdd`、category 和实际 `cooldown` 只作诊断；它们不触发另一次硬件写入。
- 本文第 16–18 节中出现的 `setChargingLevel` 操作属于历史记录，已全部废止，不得照做。
- 第 3–35 节保留历史实验记录；其中与第 0 节或第 36 节冲突的禁用/轮询描述，一律以第 0、36 节的现行 opt-in 约束为准。
- OPPower 不得读取或写入 `SmartChgMode`；普通模式不得加入低频或高频守门轮询。高功率保活的 3 秒复写只服务于显式开启的独立模式。

## 1. 安全边界与测试原则

- 仅在真实亮屏、AC 接电、原生热保护保持运行时测试。
- 一次只改变一个变量。单项写入后至少等待一个稳定刷新周期，再记录一次读数；不在温度或热控阶段明显变化时下结论。
- 每次对照同时记录 SOC、电池温度、`Thermal Levels[0]`、`cosa_ctc_value`、计算 gear、`chargeVal`、电池侧电压和电流。
- 电池侧功率按 `|Battery current| × Battery voltage` 计算。它只用于同一台手机短时 A/B，最终功率以外部功率计为准。
- “基础档自然升高”和“SOC 阶段变化”属于被动变化，不能算作可稳定控制的参数增益。
- 测试写入前保存原值；未产生稳定正收益就恢复。不得把临时测试值留在设备上。
- 不修改 `single_speed_charge_state`。它已验证会干扰回读，且实际收益不如 `cosa`。
- `chargeVal` 是策略对象的诊断字段，不是应用可直接写入的控制参数。
- beta16–beta27 的常规控制不调用 `setChargingLevel`。当前版本仅在用户显式开启高功率保活时允许 0/2 两条白名单命令；不得把该模式接回普通 gear 循环。

## 2. 当前控制模型

```text
计算 gear = Thermal Levels[0] + cosa_ctc_value
目标 cosa = 目标 gear - Thermal Levels[0]
```

OPPower 每秒重新计算目标。基础档或外部 `cosa` 变化时，只写一次新的 `cosa` 并触发一次热控重算，不先经过 `cosa=0`。

OPPower 写入目标 `cosa` 后立即触发一次系统热控重算；稳定期间不重写。优先把 `oplus_float_window_show` 的同一整数切换成等价写法，`0/1/00/01` 均保持原语义；该设置缺失（`null`）时写一次规范值 `0`，因为系统只把整数 `1` 当作浮窗场景，`null` 与 `0` 都表示非浮窗。目标 gear 只改变系统热控的计算输入，最终 `chargeVal/cooldown` 仍由当前场景策略决定。

常规模式暂停、关闭总开关、拔电、SOC 暂停或充满时，恢复 `cosa=0` 并触发一次热控重算。高功率保活独立于总开关，关闭总开关不会让它退出；仅在关闭保活且总开关也未开启、拔电、SOC/充满/息屏暂停或一键恢复时，先恢复原生安全级 `setChargingLevel 2`。若当时处于基础功率模式，则不额外写原生级。二级页“息屏也生效”默认关闭，关闭时熄屏按同样策略暂停全部快充增强。


## 3. 已验证有效的主动入口

| 入口 | 已验证结果 | 使用结论 |
| --- | --- | --- |
| `cosa_ctc_value` | 能按基础档计算目标 gear；外值漂移后下一周期可纠正；切换目标不经过 0 | 核心控制路径，稳定保留 |
| `smart_charge_switch_state=0` | 同条件相邻复测约增加 0.55–0.99 W，稳定但不足以单独突破功率层 | beta21 曾作为补充提升；**beta27 已移除全部读写，系统值保持用户设置的 1** |
| BatteryService `setChargingLevel` | beta9–beta15 曾用于绕过空策略；会与热控重算写回 12/15 冲突并造成跳 0 W | **常规模式禁止；仅允许默认关闭的 Level 0 opt-in 使用 0/2 与 3 秒保活** |
| `SmartChgMode=0` + 当时的 `customize=1` | 历史组合只有约 1–3 W 的小幅、不稳定收益，后期还改变了自然策略 | **beta17 已移除，禁止接入** |
| `set_charge(0,-2)` 广播 | 接收器要求 `oplus.permission.OPLUS_COMPONENT_SAFE`，Shizuku/ADB 的 shell UID 不持有该签名权限；`Broadcast completed` 不代表送达 | **排除：不会进入 `D1()`** |

## 4. 已排除或未确认的入口

| 入口 | 测试/发现 | 结论 |
| --- | --- | --- |
| `customize_charge_switch_state=1` | 早期自然爬升对照曾被 `cosa` 写入混入；独立复测约 35.66–35.83W → 34.53–35.83W，没有独立增益 | beta11 已移除，不再读写 |
| `single_speed_charge_state=1` | 会影响 gear 回读，收益不如 `cosa` | 排除 |
| `speed_charge_state=3` | 系统原生快充基础状态，当前已经是 3 | 不算额外增益 |
| 场景广播 / `send_pkg` | 未测到稳定独立增益 | 暂停重复测试 |
| `CustomSelectChgMode` 0/1/2 | 未测到稳定独立增益 | 暂停重复测试 |
| 所有 `setChargingLevel` 档位 | 没有一种写入能与 `cosa` 稳定叠加，反而会争夺 cooldown 最终值 | beta16 全部废止 |
| 长充保护瓦数模拟 | 可影响慢充 AI 门控，但不直接修改充电电流，不会写 `chargeVal` | 低优先级，仅在确认慢充保护实际进入时测试 |
| `setPsySlowChgEnable` | shell/Shizuku 身份返回 `READ_COMMON` 权限拒绝 | 当前应用路径不可用 |
| `QuickModeGain` | GuardElf 事务 27 可读接口存在，但读取返回 `READ_COMMON` 权限拒绝 | 不能作为应用可读反馈，也不构成写入入口 |
| Charger HAL 直连 | `setChgConfig`、`setChargerCoolDown`、慢充配置等接口存在；shell 直连返回权限拒绝或 DeadObject | 当前不可用，不能凭接口名盲测 |
| `speedChargeAdd` | `D1()` 只在 single-speed 条件成立时把它加到原生 level；负值可能生成无效 level，正值只改变 level 编号。2026-10-23 正常模式下实测 `level=4, speedAdd=4` 没有增益 | 它不是独立升功率倍率；不打开 single-speed 专门触发该分支 |
| `Max charging current=2000000` | `dumpsys battery` 观测字段，当前不同 SOC 下都有出现，不能证明它是可写的硬件限流 | 只记录，不通过 `cmd battery set current_now` 伪造读数 |
| `setChargerCycle`、`setChgOlcConfig`、`setChgRusConfig`、`setChgConfig` | 系统权限拒绝；长期恢复行为不明确 | 不盲测 |
| `SmartChgMode` 的 2/3 等非 bit0 值 | bit 语义未确认 | 不盲写 |

## 5. 重要功率实验记录

### 5.1 高电量段

2026-10-23 的同一台手机在目标 gear 0 下仍出现明显不同的功率层：

| SOC | 目标 gear | 电池侧功率 |
| ---: | ---: | ---: |
| 43–44% | 0 | 约 31.8–38.7W |
| 68–70% | 0 | 约 34.5–35.8W |
| 79–80% | 0 | 约 15.4–15.6W |
| 84–89% | 0 | 约 7.9–16.4W |

结论：gear 0 只代表目标热控档，不单独代表最高功率。SOC 阶段、基础档、`chargeVal` 和 `speedChargeAdd` 必须同时记录。

### 5.2 基础档自然抬高

| 状态 | SOC | 温度 | 基础档 | cosa | 计算 gear | 功率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 负载前 | 84% | 35.99°C | 5 | -5 | 0 | 12.55W |
| 停载后稳定读数 | 86% | 41.01°C | 8 | -8 | 0 | 16.36W |

基础档从 5 自然升到 8 后，控制器自动把 `cosa` 从 -5 重算为 -8，计算 gear 仍为 0，功率出现约 3.8W 提升。该测试同时改变了温度、SOC 和基础档，因此属于被动增益证据，不是可直接控制的稳定参数。

### 5.3 旧版抖音场景的 gear 功率分界

历史操作日志在抖音极速版前台、AC 在线、不同 SOC/温度下的验收结果如下。它们证明同一设备上 gear 与功率并非简单连续线性关系：

| 目标 gear | 观测功率 | 备注 |
| ---: | ---: | --- |
| 1 | 约 35–40W | 多次复测，策略对象偶尔为 null |
| 2 | 约 25W | 抖音规则显式覆盖 |
| 3 / 4 | 约 20W | 两档均约 `chargeVal=9` |
| 5 | 约 11W | 抖音组低功率端 |
| 6 | 约 8W | 原生基线 |

这些数值只属于当时的抖音热控分组。当前最大输出仍使用 gear 0，并受 SOC、温度和策略组影响。

### 5.4 `chargeVal` 与 gear

- 桌面前台曾观察到 `base=6`、`cosa=-3`、计算 `gear=3`、`chargeVal=0`，说明 `chargeVal=0` 不必然对应 gear 0。
- OPPower 自身没有包名热控策略，前台时 `getCurThermalPolicy` 可能为 `null`，界面因此显示 `chargeVal=--`。
- `chargeVal` 只作独立诊断；OPPower 不再用原生充电级回读或写入判断控制是否成功。

## 6. 自定义规则切换检查

- 开关关闭时进入 `默认规则`：默认温度阈值、默认 80% 本次插电暂停和默认电量分段参与判断。
- 开关开启后进入 `自定义规则`：温度阈值、电量阈值和“本次插电暂停阈值”都从 `customRules` 读取，默认规则不再参与。
- 自定义模式下，温度和电量先分别选出大档，再取两者中更保守的一档；大档确定后，只有温度阈值继续负责档内 gear 分段。
- 100% / 电池 FULL 仍固定暂停，这是独立安全条件。
- 界面开启自定义后会隐藏默认规则卡片，并显示自定义规则预览。
- beta11 增加两项回归测试，证明默认 41/43°C 温度和默认 80% 暂停不会在自定义模式继续生效。

### 5.5 2026-10-23 20:28 待执行

- 设备状态：AC 未接电，电量 93%，电池温度约 39.6°C。
- 计划：保持目标 gear 0，只比较 GuardElf `SmartChgMode=1` 与 `0`。
- 状态：因未接电未执行；不得用拔电时的电池功率作对照。
- 接电后动作：先固定 gear 0 和 `set_charge level 0`，再按“1 → 0 → 恢复 1”记录同条件功率。

## 7. 常用只读检查

无线 ADB 端口每次连接可能变化，先确认设备：

```powershell
adb connect 192.168.10.7:<端口>
adb devices
adb shell dumpsys battery
adb shell settings get system speed_charge_state
adb shell settings get system single_speed_charge_state
adb shell settings get global cosa_ctc_value
adb shell service call guardelfthermalcontrol 19
adb shell dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus
adb shell dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy
```

`dumpsys battery` 中需要记录：

```text
level
temperature
status
voltage
Battery current
Max charging current
```

## 8. 单项 A/B 流程

1. 固定前台应用、SOC、屏幕状态和温度，确认 OPPower 控制已稳定。
2. 记录基线：`Thermal Levels[0]`、`cosa`、计算 gear、`chargeVal`、电压、电流、电池侧功率。
3. 保存准备修改的参数原值。
4. 只修改一个候选参数；如果会改变 gear，先确认目标 gear 不变。
5. 等待系统完成刷新，记录一次稳定读数；不在过渡样本上判定成功。
6. 至少重复一次同条件对照，确认不是缓存或 SOC 阶段偶发变化。
7. 未得到稳定正收益就立即恢复原值；得到正收益也要记录恢复结果。
8. 把实验追加到本文件，写明“有效、无独立增益、阻塞”中的一种，避免下一轮重复测试。

## 9. 恢复清单

结束实验前确认：

```text
cosa_ctc_value = 0
SmartChgMode = 原值（默认 1）
single_speed_charge_state = 0
speed_charge_state = 3
customize_charge_switch_state = 不设置（null）
OPPower 关闭控制后仅恢复 cosa=0；不回写原生充电级
```

`customize_charge_switch_state` 已不属于 OPPower 管理范围；只有用户或系统原先主动设置时才保留原值。

## 10. 下一轮优先级

1. 在 beta11 下单独比较 `SmartChgMode=0` 与原值 1，确认移除 `customize=1` 后是否仍有稳定增益。
2. 检查 `vendor.oplus.hardware.charger.ICharger` 的 V12 接口，只读取语义明确的门控、状态和限流字段，确认是否存在可审计且可恢复的主动控制入口。
3. 检查电池服务中的 `Max charging current` 与 OplusBatteryService 命令，区分 Android 观测值、壳温保护和真实硬件限流，禁止直接把展示字段当成硬件 setter。
4. 只有发现系统代执行或明确可恢复的接口后，再评估慢充保护与充电配置入口。
5. 不复测场景广播、CustomSelectChgMode 0/1/2 或任何 `setChargingLevel`；也不把 SOC 或基础档自然爬升当作新产品功能。

## 11. 每次记录模板

```text
日期/时间：
版本：
ADB 端口：
前台应用：
接电/SOC/温度：
基线：基础档 | cosa | gear | native level | V | A | W
动作：参数名、原值、写入值
结果：稳定读数、重复次数
结论：有效 / 无独立增益 / 阻塞
恢复：各项最终值及回读
备注：是否出现通知、刷新、gear 回读或温度异常
```

## 12. 第三轮参数发现（2026-10-23）

本节记录第三轮源码审计和只读权限确认。本轮**未接电做功率 A/B**，所有“可能增益”只表示参数会进入充电决策链，不代表已实测能提高功率。只读基线为 RMX8899 / ColorOS 17.0.0.106、SOC 87%、未接电、电池约 39.2°C、`Thermal status=7`、`Thermal Levels=[7]`、`cosa_ctc_value=0`、抖音极速版策略 `gearVal=7`、`chargeVal=3`、`speedChargeAdd=-2`。`ro.debuggable=0`，SELinux 为 Enforcing，`sys.deepsleep.allow.debug` 为空；shell 访问 `/sys/class/oplus_chg/*` 和 `/sys/class/power_supply/battery/current_now` 均为 Permission denied。

### 12.1 第三轮参数表

| 参数 / 入口 | 写入语义 | 原值 / 默认值 | 潜在增益机制 | 当前权限状态 | 恢复方式 | 风险 | 当前结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ConfigFS `bcc_current` | 整数单位 `0.1A`；内部换算为 mA。双电芯直接使用换算值，单电芯投票值再除以 2；分别投 `VOOC_CURR`、UFCS/PPS 的 `BCC_VOTER` | 实际原值当前不可读；`0` 表示撤销 BCC 投票 | 清除残留 BCC 限流；若要提高，只能依赖其他 `VOTE_MIN` 安全票允许更高值 | shell `stat` 已拒绝；Shizuku 的 shell/2000 身份同样不能绕过 SELinux；需要系统代执行 | 写 `0` 撤销 `BCC_VOTER`；若原值非 0，正式集成前必须先建立可读回或保存机制 | 高 | **权限阻塞，未测**。不能把“写入较大值”当作主动升功率，`VOTE_MIN` 仍取所有安全票最小值 |
| GKI `battery/current_now` | `battery_psy_set_prop()` 把输入值同时投到 `WIRED_ICL`、`VOOC_CURR`、`UFCS_CURR`、`PPS_CURR`；快充 ICL 最低按 1500mA；`0` 撤销 HIDL 投票 | 系统动态电流；恢复值通常为 `0`，但写入单位及其 sysfs 转换仍需真机确认 | 可撤销 HIDL 留下的电流上限并触发重算；受其他 `VOTE_MIN` 票约束，本身不能突破安全上限 | `/sys/class/power_supply/battery/current_now` 读取/`stat` 均 Permission denied；还受 `oplus,smart_charging_screenoff` 设备树门控，当前节点不可读 | 写 `0` 撤销 `HIDL_VOTER`；若设备未开启 `smart_charging_screenoff`，写入直接返回不支持 | 高 | **权限阻塞，未测**。只适合作为“释放残留限流”候选 |
| GuardElf `setPsySlowChgEnable` / ConfigFS `slow_chg_en` | 三参数 `pct,watt,en`；`en=0` 释放慢充限流，`en=1` 进入慢充限制 | 当前节点不可读；系统调试命令实际固定使用 `pct=240, watt=33, status=<参数>` | 若系统或旧会话遗留慢充票，`status=0` 可释放；`status=1` 只会在约 33W 内限流，不是升功率开关 | GuardElf 源码要求 `com.oplus.permission.safe.READ_COMMON`；shell 调用被拒绝，需要系统进程代执行 | `status=0`；恢复到调用前状态 | 中 | **恢复候选，未测**。旧记录里的 `60,33` 已过时，当前 APK 源码静态值为 `240,33` |
| ConfigFS `cool_down` / `setSmartCoolDown` | 写 `0` 只撤销 `USER_VOTER`，不会清掉温度、SOC、适配器等其他投票 | 实际原值不可读；恢复写 `0` 或原值 | 仅释放用户侧残留降温票；其他票存在时不会提升 | ConfigFS 节点 shell 拒绝；系统代执行可用性未确认 | 写 `0` 撤销用户票 | 中 | **仅释放限流，不保证增益** |
| Android `POWER_SUPPLY_PROP_CHARGE_CONTROL_LIMIT` | 对外报告 `6500000`，最大档位为 `1`，属性标成可写 | 固定显示值 | 名字看起来像充电上限，实际 `battery_psy_set_prop()` 对应分支直接 `break` | 伪可写，不产生内核动作 | 无需恢复 | 低 | **已排除**。写入不会提高或降低真实充电电流 |
| `normal_current_now` | 把当前值保存为 Quick Mode 统计/增益基线 | 动态；离线时内部归零 | 不直接设置充电电流 | ConfigFS 节点本身同样受 shell 权限限制 | 写 `0` | 低 | **只统计，已排除** |
| `normal_cool_down` | 保存 Quick Mode 的“普通档”基线；非 0 时 `normal_current` 更新会被跳过 | 动态；恢复 `0` | 不改变当前充电档，最多影响 Quick Mode 统计 | 同 ConfigFS 权限边界 | 写 `0` | 低 | **只统计，已排除** |
| `chg_up_limit` | 解析充电上限状态、SOC 和重充阈值，不是瓦数或电流倍率 | 系统当前 SOC 上限配置 | 可能改变何时显示充满/重充，不提供更高瞬时功率 | ConfigFS shell 拒绝 | 写回系统原配置；不可凭猜测构造 | 中 | **不是升功率入口** |
| ConfigFS `fast_chg_type` | 只覆盖用户可读的快速充电类型；内部适配器功率镜像也可能读取该覆盖值 | `0` 时用真实类型 | 不会改变 VOOC/UFCS 电流；伪造类型反而会污染诊断和策略读数 | ConfigFS shell 拒绝 | 写 `0` | 中 | **显示镜像，已排除** |
| ConfigFS `bcc_parms` | 在 `BCC_SET_DEBUG_PARMS` 开启时覆盖 BCC 读取数据 | 实际 BCC 曲线参数 | 只影响读出的 BCC 参数；没有生产电流 setter | ConfigFS shell 拒绝 | 关闭调试字符串，恢复真实读取 | 中 | **不等同于 `bcc_current`，已排除为升功率项** |
| 内核 `supplementary_power_mos` | 电池均衡/补充放电路径的控制 MOS，内部函数为 `oplus_wired_set_supplementary_power_mos(topic, bool)` | 由电池均衡状态控制 | 可能改变并联电池路径，但代码把它归入电池均衡与异常保护 | 没有 ConfigFS 或 HAL 公共写入入口；只能走内核内部调用 | 由电池均衡离线清理逻辑复位为关闭 | 高 | **可达性阻塞**。不作为普通单向提功率入口 |
| ConfigFS `bms_heat_temp_compensation` | 当壳体温度低于 `OPLUS_BMS_HEAT_THRE=250`（25°C）时，从壳体温度减去该补偿值 | 默认 `0` | 通过降低低温场景的热控输入影响策略 | ConfigFS shell 拒绝 | 写 `0` | 高 | **当前 39–42°C 场景不会触发，测试注入性质，排除** |
| OplusBatteryService `setThermalControlTest 1` + `setTempLevel` | 直接令热控采用测试档位数组，可替换 `Thermal Levels[0]` | 测试模式关闭 | 可把基础热档调高，再用负 `cosa` 计算到 gear 0；可能改变 gear 0 对应的电流映射 | 受 `sys.deepsleep.allow.debug` 门控；当前属性为空，既往命令返回 `cmd not allow` | 立即 `setThermalControlTest 0` 并恢复正常热控 | 极高 | **禁止纳入正式功能**。仅可在隔离实验、持续监控温度时考虑 |
| OplusBatteryService `setAmbientTemperature N` | 伪造场景环境温度并触发热策略重算 | 系统实际环境温度 | 可绕过环境温度对热档的影响 | 同一 debug 门控，当前不可达 | 取消测试源并等待真实传感器重算；无统一恢复 setter | 极高 | **测试注入，当前阻塞，排除** |
| OplusBatteryService `setTsensorSource N` | 切换/伪造测试温度传感器来源 | 系统实际传感器源 | 可间接改变热档和充电策略 | 同一 debug 门控，当前不可达 | 必须先保存真实源值，再切回；当前无法建立可靠恢复 | 极高 | **测试注入，当前阻塞，排除** |
| ConfigFS `plc` | `switch=0/1&#124;callname=...` 控制 PLC 通信使能；`buck=...` 投强制 buck 票 | 由适配器协议控制 | 属于协议通信与兼容路径，不直接增流 | ConfigFS shell 拒绝 | `switch=0`，并让协议状态机自然恢复 | 中 | **不是功率倍率，低优先级** |
| ConfigFS `sec_ic_test` | 向次级充电 IC 下发测试命令并等待结果 | 无测试任务 | 可能直接操作次级 IC 寄存器，语义由测试命令决定 | ConfigFS shell 拒绝 | 无公开统一恢复；必须断电或等待 IC 自恢复 | 极高 | **禁止盲测** |
| Charger HAL `setCV(cv_value, flag)` | V12 事务 132，设置恒压相关参数；当前源码摘要没有实现语义和范围校验 | 未知 | 理论上可改变充电电压终点或 CV 行为 | HAL 直连此前权限拒绝/DeadObject；未找到电池服务公开调用面 | 未知；必须先获得实现源码和设备原值 | 极高 | **阻塞，不盲测** |
| Charger HAL `setChargerControl(data)` | V12 事务 81，直接下发充电控制字符串 | 未知 | 可能含模式、路径或硬件控制命令 | 同上 | 未知 | 极高 | **阻塞，不盲测** |
| Charger HAL `setBobStatus(data)` | V12 事务 118，BOB 状态设置；接口存在但电池应用未找到公开调用构造 | 未知 | 可能影响供电路径，不能确认会影响充电电流 | 同上 | 未知 | 极高 | **语义不足，排除到取得实现源码后** |
| Charger HAL `setChargerFactoryModeTest(data)` | V12 事务 85，工厂模式充电测试 | 未知 | 测试模式可能绕开正常策略 | 同上 | 未知 | 极高 | **禁止纳入正式功能** |
| GuardElf / HAL `setTbattPwrOff` | 热电池电源关闭/保护路径 | 未知 | 方向是保护关断，不是升功率 | GuardElf shell 权限拒绝 | 恢复原状态；当前无法审计 | 极高 | **排除** |
| GuardElf / HAL `setPsyMmiChgEn` | MMI 充电使能/禁止 | 系统当前状态 | 只能开关，无法设定功率上限 | 无显式权限校验，事务 14 可调用 | 恢复为 `1` | 中 | **已实测无效**：只造成一次短暂约 20 W 重协商，随后回落；不再测试或接入 |
| GuardElf / HAL `setChargingModeInGsmCall` | 电话场景充电模式 | 系统当前模式 | 通常是降流或兼容门控 | GuardElf shell 权限拒绝 | 恢复原模式 | 中 | **只可能限制功率，排除** |
| `setReserveSocDebug` | 调试保留 SOC/显示值 | 当前调度值 | 只影响 SOC 展示与保留区间 | GuardElf 权限边界 | 清除调试值 | 中 | **不是充电电流入口** |
| `setChargerCycle` / `setChgOlcConfig` / `setChgRusConfig` / `setChgConfig` | 充电周期、OLC、RUS、通用配置命令 | 未知 | 可能改变策略或硬件配置，但缺乏字段级语义 | GuardElf shell 权限拒绝，GuardElf 事务 34–44 已出现 `READ_COMMON` 或 `POWER` 拒绝 | 未知 | 极高 | **权限及语义阻塞，不盲测** |

### 12.2 第三轮结论

1. 第三轮没有发现新的、可由普通应用直接写入且能越过其他安全票的“大功率 setter”。
2. `bcc_current` 和 `CURRENT_NOW` 都进入 `VOTE_MIN` 链，只能清除残留上限或索取更低电流，不能把系统当前安全上限抬高。
3. `slow_chg_en` 的源码公式已还原：启用时先按 watt 换算，再用 `pct` 限制曲线电流，并保底 `SLOW_CHG_MIN_CURR`；当前调试链的 `240,33` 仍属于约 33W 的慢充限制，`status=0` 才撤销该票。
4. `CHARGE_CONTROL_LIMIT`、`normal_current_now`、`normal_cool_down`、`fast_chg_type` 和四类 power 镜像字段都不能直接提高充电功率，不再围绕这些字段重复实验。
5. `supplementary_power_mos` 属于电池均衡与异常保护路径，没有安全的应用写入面；`setCV`、`setChargerControl`、`setBobStatus` 等 HAL 入口在缺少实现语义时全部保持阻塞。
6. 真正的可控大方向仍是已经验证的组合：目标 gear、`cosa_ctc_value`、原生 `set_charge level`、`SmartChgMode=0`，以及权限允许时释放残余慢充/降温票。

### 12.3 下一次接电后的最小验证顺序

只有获得可审计的系统代执行入口后才执行，且每次只改变一个变量：

1. 先读 `slow_chg_en`；若启用，记录 `pct,watt,en`，写 `status=0`，确认功率与 `chargeVal` 是否变化。
2. 再检查 `COOL_DOWN` 是否存在 `USER_VOTER` 残留；写 `0` 后确认其他冷却票是否仍存在。
3. 只有确认设备树启用了 `smart_charging_screenoff` 后，才评估 `battery/current_now` 的 `HIDL_VOTER` 释放；不把写入大值当作升功率手段。
4. `bcc_current` 只作为残余 BCC 限流检查项，写入前必须解决原值读回和异常恢复。
5. 不把 `setThermalControlTest`、`setTempLevel`、`setAmbientTemperature`、`setTsensorSource` 集成进 OPPower；这些只保留为隔离源码研究结论。
## 13. 第三轮发现中的实际可用项（2026-10-23）

本节只统计第 12 节新发现的参数。前几轮已经交付并接入的参数不重复计入本节。

### 13.1 筛选结果

| 项目 | 数量 | 结论 |
| --- | ---: | --- |
| 第三轮新增参数/入口 | 23 | 均已完成源码或权限筛选 |
| 当前设备可直接用于正式控制 | 0 | 没有新增的正式可用入口 |
| 当前只能释放残留限流、但无法访问 | 3 | `bcc_current`、`CURRENT_NOW`、`slow_chg_en` / `COOL_DOWN` 相关限流票 |
| 有接口但不可用或不应使用 | 20 | 权限拒绝、debug 门控、无实际作用、测试注入或字段语义不足 |

因此，第三次发现对 OPPower 当前正式控制逻辑的可交付结论是：**新增可实际使用的参数为 0 项，不增加新控制功能。**

### 13.2 第三轮新增项的实际状态

| 分组 | 第三轮参数 | 实际状态 |
| --- | --- | --- |
| 电流/慢充限流 | `bcc_current`、`current_now`、`slow_chg_en`、`cool_down` | shell/Shizuku 权限拒绝；即使可写也只是 `VOTE_MIN` 限流票，不能突破其他安全上限 |
| 伪可写或仅统计 | `charge_control_limit`、`normal_current_now`、`normal_cool_down`、`chg_up_limit`、`fast_chg_type`、`bcc_parms` | 无实际升功率作用或仅污染/覆盖诊断读数 |
| 内核内部路径 | `supplementary_power_mos` | 没有可供 OPPower 使用的安全公共写入面 |
| 温度补偿/测试注入 | `bms_heat_temp_compensation`、`setThermalControlTest`、`setTempLevel`、`setAmbientTemperature`、`setTsensorSource` | 当前场景无效、debug 门控或会绕过真实温度保护，禁止接入 |
| 协议/硬件测试 | `plc`、`sec_ic_test`、`setCV`、`setChargerControl`、`setBobStatus`、工厂模式、OLC/RUS/ChgConfig | 权限、字段语义或恢复机制不足，禁止盲测 |
| 仅关闭或显示 | `setTbattPwrOff`、`setPsyMmiChgEn`、`setChargingModeInGsmCall`、`setReserveSocDebug` | 不属于提高功率的控制入口 |

### 13.3 实测权限确认

```text
handleSlowCharge 0                     -> cmd not allow
setPsySlowChgEnable                    -> READ_COMMON 权限拒绝
setSmartCoolDown                       -> READ_COMMON 权限拒绝
bcc_current / current_now              -> Permission denied
slow_chg_en / cool_down                -> Permission denied
setThermalControlTest / setTempLevel   -> debug 门控
```

本轮没有向 OPPower 增加任何第三次发现的新功能，也没有修改 APK 或正式源码。

## 14. 第四轮控制面复核（2026-10-23）

本轮继续检查 `BatteryServiceExtImpl$GuardElfThermalControl`、`OplusBatteryServiceFeature`、`ThermalControlUtils` 和充电 HAL 包装层，并在设备恢复无线 ADB 后完成一次插电实测。

### 14.1 GuardElf 权限筛选

`BatteryServiceExtImpl$GuardElfThermalControl` 的 50 个 Binder 方法中，与有线充电有关、增加了新控制可能性的无权限写入口如下：

| 事务 | 方法 | 权限检查 | 当前状态 |
| ---: | --- | --- | --- |
| 20 | `setSmartChgMode` | 无 | 已接入 `SmartChgMode=0`，不重复统计 |
| 24 | `setCustomSelectChgMode` | 无 | 0/1/2 已测无稳定独立增益，不重复统计 |
| 14 | `setPsyMmiChgEn` | **无** | **已实测无效**：只短暂重协商约 20 W，随后回落 |
| 1 | `setChargeLevel` | 包装层无权限检查，但要求调用 UID 为 1000 | shell/Shizuku 不能直接调用；当前通过系统热控广播下发 |

`setPsySlowChgEnable`、`setSmartCoolDown`、`setReserveSocDebug`、`setSuperEnduranceStatus/Count`、`setChargerCycle`、`setChgOlcConfig` 等方法继续受 `com.oplus.permission.safe.READ_COMMON`；`setChgConfig`、`setChgRusConfig`、`setChargingModeInGsmCall` 和 `setTbattPwrOff` 继续受 `com.oplus.permission.safe.POWER`。因此本轮没有出现新的慢充、降温或 BCC 解限入口。

### 14.2 `setPsyMmiChgEn` 的边界

调用链为：

```text
service call guardelfthermalcontrol 14 s16 <0|1>
→ BatteryServiceExtImpl$GuardElfThermalControl.setPsyMmiChgEn()
→ OplusBatteryServiceFeature.setPsyMmiChgEn()
→ vendor.oplus.hardware.charger.ICharger.setPsyMmiChgEn()
```

它控制底层 MMI 充电使能，语义是关闭或恢复充电使能，不是电流、瓦数或 BCC 上限 setter。可验证的用途是一次短时 `0 → 1` 软重插，让 PDO、VOOC/PPS 或残留限流重新协商；不能把 `1` 当作提高功率倍率，也不能让 `0` 常驻。

实测环境为 RMX8899、ColorOS 17、SOC 66%、约 35.5°C、OPPower 前台、真实亮屏、AC 充电、基础热档 5、`cosa=0`、`chargeVal=4`。每次只改变这一项：

```text
基线：基础档 | cosa | 计算 gear | chargeVal | native level | V | A | W
动作：service call guardelfthermalcontrol 14 s16 0
观察：充电状态和电流是否归零
恢复：立即 service call guardelfthermalcontrol 14 s16 1
观察：30 秒稳定功率、快充类型、温度、gear
```

只有确认 `1` 已恢复、电流重新建立，并且功率持续高于同一 SOC/温度下的基线，才可记为有效；否则只保留为故障恢复手段。测试后必须回读 `dumpsys battery`，确认 AC/USB 已恢复且 `Charging state` 非 0；禁止把 `0` 留在设备上。

### 14.2.1 实测结果

用户外接功率计观察到：写入 `0 → 1` 后只出现一次短暂约 20 W 的重新协商，随后回落到原功率层。OPPower 界面稳定采样为：

| 阶段 | 显示功率 | 电池电流 | 温度 | 状态 |
| --- | ---: | ---: | ---: | --- |
| 基线 | 10.5–10.6 W | 2517–2557 mA | 35.7°C | 充电中 |
| `0 → 1` 后 | 10.4–10.6 W，随后 8.3 W | 2488–2541 mA，随后 2004 mA | 35.7°C | 充电中 |

`setPsyMmiChgEn 0` 在 1 秒内没有改变 AC、PlugType、快充标志、`Charging state` 或电池电流，说明生产模式下它不会可靠地断开并重新协商底层充电；暂时出现的约 20 W 属于一次短时协商，不能跨过后续系统限流。最终已恢复到 `1`，AC 仍为 `true`、`PlugType=1`、`ChargeFastCharger=true`。

### 14.3 本轮排除项

- `oplus_battery_cool_charge` 只记录 `BatteryStatsManager` 的酷充事件，不进入充电环路或电流控制。
- `setLongChargeProtectionTestMode`、`setLongChargeProtectionBatteryLevel` 和 `setLongChargeProtectionChargeWattage` 只写入 `x8.k` 的测试内存字段，用于覆盖智能充电判断，不直接写硬件电流。
- `oplus_settings_hightemp_protect`、`oplus_settings_hightemp_safety_state`、`settings_thermal_high` 是高温流程状态位，不是提功率开关。
- `smart_charge_switch_state` 控制智能充电调度；`smart_charge_protection_switch_state` 控制高电量后的长充保护。两者可能避免后续降速，但不提供更高的安全电流上限。
- `setChargerControl`、`setCV`、`setBobStatus` 等 HAL 方法没有安全的应用侧包装面，继续禁止盲写。

### 14.4 第四轮结论

源码层面新增的 `setPsyMmiChgEn` 虽然可达，但实测不能稳定提高功率，只造成一次短暂约 20 W 重协商，随后回落。**第四轮新增的正式可用参数仍为 0 项**；该入口已排除，不接入 OPPower，也不再重复测试。

## 15. 正常模式正 `speedChargeAdd` 实测（2026-10-23）

测试环境与第 14 节相同：RMX8899 / ColorOS 17、OPPower 前台、真实亮屏、AC 充电、约 36°C、基础热档 5、`cosa=0`，当前策略为 `gearVal=5, chargeVal=4, speedChargeAdd=0`、`single_speed_charge_state=0`、`speed_charge_state=3`。

| 阶段 | 界面功率 | 电池电流 | 结果 |
| --- | ---: | ---: | --- |
| 正常基线 | 10.5–10.6 W | 2517–2557 mA | 充电中 |
| `set_charge(level=4, speedAdd=4, pkg=com.oppower)` | 10.2–10.5 W | 2448–2659 mA | 无持续提升，策略回读仍为 `speedChargeAdd=0` |
| 恢复 `speedAdd=0` 后 | 11.0 W | 2623 mA | 属于继续充电后的自然变化，不能归因于写入 |

广播均返回 `Broadcast completed: result=0`，恢复后 AC、`PlugType=1`、`ChargeFastCharger=true`、单速充电状态仍为 `0`。正常使用条件下，正 `speedChargeAdd` 没有形成可重复的额外功率；不启用 `single_speed_charge_state=1` 去专门触发该分支，因为此前已确认它会遮蔽档位回读并引入副作用。该项从待测候选改为排除。
## 16. 高温、基础档与功率的悖论复核（2026-10-23）

设备热控配置已从当前机器重新拉取到 `work/device_pull/sys_thermal_control_config-20261023.xml`，版本为 `2026082712`。复核源码和 XML 后，现有证据不支持“温度越高本身会提高充电功率”：温度升高会增加热控基础档，而基础档是限制项；命中同一最终 `gear` 时，温控 XML 给出的是同一行 `charge`（即运行时 `chargeVal`）。

### 16.1 当前设备的策略映射

同一份 XML 中，实际策略按“全局策略 → 应用/category/scene → complexscene”逐层覆盖。当前 OPPower 前台回读为 `common_config_float_window_scene_102`，命中 `float_window_scene_101-float_window_scene_102` 这一组：

| gear | chargeVal | speedChargeAdd |
| ---: | ---: | ---: |
| 0 | 12 | 0 |
| 1 | 9 | 0 |
| 2 | 6 | 0 |
| 3 | 6 | 0 |
| 4 | 6 | 0 |
| 5 | 4 | 0 |
| 6 | 2 | 0 |
| 7 | 2 | 0 |
| 8 | 2 | 0 |

没有 complexscene 覆盖、最终回落到 `globalPolicy` 时，映射又不同：

| gear | chargeVal | speedChargeAdd |
| ---: | ---: | ---: |
| 0 | 0 | 未设置 |
| 1 | 0 | 未设置 |
| 2 | 7 | 0 |
| 3 | 7 | 1 |
| 4 | 7 | 2 |
| 5 | 5 | 4 |
| 6 | 3 | 4 |
| 7 及以上 | 2 | 4 或更高 |

这解释了为什么“同是 gear 0 或 chargeVal 0”却可能相差很多：`chargeVal` 是策略行结果，功率还取决于当前应用/category/complexscene、充电协议和 SOC 阶段。

### 16.2 温度、基础档与 cosa 的真实关系

`ThermalControllerCenter.i1()` 先取系统基础温度档，再加入位置、环境、场景等修正，最后叠加 `cosa_ctc_value`：

```text
最终 gear = 热控基础档 + 场景/其他修正 + cosa_ctc_value
```

如果目标 gear 固定为 0，基础档从 5 升到 8 时，OPPower 会把 `cosa` 从 `-5` 重算成 `-8`。最终仍是 gear 0，应读取同一策略行。因此：

- 基础档升高本身不是“增加功率”的控制量，反而代表系统在升温时提高保护级别。
- `cosa` 只是把最终 gear 拉回目标值，不能改变同一 gear 行的 `chargeVal`。
- 高温样本中短暂变高的功率不能归因于高温，必须检查同时发生的 `set_charge(0)`、策略组变化、SOC/电压阶段、适配器重新协商或缓存错误。

### 16.3 下午 30–40 W 连续段的重新判定

原始采样文件位于 `OPPower/.codex-inspect/`。下午高功率段不是几秒钟的波动，表格中有连续多段：

| 时间 | SOC | 电池温度 | 基础档 | 计算 gear | 服务端策略 | 电池侧功率 |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| 15:33:21–15:33:25 | 43% | 37.2°C | 5 | 5，`cosa=0` | policy gear 3，`chargeVal=0`，`speedChargeAdd=-2` | 32.38 W |
| 15:33:31–15:34:01 | 43–44% | 37.1–37.2°C | 5 | 0，`cosa=-5` | 缓存仍先为 gear 3，随后读取不到 | 31.76–38.72 W |
| 15:36:29–15:36:34 | 47% | 36.99°C | 5 | -1，`cosa=-6` | 读取不到 | 40.02 W |
| 15:40:01–15:40:35 | 54–55% | 39.7–39.9°C | 7 | 1，`cosa=-6` | policy gear 1，`chargeVal=0`，`speedChargeAdd=-2` | 38.07–38.85 W |
| 15:41:59–15:42:28 | 58–59% | 40.7–41.0°C | 8 | 2，`cosa=-6` | policy gear 1，`chargeVal=0`，`speedChargeAdd=-2` | 38.97–39.45 W |
| 15:49:26–15:50:09 | 68–70% | 40.7–40.9°C | 7 | 0，`cosa=-7` | 读取不到 | 34.53–35.83 W |

这推翻了“只是临时重协商”的解释。高功率期间至少持续了数十秒到两分钟，而且外接功率计也观察到 30 W 以上。

因果关系也不是温度：

- 15:33:14–15:33:19 时 SOC 42%、电池 37.2°C，功率约 15.58 W。
- 15:33:16 活动策略从 `chargeVal=6, speedChargeAdd=1` 切到 `chargeVal=0, speedChargeAdd=-2`。
- 15:33:21 功率升到 32.38 W，温度、基础档和 `cosa` 都没有变化。
- 15:33:31 随后才写入 `cosa=-5`；功率没有再次跳变，仍保持 32.38 W。

因此最有力的关联变量是 `chargeVal=0, speedChargeAdd=-2`，也就是最终进入原生 cooldown 0 的状态。温度、基础档和 `cosa` 只是当时同时存在的观察值。
15:37:42 的原始系统日志直接给出了这一调用：

```text
tempLevel=[6]
full=[com.android.launcher]
tempGear=[1], configItemName=common_config
chargeVal:0, speedChargeAdd:-2
set cooldown:0, normalCoolDown:0
```

这不是推断：此时前台就是桌面，系统实际下发了 cooldown 0。

`chargeVal=0` 经源码进入：

```text
ThermalControllerCenter.B2()
→ policy.charge
→ ThermalControlUtils.D1(chargeLevel, speedChargeAdd, foregroundPkg)
→ D1(0, -2, pkg)
→ setSmartCoolDown(0, 0, pkg)
```

`m2()` 的约 5 秒重算只有在重算结果仍为 `chargeVal=0` 时才会保持高功率；如果重算回到当前 OPPower/float-window 分组的 `gear 0 → chargeVal=12`，才会重新施加 cooldown。高功率不是系统“因为热而抬高”，而是充电 cooldown 被留在 0。

这组记录还证明：`getCurThermalPolicy.gearVal` 与 OPPower 按 `基础档 + cosa` 计算的 gear 多次不一致。第一次高功率出现时，服务端仍是 gear 3，`cosa=0`；不能拿服务端缓存的 gear 证明实际档位，也不能把 OPPower 算出的 gear 0 当成已经获得 cooldown 0。

`SmartChgMode=0` 和再次发送 `set_charge(level=0)` 在高功率已经建立后都没有产生额外增益；说明当时硬件已经是 level/cooldown 0。之后发送 `setChargingLevel 2` 才把功率从约 36.9 W 降到约 6 W，进一步证明真正控制硬件的是原生充电 level，而不是显示出来的齿轮。

### 16.3.1 当前仍未解释的部分

15:33 的高功率是在 `cosa=0` 时由活动策略变成 `chargeVal=0` 触发；当时的直接触发源仍需结合前台应用变化确认。历史日志同时记录了 `top=com.android.launcher.Launcher`，因此很可能与桌面包名触发的策略组切换有关。15:36 写入 `customize_charge_switch_state=1` 后也进入了一直锁存到约 15:50 的高功率段，但期间 `chargeVal` 多次读不到，暂不能把 `customize`、包名切换或热控刷新中的某一项单独认定为根因。

下一步必须在同一前台应用、同一 SOC/温度下，分别固定和改变：

1. 前台包名/category；
2. `chargeVal` 是否等于 0；
3. 热控自身是否把计算 gear 0 映射为 `chargeVal=0, speedChargeAdd=-2`。只观察，不写原生 level。

只有这三项拆开后，才能判断怎样稳定进入并维持 30 W+。
### 16.4 当前实机只读基线

2026-10-23 23:25–23:30，ADB `192.168.10.7:44901`：

| 阶段 | SOC | 温度 | 基础档 | 策略 | 电流/电流上限 |
| --- | ---: | ---: | ---: | --- | --- |
| 插电、OPPower 后台/酷安前台 | 71% | 约 37.5°C | 5 | gear 5，chargeVal 4 | 约 2.25 A；`Max charging current=2,000,000` |
| 随后拉回 OPPower 前台上报状态 | 72% | 约 37.9°C | 6 | gear 6，chargeVal 2 | AC 已拔出，不能用于功率 A/B |

`Max charging current` 只能作为 Android 观测字段，当前在插电样本中与电池电流不严格相等，不能据此直接认定存在可由应用解除的 2 A setter。

### 16.5 下一项必须验证的假设

接电后固定同一前台应用、SOC 和温度，按以下顺序验证：

1. 在 OPPower 前台保持计算 gear 0，读取当前 `chargeVal` 和电池电压/电流。
2. 如果当前不是高功率，只记录原策略并检查 `cosa` 与基础档计算结果；不得执行 `setChargingLevel`。
3. 若高功率与 `chargeVal=0` 同时出现，比较同一 SOC/温度下 OPPower 与普通前台的策略 category；仍不写原生 level。
4. 只在不切换应用的情况下测试热控重算后的状态保持，不能把升温当作升功率手段。

在上述 A/B 完成前，不把高温、基础档升高或 `Max charging current=2,000,000` 中的任何一项宣称为新的稳定增益入口；已有数据支持的候选是原生 cooldown/charge level 0。

## 17. `chargeVal=0, speedChargeAdd=-2` 门控实现（2026-10-24）

### 17.1 触发链复核

用户指出的对照条件是正确的：下午低功率到高功率跃迁时，热控策略从 `chargeVal=6, speedChargeAdd=1` 切为 `chargeVal=0, speedChargeAdd=-2`，最终调用 `ThermalControlUtils.D1(0, -2, pkg)`。之前单独比较 `SmartChgMode 0/1` 没有复现该变量，只是排除了 `SmartChgMode` 的独立贡献。

源码确认 `set_charge` 广播会把 `level` 和 `speedChargeAdd` 同时传入 `D1()`。正常模式下 `D1(0, -2)` 最终以 cooldown 0 调用 `setSmartCoolDown(0, 0, pkg)`。`getCurThermalPolicy.chargeVal` 仍是系统策略行的只读诊断值；应用直接下发 `set_charge` 后不伪造也不强写这个回读值。

### 17.2 复杂场景阻断

2026-10-24 00:09 的热控日志显示：`float={com.tencent.mm}`、`full={com.android.launcher}`，即使桌面全屏，也叠加了 `com.android.launcher_float_window_scene_102`。该行的 gear 0 映射为 `chargeVal=12, speedChargeAdd=0`，会覆盖普通 `common_config` 的 `chargeVal=0` 结果。

先回桌面的单变量测试没有切换出普通 category，说明仅改变前台包名还不够；微信浮窗任务仍被热控计入 `float`。为保留用户微信状态，本轮没有关闭或移除微信任务 1712。后续真机复测必须记录热控日志中的 `float={...}` 和 `full={...}`，并在普通 `common_config` 与浮窗复杂场景分别采样，不能只比较功率。

2026-10-24 01:08 又做了单项复核：把 `oplus_float_window_show` 从 `1` 改成 `0`，再通过 `oplus_camera_3rd_activity` 的等值别名 `0 → 00` 触发 `m2()`。微信进程和可见的 `TYPE_APPLICATION_OVERLAY` 窗口都保留。重算日志先出现 `float={com.tencent.mm}`、`com.android.launcher_float_window_scene_102`，随后为 `common_config_float_window_scene_102`；8 秒后 `getCurThermalPolicy` 仍返回 `categoryName: common_config_float_window_scene_102`。因此该开关不会关闭微信浮窗，也没有在当前 ColorOS 17 固件上立即把浮窗移出热控分类。测试后已恢复为 `1`。日志见 `.codex-inspect/float-show-0-refresh-20261024-010842-logcat.txt`。

### 17.3 `chargeVal=--` 的版本与语义

2026-10-23 15:49–15:50 和 15:57–15:58 出现 `chargeVal=--` 时，设备运行的是 beta8；beta9 到 20:01 才构建完成。原始 TSV 中同一时刻的 `policyGear`、`chargeVal`、`speedChargeAdd` 全部为空，界面把它们统一显示成 `--`。因此这次看到的是策略对象/字段读取失败，不是读取到了 `chargeVal=-2`。

下午 32–40 W 的记录里，成功读到的是 `chargeVal=0, speedChargeAdd=-2`；另有 `policyGear=3`、基础档 5、`cosa=0`、计算档 5 的缓存不一致。可以确认 `-2` 属于 `speedChargeAdd`。`chargeVal=-2` 没有被下午的原始日志证实，不能据此建立映射。
### 17.4 beta13 控制变更

beta13 将目标 gear 与原生下达统一为：

| 目标 gear | 原生 level | speedChargeAdd |
| ---: | ---: | ---: |
| 0 | 0 | -2 |
| 1 | 0 | 0 |
| 2–4 | 7 | 0 |
| 5 | 5 | 0 |
| 6 | 3 | 0 |
| 7+ | 2 | 0 |

- 目标首次进入或档位变化时立即下发。
- 同一目标稳定时每 3 秒重新下发一次，覆盖系统约 5 秒一次的内部重算。
- `cosa` 仍按 `目标 gear - Thermal Levels[0]` 每秒实时计算；变化时立即写入并触发一次系统重算。
- 暂停、关闭总开关、拔电、SOC 暂停或充满时恢复 `cosa=0`、原生安全级 2、speed 0，并恢复会话前的 `SmartChgMode`。
- beta12 实测发现：`set_charge` 广播返回 `Broadcast completed: result=0`，但 Battery 服务没有出现对应的 `setSmartCoolDown`，说明 shell 身份并没有实际调用到 `D1()`。beta13 改为受限的 `dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel N`；日志已确认 `level=0` 会调用 `setSmartCoolDown(0,0)` 并到达 Charger HAL。
- Shizuku 白名单只允许 `setChargingLevel 0/2/3/5/7`，不再允许 `set_charge` 广播。

### 17.5 真机验收结果

2026-10-24 00:57 已在 RMX8899 上完成接电验收。微信进程 `14364 14618` 和可见的 `TYPE_APPLICATION_OVERLAY` 浮窗始终保留；`oplus_float_window_show=1`，没有强制停止或移除微信任务。

- OPPower 保持前台，电量 88%，电池温度 38.5–38.6°C。
- UI 显示基础档 6、`cosa=-6`、计算档 0、policy `chargeVal=12`、原生充电级 0、`speedAdd=-2`。
- 25 秒日志中共出现 8 次 `setChargingLevel 0`，每次都到达 `setSmartCoolDown: coolDown = 0` 和 HAL `set cooldown:0`；本段没有非零 cooldown 写回。
- 同期功率约 11.10–11.53 W，没有因连续下发而提升。
- 结论：连续下发确实有效到达 HAL，但在“微信浮窗 + 高 SOC + 当前 10 W 协商状态”下不足以提高功率。它不能单独解决 `float_window_scene_102` 的 `chargeVal=12` 映射。
- 下一步需要在同样保留浮窗的前提下，于较低 SOC 复测，或寻找比 `setChargingLevel` 更高层且能改变 `speed_charge_state`/HAL 协商的门控。重复增加下发频率没有证据支持。

### 17.6 beta14 发热与调用收敛

beta14 回退 beta13 的稳定期连续 native 下发，避免每 3 秒唤醒 BatteryService 和 Charger HAL：

- beta8 本身没有主动调用 `setChargingLevel`；下午 32–40 W 的 `chargeVal=0, speedChargeAdd=-2` 是热控 `m2()` 自行生成的策略。
- beta14 只在首次进入目标，或原生 level / speedAdd 映射发生变化时调用一次 `setChargingLevel`。
- 同一目标、同一基础档且无需修正 `cosa` 时，不再重复执行 native 命令。
- `SmartChgMode` 在一个充电会话内只读取、设置并确认一次；稳定期不再每秒回读。
- 页面指标仍按 1 秒刷新，控制循环仍每秒读取基础档和 `cosa`，以保持及时计算；曲线继续按 LLPower 的 3/5/180 秒节奏采样。
- 这些调整用于避免应用进程额外制造持续 Binder、shell 和 HAL 调用；不以高频轮询或升温换功率。
### 17.7 beta15 门控协商收敛

beta15 将后续控制改为状态事件驱动，不再把温度当成升功率条件：

- 目标 gear 0 时，如果策略已经读到 `chargeVal=0, speedChargeAdd=-2`，直接复用该热控路径，不调用 `setChargingLevel`。
- 系统策略 category 变化并重新变成受限状态时，允许一次额外的 native 复协商。
- 每个稳定目标最多执行一次 category 复协商；场景来回变化也不会持续重发。
- native 调用即使失败也记录为已尝试，避免每秒重试造成 Binder/HAL 负载和额外发热。
- 20–40 W 和 10–30 W 默认仍以 43°C 作为安全暂停；最大输出自 beta24 起不再执行 OPPower 自身的 41/42/43°C 分档。温度上升永远不会提高目标 gear。
- `cosa` 仍每秒读取，实时基础档变化时立即重算并触发一次 `m2()`。
## 18. 可写充电参数筛选（2026-10-24）

本轮先从 BatteryService、GuardElf、Charger V12 和已持久化 settings 中筛选“可由 Shizuku/ADB 写入”的入口，再排除只读诊断与统计字段。

### 18.1 当前可写入口

| 参数/入口 | 写入方式 | 写入验证 | 当前结论 |
| --- | --- | --- | --- |
| `cosa_ctc_value` | `settings put global` | 写入并回读成功，数值变化可触发热控监听 | 正式使用；负责计算 gear 和场景修正 |
| BatteryService `setChargingLevel N` | 历史可写入口 | 能到 HAL，但会与热控自行写回 12/15 冲突 | **beta16 删除；不可用于正式功能或实验** |
| GuardElf `setSmartChgMode`（事务 20） | `service call guardelfthermalcontrol` | 写入和事务 19 回读均可达 | 可写；只有有限/不稳定增益，不作为单独提功率参数 |
| GuardElf `setCustomSelectChgMode`（事务 24） | `service call guardelfthermalcontrol` | 启用 mode 4 后 getter 从 3 变 4；关闭 mode 4 后恢复 3 | 可写，状态可切换；power 增益未成立 |
| GuardElf `setPsyMmiChgEn`（事务 14） | `service call guardelfthermalcontrol` | 无显式权限校验并直达 ICharger | 只能触发短时重协商，已排除持续增益 |
| `speed_charge_state` | `settings put system` | `3 → 0 → 3` 均回读成功 | 原生快充基础状态；当前应为 3，不能单独突破功率上限 |
| `single_speed_charge_state` | `settings put system` | `0 → 1 → 0` 均回读成功；写 1 后 HAL mode 变为 4 | 可写但有回读副作用；本轮已关闭 mode 4 并恢复 |
| `oplus_battery_cool_charge` | `settings put system` | `0 → 1 → 0` 均回读成功 | 温控充电事件/界面状态，尚无提功率证据 |
| `oplus_float_window_show` | `settings put system` | `1 → 0 → 1` 均回读成功并触发观察者 | 不关闭微信浮窗，也未使场景退出 float_window_scene_102 |

### 18.2 已排除

| 参数/入口 | 原因 |
| --- | --- |
| `chargeVal`、`speedChargeAdd`、policy category | 系统热控计算结果，只读 |
| Thermal Levels / 温度 / SOC / 实际功率 | 环境或结果字段，不能主动写入 |
| `battery_charging_state_enforce_level` | 属于 `BatteryStatsImpl.Constants`，只影响充电统计状态延迟/确认阈值，不连接充电 HAL |
| `battery_charging_state_update_delay` | 同上，仅控制 BatteryStats 更新与延迟 |
| `oplus_battery_settings_charge_wattage` | 来自 `cpa_charge_wattage` 广播的 UI 诊断值 |
| `setChgConfig`、`setSmartCoolDown`、`setPsySlowChgEnable`、`setByPassChargeStatus` | BatteryService 调试命令要求 `sys.deepsleep.allow.debug`，GuardElf 写入还受 `READ_COMMON` 或 `POWER` 权限限制 |
| `setSingleSpeedChargeState` BatteryService 调试命令 | 需要 deep-sleep debug 权限；直接修改对应 system setting 可写，但已证明有 HAL 副作用 |

### 18.3 接电后的正式筛选

设备在本轮筛选时未接 AC，不能把“写成功”当作“功率有效”。下一次接电后只做单变量、非周期测试：

1. 固定 SOC、温度、充电器、线材、前台包和浮窗状态。
2. 基线 30 秒，不执行 native 重压。
3. 禁止执行 `setChargingLevel 1..12`；只比较 `cosa` 刷新前后的系统策略和实际功率。
4. 对每个候选执行两轮；只有功率提升至少 5 W、持续至少 30 秒且可重复，才列为有效参数。
5. `setSmartChgMode`、`setCustomSelectChgMode` 和 system settings 分别做独立 A/B，不能和 level 扫描混合。
6. 每次测试后只恢复 `cosa=0`、`speed_charge_state=3`、`single_speed_charge_state=0`；不发送任何原生充电级。

截至本轮，**尚未新增可宣称稳定提高功率的参数**。当前已验证有效的仍是“计算 gear 0 + 30 秒内到达 cooldown 0”这一短时/条件性路径；稳定持久入口仍待接电测试。

### 18.4 第三轮补充发现：场景配置与无效“伪参数”（2026-10-24）

本轮增加验证了 COSA 非持久配置、游戏场景开关和若干看似“充电/高性能”的设置。设备此时未接 AC，温度为 39.87–39.95°C、电量 83%，所以以下只判定“能否写、是否改变热控策略”，不宣称实际瓦数提升。

| 参数/入口 | 写入方式 | 实测变化 | 功率可用性 | 结论 |
| --- | --- | --- | --- | --- |
| `cosa_gameMode` | `content insert/delete` 写入 `com.oplus.appplatform.settings/nonpersist`，可回读、删除恢复 | `0` 无变化；`1` 令当前策略 `gear 7 → 8`，同时 `restrict 0 → 1`、`modem 0 → 1`、`disVideoSR 0 → 1`；`2` 回到原策略；`chargeVal` 始终为 2 | 未提高充电策略，`1` 反而增加限制 | 可写，但排除 |
| `cosa_refreshSet=reset` | 同一非持久 Provider；可写、删除 | 可触发 ThermalControlUtils 刷新；会调用控制器重算 | 是刷新触发器，不是功率字段 | 仅用于诊断，不接入 |
| `debug_gamemode_value=1` | `settings put global`，可回读并恢复 0 | 在抖音非游戏场景下没有改变 category、gear 或充电字段 | 没有独立收益 | 可写，但排除 |
| `oplus_float_window_show=0` | `settings put system`，恢复 1 | 补做 HOME → 抖音重新分类后，category 仍为 `com.ss.android.ugc.aweme.lite_category_16_fw_scene_102`；`gearVal=7`、`chargeVal=2`、`speedChargeAdd=0` | 没有退出浮窗复杂场景，也没有提高充电级 | 可写，但不能作为提速开关 |
| `oplus_battery_cool_charge` | `settings put system` | 写入、回读和恢复成功 | 源码只用于 `BatteryStatsManager` 的 cool-charge 事件/计时统计，不是 HAL 充电控制 | 可写，但排除 |
| `speed_charge_state` | `settings put system` | 仅按 0/3 表示快充开关 | 官方便捷开关写入 3 后调用 `setCustomSelectChgMode(3, true)`；单独改设置不等于修改 cooldown | 可写，但不是独立功率级 |
| `IOplusUsageService.updateMaxChargeCurrent()` | IOplusUsageService | 服务 Binder 实现固定返回 `false`，不写 HAL | 无硬件效果 | 空实现，排除 |
| `IOplusUsageService.updateMaxChargeTemperature()` / `updateMinChargeTemperature()` | IOplusUsageService | 服务 Binder 实现固定返回 `false` | 无硬件效果 | 空实现，排除 |
| `setChgOlcConfig` / `setChgRusConfig` | GuardElf 事务 35/43 | 入口直达 ICharger，但需 `READ_COMMON` / `POWER`；当前 shell 被拒 | 无安全可用的恢复方式 | 权限阻塞，不盲测 |
| `setChargeEMMode` / `setChargerFactoryModeTest` | ICharger 事务 80/85，工程模式内部调用 | 工程测试会打开 EM/Factory 路径，未发现可由 shell 独立调用的安全包装 | 恢复语义不完整 | 不接入正式功能 |

第三轮可确认的是：`cosa_ctc_value` 仍是唯一已经证明既能主动调整、又能改变实际充电策略的通用偏移量；`SmartChgMode=0` 只有很小且不稳定的辅助效果；`chargeVal=0 + speedChargeAdd=-2` 仍是最关键的实际高功率门控。上述新字段都没有达到“单次写入后稳定提高至少 5 W”的标准。

当前场景在 `fw_scene_102`、基础档 7 时对应 `chargeVal=2`。该复杂场景 XML 中 gear 0/1 分别为 charge 12/9，但这两行仍会受充电协议、SOC、缓存重算和 cooldown 影响；不能用 `chargeVal` 数字直接换算固定瓦数。

### 18.5 第四轮接电复核：游戏模式与直接电流节点（2026-10-24）

本轮在真实接电、OPPower 强制亮屏快充开启、微信浮窗存在的条件下复测。设备电量 79%–82%，电池温度约 38.7–40.1°C；接电前为 `cosa=0`，接电后 OPPower 正常计算 `cosa=-6/-7` 使计算 gear 0。基线外接功率计约 15 W，电池侧约 4.33 A × 4.138 V = 17.9 W。策略为 `float_window_scene_102`、`chargeVal=12`、`speedChargeAdd=0`；`SmartChgMode=0`、`CustomSelectChgMode=3`。

| 项目 | 操作 | 实测结果 | 判定 |
| --- | --- | --- | --- |
| `cosa_gameMode=1` | 写入 nonpersist Provider，持续观察 30 秒，随后删除 | 写入和回读成功；外接功率计约 16 W，策略 `chargeVal` 仍为 12；没有持续高 5 W 的变化 | 可写但无提速收益，排除 |
| `/sys/class/oplus_chg/common/bcc_current` | 源码审计及设备权限检查 | 内核 store 会把 0.1 A 单位换算成 mA，并直接给 VOOC、UFCS、PPS 的 `BCC_VOTER` 电流 votable 投票；但 shell/Shizuku UID 2000 无法进入 `/sys/class/oplus_chg/common`，返回 Permission denied | 真实电流入口存在，但当前权限不可用 |
| `getChgConfig` / `getChargingModeInGsmCall` | GuardElf 事务 45 / 40 只读探测 | Binder 返回 `com.oplus.permission.safe.POWER` 权限拒绝 | 当前权限不可读，也不能据此接入 |
| `smart_charge_switch_state` | 历史 A/B | 系统默认 `1`；beta27 起 OPPower 不提供读取、写入或恢复入口 | 保留系统值 `1`，不再是应用可调候选 |
| `smart_charge_protection_switch_state` | 当前值检查 | 当前为 `1`，智能充电保护策略也开启 | 与上一项分开做单变量 A/B，不能同时切换 |

现有结果仍不能证明存在新的稳定提速写入入口。`bcc_current` 虽然是最接近主动电流控制的节点，但不能绕过 SELinux，也不应通过其他方式伪装系统 UID。历史上的 `smart_charge_switch_state` A/B 仅得到约 0.55–0.99 W 且伴随升温，beta27 已停止应用读写并把系统值保留为 `1`。

## 19. beta16：移除原生充电级，修复跳 0 W（2026-10-24）

真机出现约 40 W → 0 W → 恢复 → 再次 0 W，随后稳定约 27 W。日志中同时存在 OPPower 目标 cooldown 0 与 ColorOS 场景热控对 `cooldown=12/15` 的重算。继续由 OPPower 下发 `setChargingLevel` 会与系统重算争夺硬件最终状态，因此 beta16 完整删除控制器调用、Shizuku 方法、命令白名单和相关状态字段。

新的验收只需要确认：

1. OPPower 前台开启强制亮屏快充后，`cosa` 自动写为目标值，无需切应用。
2. 日志中不再出现 OPPower 触发的 `setChargingLevel`。
3. gear 0 保持期间不再出现由两套控制链交替写入导致的反复 0 W。
4. 记录达到 30 W+ 时的 SOC、温度、基础档、`cosa`、`chargeVal`、category 和功率，作为下一次主动优化依据；不以升温或原生重压换功率。

## 20. beta17：恢复 beta8 的纯 cosa 路径（2026-10-24）

历史充电曲线数据库确认，2026-10-23 15:47–15:54 在 beta8 下持续约 30.45–35.40 W，约 7 分钟保持 30 W 以上；该段 SOC 66–78%，温度约 40.0–41.4°C。beta8 控制器没有调用 `setChargingLevel`，也没有写 `SmartChgMode`，只写 `cosa` 并触发一次热控重算。

因此 beta17 删除 `SmartChgMode` 的读取、写入、恢复和 Shizuku 白名单，也不加入任何轮询守门。随后确认 `set_charge(0,-2)` 广播需要发送方持有 `oplus.permission.OPLUS_COMPONENT_SAFE`，Shizuku/ADB 没有该签名权限，命令即使返回成功也不会进入 `D1()`。该候选已从控制器、白名单和测试中删除，只保留 `cosa + 热控刷新`。

## 21. beta18：立即刷新候选（2026-10-24）

- 源码确认 `oplus_float_window_show` 的监听回调 `d1()` 会调用 `m.j2(null, 0ms)`，立即发送重算消息；旧的 `oplus_camera_3rd_activity` 路径最终调用 `m2()`，固定延迟约 5 秒。
- beta18 写入目标 `cosa` 后，优先把 `oplus_float_window_show` 的同一整数在 `0/00` 或 `1/01` 之间切换。只改变字符串表示，不改变系统读取到的整数，也不关闭微信或浮窗。
- 如果该设置不可用，才回退到相机监听入口。稳定期间仍不轮询，不调用 `setChargingLevel`，不写 `SmartChgMode`，也不发送 `set_charge` 广播。
- JVM 单测已通过。真机接电与解锁后的功率复验仍待完成；未完成前不得把“立即重算成功”写成“稳定提高功率”。
## 22. beta19：gear 0 脉冲保护（2026-10-24）

真机在 13%–24% SOC、AC 在线、OPPower 前台时观察到 gear 0 反复出现 `10 mA`，应用数据库记录为约 `0.038 W`。逐段结果：

- 温度 40°C 左右，gear 0 可保持约 32–37 W。
- 温度升到约 41°C 后，gear 0 开始出现 3–10 秒的 10 mA 停顿，随后恢复到约 38 W；42.3°C 后系统按规则降到约 18 W。
- 关闭 OPPower、`cosa=0` 后仍出现一次 10 mA 停顿，排除单纯由 `cosa` 写入造成。
- 手动保持 gear 1 的两轮测试中，41–43°C 下功率约为 33–38 W，数据库没有出现 0 W。

beta19 因此增加无防抖脉冲保护：当自定义规则仍要求最大输出且电池温度达到 `40.5°C` 时，立即使用 gear 1，并在本段最大输出期间保持；进入更温和档位、拔电或新会话后清除。稳定期间仍只写一次 `cosa` 并触发一次立即重算，不轮询 `setChargingLevel`，也不写 `SmartChgMode`。

JVM 单测已通过。beta19 的安装后接电复验需确认：41°C 附近不再出现 0 W，gear 1 能保持 30 W 以上，42°C 后仍按既有温和规则降档。

## 23. beta20：撤销 gear 1 保护并修复采样卡住（2026-10-24）

用户要求 gear 0 必须保持 gear 0，不能通过自动切到 gear 1 掩盖问题。beta20 因此完整移除 beta19 的温度保护状态和单元测试。

同时处理“OPPower 主功率卡在 0 W、LLPower 仍正常”的应用侧风险：

- 电流读取改为与 LLPower 一致的 `BatteryManager.getLongProperty(BATTERY_PROPERTY_CURRENT_NOW)`。
- 当 `CURRENT_NOW` 返回 0 时，回退到 `CURRENT_AVERAGE`。
- 页面每秒采样与历史数据库写入拆开，数据库写入不再阻塞主功率刷新。
- 页面采样循环和前台控制循环都捕获单次异常并继续下一周期，避免一次临时错误导致界面或控制器永久停住。
- gear 0 的目标、`cosa` 写入和一次立即刷新路径不变，不恢复 `setChargingLevel`、`SmartChgMode`、`set_charge` 或轮询。

`Thermal Levels` 只能读，当前权限不能写。源码中的 `setThermalControlTest`、`setTempLevel` 受 `sys.deepsleep.allow.debug` 门控；设备该属性为空，命令返回 `cmd not allow`。因此不把修改 Thermal Levels 纳入应用功能。

真机初测：微信无通话悬浮窗、OPPower 前台时，热控类别为 `common_config`，基础档 5–6、`cosa=-5/-6`、计算 gear 0，策略为 `chargeVal=3, speedChargeAdd=-2`，实际功率约 22–24 W。接电后 gear 0 无需切应用即可形成并持续刷新，但该条件下没有达到 30 W。桌面/后台控制条件仍待 AC 在线时复测。JVM 单测与 Debug 构建通过，安装版本 `1.0.0-beta20`。

## 24. `appCategory=game` / `category_4` 对照（2026-10-24，失败并撤销）

- 测试条件：RMX8899、ColorOS 17、真实亮屏、AC 接电、OPPower 前台；SOC 68%–72%，电池温度约 41.0–41.9°C。
- 临时把 OPPower Manifest 设置为 `android:appCategory="game"`，系统将其识别为游戏，`cmd game list-modes com.oppower` 可用，并设置为 `custom`。
- 开启 OPPower 现有保活悬浮窗后，系统确认 `TYPE_APPLICATION_OVERLAY` 已存在；`getCurThermalPolicy` 一度返回 `categoryName: float_window_common`。
- 单次 `oplus_float_window_show 0 → 00` 重算日志显示：`appType=-1`、`configItemName=common_config`、`float={com.oppower}`、`full={com.oppower}`，没有出现 `category_4_fw_common`，也没有 `setSmartCoolDown: cooldown = 0`。
- 同期电池侧功率约 24.8 W（5.69 A × 4.356 V），只能归因于 gear 0 和当前策略，不能归因于 `appCategory=game`。
- 对照测试包 `com.pi.czrxdfirst` 之所以能进入 `category_4_fw_common`，是因为它已在厂商 XML 热控名单中并被识别为 `appType=4`；普通应用只声明 `android:appCategory="game"` 不会获得这个系统分类。
- 已撤销 Manifest 游戏分类、`cmd game reset com.oppower`、关闭保活悬浮窗测试开关、恢复 `SYSTEM_ALERT_WINDOW` 为默认、恢复 `oplus_float_window_show=0`，并重新构建安装普通版本。
- 结论：该路径不能作为 OPPower 的稳定提速功能，不再重复测试。稳定 30 W+ 仍需找到能影响原生 `chargeVal=0, speedChargeAdd=-2` / cooldown 0 的可写策略条件。

## 25. 2026-10-25 服务接口与剩余设置审计

- 设备通过 `192.168.10.7:33881` 连接，RMX8899、OPPower `1.0.0-beta20`；审计时未接 AC，SOC 约 40%，电池约 37.7°C。
- OPPower 前台时因微信悬浮窗存在，活动热控策略为 `common_config_float_window_scene_102`，基础档 5、策略 `chargeVal=4, speedChargeAdd=0`；不能把 `cosa=0` 或 gear 回读值当作实际硬件充电级。
- `power_monitor` 的 shell 帮助虽然列出 `get/set`，但源码没有可用的功率 setter；当前实现只包含帮助、暂停控制、唤醒和子系统读取路径。`set` 还需要 `android.permission.DEVICE_POWER`，没有新增可用入口。
- `horae` 被 Battery 与 COSA 用于注册热控回调、读取 thermal status/Thermal Levels/温度、上报屏幕/闪光灯/折叠状态；没有充电电流或 cooldown setter。shell 调用 `cmd horae` 被 SELinux 拒绝，不接入。
- `oplus_battery_stats_service` 的 `IOplusBatteryStatsService` 只有 `getSystemServerBinderCallsStats` 与 `getUidSipper` 两个读取接口，没有充电控制面。
- `oplus_battery_cool_charge` 只驱动 HANS 酷充事件和后台场景，不写充电环路；`oplus.thermal.low.voltage`、相机低电压、户外场景和安全模式是电压/场景降级状态，当前均已处于不额外限制的状态，继续提高这些值没有合理收益。
- `customize_power_temperature_control_*` 是 HBM、OSIE、视频超分与流畅度的功能开关，不属于充电功率 setter。
- `ICharger.setChargerCoolDown`、`setChgConfig`、`setPsySlowChgEnable` 等直接 HAL 入口继续受 `READ_COMMON` / `POWER` 权限限制；不绕过 SELinux、不伪装 UID。
- 尚未完成 AC 单变量复核的只剩 `smart_charge_switch_state` 与 `smart_charge_protection_switch_state`。测试必须在同一 SOC、温度、充电器、前台包和浮窗状态下分别进行，每项观察至少 30 秒，并记录实际功率、策略 category、`chargeVal`、`speedChargeAdd` 与计算 gear。
- 历史高功率对照进一步确认：稳定 30 W+ 的核心仍是原生策略进入 `chargeVal=0, speedChargeAdd=-2` / cooldown 0；单纯把计算 gear 写为 0、提高温度或增加 HAL 写入都不能保证达到该状态。

## 26. 2026-10-25 AC 下智能充电开关 A/B

设备条件：RMX8899、系统 ColorOS 17、OPPower beta20 前台、AC 10 V 充电、真实亮屏、微信与 MultiApp 微信均保持运行。测试全程保持 `speed_charge_state=3`、`single_speed_charge_state=0`、`regular_charge_protection_switch_state=0`，计算 gear 0；策略始终为 `float_window_scene_102 / gearVal=0 / chargeVal=12 / speedChargeAdd=0`。功率按电池侧 `|Battery current| × Battery voltage` 计算。

| 阶段 | 主开关 | 保护开关 | 样本 | 平均功率 | 范围 | 温度 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 基线 | 1 | 1 | 30 | 25.40 W | 25.29–26.39 W | 38.1–38.4°C |
| 主开关关闭 | 0 | 1 | 30 | 26.39 W | 26.34–26.42 W | 38.5–38.7°C |
| 恢复主开关 | 1 | 1 | 30 | 26.13 W | 26.08–26.30 W | 38.8–39.0°C |
| 主开关再次关闭 | 0 | 1 | 30 | 26.68 W | 26.45–26.74 W | 39.2–39.5°C |
| 两项基线 | 1 | 1 | 30 | 26.81 W | 26.45–26.86 W | 39.5–39.8°C |
| 保护开关关闭 | 1 | 0 | 30 | 26.48 W | 26.21–26.76 W | 39.8–40.1°C |

结论：

- `smart_charge_switch_state=0` 的相邻重复对照只增加约 0.55–0.99 W，且温度同步上升，不能视为突破功率层的独立入口。beta21 曾将其作为补充提升保留；beta27 已按用户要求彻底移除读写，系统值保持为 `1`。
- `smart_charge_protection_switch_state=0` 反而下降约 0.33 W，策略字段不变；排除。
- 两项已恢复为 `1`。本轮没有新增可接入的功率参数。

## 27. MultiApp 微信悬浮窗差异

当前 WindowManager 中，MultiApp 用户 999 的微信 `PID 18113` 持有多个 `TYPE_APPLICATION_OVERLAY` 窗口；主用户微信 `PID 14364` 和推送进程均保持运行。对主用户微信临时设置 `SYSTEM_ALERT_WINDOW=ignore` 不会移除 MultiApp 已存在的窗口，热控仍保持 `float_window_scene_102`。该 app-op 已恢复为 `allow`，微信进程未被杀死。

历史高功率日志中的组合是：

```text
float={com.tencent.mm}
full={com.oppower}
tempGear=[1], configItemName=common_config
chargeVal:0, speedChargeAdd:-2
set cooldown:0
```

当前组合则是：

```text
categoryName=float_window_scene_102
gearVal=0, chargeVal=12, speedChargeAdd=0
```

因此下一步应只移除 MultiApp 用户 999 的现存悬浮窗，保留机主用户微信进程，再触发一次热控重算并比较 category 与功率。不得通过 force-stop 机主微信、绕过 SELinux 或恢复持续 HAL 轮询来实现。

## 28. beta21：补充充电开关与设置权限真值刷新（2026-10-25）

- 控制有效时读取 `smart_charge_switch_state` 原值并写为 `0`；相同状态不重复写，暂停、拔电、充满和关闭总开关时恢复原值。原值同步写入 SharedPreferences，服务重建后即使第一轮先遇到拔电，也能完成恢复。
- `cosa` 仍按“热控基础档 + cosa = 目标 gear”每秒计算；没有恢复 `setChargingLevel`、`SmartChgMode`、广播或功率守门轮询。
- 设置页改为读取 Android 真值：应用通知权限、当前通知渠道、电池优化、悬浮窗、无障碍和 Shizuku 安装/运行/授权/服务连接状态。应用启动、从系统设置返回、通知授权回调和 Shizuku 授权回调后都会刷新。
- beta21 已通过 JVM 单测、Debug 编译、lint（0 error）、构建和真机覆盖安装；设置页已正确显示系统通知权限与实时活动渠道。`smart_charge_switch_state=0` 的真机持续功率增益仍需在相同 SOC、温度和前台场景下复测。

## 29. beta22：按 LLPower 修正电流与功率读取（2026-10-25）

- 根因不是热控断充。该机 `CURRENT_NOW` 快充值在约 `-9947` 到 `-10065` 之间波动，旧 OPPower 在绝对值达到 10000 时把值除以 1000，使约 10 A 被误算成约 10 mA，功率因此显示约 0 W；回落到 9999 以下时又恢复约 38 W。
- LLPower 实际使用 `CURRENT_NOW → CURRENT_AVERAGE → sysfs current_now/main current_now/batt_current` 的回退顺序，并且只在绝对值大于 20000 时按微安换算。
- beta22 已按该逻辑接入，同时复制无效哨兵过滤、接近满电时整数安培异常值清理和电压 sysfs 回退。界面电流与功率使用同一电流结果。
- 新增边界单测：`-9947`、`10009`、`10010` 均保持 mA，`10010 mA × 3.94 V = 39.44 W`；只有 20000 以上才进入微安换算。

## 30. beta23：允许 cosa=-10 补偿基础档 10（2026-10-25）

- 真机出现 `Thermal Levels=[10]`、热控温度约 `43.98°C`、电池温度约 `43.0°C`。系统基础档 10 时，旧控制器只能写 `-9`，实际 gear 1，同时把所需 `-10` 判为越界并进入 ERROR。
- 用户明确要求基础档 10 时继续保持 gear 0，因此 beta23 将 `cosa_ctc_value` 下界扩到 `-10`，目标 gear 0 时直接写 `-10`，得到真实计算 gear 0。
- `-10` 曾在早期历史记录中表现为无效策略哨兵；beta23 将其作为当前设备的定向补偿值使用，后续真机继续核对 `chargeVal`、功率和实际充电，不再自动写成 gear 1 作为保护。
- 基础档高于 10 时仍禁止写 `-11` 及以下；控制器保持合法下界并明确显示目标不可达，待基础档回落自动重新达到 gear 0。

## 31. beta24：最大输出持续 gear 0 与基础档 11 边界（2026-10-25）

- 真机条件：RMX8899、ColorOS 17、AC 在线、物理解锁、真实亮屏、OPPower 前台，未切换应用；SOC 72%，电池温度约 42.8–43.2°C。
- 系统 `Thermal Levels[0]` 从 10 升到 11。beta24 已自动写并持续回读 `cosa_ctc_value=-10`，无需应用切换；计算 gear 为 `11 + (-10) = 1`。`-10` 是当前合法下界，不能用 `-11` 强行补到 gear 0。
- 最大输出默认不执行 OPPower 自身的 41/42/43°C 分档。40.0–45.8°C 的规则单测都返回 gear 0；80% 电量暂停、100%/FULL 暂停、20–40 W 与 10–30 W 的 43°C 安全暂停继续保留，自定义规则仍可显式改变目标。
- 2026-10-25 17:46:58–17:48:22 的 27 行只读采样中，`getCurThermalPolicy` 一直为 `null`，不能据此判断系统热控失效；同期电池侧约 2–6 W，之后另一次连续观察涨到约 21.9–23.6 W，仍未稳定达到 30 W+。
- 当前 `dumpsys battery` 同时报告 `Max charging current: 2000000`、约 4.82 V 充电电压和约 1.4 A 电池电流。这个 2 A 字段已读到，但尚未证明存在普通 shell/Shizuku 可持久修改且能改变实际协商的入口；不能把它当成可由 OPPower 强行放宽的保证。
- 下一轮只做单变量只读/短时 A/B：记录 SOC、温度、前台包、浮窗状态、充电器协商电压/电流、`Thermal Levels`、`cosa`、实际功率；优先比较同一条件下系统自然策略与当前 gear 1 的结果，禁止持续 `setChargingLevel`、低频守门、人为升温或关闭原生热保护。
- beta24 发布包：`release/1.0.0-beta24/OPPower-1.0.0-beta24-debug.apk`；SHA-256 `FACE8FD9C6BB5ACBA097CFC7AAD4ED4F9488780836394FAFEB056D328C617DC0`。42 项 JVM 单测、Debug 编译、lint（0 error，20 warning）和构建通过，已覆盖安装到 RMX8899。
## 32. beta25：伪零电流的后备采样（2026-10-25）

- 真机历史库确认 `CURRENT_NOW=0` 会在 AC 在线时连续出现 3–30 秒，虽实际仍充电；LLPower 同条件正常，说明应修复应用采样链，而不是继续改 gear 或重复写充电参数。
- AC 在线、SOC `<99`、状态不是 FULL，且主电流为 0 或电压无效时，页面与控制服务才会调用 `readBatteryFallback()`；它固定执行一次只读 `dumpsys battery`，将 `Battery current` 和 `voltage` 作为同一帧返回。
- 后备值必须非 0、未命中无效哨兵且绝对值不超过 20000 mA；电压有效范围为 3000–5500 mV。后备失败时保留真实主采样，禁止沿用旧帧。接近充满或已 FULL 时不做后备，避免把充满后的正常 0 当成异常。
- 下一轮测试应先接电并保持最大输出，同时打开 OPPower 与 LLPower：记录至少 120 秒，确认 OPPower 不再出现连续 `0 W` 而 LLPower 正常；另在拔电、SOC 98% 以上和有充电器协商切换时确认后备不会产生虚假高功率。
- 本轮没有发送任何充电写入；`setChargingLevel`、`set_charge`、`SmartChgMode`、`customize_charge_switch_state`、守门轮询、人为升温均继续禁用，通知与黄色闪电标不改。
## 33. beta26：后台控制保持与偶发零值纠错（2026-10-25）

- beta25 真机测试出现 OPPower 在后台一段时间后功率下降、切回前台随即恢复。代码复核发现 `ChargeControlService.onDestroy()` 会无条件恢复 `cosa` 和 `smart_charge_switch_state`；系统在后台重建前台服务时，这次生命周期回调会把仍有效的 gear 0 控制撤销。
- beta26 取消“服务销毁即恢复默认”的副作用。系统驱动的服务销毁只停止当前采样循环，不撤销已持久保存的控制意图；`START_STICKY` 重建后重新绑定 Shizuku，并继续依据实时基础档把 `cosa` 算回目标值。总开关关闭、拔电、SOC 暂停、充满和用户执行“一键恢复”仍会走显式恢复路径。
- 新增服务创建、启动、销毁日志，便于 logcat 区分“系统停止/重建服务”和“控制循环仍运行但采样值异常”。日志包含 action 与计算结果，不改变通知、流体云、黄色闪电图标或充电写入范围。
- 功率读取保留 LLPower 的两条只读路径：属性与 sysfs 优先，均无有效值时再执行一次固定 `dumpsys battery`。若 AC 在线且未充满，而所有通道瞬间都返回零，最多复用 3.5 秒最近一帧可靠电流与电压；超过、拔电或充满后立即接受零值。正常非零变化仍按 1 秒刷新，不做防抖或平滑。
- 本轮继续禁用 `setChargingLevel`、`set_charge(0,-2)`、`SmartChgMode`、`customize_charge_switch_state` 和守门轮询；不修改系统热保护，不操作微信。
- 真机验收应在同一充电器、同一 SOC 与温度下保持 OPPower 后台至少 3 分钟，同时用 LLPower 对照功率；若 logcat 出现 `ChargeControlService destroyed; persisted control intent retained`，确认 `created/started` 重建后 `cosa` 自动恢复而无需切前台。

## 34. 2026-10-26：保护开关与热控路径审计（只读）

本轮沿着 ColorOS 17 / RMX8899 随机 ROM 的反编译源码，审计了 `smart_charge_protection_switch_state`、`regular_charge_protection_switch_state`、`low_power_charging_state` 与热控策略写入之间的关系；没有连接手机、没有写入系统设置、没有操作微信。

- `smart_charge_protection_switch_state` 和 `regular_charge_protection_switch_state` 由 `SmartChargeProtectionController` 观察。前者关闭/开启会重置该控制器的智能慢充保护状态、计时、通知和部分本地状态；后者主要切换定时充电保护行为。它们不是 `ThermalPolicy` 的 gear 输入，也没有发现它们直接传入 `ThermalControlUtils.D1(charge, speedChargeAdd, package)`。不应把关闭电池健康保护当作稳定提速参数；本轮未测试它们对实际功率的影响。
- `low_power_charging_state` 被电池设置/低功耗充电控制器、无线充电计划和搜索索引使用；该调用链属于低功耗/无线充电计划状态，未发现它进入插电热控 `D1()` 策略选择或 VOOC/UFCS/PPS 的可写电流 voter。当前不列为有证据的有线充电提速入口。
- 热控对 `D1()` 的实际调用来自当前 `ThermalPolicy.charge` 与 `ThermalPolicy.speedChargeAdd`：普通策略处理、类别变化和充电状态变化都会复用当前 policy 字段后执行 `setSmartCoolDown()`。系统根据前台包、热状态和复杂场景选择 policy；`speedChargeAdd` 在 single-speed 分支关闭时不会成为独立功率倍率。
- ROM 热控 XML 的默认 `common_config` 并没有统一给所有档位固定写出 `charge=0/speedChargeAdd=-2`：gear 2/3 明确给 `speedChargeAdd=2`，其他列出的 gear 多为省略字段并由策略默认补齐。微信浮窗复杂场景 `float_window_scene_101-float_window_scene_102` 则明确配置 gear 0 为 `charge=12,speedChargeAdd=0`、gear 1 为 `charge=9,speedChargeAdd=0`、gear 2–4 为 `charge=6,speedChargeAdd=0`。因此同一计算 gear 会因为系统选中的场景和策略组对应不同 cooldown/附加档；这些 XML 是策略参数，不是直接的瓦数映射，也不能仅据此断言某场景必然较慢。
- `oplus_float_window_show` 的观察器会立刻调用 `m.j2(null,0)` 请求复杂窗口策略重算。设置值本身只是触发状态刷新；窗口集合仍由系统获取并分类，不能用把该值写成 0 来保证微信悬浮窗场景消失。历史上实测写 0 后仍出现 launcher/OPPower 的 `float_window_scene_102`，所以不要靠改这个开关隐藏或关闭微信浮窗。
- 本轮没有发现新的、经源码链路证实可由普通 Shizuku 稳定下发并提升有线功率的参数。beta27 只保留已确认的 `cosa_ctc_value` 目标 gear 控制；`setChargingLevel`、`set_charge(0,-2)`、`SmartChgMode`、`customize_charge_switch_state`、`single_speed_charge_state`、低频守门轮询仍按前文禁用。任何新增候选需先证明有可写路径和明确进入实际 thermal/cooldown 或 charger current 控制，再征求单变量真机测试动作；测试前告知用户并记录原值。

#### 34.1 本轮继续审计：策略偏移与热控优化编号（2026-10-26，只读）

- `vb.i` 的类名虽短，注释和实现名为 `PolicyOffsetConfig`。它解析 `mode_policy_offset` 项，可按启用模式、场景和 tempGear 对 `ThermalPolicy` 的 `charge` 应用 offset、override、max 或 min；因此该项确实可能改变策略计算结果，但它不是直接 charger/HAL 写入接口。
- 配置由 `ub.r.d()` 从 `content://com.oplus.romupdate.provider.db/update_list` 查询，随后由热控配置管理器缓存和解析。当前提取的 `sys_thermal_control_config.xml` 未包含 `level/mode_policy_offset` 项；ROM Provider 的实际数据受 `OPLUS_COMPONENT_SAFE` / `RUS_WRITE_DATABASE` 权限保护，既有 Shizuku/shell 读写探测也被拒绝。向 `sys_thermal_control_list` 写普通 Settings 字符串不能替代 Provider 数据，也不能成为该项的可写入口。故本项列为“可能参与策略、但当前不可由 OPPower/Shizuku 配置或验证”，不列为可实操提速参数。
- `oplus_customize_thermal_control_optimization_number` 在 `ThermalControlUtils.j1()` 中由系统递增并写回；热控控制器只在特定温控状态更新后调用该方法。观察器读到 0 时只把本地计数 `R` 清零。源码没有显示这个编号写入 `ThermalPolicy.charge`、`speedChargeAdd` 或 charger HAL，也没有显示写它本身会请求策略重算。把它写成 0 至多重置计数，不是功率或热控档位控制；不应把它接入 OPPower，也不安排真机功率测试。
- 本轮结论：没有新增可实操、权限可达且进入有线充电限流链路的参数。下一轮若继续源码筛选，优先找系统更新 Provider 以外、具有普通 Shizuku 可写路径且明确进入 `D1()`/充电电流 voter 的输入；禁止用未经授权的 Provider 写入或绕过权限来验证。
#### 34.2 旁路、远程服务与模式输入复核（2026-10-26）

本轮先只读审查本地源码，随后只读检查了已在线的 `192.168.10.7:45803` / RMX8899。没有在手机上写 Settings、发送广播、下发充电级、切换前台应用或操作微信；没有构建、安装 APK。以下候选的“源码路径存在”和“本机写入/功率有效”分别记录，不能互相代替。

**只读设备基线**（手机时间 2026-10-26 03:05–03:07，Asia/Shanghai）：未接电、SOC 65%、电池约 40.1°C、热控温度约 41.956°C、`Thermal Levels=[9]`、`cosa_ctc_value=0`。`gt_mode_state_setting=0`，`high_performance_mode_on=1`，`bypass_charge_state=0`，`smart_charge_switch_state=0`，`speed_charge_state=3`，`single_speed_charge_state=0`；充电保护状态字段均为 0。同次策略回读为 `com.dragon.read_float_window_scene_101 / gearVal=9 / chargeVal=1 / speedChargeAdd=-2`，仅记录未接电时策略。这些是未接电时的读数，不构成功率 A/B；`smart_charge_switch_state=0` 也不单凭当前值判断由谁写入或是否为残留。

`getThermalConfig` 当前返回 `ConfigFile=sys_thermal_control_config.xml`、`ConfigVersion=2026082712`。检查的 `/odm/etc/temperature_profile` 目录没有 `sys_thermal_control_config_gt.xml`；其余已检查的 product/vendor 配置目录不存在。系统实际通过 `OplusCfgFilePolicy` 选最高优先级文件，也可能读取受权限保护的 RUS 数据，因此上述目录检查不能证明所有配置源均无 GT 策略。

| 新复核入口 | 源码中的实际作用 | 可达/写入证据 | 本轮处理 |
| --- | --- | --- | --- |
| `bypass_charge_state` / `bypass_charge_value` | `g9.b` 读写 System Settings；`g9.a` 观察变化，达到电量门槛后经 `t6.g.K(25, "1+switch=1")` 下发旁路，退出用 `switch=0`。系统中文说明为“优先向系统供电，降低设备发热”，不是电池增流倍率 | 已只读确认本机 state=0、value 缺失；本轮未做 Settings 写入或 HAL 生效验证 | 当前关闭，不是正在限制电池充电的已知旁路条件；不作为提速设置接入。开启会改变供电分配，不能把适配器功率与电池侧功率混用 |
| `RemoteClientService` / `RemoteUiClientService` 的 `setChgConfig` 封装 | 两者确实都转入 `t6.g.K(flag, extra)`，最终调用 `OplusBatteryManager.setChgConfig` | APK Manifest 分别要求 `com.oplus.permission.safe.LOG`、`com.oplus.permission.safe.SECURITY`。本地 2026-10-19 完整权限快照中前者为 signature/privileged、后者为 signature，shell UID 2000 两项均无授权 | 没有新增普通 Shizuku 通路；不把 exported=true 当作可调用，不尝试绕过组件权限 |
| `oplus_power_auto_hide_power_state` / `ui_power_adaptive_mode` | `r9.a` 将 `deadzone/reserve` 交给 `setChgConfig(42, …)` 后记录到 Global Settings；`PowerHideDecision.onChargingStarted()` 主动取消电量保留动作 | Settings 记录本身不是 HAL setter；`restoreFromSettings` 是内部恢复流程，没有发现对该记录的实时观察器。直接 flag 42 仍受现有权限边界限制 | 属于电量保留/放电管理链，当前无进入充电增流的证据；不以修改状态镜像做功率测试 |
| `resume_charge_switch_state` | `t6.g.P()` 选择满电后 95% 再恢复充电的行为；系统资源文本明确写出 100% 停充、95% 恢复 | 本机只读为缺失，读取默认 1；本轮未写入 | 不是 65% 电量时 20 W / 30 W 分层的已证实原因，不作为中段功率控制 |
| `gt_mode_state_setting` | `b6.q.h` 的观察器调用 `m.Y1()`，再由 `rb.h.a1()` 切换 GT 热控配置并经 `l2()` 重算；区别于当前为 1 的普通高性能模式。GT 同时改变刷新率/性能状态 | 本机可读、当前 0；旧 2026-10-19 快照为 1。shell 有 WRITE_SETTINGS，但本轮未确认该键写入、GT 专用配置是否存在或功率增益。若 GT 文件缺失、RUS 无可用版本，加载器回落普通 XML | **保留为有条件、未测试的模式候选**。先确认实际策略有差异，才安排单变量接电 A/B；不自动接入，不用提高设备负载/发热代替充电增益 |
| `sys_oplus_high_performance_spec` | `BatteryBenchMarkManager` 观察该字符串，改变 benchmark 标志与 thermal mode；热控 `t1()` 对 benchmark 存在跳过部分框架控制的分支 | 本机缺失；本轮未写入，尚无独立功率证据 | 涉及多项性能/热控行为，不能当成单独充电参数。保持关闭，不用伪装跑分状态追求功率 |
| 一键降温 `one_key_cooling_charge` / 散热背夹 `heat_dissipation_back_clamp_mode` | 一键降温在 `yb.c` 中覆盖 CPU/GPU/亮度/刷新率及 charge（默认 1），`xb.o` 还经 `setChargerCycle("user_disable")` 进入充电约束。背夹键在已审 `CoolingBackClipFeature` 中控制传感器注册与识别，未发现直接 charger 电流 setter | 一键降温参数来自内部配置，广播受 SECURITY 权限保护；本机背夹键为 0。两项均未做写入/功率验证 | 不作为已证实的提速参数；不存在“写一个同名 Settings 参数即可改降温 charge”的证据 |

**权限核验边界**：本机 `cmd package check-permission` 和 `pm check-permission` 都返回 `Unknown command`，不算权限测试成功；远程服务权限结论来自 APK Manifest 和本地已保存的完整 shell shared-UID 权限快照。本轮没有连接受保护服务或调用其写方法。

#### 34.3 `chargeVal=-2` 与 `--` 的语义补充（2026-10-26）

已同时核对 `rb.b`（`HeatSourceController.java`）和对应 `smali/rb/b.smali`：`e(ThermalPolicy)` 对原始 `policy.charge == -2` 返回 `0`，其他值原样返回。`ThermalControllerCenter.A2()`、`B2()`、充电状态更新等调用先经过这个转换，再把结果传入 `ThermalControlUtils.D1()`。因此：

```text
原始 policy.charge=-2 → HeatSourceController.e()=0 → D1(0, speedChargeAdd, pkg)
```

- 原始 `chargeVal=-2` 是“此策略字段未设置”的值，在上述充电调用路径中会归一化成 level 0；不能只凭它为负数就判断充电策略无效。`ThermalPolicy.POLICY_NO_OP` 是另一个值 `-3`，不能混为一谈。
- 这不证明对应 HAL 写入已经完成。`D1()` 仍检查真实充电/亮屏/锁屏等状态；是否产生更高功率须看同一时刻的策略调用与实际功率。
- `speedChargeAdd=-2` 不会与 `chargeVal` 做加法求 gear。普通模式下它不是独立功率倍率，仍沿用现有 D1 结论。
- 当前 OPPower 用正则保留实际的负数诊断值；页面 `chargeVal=--` 只在解析结果为 null 时显示，表示没读到这个字段。`--`、原始 `-2`、显式 `0` 必须分别记录，不能拿 `--` 反推 cooldown 0。
- 此发现仅修正文档中的诊断解释；不扩大 `cosa` 写入范围，不用 `-11` 或以下去制造字段缺失，也不恢复原生充电级调用。

**本轮结果与下次动作**：没有新增“已证实普通 Shizuku 可写且可稳定保持 30 W+”的参数。GT 保留为未测试候选，其余按表记录作用或阻塞原因。下次接电优先保留同一前台/浮窗、同一 gear 和同一 SOC 温度短时条件，先读实际配置与 charge/cooldown，再决定是否值得做一次 GT 单变量 A/B。测试前告知唯一动作、原值和恢复方式；若只增加设备功耗/温度或配置没有独立变化，则不集成。既有禁用项维持不变。
#### 34.4 GT 与降温设置接电 A/B（2026-10-26）

用户指定只测试 `gt_mode_state_setting` 与“一键降温/散热背夹”两组。测试条件为 RMX8899、ColorOS 17、AC 10 V、真实亮屏、系统原生热保护开启；OPPower 使用已有最大输出将计算 gear 保持 0。每组只写一个 System Settings 键，读到回写值后采样 25 秒并恢复原值；脚本在拔电、熄屏或电池温度达到 43.0°C 时停止。三次候选测试的原始值与恢复回读均为 `0`。结束后还恢复了测试开始时关闭的亮屏快充总开关和 `cosa_ctc_value=0`。

| 阶段 | 有效样本 | 功率均值 / 范围 | 电池温度均值 | SOC | 基础档 / 计算 gear | 策略 |
| --- | ---: | --- | ---: | ---: | --- | --- |
| gear 0 基线 | 21 | 23.04 W / 22.86–23.08 | 39.34°C | 65% | 6 / 0 | `float_window_scene_101`，`chargeVal=12`，`speedChargeAdd=0` |
| GT = 1 | 21 | 23.06 W / 22.69–23.14 | 41.14°C | 69–70% | 8 / 0 | 同上 |
| 恢复 GT = 0 | 17 | 22.75 W / 22.69–23.05 | 41.32°C | 70% | 8 / 0 | 同上 |
| 一键降温 = 1 | 21 | 22.82 W / 22.66–23.23 | 41.40°C | 70–71% | 8 / 0 | 同上 |
| 散热背夹状态 = 1 | 21 | 22.56 W / 22.17–22.66 | 41.46°C | 71–72% | 8 / 0 | 同上 |
| 全部恢复 | 13 | 22.17 W / 固定值 | 41.60°C | 72% | 8 / 0 | `cosa=-8`，三项候选均为 `0` |

结论：

- `gt_mode_state_setting 0 → 1 → 0` 写入和回读成功，但没有改变 category、`chargeVal`、`speedChargeAdd` 或计算 gear，也没有提高功率。测试期间温度上升、SOC 发生跳变，不能把绝对功率差归因于 GT；反过来，策略字段完全未变，足以排除“GT 当前可定量改变这组热控充电结果”的假设。
- 一键降温写入和回读成功，但设备 `/sys.data.oplus.onekeycooling.support=false`。测试期间未观察到温度下降、基础档下降或充电策略改变；功率反而略低。当前固件没有可用的一键降温支持，不能作为提速入口。
- `heat_dissipation_back_clamp_mode=1` 只修改系统的“背夹连接状态”镜像。在未接真实背夹时，没有触发策略变化；该次结果不能评价真实物理背夹接入后的散热效果，但可确认“仅写这个设置值不会提高当前充电策略”。
- 所有测试中 `chargeVal` 始终为 12、`speedChargeAdd` 始终为 0。稳定 30 W+ 所需的原生 `chargeVal=0 / speedChargeAdd=-2` 并未由这三项设置触发。
- 本轮只做了 A/B，没有增加应用代码、没有构建 APK。测试结束后恢复：`gt_mode_state_setting=0`、`oplus_one_click_cooling=0`、`heat_dissipation_back_clamp_mode=0`、总开关关闭、`cosa_ctc_value=0`。手机仍接电，最终电池约 41.7°C，Thermal Levels=[8]。

原始逐秒记录位于 `work/live-tests/candidate-ab-20261026-*.json` 与同名 `.log`；本次使用的固定白名单采样脚本为 `work/live-tests/candidate_ab_20261026.py`，只允许上述三个 Settings 键中的单个键 `0 → 1 → 0`，不包含 HAL、广播、原生充电级或微信操作。


## 35. beta27：恢复系统智能充电默认值（2026-11-06）

- 用户要求把 `smart_charge_switch_state` 恢复为系统默认 `1`，不再用约 0.55–0.99 W 的小幅收益换取智能充电策略关闭。
- beta27 从 `ChargingGateway`、控制器、服务持久化状态、Shizuku Gateway 和特权命令白名单中移除了该设置的全部读取、写入与恢复通路。快充控制期间只写 `cosa_ctc_value` 并触发热控重算。
- 旧版本持久化的 `control_original_smart_charge_switch_state` 不再参与控制；升级后已在当前 RMX8899 上将系统值恢复为 `1`，并确认新版本不会再次写回 `0`。
- 这次改动不调整 `cosa` 范围、目标 gear、功率曲线、通知或页面布局，也不恢复任何原生充电级轮询。

## 36. 高功率保活与息屏开关（2026-11-22，独立开关修正于 2026-11-23）

- 主页面“强制亮屏快充”下方新增默认关闭的“高功率保活”，标题下显示“（强制亮屏快充不行再试试这个）”。开启后先恢复 `cosa=0`，再立即下发 `setChargingLevel 0`；后续最快每 3 秒复写一次，不进入普通 gear 温度/电量分档。
- 2026-11-23 起，该开关真正独立于“强制亮屏快充”总开关：只开启高功率保活、关闭总开关时，服务仍运行并保持保活。关闭总开关不会触发安全恢复；只有关闭保活且总开关也未开启、80%/充满、拔电、息屏暂停或一键恢复才先发送 `setChargingLevel 2`，再恢复 `cosa=0`。命令未被系统确认时界面显示错误，不宣称已生效。
- “强制亮屏快充”二级页总开关卡片底部新增默认关闭的“息屏也生效”。关闭时熄屏暂停普通快充与高功率保活；开启后两者可在熄屏状态继续。
- Shizuku 白名单只新增 `setChargingLevel 0`、`setChargingLevel 2` 两条完整固定命令；1、其他档位、分号拼接和未知命令继续拒绝。历史 ColorOS `cooldown=12/15` 与 0 W 跳变风险仍适用，因此该模式保持显式 opt-in，不进入默认路径。
- JVM 单测覆盖首次立即下发、3 秒节流、失败不谎报、总开关关闭时仍启动保活、关闭总开关不停止保活、拔电/80%/充满/熄屏恢复、息屏开关开启继续保活和一键恢复安全级。关闭最后一个充电增强开关时，服务会先执行一次显式安全恢复，再退出。真机功率收益与长时间稳定性仍需在相同充电器、线材、SOC、温度和前台场景下单独 A/B，未完成前不宣称固定瓦数提升。
