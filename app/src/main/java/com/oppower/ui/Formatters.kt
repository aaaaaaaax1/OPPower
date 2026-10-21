package com.oppower.ui

import java.util.Locale

internal fun Float.oneDecimal(): String = String.format(Locale.getDefault(), "%.1f", this)

internal fun Long.durationText(): String {
    val minutes = (this / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}
