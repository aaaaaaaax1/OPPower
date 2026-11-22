package com.oppower.service

import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

data class SharedBatterySample(
    val snapshot: BatterySnapshot,
    val capturedAtElapsedMillis: Long,
)

object ChargeControlBus {
    val status = MutableStateFlow(ControllerStatus())
    val battery = MutableStateFlow<SharedBatterySample?>(null)
    val wakeRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestUpdate() {
        wakeRequests.trySend(Unit)
    }
}
