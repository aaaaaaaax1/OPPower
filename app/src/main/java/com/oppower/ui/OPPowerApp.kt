package com.oppower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.model.RuntimeSettings

private enum class Page {
    MAIN,
    CHARGE_SETTINGS,
    SETTINGS,
}

@Composable
fun OPPowerApp(
    state: State<UiState>,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRequestShizuku: () -> Unit,
    onReadGear: () -> Unit,
    onRestore: () -> Unit,
    onDismissIntro: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var settingsScreen by rememberSaveable { mutableStateOf(SettingsScreen.HOME) }
    val context = LocalContext.current

    BackHandler(enabled = page != Page.MAIN) {
        when (page) {
            Page.MAIN -> Unit
            Page.CHARGE_SETTINGS -> page = Page.MAIN
            Page.SETTINGS -> if (settingsScreen == SettingsScreen.HOME) {
                page = Page.MAIN
            } else {
                settingsScreen = if (settingsScreen.isAboutChild()) SettingsScreen.ABOUT else SettingsScreen.HOME
            }
        }
    }

    // A direct switch avoids rendering two full pages during transitions. On
    // long, scrollable pages the crossfade created duplicate layout and drawing
    // work, which appeared as a visible hitch on the device.
    when (page) {
        Page.MAIN -> MainPage(
            state = state,
            onOpenChargeSettings = { page = Page.CHARGE_SETTINGS },
            onOpenSettings = {
                settingsScreen = SettingsScreen.HOME
                page = Page.SETTINGS
            },
            onRequestShizuku = onRequestShizuku,
            onSettingsChange = onSettingsChange,
            onJoinQq = { joinQqGroup(context) },
        )

        Page.CHARGE_SETTINGS -> ChargeSettingsPage(
            state = state.value,
            onBack = { page = Page.MAIN },
            onSettingsChange = onSettingsChange,
            onRestore = onRestore,
            onRequestShizuku = onRequestShizuku,
            onReadGear = onReadGear,
        )

        Page.SETTINGS -> SettingsRouter(
            screen = settingsScreen,
            state = state.value,
            onNavigate = { settingsScreen = it },
            onBack = {
                if (settingsScreen == SettingsScreen.HOME) {
                    page = Page.MAIN
                } else {
                    settingsScreen = if (settingsScreen.isAboutChild()) SettingsScreen.ABOUT else SettingsScreen.HOME
                }
            },
            onSettingsChange = onSettingsChange,
            onJoinQq = { joinQqGroup(context) },
            onClearHistory = onClearHistory,
            onRequestShizuku = onRequestShizuku,
        )
    }

    FirstRunHost(
        state = state,
        onDismiss = onDismissIntro,
        onJoinQq = { joinQqGroup(context) },
    )
}

@Composable
private fun FirstRunHost(
    state: State<UiState>,
    onDismiss: () -> Unit,
    onJoinQq: () -> Unit,
) {
    val showIntro = remember(state) { derivedStateOf { state.value.showIntro } }
    if (showIntro.value) {
        FirstRunDialog(onDismiss = onDismiss, onJoinQq = onJoinQq)
    }
}

private fun SettingsScreen.isAboutChild(): Boolean = when (this) {
    SettingsScreen.IMPORTANT_NOTICE,
    SettingsScreen.DEVELOPER_NOTICE,
    SettingsScreen.LICENSES,
    -> true
    else -> false
}

@Composable
private fun FirstRunDialog(onDismiss: () -> Unit, onJoinQq: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("关于 OPPower") },
        text = {
            Column {
                Text(
                    "本项目基于 LLPower 二改得来，增加了 ColorOS 亮屏快充功能。",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                )
                Row(Modifier.padding(top = 14.dp)) {
                    Text("大家感兴趣的可以加群：", color = TextSecondary, fontSize = 15.sp)
                    Text(
                        QQ_GROUP_NUMBER,
                        color = Amber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable(onClick = onJoinQq).padding(horizontal = 2.dp),
                    )
                }
                Text(
                    "点击群号可复制并尝试打开 QQ 加入群聊。有任何问题可在群内反馈。",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("知道了") }
        },
    )
}
