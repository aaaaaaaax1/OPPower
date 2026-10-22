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
            2,
            2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Keep the invisible keep-alive window inside the display's safe
            // gesture area. A 1px window at (0, 0) can collide with the system's
            // top gesture region and make the foreground app appear to vanish.
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 48
            alpha = 0.01f
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
