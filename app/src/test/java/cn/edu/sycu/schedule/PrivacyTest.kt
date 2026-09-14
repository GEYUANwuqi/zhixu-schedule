package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test

class PrivacyTest {
    @Test fun requiresBothDelayAndBottom() {
        assertFalse(canAcceptPrivacy(0, false))
        assertFalse(canAcceptPrivacy(4, true))
        assertFalse(canAcceptPrivacy(5, false))
        assertTrue(canAcceptPrivacy(5, true))
        assertTrue(canAcceptPrivacy(6, true))
    }
}
