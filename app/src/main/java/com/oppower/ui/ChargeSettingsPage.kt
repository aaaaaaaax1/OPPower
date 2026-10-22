package com.oppower.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.oppower.model.BaseTier
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import com.oppower.model.SocTierRule
import com.oppower.model.TierRule
import com.oppower.model.conservatismRank
import com.oppower.model.friendlyLabel

@Composable
internal fun ChargeSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRestore: () -> Unit,
    onRequestShizuku: () -> Unit,
    onReadGear: () -> Unit,
) {
    val temperatureInputs = remember {
        mutableStateListOf(*state.settings.customRules.temperatureRules.map { it.threshold.oneDecimal() }.toTypedArray())
    }
    val socInputs = remember {
        mutableStateListOf(*state.settings.customRules.socRules.map { it.thresholdPercent.toString() }.toTypedArray())
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Background, Surface.copy(alpha = 0.48f))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Outline),
            shadowElevation = 3.dp,
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
                HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Outline)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.gearReadResult?.let { result ->
                                result.error?.let { "读取失败 · " + it }
                                    ?: "当前计算档位：" + (result.gear?.toString() ?: "系统暂未返回")
                            } ?: "读取基础档与 cosa 计算档位（只读）",
                            color = when {
                                state.gearReadResult?.error != null -> Danger
                                state.gearReadResult?.gear != null -> Cyan
                                else -> TextSecondary
                            },
                            fontSize = 9.sp,
                        )
                        state.gearReadResult?.takeUnless { it.error != null }?.let {
                            Text(
                                buildString {
                                    append("基础档=")
                                    append(it.baseGear?.toString() ?: "--")
                                    append(" · cosa=")
                                    append(it.cosa?.toString() ?: "--")
                                    append(" · 计算=")
                                    append(it.calculatedGear?.toString() ?: "--")
                                    append(" · chargeVal=")
                                    append(it.chargeVal?.toString() ?: "--")
                                },
                                color = Cyan,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                "公式：基础档 + cosa = 计算档位 · 来源：" +
                                    (it.source ?: "系统 Thermal Levels + cosa_ctc_value"),
                                color = TextSecondary,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        state.gearReadResult?.let {
                            Text(
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.readAtMillis)),
                                color = TextSecondary,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    TextButton(onClick = onReadGear) {
                        Text("读取档位", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "关闭总开关或一键恢复时，快充增强会恢复系统默认。",
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }

        ShizukuCard(state, onRequestShizuku)

        SectionTitleRow("基础功率档位", "选择默认策略，规则可继续降档")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            enumValues<BaseTier>().forEach { tier ->
                BaseTierCard(
                    tier = tier,
                    selected = state.settings.baseTier == tier,
                    modifier = Modifier.weight(1f),
                    onClick = { onSettingsChange { it.copy(baseTier = tier) } },
                )
            }
        }

        if (!state.settings.customRulesEnabled) {
            DefaultRuleCard(state.settings)
        }

        Surface(
            color = SurfaceRaised,
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, if (state.settings.customRulesEnabled) Cyan.copy(alpha = 0.45f) else Outline),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义规则", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.settings.customRulesEnabled) "温度和电量分别设置条件，再选择目标档位" else "当前使用默认策略",
                            color = TextSecondary,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(if (state.settings.customRulesEnabled) "自定义" else "默认规则", color = TextSecondary, fontSize = 9.sp)
                    Switch(
                        checked = state.settings.customRulesEnabled,
                        onCheckedChange = { enabled -> onSettingsChange { it.copy(customRulesEnabled = enabled) } },
                    )
                }

                AnimatedVisibility(
                    visible = state.settings.customRulesEnabled,
                    enter = expandVertically(animationSpec = spring(dampingRatio = 0.82f)) + fadeIn(tween(180)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Surface(
                            color = AccentSurface,
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.24f)),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("当前状态", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (state.battery.acOnline) "已接电 · 自动执行" else "未接电 · 接电后执行",
                                    color = if (state.battery.acOnline) Cyan else Amber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        RuleGroupHeader("温度规则", "填写温度，再选择达到后使用的档位")
                        state.settings.customRules.temperatureRules.forEachIndexed { index, rule ->
                            val input = temperatureInputs.getOrElse(index) { rule.threshold.oneDecimal() }
                            val numeric = input.toFloatOrNull()
                            val previous = state.settings.customRules.temperatureRules.getOrNull(index - 1)?.threshold
                            val next = state.settings.customRules.temperatureRules.getOrNull(index + 1)?.threshold
                            val minimum = previous?.plus(0.5f) ?: 30f
                            val maximum = next?.minus(0.5f) ?: 46f
                            val valid = numeric != null && numeric in minimum..maximum
                            RuleEditorRow(
                                title = "温度条件 ${index + 1}",
                                input = input,
                                suffix = "°C",
                                valid = input.isEmpty() || valid,
                                keyboardType = KeyboardType.Decimal,
                                onInput = { value ->
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
                                error = if (input.isNotEmpty() && !valid) "请输入 ${minimum.oneDecimal()}–${maximum.oneDecimal()}°C" else null,
                                selected = rule.target,
                                maximumPower = state.settings.customRules.temperatureRules
                                    .getOrNull(index - 1)?.target ?: settingsBaseTier(state.settings),
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

                        RuleGroupHeader("电量规则", "电量只切换大档，不参与档内温度分档")
                        state.settings.customRules.socRules.forEachIndexed { index, rule ->
                            val input = socInputs.getOrElse(index) { rule.thresholdPercent.toString() }
                            val numeric = input.toIntOrNull()
                            val previous = state.settings.customRules.socRules.getOrNull(index - 1)?.thresholdPercent
                            val next = state.settings.customRules.socRules.getOrNull(index + 1)?.thresholdPercent
                            val minimum = previous?.plus(1) ?: 1
                            val maximum = next?.minus(1) ?: 99
                            val valid = numeric != null && numeric in minimum..maximum
                            RuleEditorRow(
                                title = "电量条件 ${index + 1}",
                                input = input,
                                suffix = "%",
                                valid = input.isEmpty() || valid,
                                keyboardType = KeyboardType.Number,
                                onInput = { value ->
                                    if (value.length <= 3 && value.all(Char::isDigit)) {
                                        socInputs[index] = value
                                        value.toIntOrNull()?.takeIf { it in minimum..maximum }?.let { parsed ->
                                            onSettingsChange { settings ->
                                                settings.copy(
                                                    customRules = settings.customRules.copy(
                                                        socRules = settings.customRules.socRules.mapIndexed { itemIndex, old ->
                                                            if (itemIndex == index) old.copy(thresholdPercent = parsed) else old
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                                error = if (input.isNotEmpty() && !valid) "请输入 $minimum–$maximum%" else null,
                                selected = rule.target,
                                maximumPower = state.settings.customRules.socRules
                                    .getOrNull(index - 1)?.target ?: settingsBaseTier(state.settings),
                                onSelected = { target ->
                                    onSettingsChange { settings ->
                                        settings.copy(
                                            customRules = settings.customRules.copy(
                                                socRules = settings.customRules.socRules.mapIndexed { itemIndex, old ->
                                                    if (itemIndex == index) SocTierRule(old.thresholdPercent, target) else old
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                        }

                        Surface(
                            color = AmberSurface,
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, Amber.copy(alpha = 0.34f)),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("100% / 充满", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("固定暂停快充增强", color = TextPrimary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        if (state.settings.customRulesEnabled) {
            CustomRulePreview(state.settings)
        }

        Surface(color = Surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(11.dp)) {
                Text("自动调节说明", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("• 温度升高时自动降低充电功率。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                Text("• 温度跨过 0.5°C 分段并持续 5 秒才降档；回落并持续 8 秒才升档。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                Text("• 温和策略只随温度调整，电量达到上限后暂停。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
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
    Surface(
        color = Surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Outline),
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("系统控制权限", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !state.shizukuInstalled -> "未安装 · 点击打开下载页"
                        !state.shizukuAvailable -> "未运行 · 点击打开 Shizuku"
                        !state.shizukuPermission -> "未授权 · 点击授权给 OPPower"
                        else -> "已授权 OPPower，可自动控制充电"
                    },
                    color = if (state.shizukuAvailable && state.shizukuPermission) Cyan else Amber,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (!state.shizukuAvailable || !state.shizukuPermission) {
                Button(onClick = onRequestShizuku) {
                    Text(if (!state.shizukuInstalled) "下载" else if (!state.shizukuAvailable) "启动" else "授权", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitleRow(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.width(3.dp).height(17.dp).background(Cyan, RoundedCornerShape(3.dp)),
        )
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text(trailing, color = TextSecondary, fontSize = 8.sp)
    }
}

@Composable
private fun RuleGroupHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(subtitle, color = TextSecondary, fontSize = 8.sp)
    }
}

@Composable
private fun RuleEditorRow(
    title: String,
    input: String,
    suffix: String,
    valid: Boolean,
    keyboardType: KeyboardType,
    onInput: (String) -> Unit,
    error: String?,
    selected: ControlTier,
    maximumPower: ControlTier,
    onSelected: (ControlTier) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(
        color = Surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (error != null) Text(error, color = Danger, fontSize = 7.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (valid) SurfaceRaised else Color(0xFF402624),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, if (valid) Cyan.copy(alpha = 0.52f) else Danger),
                    modifier = Modifier.width(60.dp).height(34.dp),
                ) {
                    Row(Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = input,
                            onValueChange = onInput,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                            textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                        )
                        Text(suffix, color = Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text("→", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 5.dp))
                Box {
                    Surface(
                        modifier = Modifier.width(118.dp).height(34.dp).clickable { expanded = true },
                        color = AccentSurface,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.45f)),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                selected.label,
                                color = when (selected) {
                                    ControlTier.PAUSED -> Danger
                                    ControlTier.GENTLE -> Amber
                                    else -> Cyan
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text("⌄", color = Cyan, fontSize = 13.sp)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ControlTier.entries.forEach { tier ->
                            val enabled = tier.conservatismRank() <= maximumPower.conservatismRank()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        tier.label,
                                        color = when {
                                            tier == selected -> Cyan
                                            enabled -> TextPrimary
                                            else -> TextSecondary
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = if (tier == selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                enabled = enabled,
                                onClick = {
                                    onSelected(tier)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BaseTierCard(tier: BaseTier, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val borderColor by animateColorAsState(if (selected) Cyan else Outline, tween(180), label = "tier-border")
    val scale by animateFloatAsState(if (selected) 1f else 0.97f, spring(dampingRatio = 0.76f), label = "tier-scale")
    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        color = if (selected) AccentSurface else Surface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        shadowElevation = if (selected) 4.dp else 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierGlyph(tier, selected)
                Spacer(Modifier.weight(1f))
                if (selected) Text("✓", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
            Text(
                tier.friendlyLabel,
                color = if (selected) Cyan else TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 7.dp),
            )
            Text(
                tier.label,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                when (tier) {
                    BaseTier.MAX_OUTPUT -> "不额外限制输出"
                    BaseTier.HIGH -> "按温度自动调整"
                    BaseTier.GENTLE -> "高温时更平稳"
                },
                color = TextSecondary,
                fontSize = 8.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Outline)
            Text(
                when (tier) {
                    BaseTier.MAX_OUTPUT -> "功率由系统决定"
                    BaseTier.HIGH -> "档内按温度选档"
                    BaseTier.GENTLE -> "温度升高时更稳"
                },
                color = if (selected) Cyan else TextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TierGlyph(tier: BaseTier, selected: Boolean) {
    val color = if (selected) Cyan else TextSecondary
    Canvas(Modifier.width(25.dp).height(22.dp)) {
        when (tier) {
            BaseTier.MAX_OUTPUT -> {
                val path = Path().apply {
                    moveTo(size.width * 0.56f, 0f)
                    lineTo(size.width * 0.12f, size.height * 0.57f)
                    lineTo(size.width * 0.45f, size.height * 0.57f)
                    lineTo(size.width * 0.33f, size.height)
                    lineTo(size.width * 0.88f, size.height * 0.42f)
                    lineTo(size.width * 0.57f, size.height * 0.42f)
                    close()
                }
                drawPath(path, color)
            }
            BaseTier.HIGH -> {
                drawCircle(color.copy(alpha = 0.18f), radius = size.minDimension * 0.42f, center = center)
                drawLine(color, Offset(size.width * 0.55f, 0f), Offset(size.width * 0.25f, size.height * 0.58f), 2.5f)
                drawLine(color, Offset(size.width * 0.25f, size.height * 0.58f), Offset(size.width * 0.55f, size.height * 0.58f), 2.5f)
                drawLine(color, Offset(size.width * 0.55f, size.height * 0.58f), Offset(size.width * 0.35f, size.height), 2.5f)
            }
            BaseTier.GENTLE -> {
                drawCircle(color.copy(alpha = 0.2f), radius = size.minDimension * 0.45f, center = center)
                drawArc(color, 90f, 180f, true, topLeft = Offset(2f, 2f), size = androidx.compose.ui.geometry.Size(size.width - 4f, size.height - 4f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
            }
        }
    }
}

@Composable
private fun DefaultRuleCard(settings: RuntimeSettings) {
    val rows = if (settings.baseTier == BaseTier.MAX_OUTPUT) {
        listOf(
            "低于 41.0°C" to "最大输出",
            "41.0°C 至 41.9°C" to "自动调整 · 20–40 W",
            "42.0°C 至 42.9°C" to "自动调整 · 10–30 W",
            "达到 43.0°C" to "暂停快充增强，恢复系统默认",
        )
    } else {
        listOf(
            "低于 43.0°C" to "${settings.baseTier.friendlyLabel} · 随温度调整",
            "达到 43.0°C" to "暂停快充增强，恢复系统默认",
        )
    }
    Surface(
        color = Surface,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, Outline),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(11.dp)) {
            Text("默认策略 · ${settings.baseTier.friendlyLabel}", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            rows.forEach { (range, target) ->
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text(range, color = Amber, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(target, color = TextPrimary, fontSize = 10.sp)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 7.dp), color = Outline)
            Text("电量 ≥ 80%：本次插电暂停快充增强", color = Amber, fontSize = 10.sp)
            Text("电量充满：恢复系统默认", color = Danger, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
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
            active -> AccentSurface
            enabled -> SurfaceRaised
            else -> Color(0xFF11181C)
        },
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (active) Cyan else Outline),
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
    val tempRules = settings.customRules.temperatureRules.sortedBy { it.threshold }
    val socRules = settings.customRules.socRules.sortedBy { it.thresholdPercent }
    val t1 = tempRules.getOrNull(0)?.threshold ?: 42f
    val t2 = tempRules.getOrNull(1)?.threshold ?: 43f
    val tempTarget1 = tempRules.getOrNull(0)?.target ?: ControlTier.HIGH
    val tempTarget2 = tempRules.getOrNull(1)?.target ?: ControlTier.PAUSED
    val soc1 = socRules.getOrNull(0)?.thresholdPercent ?: 60
    val soc2 = socRules.getOrNull(1)?.thresholdPercent ?: 85
    val socTarget1 = socRules.getOrNull(0)?.target ?: ControlTier.HIGH
    val socTarget2 = socRules.getOrNull(1)?.target ?: ControlTier.PAUSED
    val stepCount = (((t2 - t1) / 0.5f).toInt()).coerceAtLeast(1)

    Surface(color = Surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Outline), shadowElevation = 2.dp) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("动态控制示意", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("用户填写阈值后自动更新", color = TextSecondary, fontSize = 8.sp)
            }
            DynamicSegmentBar(
                labels = listOf("< " + t1.oneDecimal() + "°C " + settings.baseTier.label, t1.oneDecimal() + "–" + t2.oneDecimal() + "°C " + tempTarget1.label, "≥ " + t2.oneDecimal() + "°C " + tempTarget2.label),
                weights = listOf(2.8f, 1.4f, 1.2f),
                colors = listOf(Cyan.copy(alpha = 0.30f), Amber.copy(alpha = 0.28f), Danger.copy(alpha = 0.24f)),
            )
            DynamicSegmentBar(
                labels = listOf("< " + soc1 + "% " + settings.baseTier.label, soc1.toString() + "–" + soc2 + "% " + socTarget1.label, "≥ " + soc2 + "% " + socTarget2.label),
                weights = listOf(2.5f, 1.2f, 1.2f),
                colors = listOf(Cyan.copy(alpha = 0.28f), Amber.copy(alpha = 0.24f), Danger.copy(alpha = 0.22f)),
            )
            Text("温度和电量先各自选择大档，取更保守者；进入功率大档后再只按温度自动分档。", color = TextSecondary, fontSize = 8.sp, lineHeight = 12.sp)

            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("功率档内部温度分段", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("至少 0.5°C / 段", color = TextSecondary, fontSize = 8.sp)
            }
            Text(t1.oneDecimal() + "–" + t2.oneDecimal() + "°C · " + stepCount + " 个温度段", color = TextSecondary, fontSize = 8.sp)
            val gearSet = when (tempTarget1) {
                ControlTier.HIGH -> BaseTier.HIGH.gearSet
                ControlTier.GENTLE -> BaseTier.GENTLE.gearSet
                else -> emptyList()
            }
            if (gearSet.isNotEmpty()) {
                repeat(stepCount) { index ->
                    val lower = t1 + index * 0.5f
                    val upper = lower + 0.5f
                    val label = when {
                        stepCount == 1 || index >= (stepCount * 2) / 3 -> "低功率端"
                        index >= stepCount / 3 -> "中功率端"
                        else -> "高功率端"
                    }
                    Surface(color = SurfaceRaised, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Outline)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(lower.oneDecimal() + "°C ≤ 温度 < " + upper.oneDecimal() + "°C", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
                            Text(label + " · " + tempTarget1.label, color = Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text("温度条件 1 不是功率档，仅执行所选大档。", color = TextSecondary, fontSize = 8.sp)
            }
            Text(tempTarget1.label + " 在实测功率范围内自动选择高、中、低功率端。", color = TextSecondary, fontSize = 8.sp)

            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("规则区间", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("严格按边界判断", color = TextSecondary, fontSize = 8.sp)
            }
            RuleRangeRow("温度  < " + t1.oneDecimal() + "°C", settings.baseTier.label)
            RuleRangeRow(t1.oneDecimal() + "°C ≤ 温度 < " + t2.oneDecimal() + "°C", tempTarget1.label)
            RuleRangeRow("温度  ≥ " + t2.oneDecimal() + "°C", tempTarget2.label)
            RuleRangeRow("电量  < " + soc1 + "%", settings.baseTier.label)
            RuleRangeRow(soc1.toString() + "% ≤ 电量 < " + soc2 + "%", socTarget1.label)
            RuleRangeRow(soc2.toString() + "% ≤ 电量 < 100%", socTarget2.label)
            RuleRangeRow("电量  ≥ 100% 或充满", "暂停快充 · 关闭快充保持", warning = true)
        }
    }
}

@Composable
private fun DynamicSegmentBar(labels: List<String>, weights: List<Float>, colors: List<Color>) {
    Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(5.dp))) {
        labels.forEachIndexed { index, label ->
            Box(
                Modifier.weight(weights.getOrElse(index) { 1f }).fillMaxSize().background(colors.getOrElse(index) { SurfaceRaised }).padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = TextPrimary, fontSize = 6.5.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RuleRangeRow(condition: String, target: String, warning: Boolean = false) {
    Surface(color = if (warning) AmberSurface else SurfaceRaised, shape = RoundedCornerShape(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(condition, color = if (warning) Amber else TextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
            Text(target, color = if (warning) Amber else TextPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
