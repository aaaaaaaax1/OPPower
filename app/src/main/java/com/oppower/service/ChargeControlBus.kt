package com.oppower.service

import com.oppower.model.ControllerStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

object ChargeControlBus {
    val status = MutableStateFlow(ControllerStatus())
    val wakeRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestUpdate() {
        wakeRequests.trySend(Unit)
    }
}
