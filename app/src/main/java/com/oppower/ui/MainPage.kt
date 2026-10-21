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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.model.ChartPoint
import com.oppower.model.ChargeRecord
import com.oppower.model.ControllerPhase
import com.oppower.model.RuntimeSettings
import com.oppower.model.friendlyLabel
import com.oppower.model.powerTierLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun MainPage(
    state: UiState,
    onOpenChargeSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestShizuku: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onJoinQq: () -> Unit,
) {
    val battery = state.battery
    var recordKind by remember { mutableStateOf<RecordKind?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Background, Surface.copy(alpha = 0.54f))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
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

        PowerHero(state)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric(
                label = "电池电流",
                value = if (state.settings.showCurrentInAmpere) {
                    "${(abs(battery.currentMa) / 1000f).oneDecimal()} A"
                } else {
                    "${battery.currentMa} mA"
                },
                modifier = Modifier.weight(1f),
                onClick = {
                    onSettingsChange { it.copy(showCurrentInAmpere = !it.showCurrentInAmpere) }
                },
            )
            Metric("电池电压", "${(battery.voltageMv / 1000f).oneDecimal()} V", Modifier.weight(1f))
            Metric("电池温度", "${battery.temperatureC.oneDecimal()} °C", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric("系统电量", "${battery.levelPercent}%", Modifier.weight(1f))
            Metric("电池状态", battery.statusLabel, Modifier.weight(1f), battery.isCharging)
            Metric("供电方式", if (battery.acOnline) "电源适配器" else "未接电", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionTile(
                "充电记录",
                if (state.points.any { it.acOnline }) "查看 ›" else "等待接电",
                Modifier.weight(1f),
                onClick = { recordKind = RecordKind.CHARGE },
            )
            ActionTile(
                "放电记录",
                if (state.points.any { !it.acOnline }) "查看 ›" else "等待数据",
                Modifier.weight(1f),
                onClick = { recordKind = RecordKind.DISCHARGE },
            )
            ActionTile("实时通知", if (state.settings.liveNotificationEnabled) "已开启" else "已关闭", Modifier.weight(1f))
        }

        SectionHeader("快捷控制", "接电后自动开始")
        ForceChargeCard(
            state = state,
            onOpenSettings = onOpenChargeSettings,
            onToggle = { enabled ->
                onSettingsChange { it.copy(masterEnabled = enabled) }
            },
        )

        SectionHeader("权限与运行", state.controllerStatus.phase.label)
        if (!state.shizukuAvailable || !state.shizukuPermission) {
            Button(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
                Text(if (!state.shizukuAvailable) "启动权限服务后授权" else "授权系统控制权限")
            }
        }

        if (state.controllerStatus.phase == ControllerPhase.ERROR) {
            ControlIssueCard(
                state = state,
                onRequestShizuku = onRequestShizuku,
                onOpenChargeSettings = onOpenChargeSettings,
            )
        }

        SectionHeader("充放电记录", "保存在本机")
        SessionRecords(
            points = state.points,
            chargeRecords = state.chargeRecords,
            dischargeRecords = state.dischargeRecords,
            onOpenCharge = { recordKind = RecordKind.CHARGE },
            onOpenDischarge = { recordKind = RecordKind.DISCHARGE },
        )
        SectionHeader("实时曲线", "两张图可独立拖动")
        RealtimeCharts(state.points)
        Text(
            "图表用于观察趋势；实际输入功率以外部功率计为准。",
            color = TextSecondary,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(12.dp))
    }

    recordKind?.let { kind ->
        RecordDialog(
            kind = kind,
            points = state.points,
            records = if (kind == RecordKind.CHARGE) state.chargeRecords else state.dischargeRecords,
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
    state: UiState,
    onRequestShizuku: () -> Unit,
    onOpenChargeSettings: () -> Unit,
) {
    val permissionMissing = !state.shizukuAvailable || !state.shizukuPermission
    val waitingForPower = !state.battery.acOnline
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
                    "系统返回：${state.controllerStatus.message}",
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
                        Text(if (!state.shizukuAvailable) "打开 Shizuku" else "授权 OPPower")
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
private fun PowerHero(state: UiState) {
    val shape = RoundedCornerShape(14.dp)
    val battery = state.battery
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(7.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(AccentSurface, SurfaceRaised, Surface)))
            .border(1.dp, if (state.settings.masterEnabled) Cyan.copy(alpha = 0.34f) else Outline, shape),
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
                            if (state.settings.masterEnabled) "快充已启用" else "快充未启用",
                            color = Cyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(state.controllerStatus.phase.label, color = TextSecondary, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 7.dp)) {
                Text(battery.powerW.oneDecimal(), color = TextPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(" W", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 7.dp))
            }
            Text("实时电池功率", color = TextSecondary, fontSize = 10.sp)
            if (state.settings.masterEnabled) {
                Text(
                    "当前档位：${powerTierLabel(state.controllerStatus.desiredGear)}",
                    color = if (state.controllerStatus.phase == ControllerPhase.ACTIVE) Cyan else Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${battery.levelPercent}%", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            .fillMaxWidth((battery.levelPercent / 100f).coerceIn(0f, 1f))
                            .height(5.dp)
                            .background(Cyan, RoundedCornerShape(99.dp)),
                    )
                }
                Text("${battery.temperatureC.oneDecimal()}°C", color = TextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        color = Surface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, Outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Box(Modifier.width(18.dp).height(2.dp).background(Cyan.copy(alpha = 0.72f), RoundedCornerShape(2.dp)))
            Text(label, color = TextSecondary, fontSize = 9.sp)
            Text(
                value,
                color = if (highlight) Cyan else TextPrimary,
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
    value: String,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        color = Surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            Text(label, color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            Text(value, color = TextPrimary, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ForceChargeCard(
    state: UiState,
    onOpenSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(
                if (state.settings.masterEnabled) {
                    Brush.linearGradient(listOf(AccentSurface, Surface))
                } else {
                    Brush.linearGradient(listOf(SurfaceRaised, Surface))
                },
            )
            .border(1.dp, if (state.settings.masterEnabled) Cyan.copy(alpha = 0.34f) else Outline, shape),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("强制亮屏快充", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "基础档：${state.settings.baseTier.friendlyLabel} · ${if (state.settings.customRulesEnabled) "自定义规则" else "默认规则"}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Switch(checked = state.settings.masterEnabled, onCheckedChange = onToggle)
            }
            Text(defaultSummary(state.settings), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            if (state.settings.masterEnabled) {
                Text(
                    "${state.controllerStatus.phase.label} · ${powerTierLabel(state.controllerStatus.desiredGear)}",
                    color = if (state.controllerStatus.phase == ControllerPhase.ACTIVE) Cyan else Amber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    state.controllerStatus.message,
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
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        RecordCard(
            "充电记录",
            points.filter { it.acOnline },
            chargeRecords.lastOrNull(),
            Cyan,
            Modifier.weight(1f),
            onOpenCharge,
        )
        RecordCard(
            "放电记录",
            points.filter { !it.acOnline },
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
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("查看 ›", color = TextSecondary, fontSize = 8.sp)
            }
            val display = lastRecord
            if (display != null) {
                val delta = display.endPercent - display.startPercent
                Text(
                    "${display.startPercent}% → ${display.endPercent}%  ${if (delta >= 0) "+" else ""}$delta%",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${SimpleDateFormat("今天 HH:mm", Locale.getDefault()).format(Date(display.startedAtMillis))} · ${(display.endedAtMillis - display.startedAtMillis).durationText()}",
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

@Composable
private fun RealtimeCharts(points: List<ChartPoint>) {
    val now = System.currentTimeMillis()
    val windowMillis = 2L * 60L * 60L * 1_000L
    var powerEndFraction by remember { mutableFloatStateOf(1f) }
    var temperatureEndFraction by remember { mutableFloatStateOf(1f) }
    val firstMillis = points.firstOrNull()?.epochMillis ?: now
    val lastMillis = points.lastOrNull()?.epochMillis ?: now
    val availableRange = (lastMillis - firstMillis - windowMillis).coerceAtLeast(0L)
    val powerWindowStart = firstMillis + availableRange * powerEndFraction.coerceIn(0f, 1f)
    val powerVisiblePoints = points.filter { it.epochMillis in powerWindowStart.toLong()..(powerWindowStart + windowMillis).toLong() }
    val peakPower = powerVisiblePoints.maxOfOrNull { it.powerW } ?: 5f
    val powerMax = (Math.ceil(peakPower / 10.0).toFloat() * 10f).coerceAtLeast(10f)
    val temperatureWindowStart = firstMillis + availableRange * temperatureEndFraction.coerceIn(0f, 1f)
    val temperatureVisiblePoints = points.filter { it.epochMillis in temperatureWindowStart.toLong()..(temperatureWindowStart + windowMillis).toLong() }
    val temps = temperatureVisiblePoints.map { it.temperatureC }
    val tempMin = (temps.minOrNull() ?: 30f).coerceAtMost(40f)
    val tempMax = max(tempMin + 1f, temps.maxOrNull() ?: 42f)

    Text("功率曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    ChartCard(
        title = "实时功率",
        unit = "W",
        color = Cyan,
        points = powerVisiblePoints,
        windowStart = powerWindowStart.toLong(),
        windowMillis = windowMillis,
        minimumY = 0f,
        maximumY = powerMax,
        value = { it.powerW },
        onDrag = { dragAmount ->
            powerEndFraction = (powerEndFraction - dragAmount).coerceIn(0f, 1f)
        },
    )
    Text("温度曲线", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    ChartCard(
        title = "电池温度",
        unit = "°C",
        color = Amber,
        points = temperatureVisiblePoints,
        windowStart = temperatureWindowStart.toLong(),
        windowMillis = windowMillis,
        minimumY = tempMin,
        maximumY = tempMax,
        value = { it.temperatureC },
        onDrag = { dragAmount ->
            temperatureEndFraction = (temperatureEndFraction - dragAmount).coerceIn(0f, 1f)
        },
    )
    Text(
        "记录最近 24 小时 · 每次显示 2 小时 · 左右拖动分别查看 · 每 15 分钟一个时间节点",
        color = TextSecondary,
        fontSize = 8.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    color: Color,
    points: List<ChartPoint>,
    windowStart: Long,
    windowMillis: Long,
    minimumY: Float,
    maximumY: Float,
    value: (ChartPoint) -> Float,
    onDrag: (Float) -> Unit,
) {
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
                    .height(128.dp)
                    .padding(top = 6.dp)
                    .pointerInput(windowMillis) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            onDrag(dragAmount / size.width)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize().padding(start = 31.dp, end = 32.dp, top = 9.dp, bottom = 16.dp)) {
                    val right = size.width
                    val bottom = size.height
                    val span = (maximumY - minimumY).coerceAtLeast(1f)
                    repeat(5) { index ->
                        val y = bottom * index / 4f
                        drawLine(Outline, Offset(0f, y), Offset(right, y), 1f)
                    }
                    if (points.size >= 2) {
                        val path = Path()
                        points.forEachIndexed { index, point ->
                            val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                            val y = bottom - bottom * ((value(point) - minimumY) / span).coerceIn(0f, 1f)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(2f))
                    }
                }
                Text("${maximumY.oneDecimal()} $unit", color = color, fontSize = 7.sp, modifier = Modifier.align(Alignment.TopStart))
                Text("${minimumY.oneDecimal()} $unit", color = TextSecondary, fontSize = 7.sp, modifier = Modifier.align(Alignment.BottomStart))
                if (points.size < 2) {
                    Text("等待采样…", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            TimelineLabels(windowStart, windowMillis)
        }
    }
}

@Composable
private fun TimelineLabels(windowStart: Long, windowMillis: Long) {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(9) { index ->
            Text(
                formatter.format(Date(windowStart + windowMillis * index / 8L)),
                color = TextSecondary,
                fontSize = 7.sp,
            )
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
