package com.example.data

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Information about a software update available from GitHub Releases.
 */
data class AppUpdateInfo(
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String,
    val isNewer: Boolean
)

/**
 * Network client for checking GitHub Releases API for KernoVM app updates.
 */
class UpdateChecker(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val currentVersion: String = CURRENT_VERSION_NAME,
    private val currentBuild: Int = CURRENT_BUILD_NUMBER,
    private val releasesApiUrl: String = DEFAULT_RELEASES_URL
) {
    /**
     * Queries the GitHub Releases API for the latest release.
     * Returns [AppUpdateInfo] if a release was found, or null on error / empty releases.
     */
    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(releasesApiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KernoVM/$currentVersion")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) return@use null
                parseReleasesJson(bodyStr, currentVersion, currentBuild)
            }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_RELEASES_URL = "https://api.github.com/repos/Iky969/vm/releases"
        const val CURRENT_VERSION_NAME = "0.1.0"
        val CURRENT_BUILD_NUMBER: Int = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(1)

        /**
         * Parses a GitHub Releases API JSON array string into [AppUpdateInfo].
         */
        fun parseReleasesJson(
            jsonString: String,
            currentVersion: String = CURRENT_VERSION_NAME,
            currentBuild: Int = CURRENT_BUILD_NUMBER
        ): AppUpdateInfo? {
            return runCatching {
                val releases = JSONArray(jsonString)
                if (releases.length() == 0) return null

                // Look for the newest non-draft release
                var latestObj: JSONObject? = null
                for (i in 0 until releases.length()) {
                    val obj = releases.getJSONObject(i)
                    if (!obj.optBoolean("draft", false)) {
                        latestObj = obj
                        break
                    }
                }
                val release = latestObj ?: return null

                val tagName = release.getString("tag_name").trim()
                val releaseName = release.optString("name", tagName).trim()
                val body = release.optString("body", "").trim()
                val htmlUrl = release.optString("html_url", "https://github.com/Iky969/vm/releases")

                // Extract APK download URL from assets
                var apkDownloadUrl = ""
                val assets = release.optJSONArray("assets")
                if (assets != null) {
                    var releaseApkUrl = ""
                    var debugApkUrl = ""
                    var anyApkUrl = ""

                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "").lowercase()
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk")) {
                            if (name.contains("release")) {
                                releaseApkUrl = downloadUrl
                            } else if (name.contains("debug")) {
                                debugApkUrl = downloadUrl
                            }
                            if (anyApkUrl.isEmpty()) {
                                anyApkUrl = downloadUrl
                            }
                        }
                    }
                    apkDownloadUrl = when {
                        releaseApkUrl.isNotEmpty() -> releaseApkUrl
                        anyApkUrl.isNotEmpty() -> anyApkUrl
                        debugApkUrl.isNotEmpty() -> debugApkUrl
                        else -> htmlUrl
                    }
                }
                if (apkDownloadUrl.isEmpty()) {
                    apkDownloadUrl = htmlUrl
                }

                val versionDisplay = if (releaseName.isNotBlank() && releaseName != tagName) {
                    releaseName
                } else {
                    tagName
                }

                val isNewer = isVersionNewer(tagName, currentVersion, currentBuild)

                AppUpdateInfo(
                    latestVersion = versionDisplay,
                    releaseNotes = body,
                    downloadUrl = apkDownloadUrl,
                    htmlUrl = htmlUrl,
                    isNewer = isNewer
                )
            }.getOrNull()
        }

        /**
         * Compares release tag with currently running version/build.
         */
        fun isVersionNewer(
            latestTag: String,
            currentVersion: String = CURRENT_VERSION_NAME,
            currentBuild: Int = CURRENT_BUILD_NUMBER
        ): Boolean {
            // Check for build<N> pattern (e.g. test-v1.0-build6)
            val buildRegex = Regex("""build(\d+)""", RegexOption.IGNORE_CASE)
            val latestBuildMatch = buildRegex.find(latestTag)?.groupValues?.get(1)?.toIntOrNull()
            if (latestBuildMatch != null && currentBuild > 0) {
                return latestBuildMatch > currentBuild
            }

            // Compare semantic version (e.g. v1.1 vs 1.0)
            val cleanLatest = latestTag.trimStart('v', 'V')
                .replace(Regex("""^test-v?""", RegexOption.IGNORE_CASE), "")
                .split("-")[0]
            val cleanCurrent = currentVersion.trimStart('v', 'V').split("-")[0]

            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }

            return false
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
