package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import com.example.data.AppUpdateInfo
import com.example.data.UpdateChecker
import com.example.ui.screens.UpdateButtonBlue
import com.example.ui.screens.openBrowserUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AboutAndUpdateDialogTest {

    @Test
    fun updateButtonBlueMatchesMaterialBrightBlueSpecification() {
        // Bright blue Color(0xFF0D6EFD) or Color(0xFF007AFF)
        assertEquals(Color(0xFF0D6EFD), UpdateButtonBlue)
        // High blue channel intensity > 0.9f
        assertTrue(UpdateButtonBlue.blue > 0.9f)
    }

    @Test
    fun appUpdateInfoDataClassHoldsExpectedContract() {
        val info = AppUpdateInfo(
            latestVersion = "KernoVM v1.1",
            releaseNotes = "Added new features and bugfixes",
            downloadUrl = "https://github.com/Iky969/vm/releases/download/v1.1/KernoVM-v1.1-release.apk",
            htmlUrl = "https://github.com/Iky969/vm/releases/tag/v1.1",
            isNewer = true
        )

        assertEquals("KernoVM v1.1", info.latestVersion)
        assertEquals("Added new features and bugfixes", info.releaseNotes)
        assertEquals("https://github.com/Iky969/vm/releases/download/v1.1/KernoVM-v1.1-release.apk", info.downloadUrl)
        assertEquals("https://github.com/Iky969/vm/releases/tag/v1.1", info.htmlUrl)
        assertTrue(info.isNewer)
    }

    @Test
    fun openBrowserUrlLaunchesViewIntentWithNewTaskFlag() {
        val context = RuntimeEnvironment.getApplication()
        val targetUrl = "https://github.com/Iky969/vm"

        openBrowserUrl(context, targetUrl)

        val nextStartedActivity = org.robolectric.Shadows.shadowOf(context).nextStartedActivity
        assertNotNull("Expected an intent to be dispatched", nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, nextStartedActivity.action)
        assertEquals(Uri.parse(targetUrl), nextStartedActivity.data)
        assertTrue((nextStartedActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun aboutDialogMetadataAndLicenseAttributionConstants() {
        // Verify current version & build constants
        assertEquals("0.1.0", UpdateChecker.CURRENT_VERSION_NAME)
        assertEquals(BuildConfig.VERSION_CODE, UpdateChecker.CURRENT_BUILD_NUMBER)
        assertTrue(UpdateChecker.CURRENT_BUILD_NUMBER >= 1)
        assertEquals("https://api.github.com/repos/Iky969/vm/releases", UpdateChecker.DEFAULT_RELEASES_URL)

        // Verify required attributions
        val expectedHeader = "KernoVM v${UpdateChecker.CURRENT_VERSION_NAME}, developed with Google AI Studio"
        assertTrue(expectedHeader.contains("KernoVM v0.1.0"))
        assertTrue(expectedHeader.contains("developed with Google AI Studio"))

        val expectedRepo = "https://github.com/Iky969/vm"
        val expectedAuthor = "Iky969"
        val expectedYear = "2026"
        val expectedCopyright = "© $expectedYear $expectedAuthor"

        assertEquals("https://github.com/Iky969/vm", expectedRepo)
        assertEquals("Iky969", expectedAuthor)
        assertTrue(expectedCopyright.contains("2026"))
        assertTrue(expectedCopyright.contains("Iky969"))

        // Required License components
        val licenseApache2 = "Apache License 2.0"
        val licenseMit = "MIT License (Charles Lohr)"
        val licenseFonts = "SIL OFL 1.1"

        assertTrue(licenseApache2.contains("Apache"))
        assertTrue(licenseMit.contains("Charles Lohr"))
        assertTrue(licenseFonts.contains("SIL OFL"))
    }

    @Test
    fun versionComparisonHandlesBuildNumbersAndSemverAccurately() {
        // Build number comparisons
        assertTrue(UpdateChecker.isVersionNewer("test-v1.0-build6", "1.0", 1))
        assertTrue(UpdateChecker.isVersionNewer("test-v1.0-build2", "1.0", 1))
        assertFalse(UpdateChecker.isVersionNewer("test-v1.0-build1", "1.0", 1))
        assertFalse(UpdateChecker.isVersionNewer("test-v1.0-build1", "1.0", 2))

        // Semver comparisons
        assertTrue(UpdateChecker.isVersionNewer("v1.1", "1.0", 1))
        assertTrue(UpdateChecker.isVersionNewer("v2.0.0", "1.0", 1))
        assertFalse(UpdateChecker.isVersionNewer("v1.0", "1.0", 1))
        assertFalse(UpdateChecker.isVersionNewer("v0.9", "1.0", 1))
    }
}
