package com.oppower.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.history.ChartHistoryStore
import com.oppower.history.CurveAxis
import com.oppower.history.CurveProcessing
import com.oppower.history.CurveSegment
import com.oppower.history.CurveSnapshot
import com.oppower.model.BatterySnapshot
import com.oppower.model.ChartPoint
import com.oppower.model.ChargeRecord
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.RuntimeSettings
import com.oppower.model.friendlyLabel
import com.oppower.model.powerTierLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun MainPage(
    state: State<UiState>,
    onOpenChargeSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestShizuku: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onJoinQq: () -> Unit,
) {
    // Keep the scroll container independent from the one-second telemetry
    // updates. Each visible row observes only the slices it actually renders.
    val batteryState = remember(state) { derivedStateOf { state.value.battery } }
    val settingsState = remember(state) { derivedStateOf { state.value.settings } }
    val controllerStatusState = remember(state) { derivedStateOf { state.value.controllerStatus } }
    val pointsState = remember(state) { derivedStateOf { state.value.points } }
    val chargeRecordsState = remember(state) { derivedStateOf { state.value.chargeRecords } }
    val dischargeRecordsState = remember(state) { derivedStateOf { state.value.dischargeRecords } }
    val shizukuAccessState = remember(state) {
        derivedStateOf { state.value.shizukuAvailable to state.value.shizukuPermission }
    }
    val historyAvailabilityState = remember(pointsState) {
        derivedStateOf {
            val points = pointsState.value
            points.any { it.acOnline } to points.any { !it.acOnline }
        }
    }
    val pageBackground = remember {
        Brush.verticalGradient(listOf(Background, Surface.copy(alpha = 0.54f)))
    }
    var recordKind by remember { mutableStateOf<RecordKind?>(null) }
    Box(Modifier.fillMaxSize().background(pageBackground)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OPPower", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 23.sp)
                    Text("RMX8899 · ColorOS 17", color = TextSecondary, fontSize = 10.sp)
                }
                Text(
                    text = "加入 QQ 群",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onJoinQq)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceRaised,
                    border = BorderStroke(1.dp, Outline),
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                ) {
                    Text(
                        "⚙",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                }
            }
        }

        item(key = "power") {
            val settings by settingsState
            val controllerStatus by controllerStatusState
            PowerHero(batteryState, settings, controllerStatus)
        }

        item(key = "metrics") {
            val currentValue = remember(batteryState, settingsState) {
                derivedStateOf {
                    val battery = batteryState.value
                    if (settingsState.value.showCurrentInAmpere) {
                        "${(abs(battery.currentMa) / 1000f).oneDecimal()} A"
                    } else {
                        "${battery.currentMa} mA"
                    }
                }
            }
            val voltageValue = remember(batteryState) {
                derivedStateOf { "${(batteryState.value.voltageMv / 1000f).oneDecimal()} V" }
            }
            val temperatureValue = remember(batteryState) {
                derivedStateOf { "${batteryState.value.temperatureC.oneDecimal()} °C" }
            }
            val levelValue = remember(batteryState) {
                derivedStateOf { "${batteryState.value.levelPercent}%" }
            }
            val statusValue = remember(batteryState) {
                derivedStateOf { batteryState.value.statusLabel }
            }
            val chargingState = remember(batteryState) {
                derivedStateOf { batteryState.value.isCharging }
            }
            val supplyValue = remember(batteryState) {
                derivedStateOf { if (batteryState.value.acOnline) "电源适配器" else "未接电" }
            }
            val chargeActionValue = remember(historyAvailabilityState) {
                derivedStateOf { if (historyAvailabilityState.value.first) "查看 ›" else "等待接电" }
            }
            val dischargeActionValue = remember(historyAvailabilityState) {
                derivedStateOf { if (historyAvailabilityState.value.second) "查看 ›" else "等待数据" }
            }
            val notificationValue = remember(settingsState) {
                derivedStateOf { if (settingsState.value.liveNotificationEnabled) "已开启" else "已关闭" }
            }
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Metric(
                        label = "电池电流",
                        value = currentValue,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSettingsChange { it.copy(showCurrentInAmpere = !it.showCurrentInAmpere) }
                        },
                    )
                    Metric("电池电压", voltageValue, Modifier.weight(1f))
                    Metric("电池温度", temperatureValue, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Metric("系统电量", levelValue, Modifier.weight(1f))
                    Metric("电池状态", statusValue, Modifier.weight(1f), highlight = chargingState)
                    Metric("供电方式", supplyValue, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionTile(
                        "充电记录",
                        chargeActionValue,
                        Modifier.weight(1f),
                        onClick = { recordKind = RecordKind.CHARGE },
                    )
                    ActionTile(
                        "放电记录",
                        dischargeActionValue,
                        Modifier.weight(1f),
                        onClick = { recordKind = RecordKind.DISCHARGE },
                    )
                    ActionTile(
                        "实时通知",
                        notificationValue,
                        Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "quick-control") {
            val settings by settingsState
            val controllerStatus by controllerStatusState
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeader("快捷控制", "接电后自动开始")
                ForceChargeCard(
                    settings = settings,
                    controllerStatus = controllerStatus,
                    onOpenSettings = onOpenChargeSettings,
                    onToggle = { enabled ->
                        onSettingsChange { it.copy(masterEnabled = enabled) }
                    },
                )
            }
        }

        item(key = "permissions") {
            val shizukuAccess by shizukuAccessState
            val shizukuAvailable = shizukuAccess.first
            val shizukuPermission = shizukuAccess.second
            val controllerStatus by controllerStatusState
            val battery by batteryState
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeader("权限与运行", controllerStatus.phase.label)
                if (!shizukuAvailable || !shizukuPermission) {
                    Button(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
                        Text(if (!shizukuAvailable) "启动权限服务后授权" else "授权系统控制权限")
                    }
                }
                if (controllerStatus.phase == ControllerPhase.ERROR) {
                    ControlIssueCard(
                        battery = battery,
                        shizukuAvailable = shizukuAvailable,
                        shizukuPermission = shizukuPermission,
                        controllerStatus = controllerStatus,
                        onRequestShizuku = onRequestShizuku,
                        onOpenChargeSettings = onOpenChargeSettings,
                    )
                }
            }
        }

        item(key = "records") {
            val points by pointsState
            val chargeRecords by chargeRecordsState
            val dischargeRecords by dischargeRecordsState
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeader("充放电记录", "保存在本机")
                SessionRecords(
                    points = points,
                    chargeRecords = chargeRecords,
                    dischargeRecords = dischargeRecords,
                    onOpenCharge = { recordKind = RecordKind.CHARGE },
                    onOpenDischarge = { recordKind = RecordKind.DISCHARGE },
                )
            }
        }

        item(key = "charts-header", contentType = "section-header") {
            SectionHeader("实时曲线", "两张图可独立拖动")
        }

        item(key = "power-chart", contentType = "chart") {
            val points by pointsState
            val recordInterval = remember(batteryState) {
                derivedStateOf {
                    ChartHistoryStore.sampleIntervalMillis(
                        isCharging = batteryState.value.isCharging,
                        screenOn = true,
                    )
                }
            }
            PowerRealtimeChart(
                points = points,
                recordIntervalMs = recordInterval,
            )
        }

        item(key = "temperature-chart", contentType = "chart") {
            val points by pointsState
            TemperatureRealtimeChart(points = points)
        }

        item(key = "charts-footer", contentType = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "记录最近 8 小时 · 每次显示 2 小时 · 曲线按 LLPower 节奏采样 · 时间节点 15 分钟",
                    color = TextSecondary,
                    fontSize = 8.sp,
                )
                Text(
                    "图表用于观察趋势；实际输入功率以外部功率计为准。",
                    color = TextSecondary,
                    fontSize = 9.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
            }
        }
    }

    recordKind?.let { kind ->
        val points by pointsState
        val chargeRecords by chargeRecordsState
        val dischargeRecords by dischargeRecordsState
        RecordDialog(
            kind = kind,
            points = points,
            records = if (kind == RecordKind.CHARGE) chargeRecords else dischargeRecords,
            onDismiss = { recordKind = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(15.dp).background(Cyan.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
        Text(
            title,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(start = 7.dp),
        )
        Text(trailing, color = TextSecondary, fontSize = 9.sp)
    }
}

@Composable
private fun ControlIssueCard(
    battery: BatterySnapshot,
    shizukuAvailable: Boolean,
    shizukuPermission: Boolean,
    controllerStatus: ControllerStatus,
    onRequestShizuku: () -> Unit,
    onOpenChargeSettings: () -> Unit,
) {
    val permissionMissing = !shizukuAvailable || !shizukuPermission
    val waitingForPower = !battery.acOnline
    val title = when {
        permissionMissing -> "需要完成 Shizuku 授权"
        waitingForPower -> "等待接入电源"
        else -> "系统暂时没有确认这次功率设置"
    }
    val detail = when {
        permissionMissing -> "OPPower 需要 Shizuku 权限才能读取和调整系统充电设置。完成授权后会自动重新检测。"
        waitingForPower -> "接上充电器和外接功率计，保持真实亮屏，应用会自动重新校准并开始控制。"
        else -> "已安全恢复系统默认。可能是系统仍在刷新充电状态，或当前前台应用切换了功率档。重新检测会重新读取当前状态；连续失败时请进入详细设置选择较温和的档位。"
    }
    Surface(
        color = AmberSurface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.46f)),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(title, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = TextPrimary, fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp))
            if (!permissionMissing && !waitingForPower) {
                Text(
                    "系统返回：${controllerStatus.message}",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (permissionMissing) {
                    Button(onClick = onRequestShizuku, modifier = Modifier.weight(1f)) {
                        Text(if (!shizukuAvailable) "打开 Shizuku" else "授权 OPPower")
                    }
                }
                TextButton(onClick = onOpenChargeSettings, modifier = Modifier.weight(1f)) {
                    Text(if (permissionMissing) "查看处理步骤" else "查看详细设置")
                }
            }
        }
    }
}

