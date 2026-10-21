package com.oppower.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

const val QQ_GROUP_NUMBER = "1126197031"
const val QQ_GROUP_INVITE = "https://qm.qq.com/q/eXdtZrzjKo"

fun joinQqGroup(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("OPPower QQ 群号", QQ_GROUP_NUMBER))
    Toast.makeText(context, "群号 $QQ_GROUP_NUMBER 已复制", Toast.LENGTH_SHORT).show()
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_INVITE)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        Toast.makeText(context, "无法打开邀请链接，群号已复制", Toast.LENGTH_LONG).show()
    }
}
