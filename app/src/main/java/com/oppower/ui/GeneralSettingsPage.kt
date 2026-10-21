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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.model.RuntimeSettings

@Composable
internal fun GeneralSettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onJoinQq: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = TextPrimary,
                fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 13.dp),
            )
            Column {
                Text("设置", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("OPPower · RMX8899 / ColorOS 17", color = TextSecondary, fontSize = 10.sp)
            }
        }

        SettingsSection("实时监测") {
            SettingSwitchRow(
                title = "实时通知",
                description = "在常驻通知中显示当前控制状态",
                checked = state.settings.liveNotificationEnabled,
                onCheckedChange = { enabled ->
                    onSettingsChange { it.copy(liveNotificationEnabled = enabled) }
                },
            )
            SettingsDivider()
            SettingInfoRow(
                title = "采样周期",
                value = if (state.battery.acOnline) "充电时 2 秒" else "界面打开时 2 秒",
            )
            SettingsDivider()
            SettingInfoRow(
                title = "曲线窗口",
                value = "最近 180 个采样点",
            )
        }

        SettingsSection("后台运行") {
            SettingInfoRow(
                title = "控制服务",
                value = "前台服务 · 可划掉界面后继续",
                valueColor = Cyan,
            )
            SettingsDivider()
            Text(
                "充电时每 5 秒检查一次状态；未接电时每 30 秒检查一次。拔电、充满、关闭总开关或服务销毁时会恢复 cosa_ctc_value=0。",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 9.dp),
            )
        }

        SettingsSection("显示与组件") {
            SettingInfoRow(
                title = "主题",
                value = "深色 · 高对比",
            )
            SettingsDivider()
            SettingInfoRow(
                title = "桌面组件",
                value = "首版预留，当前未发布组件",
            )
            SettingsDivider()
            SettingInfoRow(
                title = "字体与尺寸",
                value = "跟随系统",
            )
        }

        SettingsSection("数据说明") {
            Text(
                "主界面的充电、放电记录来自本次打开应用后的采样，不会上传网络。手机电池功率来自系统接口，外部功率计读数通常更高。",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 9.dp),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onJoinQq),
            color = Color(0xFF172A28),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2F6A5D)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("加入 QQ 群", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("群号 $QQ_GROUP_NUMBER · 点击复制并打开邀请链接", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }

        Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF2A3B40))) {
            Column(Modifier.padding(12.dp)) {
                Text("关于 OPPower", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("本项目基于 LLPower 二改得来，增加了 ColorOS 亮屏快充功能。", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                Text("版本 1.0.0 · 仅供已了解风险的用户在 RMX8899 上使用", color = TextSecondary, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Surface(color = Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFF2A3B40))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { content() }
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
            Text(description, color = TextSecondary, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingInfoRow(title: String, value: String, valueColor: Color = TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Color(0xFF2A3B40))
}