@Composable
private fun PowerHero(
    batteryState: State<BatterySnapshot>,
    settings: RuntimeSettings,
    controllerStatus: ControllerStatus,
) {
    val shape = RoundedCornerShape(14.dp)
    val powerValue = remember(batteryState) {
        derivedStateOf { batteryState.value.powerW.oneDecimal() }
    }
    val levelValue = remember(batteryState) {
        derivedStateOf { "${batteryState.value.levelPercent}%" }
    }
    val levelFraction = remember(batteryState) {
        derivedStateOf { (batteryState.value.levelPercent / 100f).coerceIn(0f, 1f) }
    }
    val temperatureValue = remember(batteryState) {
        derivedStateOf { "${batteryState.value.temperatureC.oneDecimal()}°C" }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(AccentSurface, SurfaceRaised, Surface)))
            .border(1.dp, if (settings.masterEnabled) Cyan.copy(alpha = 0.34f) else Outline, shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Cyan.copy(alpha = 0.055f),
                radius = size.width * 0.42f,
                center = Offset(size.width * 0.94f, size.height * 0.04f),
            )
            drawCircle(
                color = Cyan.copy(alpha = 0.022f),
                radius = size.width * 0.31f,
                center = Offset(size.width * 0.94f, size.height * 0.04f),
            )
        }
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AccentSurface, shape = RoundedCornerShape(99.dp)) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(Cyan, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (settings.masterEnabled) "快充已启用" else "快充未启用",
                            color = Cyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(controllerStatus.phase.label, color = TextSecondary, fontSize = 10.sp)
            }
            HeroPowerValue(powerValue)
            Text("实时电池功率", color = TextSecondary, fontSize = 10.sp)
            if (settings.masterEnabled) {
                Text(
                    "当前档位：${powerTierLabel(controllerStatus.desiredGear)}",
                    color = if (controllerStatus.phase == ControllerPhase.ACTIVE) Cyan else Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            HeroBatteryFooter(levelValue, levelFraction, temperatureValue)
        }
    }
}

