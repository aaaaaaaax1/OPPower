package com.oppower.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.model.ChartPoint
import com.oppower.model.ControllerPhase
import com.oppower.model.RuntimeSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
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
                color = Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onJoinQq)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceRaised,
                border = BorderStroke(1.dp, Color(0xFF34484E)),
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
            Metric("电池电流", "${battery.currentMa} mA", Modifier.weight(1f))
            Metric("电池电压", "${(battery.voltageMv / 1000f).oneDecimal()} V", Modifier.weight(1f))
            Metric("电池温度", "${battery.temperatureC.oneDecimal()} °C", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Metric("系统电量", "${battery.levelPercent}%", Modifier.weight(1f))
            Metric("电池状态", battery.statusLabel, Modifier.weight(1f), battery.isCharging)
            Metric("供电方式", if (battery.acOnline) "电源适配器" else "未接电", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionTile("充电记录", if (state.points.any { it.acOnline }) "已记录" else "等待接电", Modifier.weight(1f))
            ActionTile("放电记录", if (state.points.any { !it.acOnline }) "已记录" else "等待数据", Modifier.weight(1f))
            ActionTile("实时通知", if (state.settings.liveNotificationEnabled) "已开启" else "已关闭", Modifier.weight(1f))
        }

        Text("快捷控制", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        ForceChargeCard(
            state = state,
            onOpenSettings = onOpenChargeSettings,
            onToggle = { enabled ->
                onSettingsChange { it.copy(masterEnabled = enabled) }
            },
        )
        if (!state.shizukuAvailable || !state.shizukuPermission) {
            Button(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
                Text(if (!state.shizukuAvailable) "启动 Shizuku 后授权" else "授权 Shizuku")
            }
        }

        Text("充放电记录", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        SessionRecords(state.points, state.chargeRecords, state.dischargeRecords)
        Text("实时曲线", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        RealtimeChart(state.points)
        Text(
            "功率和温度来自手机电池接口，仅用于趋势判断；实际输入功率以外部功率计为准。",
            color = TextSecondary,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PowerHero(state: UiState) {
    val shape = RoundedCornerShape(17.dp)
    val battery = state.battery
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF142A28), Color(0xFF101B22)),
                    start = Offset.Zero,
                    end = Offset(900f, 420f),
                ),
            )
            .border(1.dp, Color(0xFF2F6A5D), shape)
            .padding(15.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF174B3F), shape = RoundedCornerShape(99.dp)) {
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
                Text(battery.powerW.oneDecimal(), color = TextPrimary, fontSize = 39.sp, fontWeight = FontWeight.Black)
                Text(" W", color = TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 7.dp))
            }
            Text("实时电池功率", color = TextSecondary, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${battery.levelPercent}%", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0xFF304149)),
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
) {
    Surface(
        modifier = modifier,
        color = Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3B40)),
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
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
private fun ActionTile(label: String, value: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Surface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3B40)),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            Text(label, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextSecondary, fontSize = 8.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ForceChargeCard(
    state: UiState,
    onOpenSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF2C6B5F)),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("强制亮屏快充", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "基础档：${state.settings.baseTier.label} · ${if (state.settings.customRulesEnabled) "自定义规则" else "默认规则"}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Switch(checked = state.settings.masterEnabled, onCheckedChange = onToggle)
            }
            Text(defaultSummary(state.settings), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
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
        "自定义：${settings.customRules.temperatureRules.size} 个温度节点 · ${settings.customRules.socStopPercent}% 暂停"
    } else when (settings.baseTier.label) {
        "最大输出" -> "41°C→20–40W · 42°C→10–30W · 43°C/80%暂停 · 100%关单速"
        else -> "低于 43°C 按温度内部降档 · 43°C/80%暂停 · 100%关单速"
    }
}

@Composable
private fun SessionRecords(
    points: List<ChartPoint>,
    chargeRecords: List<com.oppower.model.ChargeRecord>,
    dischargeRecords: List<com.oppower.model.ChargeRecord>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        RecordCard("充电记录", points.filter { it.acOnline }, chargeRecords.lastOrNull(), Cyan, Modifier.weight(1f))
        RecordCard("放电记录", points.filter { !it.acOnline }, dischargeRecords.lastOrNull(), Amber, Modifier.weight(1f))
    }
}

@Composable
private fun RecordCard(
    title: String,
    points: List<ChartPoint>,
    lastRecord: com.oppower.model.ChargeRecord?,
    color: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3B40)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            val display = lastRecord
            if (display != null) {
                Text(
                    "${display.startPercent}% → ${display.endPercent}%",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${(display.endedAtMillis - display.startedAtMillis).durationText()} · 峰值 ${display.peakPowerW.oneDecimal()} W",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    "最高 ${display.maxTemperatureC.oneDecimal()}°C",
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

@Composable
private fun RealtimeChart(points: List<ChartPoint>) {
    val powerMax = max(5f, points.maxOfOrNull { it.powerW } ?: 5f)
    val temps = points.map { it.temperatureC }
    val tempMin = (temps.minOrNull() ?: 30f).coerceAtMost(40f)
    val tempMax = max(tempMin + 1f, temps.maxOrNull() ?: 42f)
    Surface(
        color = Surface,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3B40)),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(Cyan, "功率 W")
                Spacer(Modifier.width(12.dp))
                Legend(Amber, "温度 °C")
                Spacer(Modifier.weight(1f))
                Text("真实时间", color = TextSecondary, fontSize = 9.sp)
            }
            Box(Modifier.fillMaxWidth().height(164.dp).padding(top = 8.dp)) {
                Canvas(Modifier.fillMaxSize().padding(start = 34.dp, end = 36.dp, top = 13.dp, bottom = 18.dp)) {
                    val right = size.width
                    val bottom = size.height
                    repeat(5) { index ->
                        val y = bottom * index / 4f
                        drawLine(Color(0xFF2A3B40), Offset(0f, y), Offset(right, y), 1f)
                    }
                    if (points.size >= 2) {
                        val powerPath = Path()
                        val tempPath = Path()
                        points.forEachIndexed { index, point ->
                            val x = right * index / (points.size - 1).toFloat()
                            val powerY = bottom - bottom * (point.powerW / powerMax).coerceIn(0f, 1f)
                            val tempY = bottom - bottom * ((point.temperatureC - tempMin) / (tempMax - tempMin)).coerceIn(0f, 1f)
                            if (index == 0) {
                                powerPath.moveTo(x, powerY)
                                tempPath.moveTo(x, tempY)
                            } else {
                                powerPath.lineTo(x, powerY)
                                tempPath.lineTo(x, tempY)
                            }
                        }
                        drawPath(tempPath, Amber, style = Stroke(1.8f))
                        drawPath(powerPath, Cyan, style = Stroke(2.3f))
                    }
                }
                Text("${powerMax.oneDecimal()} W", color = Cyan, fontSize = 8.sp, modifier = Modifier.align(Alignment.TopStart))
                Text("0 W", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomStart))
                Text("${tempMax.oneDecimal()} °C", color = Amber, fontSize = 8.sp, modifier = Modifier.align(Alignment.TopEnd))
                Text("${tempMin.oneDecimal()} °C", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd))
                if (points.size < 2) {
                    Text("等待采样…", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val start = points.firstOrNull()?.epochMillis ?: System.currentTimeMillis()
            val end = points.lastOrNull()?.epochMillis ?: start
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatter.format(Date(start)), color = TextSecondary, fontSize = 8.sp)
                Text(formatter.format(Date(end)), color = TextSecondary, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = TextSecondary, fontSize = 9.sp)
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
