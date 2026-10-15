package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.updater.ChecksumVerifier
import com.etaegord.ghostscreen.updater.UpdateInfo
import com.etaegord.ghostscreen.updater.UpdateState
import com.etaegord.ghostscreen.updater.VersionComparator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFlowLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testUpdateInfoFormattedSize() {
        val unknownSizeInfo = UpdateInfo(
            tagName = "v1.0.0",
            versionName = "1.0.0",
            title = "Test",
            changelog = "Changelog",
            downloadUrl = "https://example.com/app.apk",
            apkSize = 0L,
            publishedAt = "2026-10-15T18:00:00Z"
        )
        assertEquals("Unknown size", unknownSizeInfo.formattedSize)

        val kbInfo = unknownSizeInfo.copy(apkSize = 512 * 1024L)
        assertEquals("512.0 KB", kbInfo.formattedSize)

        val mbInfo = unknownSizeInfo.copy(apkSize = 18_456_123L)
        assertEquals("17.6 MB", mbInfo.formattedSize)
    }

    @Test
    fun testUpdateInfoFormattedDate() {
        val validDateInfo = UpdateInfo(
            tagName = "v1.1.0",
            versionName = "1.1.0",
            title = "Test",
            changelog = "Changelog",
            downloadUrl = "https://example.com/app.apk",
            apkSize = 1000L,
            publishedAt = "2026-10-15T18:00:00Z"
        )
        assertTrue(validDateInfo.formattedDate.contains("2026"))
        assertTrue(validDateInfo.formattedDate.contains("Oct") || validDateInfo.formattedDate.contains("10"))

        val emptyDateInfo = validDateInfo.copy(publishedAt = "")
        assertEquals("", emptyDateInfo.formattedDate)

        val simpleDateInfo = validDateInfo.copy(publishedAt = "2026-11-20")
        assertTrue(simpleDateInfo.formattedDate.contains("2026"))
    }

    @Test
    fun testSkipVersionLogic() {
        val skippedTag = "v1.1.0"
        val candidateTag = "v1.1.0"
        val newerTag = "v1.2.0"

        fun shouldNotify(tag: String, isManual: Boolean, skipped: String): Boolean {
            if (isManual) return true
            return tag != skipped
        }

        // On auto check, skipped version does not notify
        assertFalse(shouldNotify(candidateTag, isManual = false, skipped = skippedTag))

        // On manual check, skipped version still notifies
        assertTrue(shouldNotify(candidateTag, isManual = true, skipped = skippedTag))

        // Newer version not yet skipped always notifies
        assertTrue(shouldNotify(newerTag, isManual = false, skipped = skippedTag))
    }

    @Test
    fun testVersionCodeResolutionPrecedence() {
        // Tag has no code, asset has _c10005_
        val assetName = "GhostScreen_v1.0.0_c10005_release.apk"
        val tag = "v1.0.0"
        val currentCode = 10000L

        val extractedCode = VersionComparator.extractVersionCode(assetName)
        assertEquals(10005L, extractedCode)

        assertTrue(
            VersionComparator.isNewer(
                remoteVersion = tag,
                currentVersion = "1.0.0",
                remoteVersionCode = extractedCode,
                currentVersionCode = currentCode
            )
        )
    }

    @Test
    fun testChecksumVerificationCorruptedApkHandling() {
        val apkFile = tempFolder.newFile("GhostScreen_test.apk")
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)) // Partial zip header

        val realSha256 = ChecksumVerifier.calculateSha256(apkFile)

        // Valid hash passes
        assertTrue(ChecksumVerifier.verifySha256(apkFile, realSha256))

        // Alter byte in APK to simulate transmission error
        apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0xFF.toByte()))

        // Tampered APK fails verification
        assertFalse(ChecksumVerifier.verifySha256(apkFile, realSha256))
    }
}
