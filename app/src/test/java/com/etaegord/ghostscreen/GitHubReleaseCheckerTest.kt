package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.updater.GitHubReleaseChecker
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseCheckerTest {

    @Test
    fun testParseReleaseJsonWithApkAssetAndSha256InBody() {
        val sampleJson = """
        {
          "tag_name": "v1.1.0",
          "name": "GhostScreen v1.1.0 - Next-Gen Privacy",
          "body": "## What's Changed\n- Added tilt sensor shield\n- Performance improvements\n\n**Checksums**:\nSHA-256: 3a7bd3e2360a3d29eea436cdfb27d374d09cf9b75fc61f033fd9e0ce3f79f005",
          "published_at": "2026-10-15T15:30:00Z",
          "assets": [
            {
              "name": "GhostScreen_v1.1.0_release.apk",
              "size": 18456123,
              "browser_download_url": "https://github.com/Piyushdas1624/ghostscreen/releases/download/v1.1.0/GhostScreen_v1.1.0_release.apk"
            }
          ]
        }
        """.trimIndent()

        val updateInfo = GitHubReleaseChecker.parseReleaseJson(sampleJson)

        assertEquals("v1.1.0", updateInfo.tagName)
        assertEquals("1.1.0", updateInfo.versionName)
        assertEquals("GhostScreen v1.1.0 - Next-Gen Privacy", updateInfo.title)
        assertTrue(updateInfo.changelog.contains("Added tilt sensor shield"))
        assertEquals("https://github.com/Piyushdas1624/ghostscreen/releases/download/v1.1.0/GhostScreen_v1.1.0_release.apk", updateInfo.downloadUrl)
        assertEquals(18456123L, updateInfo.apkSize)
        assertEquals("2026-10-15T15:30:00Z", updateInfo.publishedAt)
        assertEquals("3a7bd3e2360a3d29eea436cdfb27d374d09cf9b75fc61f033fd9e0ce3f79f005", updateInfo.sha256)
        assertEquals("17.6 MB", updateInfo.formattedSize)
    }

    @Test
    fun testParseReleaseJsonWithoutApk() {
        val jsonWithoutApk = """
        {
          "tag_name": "v1.0.5",
          "name": "v1.0.5",
          "body": "Bugfix release",
          "html_url": "https://github.com/Piyushdas1624/ghostscreen/releases/tag/v1.0.5",
          "assets": []
        }
        """.trimIndent()

        val updateInfo = GitHubReleaseChecker.parseReleaseJson(jsonWithoutApk)
        assertEquals("v1.0.5", updateInfo.tagName)
        assertEquals("1.0.5", updateInfo.versionName)
        assertEquals("https://github.com/Piyushdas1624/ghostscreen/releases/tag/v1.0.5", updateInfo.downloadUrl)
        assertEquals(0L, updateInfo.apkSize)
        assertNull(updateInfo.sha256)
    }

    @Test
    fun testMultipleAssetsSelectsApk() {
        val jsonWithMultipleAssets = """
        {
          "tag_name": "v1.2.0",
          "name": "GhostScreen v1.2.0",
          "body": "Release with extra assets\nb94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
          "assets": [
            {
              "name": "sources.zip",
              "size": 5000000,
              "browser_download_url": "https://example.com/sources.zip"
            },
            {
              "name": "GhostScreen_v1.2.0.apk",
              "size": 25000000,
              "browser_download_url": "https://example.com/GhostScreen_v1.2.0.apk"
            },
            {
              "name": "checksums.txt",
              "size": 128,
              "browser_download_url": "https://example.com/checksums.txt"
            }
          ]
        }
        """.trimIndent()

        val updateInfo = GitHubReleaseChecker.parseReleaseJson(jsonWithMultipleAssets)
        assertEquals("https://example.com/GhostScreen_v1.2.0.apk", updateInfo.downloadUrl)
        assertEquals("GhostScreen_v1.2.0.apk", updateInfo.assetName)
        assertEquals(25000000L, updateInfo.apkSize)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", updateInfo.sha256)
    }

    @Test
    fun testEmptyBodyDefaultsChangelog() {
        val json = """
        {
          "tag_name": "1.0.0",
          "name": "Initial"
        }
        """.trimIndent()

        val updateInfo = GitHubReleaseChecker.parseReleaseJson(json)
        assertEquals("1.0.0", updateInfo.tagName)
        assertEquals("1.0.0", updateInfo.versionName)
        assertEquals("No changelog provided.", updateInfo.changelog)
        assertNull(updateInfo.sha256)
    }

    @Test
    fun testSha256AssetUrlAndVersionCodeExtracted() {
        val json = """
        {
          "tag_name": "v1.3.0",
          "name": "GhostScreen v1.3.0",
          "body": "Clean release without body hash",
          "published_at": "2026-10-15T18:00:00Z",
          "assets": [
            {
              "name": "GhostScreen_v1.3.0_c10300_release.apk",
              "size": 15000000,
              "browser_download_url": "https://example.com/app.apk"
            },
            {
              "name": "GhostScreen_v1.3.0_c10300_release.apk.sha256",
              "size": 64,
              "browser_download_url": "https://example.com/app.apk.sha256"
            }
          ]
        }
        """.trimIndent()

        val updateInfo = GitHubReleaseChecker.parseReleaseJson(json)
        assertEquals(10300L, updateInfo.versionCode)
        assertEquals("https://example.com/app.apk.sha256", updateInfo.sha256AssetUrl)
        assertNull(updateInfo.sha256)
        assertTrue(updateInfo.formattedDate.contains("2026"))
    }
}
