package cn.edu.sycu.schedule

import org.junit.Assert.*
import org.junit.Test

class AboutTest {
    @Test fun releaseIncludesLiveNotesAndOnlyExpectedApk() {
        val url = "$PROJECT_URL/releases/download/v0.2.0/zhixu-0.2.0.apk"
        val json = """{"tag_name":"v0.2.0","draft":false,"prerelease":false,"published_at":"2026-09-15T01:00:00Z","body":"修复课程显示\n新增主题","assets":[{"browser_download_url":"$url"}]}"""
        val release = releaseResult(200, json, "0.1.1")
        assertEquals(url, release.download)
        assertEquals("2026-09-15T01:00:00Z", release.published)
        assertEquals("修复课程显示\n新增主题", release.notes)
        assertNull(releaseResult(200, json.replace(url, "https://example.com/app.apk"), "0.1.1").download)
    }
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
