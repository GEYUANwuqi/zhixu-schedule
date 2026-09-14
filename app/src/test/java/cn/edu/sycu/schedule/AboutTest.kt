package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test

class AboutTest {
    @Test fun comparesNumericVersions() {
        assertTrue(isNewerVersion("v0.10.0", "0.9.9"))
        assertTrue(isNewerVersion("v1.0.0", "0.99.99"))
        assertTrue(isNewerVersion("0.1.1", "0.1.0"))
        assertFalse(isNewerVersion("v0.1.0", "0.1.0"))
        assertFalse(isNewerVersion("v0.1.0", "0.2.0"))
    }
    @Test fun excludesInvalidAndPreviewVersions() {
        for (tag in listOf("v1.0.0-rc1", "v01.0.0", "garbage", "v1.0.0+build")) {
            assertThrows(IllegalArgumentException::class.java) { isNewerVersion(tag, "0.1.0") }
        }
    }
    @Test fun releaseResponsesDistinguishUpdateFromNoRelease() {
        assertNull(releaseResult(404, "", "0.1.0").tag)
        assertNull(releaseResult(429, "", "0.1.0").tag)
        val body = """{"tag_name":"v0.2.0","draft":false,"prerelease":false}"""
        assertEquals("v0.2.0", releaseResult(200, body, "0.1.0").tag)
        assertNull(releaseResult(200, body, "0.2.0").tag)
        assertThrows(IllegalArgumentException::class.java) { releaseResult(200, body.replace("\"draft\":false", "\"draft\":true"), "0.1.0") }
        assertThrows(IllegalArgumentException::class.java) { releaseResult(500, "", "0.1.0") }
    }
}
