package com.oppower.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class KeepAliveOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun sync(enabled: Boolean) {
        if (!enabled || !Settings.canDrawOverlays(context)) {
            remove()
            return
        }
        if (view != null) return
        val overlay = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching {
            windowManager.addView(overlay, params)
            view = overlay
        }
    }

    fun remove() {
        val overlay = view ?: return
        view = null
        runCatching { windowManager.removeViewImmediate(overlay) }
    }
}
