package com.example

import com.example.data.AppUpdateInfo
import com.example.data.UpdateChecker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateCheckerTest {

    private val sampleReleasesJson = """
    [
      {
        "tag_name": "test-v1.0-build6",
        "name": "KernoVM Test Build #6",
        "html_url": "https://github.com/Iky969/vm/releases/tag/test-v1.0-build6",
        "body": "## 🧪 KernoVM Test Pre-Release (Build #6)\n\n* Added Simple Framebuffer\n* Added VirtIO-Net",
        "draft": false,
        "assets": [
          {
            "name": "KernoVM-v1.0-debug.apk",
            "browser_download_url": "https://github.com/Iky969/vm/releases/download/test-v1.0-build6/KernoVM-v1.0-debug.apk"
          },
          {
            "name": "KernoVM-v1.0-release.apk",
            "browser_download_url": "https://github.com/Iky969/vm/releases/download/test-v1.0-build6/KernoVM-v1.0-release.apk"
          }
        ]
      },
      {
        "tag_name": "test-v1.0-build4",
        "name": "KernoVM Test Build #4",
        "html_url": "https://github.com/Iky969/vm/releases/tag/test-v1.0-build4",
        "body": "Older release",
        "draft": false,
        "assets": []
      }
    ]
    """.trimIndent()

    @Test
    fun parseReleasesJsonExtractsLatestReleaseAndReleaseApkUrl() {
        val info = UpdateChecker.parseReleasesJson(
            jsonString = sampleReleasesJson,
            currentVersion = "1.0",
            currentBuild = 1
        )
        assertNotNull(info)
        requireNotNull(info)

        assertEquals("KernoVM Test Build #6", info.latestVersion)
        assertEquals("https://github.com/Iky969/vm/releases/download/test-v1.0-build6/KernoVM-v1.0-release.apk", info.downloadUrl)
        assertEquals("https://github.com/Iky969/vm/releases/tag/test-v1.0-build6", info.htmlUrl)
        assertTrue(info.releaseNotes.contains("Added Simple Framebuffer"))
        assertTrue("Build 6 must be newer than Build 1", info.isNewer)
    }

    @Test
    fun isVersionNewerHandlesBuildNumbersAndSemver() {
        // Build number comparisons
        assertTrue(UpdateChecker.isVersionNewer("test-v1.0-build6", "1.0", 1))
        assertTrue(UpdateChecker.isVersionNewer("test-v1.0-build6", "1.0", 5))
        assertFalse(UpdateChecker.isVersionNewer("test-v1.0-build6", "1.0", 6))
        assertFalse(UpdateChecker.isVersionNewer("test-v1.0-build6", "1.0", 7))

        // Semantic version comparisons
        assertTrue(UpdateChecker.isVersionNewer("v1.1", "1.0", 0))
        assertTrue(UpdateChecker.isVersionNewer("v1.0.1", "1.0", 0))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.9", 0))
        assertFalse(UpdateChecker.isVersionNewer("v1.0", "1.0", 0))
        assertFalse(UpdateChecker.isVersionNewer("v1.0.0", "1.0", 0))
        assertFalse(UpdateChecker.isVersionNewer("v0.9", "1.0", 0))
    }

    @Test
    fun parseReleasesJsonHandlesEmptyAndFallbackCases() {
        assertNull(UpdateChecker.parseReleasesJson("[]"))
        assertNull(UpdateChecker.parseReleasesJson("invalid json"))

        val draftOnlyJson = """
        [
          {
            "tag_name": "v2.0",
            "name": "Draft v2.0",
            "draft": true,
            "assets": []
          }
        ]
        """.trimIndent()
        assertNull(UpdateChecker.parseReleasesJson(draftOnlyJson))

        val noAssetsJson = """
        [
          {
            "tag_name": "test-v1.0-build7",
            "html_url": "https://github.com/Iky969/vm/releases/tag/test-v1.0-build7",
            "body": "No asset attached",
            "draft": false,
            "assets": []
          }
        ]
        """.trimIndent()
        val info = UpdateChecker.parseReleasesJson(noAssetsJson, currentBuild = 1)
        assertNotNull(info)
        assertEquals("https://github.com/Iky969/vm/releases/tag/test-v1.0-build7", info!!.downloadUrl)
    }

    @Test
    fun liveCheckForUpdateReturnsValidReleaseInfo() = runTest {
        // Queries real public GitHub releases API for Iky969/vm
        val checker = UpdateChecker(currentBuild = 1)
        val info = checker.checkForUpdate()
        // If network is reachable, info should have latest release
        if (info != null) {
            assertTrue(info.latestVersion.isNotEmpty())
            assertTrue(info.downloadUrl.startsWith("https://"))
            assertTrue(info.htmlUrl.startsWith("https://github.com/Iky969/vm/releases"))
        }
    }
}
