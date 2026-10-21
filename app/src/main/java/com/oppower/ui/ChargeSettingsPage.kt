package com.oppower.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.model.BaseTier
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import com.oppower.model.TierRule
import com.oppower.model.conservatismRank

@Composable
internal fun ChargeSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRestore: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val temperatureInputs = remember {
        mutableStateListOf(*state.settings.customRules.temperatureRules.map { it.threshold.oneDecimal() }.toTypedArray())
    }
    var socInput by remember { androidx.compose.runtime.mutableStateOf(state.settings.customRules.socStopPercent.toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader(
            title = "强制亮屏快充",
            subtitle = "接电后校准 · 拔电自动恢复",
            onBack = onBack,
        )

        Surface(
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2A3B40)),
        ) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("总开关", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(state.controllerStatus.message, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Switch(
                        checked = state.settings.masterEnabled,
                        onCheckedChange = { enabled -> onSettingsChange { it.copy(masterEnabled = enabled) } },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Color(0xFF2A3B40))
                Text(
                    "关闭总开关或一键恢复时：cosa_ctc_value=0，single_speed_charge_state=0。",
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }

        ShizukuCard(state, onRequestShizuku)

        SectionTitle("基础功率档位")
        BaseTier.entries.forEach { tier ->
            BaseTierCard(
                tier = tier,
                selected = state.settings.baseTier == tier,
                onClick = { onSettingsChange { it.copy(baseTier = tier) } },
            )
        }

        if (!state.settings.customRulesEnabled) {
            DefaultRuleCard(state.settings)
        }

        Surface(
            color = Surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2A3B40)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义规则", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("自定义温度节点、目标档位和电量暂停点", color = TextSecondary, fontSize = 10.sp)
                    }
                    Switch(
                        checked = state.settings.customRulesEnabled,
                        onCheckedChange = { enabled -> onSettingsChange { it.copy(customRulesEnabled = enabled) } },
                    )
                }

                if (state.settings.customRulesEnabled) {
                    Text(
                        "温度节点范围 30.0–46.0°C。到达节点后只能选择与当前相同或更低的功率档位。",
                        color = Amber,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.settings.customRules.temperatureRules.forEachIndexed { index, rule ->
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF2A3B40))
                        Text(
                            "节点 ${index + 1} · 温度达到后",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val input = temperatureInputs.getOrElse(index) { rule.threshold.oneDecimal() }
                        val numeric = input.toFloatOrNull()
                        val previous = state.settings.customRules.temperatureRules.getOrNull(index - 1)?.threshold
                        val next = state.settings.customRules.temperatureRules.getOrNull(index + 1)?.threshold
                        val minimum = previous?.plus(0.5f) ?: 30f
                        val maximum = next?.minus(0.5f) ?: 46f
                        val valid = numeric != null && numeric in minimum..maximum
                        Row(
                            Modifier.fillMaxWidth().padding(top = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { value ->
                                    if (value.isEmpty() || value.matches(Regex("\\d{0,2}(\\.\\d?)?"))) {
                                        temperatureInputs[index] = value
                                        value.toFloatOrNull()?.takeIf { it in minimum..maximum }?.let { parsed ->
                                            onSettingsChange { settings ->
                                                settings.copy(
                                                    customRules = settings.customRules.copy(
                                                        temperatureRules = settings.customRules.temperatureRules.mapIndexed { itemIndex, old ->
                                                            if (itemIndex == index) old.copy(threshold = parsed) else old
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                                label = { Text("温度") },
                                suffix = { Text("°C") },
                                singleLine = true,
                                isError = input.isNotEmpty() && !valid,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            Column(Modifier.weight(1.55f)) {
                                Text("达到后切换到", color = TextSecondary, fontSize = 9.sp)
                                Text(
                                    rule.target.label,
                                    color = if (rule.target == ControlTier.PAUSED) Danger else Cyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                        if (input.isNotEmpty() && !valid) {
                            Text(
                                "请输入 ${minimum.oneDecimal()}–${maximum.oneDecimal()}°C；节点间隔至少 0.5°C",
                                color = Danger,
                                fontSize = 9.sp,
                            )
                        }
                        TargetGrid(
                            selected = rule.target,
                            maximumPower = state.settings.customRules.temperatureRules
                                .getOrNull(index - 1)?.target
                                ?: settingsBaseTier(state.settings),
                            onSelected = { target ->
                                onSettingsChange { settings ->
                                    settings.copy(
                                        customRules = settings.customRules.copy(
                                            temperatureRules = settings.customRules.temperatureRules.mapIndexed { itemIndex, old ->
                                                if (itemIndex == index) TierRule(old.threshold, target) else old
                                            },
                                        ),
                                    )
                                }
                            },
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF2A3B40))
                    Text("电量暂停点", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("电量 ≥ 此值后暂停本次 gear 策略", color = TextSecondary, fontSize = 10.sp)
                            Text("单速设置保持 1；拔电后重新开始", color = TextSecondary, fontSize = 9.sp)
                        }
                        OutlinedTextField(
                            value = socInput,
                            onValueChange = { value ->
                                if (value.length <= 3 && value.all(Char::isDigit)) {
                                    socInput = value
                                    value.toIntOrNull()?.takeIf { it in 1..100 }?.let { parsed ->
                                        onSettingsChange { settings ->
                                            settings.copy(
                                                customRules = settings.customRules.copy(socStopPercent = parsed),
                                            )
                                        }
                                    }
                                }
                            },
                            suffix = { Text("%") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(104.dp),
                        )
                    }
                }
            }
        }

        if (state.settings.customRulesEnabled) {
            CustomRulePreview(state.settings)
        }

        Surface(color = Surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color(0xFF2A3B40))) {
            Column(Modifier.padding(11.dp)) {
                Text("动态调节规则", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("• 内部档位按 0.5°C 分段计算。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                Text("• 向更低功率切换需连续确认 15 秒。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                Text("• 温度恢复后升档需连续确认 30 秒，并留出温度回差。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                Text("• 均衡档内部只看温度，不按电量继续细分。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }

        Button(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF402624),
                contentColor = Danger,
            ),
        ) {
            Text("一键恢复系统默认")
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShizukuCard(state: UiState, onRequestShizuku: () -> Unit) {
    Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF2A3B40))) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shizuku 权限", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !state.shizukuAvailable -> "Shizuku 未运行"
                        !state.shizukuPermission -> "等待授权"
                        else -> "已连接，可由后台服务执行命令"
                    },
                    color = if (state.shizukuAvailable && state.shizukuPermission) Cyan else Amber,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (!state.shizukuAvailable || !state.shizukuPermission) {
                Button(onClick = onRequestShizuku) { Text("去授权", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun BaseTierCard(tier: BaseTier, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) Color(0xFF173C36) else Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Cyan else Color(0xFF35484E)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tier.label,
                    color = if (selected) Cyan else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when (tier) {
                        BaseTier.MAX_OUTPUT -> "系统最高输出能力"
                        BaseTier.HIGH -> "gear 1–4 · 内部按温度在范围内调整"
                        BaseTier.GENTLE -> "gear 2–5 · 内部按温度在范围内调整"
                    },
                    color = TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(if (selected) "已选择" else "选择", color = if (selected) Cyan else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DefaultRuleCard(settings: RuntimeSettings) {
    val rows = if (settings.baseTier == BaseTier.MAX_OUTPUT) {
        listOf(
            "T < 41.0°C" to "最大输出",
            "41.0 ≤ T < 42.0°C" to "20–40 W · gear 1–4",
            "42.0 ≤ T < 43.0°C" to "10–30 W · gear 2–5",
            "T ≥ 43.0°C" to "暂停 gear，单速保持 1",
        )
    } else {
        listOf(
            "T < 43.0°C" to "${settings.baseTier.label} · 温度内部降档",
            "T ≥ 43.0°C" to "暂停 gear，单速保持 1",
        )
    }
    Surface(color = Surface, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Color(0xFF2A3B40))) {
        Column(Modifier.padding(11.dp)) {
            Text("默认策略 · ${settings.baseTier.label}", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            rows.forEach { (range, target) ->
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text(range, color = Amber, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(target, color = TextPrimary, fontSize = 10.sp)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 7.dp), color = Color(0xFF2A3B40))
            Text("电量 ≥ 80%：本次插电暂停 gear，单速保持 1", color = Amber, fontSize = 10.sp)
            Text("电量 = 100% 或 FULL：cosa=0，单速关闭", color = Danger, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun TargetGrid(
    selected: ControlTier,
    maximumPower: ControlTier,
    onSelected: (ControlTier) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TargetChip(ControlTier.MAX_OUTPUT, selected, maximumPower, Modifier.weight(1f), onSelected)
            TargetChip(ControlTier.HIGH, selected, maximumPower, Modifier.weight(1f), onSelected)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TargetChip(ControlTier.GENTLE, selected, maximumPower, Modifier.weight(1f), onSelected)
            TargetChip(ControlTier.PAUSED, selected, maximumPower, Modifier.weight(1f), onSelected)
        }
    }
}

@Composable
private fun TargetChip(
    target: ControlTier,
    selected: ControlTier,
    maximumPower: ControlTier,
    modifier: Modifier,
    onSelected: (ControlTier) -> Unit,
) {
    val active = target == selected
    val enabled = target.conservatismRank() <= maximumPower.conservatismRank()
    Surface(
        modifier = modifier.clickable(enabled = enabled) { onSelected(target) },
        color = when {
            active -> Color(0xFF1B4A40)
            enabled -> Color(0xFF17242A)
            else -> Color(0xFF11181C)
        },
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (active) Cyan else Color(0xFF3B4C52)),
    ) {
        Text(
            target.label,
            color = when {
                active -> Cyan
                enabled -> TextPrimary
                else -> Color(0xFF64757A)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
        )
    }
}

private fun settingsBaseTier(settings: RuntimeSettings): ControlTier = when (settings.baseTier) {
    BaseTier.MAX_OUTPUT -> ControlTier.MAX_OUTPUT
    BaseTier.HIGH -> ControlTier.HIGH
    BaseTier.GENTLE -> ControlTier.GENTLE
}

@Composable
private fun CustomRulePreview(settings: RuntimeSettings) {
    val rules = settings.customRules.temperatureRules.sortedBy { it.threshold }
    Surface(color = Surface, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Color(0xFF2A3B40))) {
        Column(Modifier.padding(11.dp)) {
            Text("当前自定义区间", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "T < ${rules.getOrNull(0)?.threshold?.oneDecimal() ?: "-"}°C → ${settings.baseTier.label}",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
            rules.forEachIndexed { index, rule ->
                val lower = rule.threshold.oneDecimal()
                val upper = rules.getOrNull(index + 1)?.threshold?.oneDecimal()
                val range = if (upper == null) "T ≥ $lower°C" else "$lower ≤ T < $upper°C"
                Text("$range → ${rule.target.label}", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Text(
                "电量 ≥ ${settings.customRules.socStopPercent}% → 暂停本次 gear 策略",
                color = Amber,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹",
            color = TextPrimary,
            fontSize = 32.sp,
            modifier = Modifier.clickable(onClick = onBack).padding(end = 13.dp),
        )
        Column {
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}
