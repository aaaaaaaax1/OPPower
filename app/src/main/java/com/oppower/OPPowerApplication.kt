package com.oppower

import android.app.Application
import com.oppower.service.ChargeControlService

class OPPowerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ChargeControlService.start(this)
    }
}
