package com.oppower.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.BuildConfig
import com.oppower.model.RuntimeSettings
import com.oppower.model.ThemeMode
import com.oppower.model.ThemePalette
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsScreen {
    HOME,
    APPEARANCE,
    NOTIFICATIONS,
    STABLE_OPERATION,
    ADVANCED_KEEP_ALIVE,
    HARDWARE,
    SYSTEM,
    DATA,
    FAQ,
    ABOUT,
    IMPORTANT_NOTICE,
    DEVELOPER_NOTICE,
    LICENSES,
}

@Composable
internal fun SettingsRouter(
    screen: SettingsScreen,
    state: UiState,
    onNavigate: (SettingsScreen) -> Unit,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onJoinQq: () -> Unit,
    onClearHistory: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    when (screen) {
        SettingsScreen.HOME -> SettingsHomePage(
            state = state,
            onBack = onBack,
            onNavigate = onNavigate,
            onJoinQq = onJoinQq,
            onRequestShizuku = onRequestShizuku,
        )
        SettingsScreen.APPEARANCE -> AppearanceSettingsPage(state, onBack, onSettingsChange)
        SettingsScreen.NOTIFICATIONS -> NotificationSettingsPage(state, onBack, onSettingsChange)
        SettingsScreen.STABLE_OPERATION -> StableOperationPage(state, onBack, onNavigate)
        SettingsScreen.ADVANCED_KEEP_ALIVE -> AdvancedKeepAlivePage(state, onBack, onSettingsChange)
        SettingsScreen.HARDWARE -> HardwareSettingsPage(state, onBack, onSettingsChange)
        SettingsScreen.SYSTEM -> SystemSettingsPage(state, onBack, onSettingsChange, onRequestShizuku)
        SettingsScreen.DATA -> DataManagementPage(state, onBack, onSettingsChange, onClearHistory)
        SettingsScreen.FAQ -> FaqPage(onBack)
        SettingsScreen.ABOUT -> AboutPage(onBack, onNavigate, onJoinQq)
        SettingsScreen.IMPORTANT_NOTICE -> NoticePage(
            title = "重要说明",
            onBack = onBack,
            paragraphs = listOf(
                "OPPower 的亮屏快充增强依赖系统控制权限。权限不可用时，应用会停止写入并恢复默认偏移。",
                "应用不会修改温度数据，也不会关闭系统原生温控。温度、电量和充满保护仍由本地规则执行。",
                "实际输入功率受充电器、线材、亮屏耗电、系统热策略和电池状态影响，界面功率来自系统电池数据。",
            ),
        )
        SettingsScreen.DEVELOPER_NOTICE -> NoticePage(
            title = "开发者说明",
            onBack = onBack,
            paragraphs = listOf(
                "OPPower 基于 LLPower 二改得来，并新增 ColorOS 亮屏快充控制。界面、数据模型和系统控制逻辑已独立实现。",
                "本项目仅适用于已经验证过的 ColorOS 策略。不同机型、固件和系统权限状态可能无法写入或回读。",
                "反馈问题时请提供机型、系统版本、当前策略状态和可复现步骤。不要在群内公开设备私密信息。",
            ),
        )
        SettingsScreen.LICENSES -> LicensesPage(onBack)
    }
}

