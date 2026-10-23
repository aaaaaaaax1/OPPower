# OPPower 真机操作书

本文件记录 RMX8899 / ColorOS 17 上已做过的测试、结论、恢复方法和下一轮操作顺序。每次真机实验前先读本文件，实验后立即补充日期、原值、动作、稳定读数、结论和恢复结果。

## 1. 安全边界与测试原则

- 仅在真实亮屏、AC 接电、原生热保护保持运行时测试。
- 一次只改变一个变量。单项写入后至少等待一个稳定刷新周期，再记录一次读数；不在温度或热控阶段明显变化时下结论。
- 每次对照同时记录 SOC、电池温度、`Thermal Levels[0]`、`cosa_ctc_value`、计算 gear、原生充电级、电池侧电压和电流。
- 电池侧功率按 `|Battery current| × Battery voltage` 计算。它只用于同一台手机短时 A/B，最终功率以外部功率计为准。
- “基础档自然升高”和“SOC 阶段变化”属于被动变化，不能算作可稳定控制的参数增益。
- 测试写入前保存原值；未产生稳定正收益就恢复。不得把临时测试值留在设备上。
- 不修改 `single_speed_charge_state`。它已验证会干扰回读，且实际收益不如 `cosa`。
- `chargeVal` 是策略对象的诊断字段，不是应用可直接写入的控制参数。
- beta11 起 OPPower 不读取、不写入、不恢复 `customize_charge_switch_state`。

## 2. 当前控制模型

```text
计算 gear = Thermal Levels[0] + cosa_ctc_value
目标 cosa = 目标 gear - Thermal Levels[0]
```

OPPower 每秒重新计算目标。基础档或外部 `cosa` 变化时，只写一次新的 `cosa` 并触发一次热控重算，不先经过 `cosa=0`。

目标 gear 同时映射为原生充电级并通过 `set_charge` 下发：

| 目标 gear | 原生充电级 |
| ---: | ---: |
| 0–1 | 0 |
| 2–4 | 7 |
| 5 | 5 |
| 6 | 3 |
| 7 及以上 | 2 |

暂停、关闭总开关、拔电、SOC 暂停或充满时，原生充电级恢复到安全级 `2`，`cosa` 恢复为 `0`。

有效控制会话还会通过 GuardElf 事务 20 设置 `SmartChgMode=0`。应用会保存进入会话前的 bitfield，并在暂停、关闭、拔电或充满时恢复。该写入每会话按需执行，稳定期间不重复写。

## 3. 已验证有效的主动入口

| 入口 | 已验证结果 | 使用结论 |
| --- | --- | --- |
| `cosa_ctc_value` | 能按基础档计算目标 gear；外值漂移后下一周期可纠正；切换目标不经过 0 | 核心控制路径，稳定保留 |
| 原生 `set_charge` level | `getCurThermalPolicy=null` 时仍能把目标 gear 对应的原生 level 下发；beta9 真机显示原生 level 0 | 核心控制路径，稳定保留 |
| `SmartChgMode=0` + 当时的 `customize=1` | 2026-10-23 15:59，81%、约 39.5°C、约 14.2W 时，用户实测提高约 1–3W，实际增益可能更高 | 有组合正收益；beta11 已移除 `customize`，独立 A/B 待接电复测 |
| `setChargingLevel 0` / `set_charge(0,0)` | 2026-10-23 16:03，84%、约 38.0°C、约 16.3W 时，用户实测有效提升 | 作为 `D1()` 下游覆盖路径保留；与原生 level 0 有重叠，不重复叠加 |

## 4. 已排除或未确认的入口

| 入口 | 测试/发现 | 结论 |
| --- | --- | --- |
| `customize_charge_switch_state=1` | 早期自然爬升对照曾被 `cosa` 写入混入；独立复测约 35.66–35.83W → 34.53–35.83W，没有独立增益 | beta11 已移除，不再读写 |
| `single_speed_charge_state=1` | 会影响 gear 回读，收益不如 `cosa` | 排除 |
| `speed_charge_state=3` | 系统原生快充基础状态，当前已经是 3 | 不算额外增益 |
| 场景广播 / `send_pkg` | 未测到稳定独立增益 | 暂停重复测试 |
| `CustomSelectChgMode` 0/1/2 | 未测到稳定独立增益 | 暂停重复测试 |
| `setChargingLevel 12` | 未测到稳定独立增益 | 暂停重复测试 |
| 长充保护瓦数模拟 | 可影响慢充 AI 门控，但不直接修改充电电流，不会写 `chargeVal` | 低优先级，仅在确认慢充保护实际进入时测试 |
| `setPsySlowChgEnable` | shell/Shizuku 身份返回 `READ_COMMON` 权限拒绝 | 当前应用路径不可用 |
| `QuickModeGain` | GuardElf 事务 27 可读接口存在，但读取返回 `READ_COMMON` 权限拒绝 | 不能作为应用可读反馈，也不构成写入入口 |
| Charger HAL 直连 | `setChgConfig`、`setChargerCoolDown`、慢充配置等接口存在；shell 直连返回权限拒绝或 DeadObject | 当前不可用，不能凭接口名盲测 |
| `speedChargeAdd` | `D1()` 只在 single-speed 条件成立时把它加到原生 level；负值可能生成无效 level，正值只改变 level 编号 | 它不是独立升功率倍率；不作为稳定增益 |
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
- 原生充电级的实际下发由 OPPower 单独显示，不能再用 `chargeVal` 是否存在来判断控制是否成功。

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
2. 记录基线：`Thermal Levels[0]`、`cosa`、计算 gear、原生充电级、电压、电流、电池侧功率。
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
OPPower 原生充电级 = 安全级 2（或关闭控制后由应用恢复）
```

`customize_charge_switch_state` 已不属于 OPPower 管理范围；只有用户或系统原先主动设置时才保留原值。

## 10. 下一轮优先级

1. 在 beta11 下单独比较 `SmartChgMode=0` 与原值 1，确认移除 `customize=1` 后是否仍有稳定增益。
2. 检查 `vendor.oplus.hardware.charger.ICharger` 的 V12 接口，只读取语义明确的门控、状态和限流字段，确认是否存在可审计且可恢复的主动控制入口。
3. 检查电池服务中的 `Max charging current` 与 OplusBatteryService 命令，区分 Android 观测值、壳温保护和真实硬件限流，禁止直接把展示字段当成硬件 setter。
4. 只有发现系统代执行或明确可恢复的接口后，再评估慢充保护与充电配置入口。
5. 不复测已经排除的场景广播、CustomSelectChgMode 0/1/2、setChargingLevel 12，也不把 SOC 或基础档自然爬升当作新产品功能。

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
