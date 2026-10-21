# OPPower 真机标定与验证记录

记录日期：2026-10-21
设备：realme Neo8 RMX8899，ColorOS 17
条件：真实亮屏、AC 在线、外接功率计；抖音极速版 `com.ss.android.ugc.aweme.lite` 前台；未发送 LV，未冻结 Athena/Battery。

## 档位实测

| 实际 gear | `cosa_ctc_value` | 策略回读 | 外接功率计 | 结论 |
| ---: | ---: | --- | --- | --- |
| 6 | 0 | `gear=6`、`chargeVal=4`、`speedChargeAdd=-2` | 约 8 W | 原生基线 |
| 5 | -5 | `gear=5`、`chargeVal=6`、`speedChargeAdd=-2` | 约 11 W | 有效 |
| 4 | -5 | `gear=4`、`chargeVal=9`、`speedChargeAdd=1` | 约 20 W | 有效 |
| 3 | -6 | `gear=3`、`chargeVal=9`、`speedChargeAdd=1` | 15–20 W | 有效 |
| 2 | -7 | `gear=2`、`chargeVal=12`、`speedChargeAdd=1` | 约 25 W | 有效 |
| 1 | -7 | `getCurThermalPolicy = null` | 首次约 38 W；复测 29–30 W；再次复测 35–40 W | 外部功率计确认高于 gear 2，但策略无法回读 |

gear 0 是全局策略下的系统最高输出档，本次没有作为功率区间测量。gear 1 也回落到全局策略，抖音分组没有显式 gear 1 项。

## 持久偏移

- `cosa_ctc_value=-9` 曾在真实亮屏、无 LV、跨应用条件下维持约 15–30 W。
- 固定负值不是通用解。基础档会随应用、温度和状态变化；负值过深会出现 `gearVal` 或相关字段为 `-2`。
- 产品每次接电和档位切换都从当前 `gearVal` 与当前 `cosa` 计算 `baseGear`，再计算目标偏移。
- 写入后要求 `cosa` 回读一致；策略回读出现空值或非法字段时恢复 `0`。

## 已排除方案

- 单次 LV0：只带来短时提升，切换应用后会回落；循环补发不是持久控制。
- 冻结 Athena/Battery：约十秒后系统因 freezer 冲突终止进程并重启，功率回落。
- 分屏、旁路充电、高性能磁贴、Doze/假亮屏、关闭智能充电：没有满足持久亮屏快充目标。
- 伪造温度、停止原生热服务、工程模式或签名权限绕过：不纳入实现。

## 软件验证

截至 2026-10-21：

- `:app:compileDebugKotlin` 通过。
- `:app:testDebugUnitTest` 通过，覆盖默认温度档、SOC 暂停、100%/FULL、禁止更高温反向升档、均衡档内部 gear、Shizuku 命令白名单和 24 小时曲线记录序列化。
- `:app:lintDebug` 通过，0 error；保留依赖版本、备份规则、SDK 版本检查和 KTX 建议等非阻断 warning。
- 设置多级页面、主题与比例、通知内容/频率、硬件修正、诊断字段、数据管理、悬浮窗与无障碍保活已接入源码。
- 设置主页、亮屏快充详情页和系统页提供 Shizuku 未安装、未运行、未授权、已授权四种状态及对应操作。
- 通知栏“本次关闭”已改为恢复完成后再停止服务；充满/熄屏自动精简不会再移除前台服务必需通知。
- 浏览器预览已验证主页面与详情页总开关同步、Shizuku 入口、外观主题/配色/字体/界面比例即时切换，以及通知栏预览。
- 第一版 beta 已使用 `:app:assembleDebug` 生成 debug key 签名 APK；构建产物不提交 Git。
- 未安装到真机，尚未验证 Shizuku user service、前台服务保活和实际外接功率。

## 后续真机验收

1. 安装 APK，启动 Shizuku 并授权。
2. 接电后检查 `cosa_ctc_value` 回读与前台服务通知。
3. 在抖音、B 站和桌面之间切换，每档至少观察 60 秒。
4. 分别验证 41/42/43°C 默认节点、80% 电量暂停和 100% 关闭单速。
5. 拔电后确认 `cosa=0`、单速为 0；再插电确认重新校准。
6. 强制终止应用、重启设备和 Shizuku 断开场景要单独记录失败边界。