@Composable
private fun SettingsHomePage(
    state: UiState,
    onBack: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit,
    onJoinQq: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val stableReady = notificationsEnabled(context) && batteryUnrestricted(context)
    SettingsPage(
        title = "设置",
        subtitle = "OPPower · 外观、通知、系统与数据",
        onBack = onBack,
    ) {
        SettingsSection("常用") {
            SettingsMenuRow(
                icon = "S",
                title = "Shizuku 授权",
                summary = shizukuStatus(state),
                accent = !state.shizukuPermission,
                onClick = onRequestShizuku,
            )
            SettingsDivider()
            SettingsMenuRow(
                icon = "◐",
                title = "外观设置",
                summary = "${state.settings.themeMode.label} · ${state.settings.themePalette.label}",
                onClick = { onNavigate(SettingsScreen.APPEARANCE) },
            )
            SettingsDivider()
            SettingsMenuRow(
                icon = "◎",
                title = "稳定运行",
                summary = if (stableReady) "后台运行权限已就绪" else "建议完成运行向导",
                accent = !stableReady,
                onClick = { onNavigate(SettingsScreen.STABLE_OPERATION) },
            )
            SettingsDivider()
            SettingsMenuRow(
                icon = "◒",
                title = "通知设置",
                summary = if (state.settings.liveNotificationEnabled) {
                    "状态通知已开启 · ${state.settings.notificationRefreshSeconds} 秒刷新"
                } else {
                    "状态通知已关闭"
                },
                onClick = { onNavigate(SettingsScreen.NOTIFICATIONS) },
            )
        }

        SettingsSection("设备与数据") {
            SettingsMenuRow(
                icon = "⌁",
                title = "硬件修正",
                summary = hardwareSummary(state.settings),
                onClick = { onNavigate(SettingsScreen.HARDWARE) },
            )
            SettingsDivider()
            SettingsMenuRow(
                icon = "◫",
                title = "系统与诊断",
                summary = if (state.settings.debugMode) "诊断模式已开启" else "后台权限、应用详情与调试",
                onClick = { onNavigate(SettingsScreen.SYSTEM) },
            )
            SettingsDivider()
            SettingsMenuRow(
                icon = "▦",
                title = "数据管理",
                summary = "24 小时曲线 · 充放电记录",
                onClick = { onNavigate(SettingsScreen.DATA) },
            )
        }

        SettingsSection("帮助与关于") {
            SettingsMenuRow(
                icon = "?",
                title = "常见问题",
                summary = "功率、后台运行与读数说明",
                onClick = { onNavigate(SettingsScreen.FAQ) },
            )
            SettingsDivider()
            SettingsMenuRow(
                icon = "i",
                title = "关于 OPPower",
                summary = "版本 ${BuildConfig.VERSION_NAME} · 更新与许可",
                onClick = { onNavigate(SettingsScreen.ABOUT) },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onJoinQq),
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Outline),
            shadowElevation = 2.dp,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = SurfaceRaised, shape = RoundedCornerShape(8.dp)) {
                    Text("Q", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(9.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                    Text("加入 OPPower 用户群", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("群号 $QQ_GROUP_NUMBER · 点击复制并打开 QQ", color = TextSecondary, fontSize = 10.sp)
                }
                Text("›", color = TextSecondary, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val settings = state.settings
    SettingsPage("外观设置", "主题、密度与界面显示", onBack) {
        SettingsSection("主题模式") {
            ChoiceGrid(
                options = enumValues<ThemeMode>().toList(),
                selected = settings.themeMode,
                label = { it.label },
                onSelect = { value -> onSettingsChange { it.copy(themeMode = value) } },
            )
        }
        SettingsSection("主题配色") {
            ChoiceGrid(
                options = enumValues<ThemePalette>().toList(),
                selected = settings.themePalette,
                label = { it.label },
                onSelect = { value -> onSettingsChange { it.copy(themePalette = value) } },
                columns = 2,
            )
        }
        SettingsSection("文字与界面比例") {
            SettingLabel("字体大小")
            ChoiceGrid(
                options = listOf(0.9f, 1f, 1.1f),
                selected = settings.fontScale,
                label = { when (it) { 0.9f -> "较小"; 1.1f -> "较大"; else -> "标准" } },
                onSelect = { value -> onSettingsChange { it.copy(fontScale = value) } },
            )
            SettingsDivider(Modifier.padding(vertical = 10.dp))
            SettingLabel("界面比例")
            ChoiceGrid(
                options = listOf(0.9f, 1f, 1.1f),
                selected = settings.uiScale,
                label = { when (it) { 0.9f -> "紧凑"; 1.1f -> "宽松"; else -> "标准" } },
                onSelect = { value -> onSettingsChange { it.copy(uiScale = value) } },
            )
        }
        SettingsSection("卡片与后台") {
            Text("卡片不透明度", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "${(settings.cardOpacity * 100).roundToInt()}%",
                color = Cyan,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Slider(
                value = settings.cardOpacity,
                onValueChange = { value -> onSettingsChange { it.copy(cardOpacity = value) } },
                valueRange = 0.65f..1f,
                steps = 6,
            )
            SettingsDivider()
            SettingSwitchRow(
                title = "隐藏后台任务",
                description = "从最近任务和预览中隐藏，不影响常驻快充服务",
                checked = settings.hideFromRecents,
                onCheckedChange = { value -> onSettingsChange { it.copy(hideFromRecents = value) } },
            )
        }
    }
}

@Composable
private fun NotificationSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    SettingsPage("通知设置", "常驻状态通知与刷新频率", onBack) {
        SettingsSection("通知") {
            SettingSwitchRow(
                title = "状态通知",
                description = "显示快充状态、功率和电池数据；关闭后保留精简后台通知",
                checked = settings.liveNotificationEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(liveNotificationEnabled = value) } },
            )
            SettingsDivider()
            SettingActionRow(
                title = "系统通知权限",
                value = if (notificationsEnabled(context)) "已允许" else "前往设置",
                onClick = { openNotificationSettings(context) },
            )
        }

        SettingsSection("通知内容") {
            SettingSwitchRow("显示电量百分比", "", settings.notificationShowBatteryPercent) { value ->
                onSettingsChange { it.copy(notificationShowBatteryPercent = value) }
            }
            SettingsDivider()
            SettingSwitchRow("显示电流", "单位 mA", settings.notificationShowCurrent) { value ->
                onSettingsChange { it.copy(notificationShowCurrent = value) }
            }
            SettingsDivider()
            SettingSwitchRow("显示电压", "单位 V", settings.notificationShowVoltage) { value ->
                onSettingsChange { it.copy(notificationShowVoltage = value) }
            }
            SettingsDivider()
            SettingSwitchRow("显示温度", "", settings.notificationShowTemperature) { value ->
                onSettingsChange { it.copy(notificationShowTemperature = value) }
            }
            SettingsDivider()
            SettingSwitchRow("显示供电方式", "", settings.notificationShowSupply) { value ->
                onSettingsChange { it.copy(notificationShowSupply = value) }
            }
            SettingsDivider()
            SettingSwitchRow("功率只显示整数", "小于 1 W 时仍保留小数", settings.notificationIntegerPower) { value ->
                onSettingsChange { it.copy(notificationIntegerPower = value) }
            }
        }

        SettingsSection("刷新与自动精简") {
            SettingLabel("刷新间隔")
            ChoiceGrid(
                options = listOf(2, 5, 10, 30),
                selected = settings.notificationRefreshSeconds,
                label = { "$it 秒" },
                onSelect = { value -> onSettingsChange { it.copy(notificationRefreshSeconds = value) } },
                columns = 4,
            )
            SettingLabel("熄屏刷新间隔")
            ChoiceGrid(
                options = listOf(3, 30, 60, 300),
                selected = settings.notificationScreenOffRefreshSeconds,
                label = { if (it < 60) "$it 秒" else "${it / 60} 分钟" },
                onSelect = { value -> onSettingsChange { it.copy(notificationScreenOffRefreshSeconds = value) } },
                columns = 4,
            )
            SettingsDivider(Modifier.padding(vertical = 10.dp))
            SettingSwitchRow(
                title = "充满后精简状态通知",
                description = "电量 100% 时恢复系统充电策略，并保留一条精简后台通知",
                checked = settings.notificationAutoCloseOnFull,
                onCheckedChange = { value -> onSettingsChange { it.copy(notificationAutoCloseOnFull = value) } },
            )
            SettingsDivider()
            SettingSwitchRow(
                title = "熄屏后精简状态通知",
                description = "熄屏时只保留必要后台通知，亮屏后恢复详细数据",
                checked = settings.notificationAutoCloseOnScreenOff,
                onCheckedChange = { value -> onSettingsChange { it.copy(notificationAutoCloseOnScreenOff = value) } },
            )
        }
    }
}

@Composable
private fun StableOperationPage(
    state: UiState,
    onBack: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit,
) {
    val context = LocalContext.current
    val notificationOk = notificationsEnabled(context)
    val batteryOk = batteryUnrestricted(context)
    val overlayOk = Settings.canDrawOverlays(context)
    val accessibilityOk = accessibilityKeepAliveEnabled(context)
    SettingsPage("稳定运行", "保证亮屏快充与记录服务持续工作", onBack) {
        SettingsSection("运行检查") {
            StatusRow("通知权限", if (notificationOk) "已就绪" else "需要授权", notificationOk)
            SettingsDivider()
            StatusRow("电池优化", if (batteryOk) "不受限制" else "可能限制后台", batteryOk)
            SettingsDivider()
            StatusRow("悬浮窗保活", if (overlayOk) "权限已授予" else "未授权", overlayOk)
            SettingsDivider()
            StatusRow("无障碍保活", if (accessibilityOk) "已启用" else "未启用", accessibilityOk)
        }
        SettingsSection("权限入口") {
            SettingActionRow("通知设置", if (notificationOk) "已允许" else "前往设置") {
                openNotificationSettings(context)
            }
            SettingsDivider()
            SettingActionRow("电池优化", if (batteryOk) "不受限制" else "前往设置") {
                openBatteryOptimizationSettings(context)
            }
            SettingsDivider()
            SettingActionRow("应用详情", "耗电与自启动管理") { openAppDetails(context) }
            SettingsDivider()
            SettingActionRow("高级保活", "悬浮窗与无障碍") { onNavigate(SettingsScreen.ADVANCED_KEEP_ALIVE) }
        }
        SettingsSection("重要说明") {
            Text(
                "ColorOS 可能在温度较高或长时间亮屏时限制后台服务。建议将 OPPower 加入电池优化白名单，并在多任务界面锁定。" +
                    "特殊情况下再启用高级保活，日常使用无需同时开启全部选项。",
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(vertical = 11.dp),
            )
            SettingsDivider()
            SettingActionRow("查看完整说明", "重要说明") { onNavigate(SettingsScreen.IMPORTANT_NOTICE) }
        }
        SettingsSection("当前控制服务") {
            StatusRow(
                "控制状态",
                state.controllerStatus.phase.label,
                state.controllerStatus.phase == com.oppower.model.ControllerPhase.ACTIVE,
            )
            Text(
                state.controllerStatus.message,
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 11.dp),
            )
        }
    }
}

@Composable
private fun AdvancedKeepAlivePage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val context = LocalContext.current
    val overlayGranted = Settings.canDrawOverlays(context)
    val accessibilityEnabled = accessibilityKeepAliveEnabled(context)
    SettingsPage("高级保活", "非特殊情况无需开启", onBack) {
        SettingsSection("悬浮窗保活") {
            SettingSwitchRow(
                title = "挂载 1×1 透明悬浮窗",
                description = "开启后持续帮助控制服务恢复；需要悬浮窗权限",
                checked = state.settings.overlayKeepAliveEnabled && overlayGranted,
                onCheckedChange = { enabled ->
                    if (enabled && !overlayGranted) {
                        onSettingsChange { it.copy(overlayKeepAliveEnabled = true) }
                        openOverlayPermission(context)
                    } else {
                        onSettingsChange { it.copy(overlayKeepAliveEnabled = enabled) }
                    }
                },
            )
            SettingsDivider()
            SettingActionRow(
                "悬浮窗权限",
                if (overlayGranted) "已授权" else "前往授权",
                onClick = { openOverlayPermission(context) },
            )
            Text(
                "透明悬浮窗尺寸仅为 1×1 像素，不显示内容，也不会拦截触摸。",
                color = TextSecondary,
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 11.dp),
            )
        }
        SettingsSection("无障碍保活") {
            StatusRow("服务状态", if (accessibilityEnabled) "已启用" else "未启用", accessibilityEnabled)
            SettingsDivider()
            SettingActionRow("打开无障碍设置", "OPPower 保活服务") {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Text(
                "无障碍服务只用于维持后台监控，不读取屏幕内容、不执行自动点击，也不保存任何辅助功能数据。",
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun HardwareSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val settings = state.settings
    SettingsPage("硬件修正", "兼容不同设备的电池读数", onBack) {
        SettingsSection("电流与功率") {
            SettingSwitchRow(
                title = "反转电流正负",
                description = "如果充电时电流显示为负，请开启此项",
                checked = settings.invertCurrentSign,
                onCheckedChange = { value -> onSettingsChange { it.copy(invertCurrentSign = value) } },
            )
            SettingsDivider()
            SettingSwitchRow(
                title = "电流和功率显示绝对值",
                description = "只改变界面显示，不影响曲线原始方向",
                checked = settings.absoluteCurrentPowerDisplay,
                onCheckedChange = { value -> onSettingsChange { it.copy(absoluteCurrentPowerDisplay = value) } },
            )
        }
        SettingsSection("电池结构") {
            SettingSwitchRow(
                title = "双芯电池修正",
                description = "双电芯机型开启后，电流与功率按两芯合并显示",
                checked = settings.doubleCellCorrection,
                onCheckedChange = { value -> onSettingsChange { it.copy(doubleCellCorrection = value) } },
            )
            SettingsDivider()
            SettingSwitchRow(
                title = "虚拟电压",
                description = "设备无法读取电压时，使用 3.85 V 估算值",
                checked = settings.virtualVoltage,
                onCheckedChange = { value -> onSettingsChange { it.copy(virtualVoltage = value) } },
            )
        }
        SettingsSection("当前读数") {
            StatusRow("电流", "${state.battery.currentMa} mA", true)
            SettingsDivider()
            StatusRow("电压", "${(state.battery.voltageMv / 1000f).oneDecimal()} V", true)
            SettingsDivider()
            StatusRow("电池功率", "${state.battery.powerW.oneDecimal()} W", true)
            Text(
                "当前机型 RMX8899 为单电芯，默认无需开启双芯修正。若读数方向不正确，先尝试“反转电流正负”。",
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun SystemSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val batteryOk = batteryUnrestricted(context)
    SettingsPage("系统与诊断", "后台权限与数据来源", onBack) {
        SettingsSection("后台运行") {
            SettingActionRow(
                title = "电池优化",
                value = if (batteryOk) "不受限制" else "前往设置",
                onClick = { openBatteryOptimizationSettings(context) },
            )
            SettingsDivider()
            SettingActionRow("应用详情", "耗电管理、自启动与权限") { openAppDetails(context) }
            SettingsDivider()
            SettingActionRow("Shizuku 授权", shizukuStatus(state), onClick = onRequestShizuku)
            Text(
                "OPPower 只通过 Shizuku 读取和调整已确认的 ColorOS 充电项目；授权失败时会停止写入并恢复系统默认。",
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 9.dp),
            )
        }
        SettingsSection("诊断") {
            SettingSwitchRow(
                title = "调试模式",
                description = "显示运行状态、充电阶段与权限状态",
                checked = state.settings.debugMode,
                onCheckedChange = { value -> onSettingsChange { it.copy(debugMode = value) } },
            )
            if (state.settings.debugMode) {
                SettingsDivider(Modifier.padding(vertical = 8.dp))
                DebugRow("当前状态", state.controllerStatus.phase.label)
                DebugRow("功率档", state.controllerStatus.desiredGear?.let(::friendlyPowerGear) ?: "等待启用")
                DebugRow("充电状态", if (state.battery.isCharging) "正在充电" else "未在充电")
                DebugRow("电源接入", if (state.battery.acOnline) "是" else "否")
                DebugRow("外部控制权限", if (state.shizukuPermission) "已授权" else "未授权")
            }
        }
        SettingsSection("应用") {
            StatusRow("版本", BuildConfig.VERSION_NAME, true)
            SettingsDivider()
            StatusRow("设备", "RMX8899 · ColorOS 17", true)
            SettingsDivider()
            StatusRow("网络权限", "未申请，数据不上传", true)
        }
    }
}

@Composable
private fun DataManagementPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onClearHistory: () -> Unit,
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val storageBytes = remember(state.points) {
        context.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    SettingsPage("数据管理", "曲线、充放电记录与本地存储", onBack) {
        SettingsSection("存储占用") {
            StatusRow("本地数据", formatBytes(storageBytes), true)
            SettingsDivider()
            StatusRow("曲线采样点", "${state.points.size} 个", true)
            SettingsDivider()
            StatusRow("充电记录", "${state.chargeRecords.size} 条", true)
            SettingsDivider()
            StatusRow("放电记录", "${state.dischargeRecords.size} 条", true)
            Text(
                "实时曲线最多保存 24 小时，每 30 秒最多记录一个采样点。所有数据只保存在本机。",
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 11.dp),
            )
        }
        SettingsSection("历史曲线") {
            SettingSwitchRow(
                title = "记录实时曲线",
                description = "关闭后停止新增采样，并移除现有曲线数据",
                checked = state.settings.historyEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(historyEnabled = value) } },
            )
            SettingsDivider()
            SettingActionRow(
                title = "清除全部本地记录",
                value = if (state.points.isEmpty() && state.chargeRecords.isEmpty()) "暂无记录" else "清除",
                enabled = state.points.isNotEmpty() || state.chargeRecords.isNotEmpty(),
                onClick = { confirmClear = true },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部记录？") },
            text = { Text("24 小时功率与温度曲线、充放电摘要会被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    confirmClear = false
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

private data class FaqEntry(val question: String, val answer: String)

@Composable
private fun FaqPage(onBack: () -> Unit) {
    var expanded by remember { mutableIntStateOf(0) }
    val entries = listOf(
        FaqEntry("为什么手机显示功率和功率计不一致？", "界面显示的是系统提供的电池侧数据，功率计显示的是充电器输入端功率。链路损耗和手机自身耗电都会造成差异。"),
        FaqEntry("为什么亮屏快充有时只维持十几瓦？", "系统会结合温度、电量、前台应用和充电策略动态限流。OPPower 能调整已验证的策略档位，但不会关闭原生温控。"),
        FaqEntry("拔掉充电器后会恢复默认吗？", "会。控制服务检测到拔电后会恢复偏移。重新接电时再按当前规则开始。"),
        FaqEntry("为什么设置已经开启却没有生效？", "请先确认系统控制权限、电池优化白名单和常驻服务状态。控制策略无法回读时，应用会主动恢复默认。"),
        FaqEntry("充满后为什么会暂停增强？", "100% 后继续提高充电功率没有意义。应用会恢复偏移并暂停增强，重新拔插后才开始新会话。"),
        FaqEntry("隐藏后台任务会影响快充吗？", "不会。它只把主界面从最近任务中移除，常驻控制服务仍会运行。"),
        FaqEntry("曲线为什么每 15 分钟才标一个时间节点？", "横轴按 15 分钟显示时间标签，实际数据仍按约 30 秒采样，可以左右滑动查看 24 小时记录。"),
    )
    SettingsPage("常见问题", "功率、读数与后台运行", onBack) {
        entries.forEachIndexed { index, entry ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == index) -1 else index },
                color = if (expanded == index) AccentSurface else Surface,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, if (expanded == index) Cyan.copy(alpha = 0.55f) else Outline),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Q${index + 1}", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            entry.question,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
                        )
                        Text(if (expanded == index) "−" else "+", color = TextSecondary, fontSize = 16.sp)
                    }
                    if (expanded == index) {
                        Text(
                            entry.answer,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutPage(
    onBack: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit,
    onJoinQq: () -> Unit,
) {
    SettingsPage("关于 OPPower", "版本 ${BuildConfig.VERSION_NAME}", onBack) {
        Surface(
            color = AccentSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.42f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("OPPower", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("ColorOS 亮屏快充控制与电池监控", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                Text("基于 LLPower 二改得来", color = Amber, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        SettingsSection("项目") {
            StatusRow("版本", BuildConfig.VERSION_NAME, true)
            SettingsDivider()
            StatusRow("适用设备", "RMX8899 / ColorOS 17", true)
            SettingsDivider()
            StatusRow("网络与上传", "无网络权限，数据仅存本机", true)
        }
        SettingsSection("说明与许可") {
            SettingActionRow("重要说明", "使用边界与风险") { onNavigate(SettingsScreen.IMPORTANT_NOTICE) }
            SettingsDivider()
            SettingActionRow("开发者说明", "来源与反馈方式") { onNavigate(SettingsScreen.DEVELOPER_NOTICE) }
            SettingsDivider()
            SettingActionRow("开源许可", "第三方组件与许可证") { onNavigate(SettingsScreen.LICENSES) }
        }
        SettingsSection("反馈") {
            SettingActionRow("OPPower 用户群", QQ_GROUP_NUMBER, onClick = onJoinQq)
            Text(
                "点击后会复制群号并尝试打开 QQ 群邀请链接。",
                color = TextSecondary,
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 11.dp),
            )
        }
    }
}

@Composable
private fun NoticePage(title: String, onBack: () -> Unit, paragraphs: List<String>) {
    SettingsPage(title, "OPPower", onBack) {
        SettingsSection("说明") {
            paragraphs.forEachIndexed { index, paragraph ->
                Text(
                    paragraph,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(vertical = 9.dp),
                )
                if (index != paragraphs.lastIndex) SettingsDivider()
            }
        }
    }
}

@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val licenses = listOf(
        "AndroidX / Jetpack Compose" to "Apache License 2.0",
        "Kotlin Coroutines" to "Apache License 2.0",
        "Shizuku API" to "Apache License 2.0",
        "Material Components" to "Apache License 2.0",
    )
    SettingsPage("开源许可", "本项目使用的第三方组件", onBack) {
        SettingsSection("第三方组件") {
            licenses.forEachIndexed { index, (name, license) ->
                StatusRow(name, license, true)
                if (index != licenses.lastIndex) SettingsDivider()
            }
            Text(
                "各组件版权与完整许可证归原作者所有。源码仓库会保留对应依赖声明和许可信息。",
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun SettingsPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Background, Surface.copy(alpha = 0.48f))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = SurfaceRaised,
                shape = CircleShape,
                border = BorderStroke(1.dp, Outline),
                modifier = Modifier.clickable(onClick = onBack),
            ) {
                Text("‹", color = TextPrimary, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 2.dp))
            }
            Column(Modifier.padding(start = 11.dp)) {
                Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp))
        Surface(
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Outline),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 2.dp), content = content)
        }
    }
}

@Composable
private fun SettingsMenuRow(
    icon: String,
    title: String,
    summary: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).background(if (accent) AmberSurface else SurfaceRaised, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = if (accent) Amber else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(summary, color = if (accent) Amber else TextSecondary, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", color = TextSecondary, fontSize = 19.sp)
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (description.isNotBlank()) {
                Text(description, color = TextSecondary, fontSize = 9.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (enabled) TextPrimary else TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (enabled) Cyan else TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(" ›", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun StatusRow(title: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (good) Cyan else Amber, CircleShape))
        Text(title, color = TextPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 9.dp))
        Text(value, color = if (good) Cyan else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = TextSecondary, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 9.sp)
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp, bottom = 2.dp))
}

@Composable
private fun <T> ChoiceGrid(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    columns: Int = 3,
) {
    Column(Modifier.padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.chunked(columns).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowOptions.forEach { option ->
                    val isSelected = option == selected
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onSelect(option) },
                        color = if (isSelected) AccentSurface else SurfaceRaised,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (isSelected) Cyan else Outline),
                    ) {
                        Text(
                            label(option),
                            color = if (isSelected) Cyan else TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 9.dp),
                        )
                    }
                }
                repeat(columns - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = Outline)
}

private fun notificationsEnabled(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun accessibilityKeepAliveEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    val expected = "${context.packageName}/${context.packageName}.service.KeepAliveAccessibilityService"
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.id.equals(expected, ignoreCase = true) }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    })
}

private fun openBatteryOptimizationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    })
}

private fun openOverlayPermission(context: Context) {
    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    })
}

private fun hardwareSummary(settings: RuntimeSettings): String {
    val enabled = listOf(
        settings.invertCurrentSign,
        settings.doubleCellCorrection,
        settings.virtualVoltage,
        settings.absoluteCurrentPowerDisplay,
    ).count { it }
    return if (enabled == 0) "电池读数修正" else "已启用 $enabled 项读数修正"
}

private fun shizukuStatus(state: UiState): String = when {
    !state.shizukuInstalled -> "未安装 · 打开下载页"
    !state.shizukuAvailable -> "未运行 · 打开 Shizuku"
    !state.shizukuPermission -> "未授权 · 授权给 OPPower"
    else -> "已授权 OPPower · 服务已连接"
}

private fun friendlyPowerGear(gear: Int): String = when (gear) {
    0 -> "最高能力"
    1, 2 -> "快速充电 · 高功率"
    3, 4 -> "快速充电 · 均衡"
    else -> "温和充电"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024f)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1_024f * 1_024f))
}
