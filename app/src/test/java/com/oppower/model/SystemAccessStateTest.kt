package com.oppower.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAccessStateTest {
    @Test
    fun notificationsReadyRequiresAppAndActiveChannelPermission() {
        val base = SystemAccessState(
            notificationsAllowed = false,
            statusNotificationChannelEnabled = true,
            liveNotificationChannelEnabled = true,
        )

        assertFalse(base.notificationsReady(usesLiveChannel = false))
        assertFalse(base.copy(notificationsAllowed = true, liveNotificationChannelEnabled = false)
            .notificationsReady(usesLiveChannel = true))
        assertTrue(base.copy(notificationsAllowed = true, liveNotificationChannelEnabled = true)
            .notificationsReady(usesLiveChannel = true))
        assertTrue(base.copy(
            notificationsAllowed = true,
            statusNotificationChannelEnabled = true,
            liveNotificationChannelEnabled = false,
        ).notificationsReady(usesLiveChannel = false))
    }
}
