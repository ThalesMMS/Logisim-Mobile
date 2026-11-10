package dev.logisim.mobile

import kotlin.test.Test

/** Runs the actual Compose runtime without a window; JVM tracing adapter is documented. */
class KeyboardDraftStrongSkippingTest {
    @Test
    fun cancelledDraftIsInvalidatedWhenAReplacementKeepsTheQueueCountEqual() {
        KeyboardDraftRuntimeTest().strongSkippingReevaluatesCancelledDraftWhenThePendingCountStaysOne()
    }
}