@Composable
private fun HeroPowerValue(powerValue: State<String>) {
    val power by powerValue
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 7.dp)) {
        Text(power, color = TextPrimary, fontSize = 42.sp, fontWeight = FontWeight.Black)
        Text(" W", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 7.dp))
    }
}

@Composable
private fun HeroBatteryFooter(
    levelValue: State<String>,
    levelFraction: State<Float>,
    temperatureValue: State<String>,
) {
    val level by levelValue
    val fraction by levelFraction
    val temperature by temperatureValue
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(level, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Outline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .background(Cyan, RoundedCornerShape(99.dp)),
            )
        }
        Text(temperature, color = TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun Metric(
    label: String,
    value: State<String>,
    modifier: Modifier = Modifier,
    highlight: State<Boolean>? = null,
    onClick: (() -> Unit)? = null,
) {
    val displayValue by value
    val highlighted = highlight?.value == true
    Surface(
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        color = Surface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Box(Modifier.width(18.dp).height(2.dp).background(Cyan.copy(alpha = 0.72f), RoundedCornerShape(2.dp)))
            Text(label, color = TextSecondary, fontSize = 9.sp)
            Text(
                displayValue,
                color = if (highlighted) Cyan else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    value: State<String>,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
) {
    val displayValue by value
    Surface(
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        color = Surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            Text(displayValue, color = TextPrimary, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ForceChargeCard(
    settings: RuntimeSettings,
    controllerStatus: ControllerStatus,
    onOpenSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (settings.masterEnabled) {
                    Brush.linearGradient(listOf(AccentSurface, Surface))
                } else {
                    Brush.linearGradient(listOf(SurfaceRaised, Surface))
                },
            )
            .border(1.dp, if (settings.masterEnabled) Cyan.copy(alpha = 0.34f) else Outline, shape),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("强制亮屏快充", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "基础档：${settings.baseTier.friendlyLabel} · ${if (settings.customRulesEnabled) "自定义规则" else "默认规则"}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Switch(checked = settings.masterEnabled, onCheckedChange = onToggle)
            }
            Text(defaultSummary(settings), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            if (settings.masterEnabled) {
                Text(
                    "${controllerStatus.phase.label} · ${powerTierLabel(controllerStatus.desiredGear)}",
                    color = if (controllerStatus.phase == ControllerPhase.ACTIVE) Cyan else Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    controllerStatus.message,
                    color = TextSecondary,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("进入详细设置", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("›", color = Cyan, fontSize = 17.sp)
            }
        }
    }
}

private fun defaultSummary(settings: RuntimeSettings): String {
    return if (settings.customRulesEnabled) {
        "自定义：${settings.customRules.temperatureRules.size} 个温度条件 · ${settings.customRules.socRules.size} 个电量条件"
    } else when (settings.baseTier) {
        com.oppower.model.BaseTier.MAX_OUTPUT -> "41°C→20–40W · 42°C→10–30W · 43°C/80%暂停 · 充满暂停"
        else -> "低于 43°C 随温度调整 · 43°C/80%暂停 · 充满暂停"
    }
}

@Composable
private fun SessionRecords(
    points: List<ChartPoint>,
    chargeRecords: List<ChargeRecord>,
    dischargeRecords: List<ChargeRecord>,
    onOpenCharge: () -> Unit,
    onOpenDischarge: () -> Unit,
) {
    val chargePoints = remember(points, chargeRecords.lastOrNull()) {
        if (chargeRecords.isEmpty()) points.filter { it.acOnline } else emptyList()
    }
    val dischargePoints = remember(points, dischargeRecords.lastOrNull()) {
        if (dischargeRecords.isEmpty()) points.filter { !it.acOnline } else emptyList()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        RecordCard(
            "充电记录",
            chargePoints,
            chargeRecords.lastOrNull(),
            Cyan,
            Modifier.weight(1f),
            onOpenCharge,
        )
        RecordCard(
            "放电记录",
            dischargePoints,
            dischargeRecords.lastOrNull(),
            Amber,
            Modifier.weight(1f),
            onOpenDischarge,
        )
    }
}

@Composable
private fun RecordCard(
    title: String,
    points: List<ChartPoint>,
    lastRecord: ChargeRecord?,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Surface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("查看 ›", color = TextSecondary, fontSize = 8.sp)
            }
            val display = lastRecord
            if (display != null) {
                val formatter = remember { SimpleDateFormat("今天 HH:mm", Locale.getDefault()) }
                val delta = display.endPercent - display.startPercent
                Text(
                    "${display.startPercent}% → ${display.endPercent}%  ${if (delta >= 0) "+" else ""}$delta%",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${formatter.format(Date(display.startedAtMillis))} · ${(display.endedAtMillis - display.startedAtMillis).durationText()}",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    "峰值 ${display.peakPowerW.oneDecimal()} W · 平均 ${display.averagePowerW.oneDecimal()} W · 最高 ${display.maxTemperatureC.oneDecimal()}°C",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (points.isEmpty()) {
                Text("本次打开后暂无记录", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                val first = points.first()
                val last = points.last()
                Text(
                    "${first.levelPercent}% → ${last.levelPercent}%",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${(last.epochMillis - first.epochMillis).durationText()} · 峰值 ${points.maxOf { it.powerW }.oneDecimal()} W",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    "最高 ${points.maxOf { it.temperatureC }.oneDecimal()}°C",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private enum class RecordKind(val title: String) {
    CHARGE("充电记录"),
    DISCHARGE("放电记录"),
}

@Composable
private fun RecordDialog(
    kind: RecordKind,
    points: List<ChartPoint>,
    records: List<ChargeRecord>,
    onDismiss: () -> Unit,
) {
    val relevantPoints = points.filter { if (kind == RecordKind.CHARGE) it.acOnline else !it.acOnline }
    val fallback = remember(records, relevantPoints) {
        records.lastOrNull() ?: relevantPoints.takeIf { it.size >= 2 }?.let { group ->
            ChargeRecord(
                startedAtMillis = group.first().epochMillis,
                endedAtMillis = group.last().epochMillis,
                startPercent = group.first().levelPercent,
                endPercent = group.last().levelPercent,
                peakPowerW = group.maxOf { it.powerW },
                averagePowerW = group.map { it.powerW }.average().toFloat(),
                maxTemperatureC = group.maxOf { it.temperatureC },
                startVoltageV = group.first().voltageMv / 1000f,
                endVoltageV = group.last().voltageMv / 1000f,
                peakCurrentMa = group.maxOf { abs(it.currentMa) },
                averageCurrentMa = group.map { abs(it.currentMa) }.average().toInt(),
                chargeDeltaMah = null,
                endReason = if (kind == RecordKind.CHARGE) "当前充电中" else "当前放电中",
                points = group,
            )
        }
    }
    var selected by remember(records, kind) { mutableStateOf(fallback) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected == null) {
                    Text("暂无记录。完成一次充电或放电后，这里会显示完整统计、结束原因和历史详情。", color = TextSecondary)
                }
                selected?.let { record ->
                    Surface(
                        color = AccentSurface,
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.42f)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                if (kind == RecordKind.CHARGE) "最近一次充电" else "最近一次放电",
                                color = if (kind == RecordKind.CHARGE) Cyan else Amber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            val delta = record.endPercent - record.startPercent
                            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${record.startPercent}% → ${record.endPercent}%",
                                    color = TextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${if (delta >= 0) "+" else ""}$delta%",
                                    color = if (delta >= 0) Cyan else Amber,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            RecordDetailRows(record)
                        }
                    }
                }
                if (records.isNotEmpty()) {
                    Text("历史记录", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                }
                records.asReversed().forEach { record ->
                    Surface(
                        modifier = Modifier.clickable { selected = record },
                        color = if (selected == record) AccentSurface else SurfaceRaised,
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, if (selected == record) Cyan else Outline),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                "${timeFormat.format(Date(record.startedAtMillis))} · ${(record.endedAtMillis - record.startedAtMillis).durationText()}",
                                color = TextSecondary,
                                fontSize = 9.sp,
                            )
                            Text(
                                "${record.startPercent}% → ${record.endPercent}%",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                "${record.peakPowerW.oneDecimal()} W 峰值 · ${record.maxTemperatureC.oneDecimal()}°C 最高 · ${record.endReason}",
                                color = TextSecondary,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun RecordDetailRows(record: ChargeRecord) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val durationMinutes = ((record.endedAtMillis - record.startedAtMillis).coerceAtLeast(0L) / 60_000.0)
    val deltaPercent = record.endPercent - record.startPercent
    val ratePerHour = if (durationMinutes > 0) deltaPercent / (durationMinutes / 60.0) else 0.0
    val averageTemp = record.points.map { it.temperatureC }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
    val rows = listOf(
        "充电时长" to (record.endedAtMillis - record.startedAtMillis).durationText(),
        "平均每小时充电" to "${String.format(Locale.getDefault(), "%.1f", ratePerHour)}%/h",
        "开始时间" to formatter.format(Date(record.startedAtMillis)),
        "结束时间" to formatter.format(Date(record.endedAtMillis)),
        "峰值功率" to "${record.peakPowerW.oneDecimal()} W",
        "平均功率" to "${record.averagePowerW.oneDecimal()} W",
        "峰值电流" to "${record.peakCurrentMa} mA",
        "平均电流" to "${record.averageCurrentMa} mA",
        "最高温度" to "${record.maxTemperatureC.oneDecimal()} °C",
        "平均温度" to "${averageTemp.oneDecimal()} °C",
        "电荷量变化" to (record.chargeDeltaMah?.let { "+$it mAh" } ?: "--"),
        "结束原因" to record.endReason,
    )
    Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = TextSecondary, fontSize = 8.sp)
                        Text(value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class RealtimeChartData(
    val power: CurveSnapshot,
    val temperature: CurveSnapshot,
)

@Composable
private fun PowerRealtimeChart(
    points: List<ChartPoint>,
    recordIntervalMs: State<Long>,
) {
    val interval by recordIntervalMs
    val snapshot by produceState<CurveSnapshot?>(
        initialValue = null,
        points,
        interval,
    ) {
        value = withContext(Dispatchers.Default) {
            val end = points.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
            CurveProcessing.processPower(
                points = points,
                windowStart = end - CurveProcessing.HISTORY_WINDOW_MS,
                windowEnd = end,
                recordIntervalMs = interval,
            )
        }
    }

    val data = snapshot
    if (data == null) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("功率曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            ChartLoadingCard("实时功率")
        }
        return
    }

    var endFraction by remember { mutableFloatStateOf(1f) }
    val windowMillis = 2L * 60L * 60L * 1_000L
    val timelineNow = remember(points) { System.currentTimeMillis() }
    val historyEnd = points.lastOrNull()?.epochMillis ?: timelineNow
    val timelineEnd = maxOf(timelineNow, historyEnd)
    val availableStart = maxOf(
        historyEnd - CurveProcessing.HISTORY_WINDOW_MS,
        timelineEnd - CurveProcessing.HISTORY_WINDOW_MS,
    )
    val availableRange = (timelineEnd - availableStart - windowMillis).coerceAtLeast(0L)
    val windowStart = availableStart + (availableRange * endFraction.coerceIn(0f, 1f)).toLong()
    val windowEnd = windowStart + windowMillis
    val segments = remember(data, windowStart, windowEnd) {
        CurveProcessing.clip(data, windowStart, windowEnd)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("功率曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ChartCard(
            title = "实时功率",
            unit = "W",
            color = Cyan,
            segments = segments,
            windowStart = windowStart,
            windowMillis = windowMillis,
            axis = data.axis,
            onDrag = { dragAmount -> endFraction = (endFraction - dragAmount).coerceIn(0f, 1f) },
        )
    }
}

@Composable
private fun TemperatureRealtimeChart(points: List<ChartPoint>) {
    val snapshot by produceState<CurveSnapshot?>(
        initialValue = null,
        points,
    ) {
        value = withContext(Dispatchers.Default) {
            val end = points.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
            CurveProcessing.processTemperature(
                points = points,
                windowStart = end - CurveProcessing.HISTORY_WINDOW_MS,
                windowEnd = end,
            )
        }
    }

    val data = snapshot
    if (data == null) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("温度曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            ChartLoadingCard("电池温度")
        }
        return
    }

    var endFraction by remember { mutableFloatStateOf(1f) }
    val windowMillis = 2L * 60L * 60L * 1_000L
    val timelineNow = remember(points) { System.currentTimeMillis() }
    val historyEnd = points.lastOrNull()?.epochMillis ?: timelineNow
    val timelineEnd = maxOf(timelineNow, historyEnd)
    val availableStart = maxOf(
        historyEnd - CurveProcessing.HISTORY_WINDOW_MS,
        timelineEnd - CurveProcessing.HISTORY_WINDOW_MS,
    )
    val availableRange = (timelineEnd - availableStart - windowMillis).coerceAtLeast(0L)
    val windowStart = availableStart + (availableRange * endFraction.coerceIn(0f, 1f)).toLong()
    val windowEnd = windowStart + windowMillis
    val segments = remember(data, windowStart, windowEnd) {
        CurveProcessing.clip(data, windowStart, windowEnd)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("温度曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        ChartCard(
            title = "电池温度",
            unit = "°C",
            color = Amber,
            segments = segments,
            windowStart = windowStart,
            windowMillis = windowMillis,
            axis = data.axis,
            onDrag = { dragAmount -> endFraction = (endFraction - dragAmount).coerceIn(0f, 1f) },
        )
    }
}

@Composable
private fun ChartLoadingCard(title: String) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("正在准备曲线", color = TextSecondary, fontSize = 8.sp)
            }
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                Text("正在处理最近 8 小时数据…", color = TextSecondary, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    color: Color,
    segments: List<CurveSegment>,
    windowStart: Long,
    windowMillis: Long,
    axis: CurveAxis,
    onDrag: (Float) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val currentOnDrag by rememberUpdatedState(onDrag)
    val tickCount = axis.tickCount
    val span = (axis.maximum - axis.minimum).coerceAtLeast(0.001f)
    val hasData = segments.any { it.points.isNotEmpty() }
    val axisLabels = remember(axis, unit) {
        val count = axis.tickCount
        val range = (axis.maximum - axis.minimum).coerceAtLeast(0.001f)
        List(count) { index ->
            val fraction = 1f - index / (count - 1).coerceAtLeast(1).toFloat()
            val value = axis.minimum + range * fraction
            if (unit == "W") {
                value.roundToInt().toString()
            } else {
                String.format(Locale.getDefault(), "%.1f", value)
            }
        }
    }
    Surface(
        color = Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("2 小时视图 · 可拖动", color = TextSecondary, fontSize = 8.sp)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(top = 6.dp)
                    .pointerInput(windowMillis) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            currentOnDrag(dragAmount / size.width.coerceAtLeast(1))
                        }
                    },
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 35.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)
                        .drawWithCache {
                            val right = size.width
                            val bottom = size.height
                            val axisStyle = TextStyle(color = TextSecondary, fontSize = 7.sp)
                            val measuredLabels = axisLabels.map { label ->
                                textMeasurer.measure(text = label, style = axisStyle)
                            }
                            val strokes: List<Pair<Path?, Offset?>> = segments.mapNotNull { segment ->
                                val points = segment.points
                                if (points.isEmpty()) return@mapNotNull null
                                if (points.size == 1) {
                                    val point = points.first()
                                    val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                                    val y = bottom - bottom * ((point.value - axis.minimum) / span).coerceIn(0f, 1f)
                                    return@mapNotNull Pair(null, Offset(x, y))
                                }
                                val path = Path()
                                points.forEachIndexed { index, point ->
                                    val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                                    val y = bottom - bottom * ((point.value - axis.minimum) / span).coerceIn(0f, 1f)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                Pair(path, null)
                            }
                            onDrawBehind {
                                val divisor = (tickCount - 1).coerceAtLeast(1).toFloat()
                                repeat(tickCount) { index ->
                                    val y = bottom * index / divisor
                                    drawLine(Outline, Offset(0f, y), Offset(right, y), 1f)
                                }
                                measuredLabels.forEachIndexed { index, measured ->
                                    val y = (bottom * index / divisor - measured.size.height / 2f)
                                        .coerceIn(0f, (bottom - measured.size.height).coerceAtLeast(0f))
                                    drawText(measured, topLeft = Offset(-measured.size.width - 5f, y))
                                }
                                strokes.forEach { (path, point) ->
                                    if (path != null) {
                                        drawPath(path, color, style = Stroke(3f))
                                    } else if (point != null) {
                                        drawCircle(color, radius = 3f, center = point)
                                    }
                                }
                            }
                        },
                    onDraw = {},
                )
                if (!hasData) {
                    Text("等待采样…", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            TimelineLabels(windowStart, windowMillis)
        }
    }
}

@Composable
private fun TimelineLabels(windowStart: Long, windowMillis: Long) {
    val labels = remember(windowStart, windowMillis) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        List(9) { index ->
            formatter.format(Date(windowStart + windowMillis * index / 8L))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { label ->
            Text(label, color = TextSecondary, fontSize = 7.sp)
        }
    }
}
private val com.oppower.model.BatterySnapshot.statusLabel: String
    get() = when (status) {
        2 -> "充电中"
        3 -> "放电中"
        4 -> "未充电"
        5 -> "已充满"
        else -> "未知"
    }

internal val ControllerPhase.label: String
    get() = when (this) {
        ControllerPhase.DISABLED -> "未启用"
        ControllerPhase.WAITING_FOR_POWER -> "等待接电"
        ControllerPhase.ACTIVE -> "控制生效"
        ControllerPhase.TEMPERATURE_PAUSED -> "温度暂停"
        ControllerPhase.SOC_PAUSED -> "电量暂停"
        ControllerPhase.FULL_PAUSED -> "已充满"
        ControllerPhase.ERROR -> "需要处理"
    }
