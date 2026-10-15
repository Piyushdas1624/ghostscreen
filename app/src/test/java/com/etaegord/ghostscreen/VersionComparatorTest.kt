package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.updater.VersionComparator
import org.junit.Assert.*
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun testSameVersionsAreEqual() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("v1.0.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("v1.2.3", "V1.2.3"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("v1.0.0", "1.0.0"))
    }

    @Test
    fun testNewerPatchVersion() {
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("v1.0.1", "v1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun testNewerMinorVersion() {
        assertTrue(VersionComparator.isNewer("1.1.0", "1.0.9"))
        assertTrue(VersionComparator.isNewer("v1.10.0", "v1.9.0"))
        assertFalse(VersionComparator.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun testNewerMajorVersion() {
        assertTrue(VersionComparator.isNewer("2.0.0", "1.99.99"))
        assertFalse(VersionComparator.isNewer("1.99.99", "2.0.0"))
    }

    @Test
    fun testBuildNumbers() {
        // Project version scheme: e.g. 1.0.0-b2 vs 1.0.0-b3
        assertTrue(VersionComparator.isNewer("1.0.0-b3", "1.0.0-b2"))
        assertFalse(VersionComparator.isNewer("1.0.0-b1", "1.0.0-b2"))
        assertTrue(VersionComparator.isNewer("v1.0.0-b10", "v1.0.0-b9"))
    }

    @Test
    fun testDifferentLengthVersions() {
        assertTrue(VersionComparator.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0.1"))
    }

    @Test
    fun testPreReleaseHandling() {
        // Full release has number, pre-release has text
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"))
        assertTrue(VersionComparator.isNewer("1.0.0-rc2", "1.0.0-rc1"))
    }

    @Test
    fun testSingleSegmentVersions() {
        assertTrue(VersionComparator.isNewer("2", "1"))
        assertFalse(VersionComparator.isNewer("1", "2"))
        assertEquals(0, VersionComparator.compare("1", "1"))
    }

    @Test
    fun testWhitespaceAndPrefixes() {
        assertTrue(VersionComparator.isNewer("  v2.0.0  ", " 1.9.9 "))
        assertFalse(VersionComparator.isNewer(" 1.0.0 ", " 1.0.0 "))
    }

    @Test
    fun testVersionCodeComparisonWhenNamesMatch() {
        // Version names match, but remote versionCode is higher
        assertTrue(
            VersionComparator.isNewer(
                remoteVersion = "1.0.0",
                currentVersion = "1.0.0",
                remoteVersionCode = 10005L,
                currentVersionCode = 10000L
            )
        )

        // Version names match, remote versionCode is lower
        assertFalse(
            VersionComparator.isNewer(
                remoteVersion = "1.0.0",
                currentVersion = "1.0.0",
                remoteVersionCode = 10000L,
                currentVersionCode = 10005L
            )
        )

        // Version names match, remote versionCode is equal
        assertFalse(
            VersionComparator.isNewer(
                remoteVersion = "1.0.0",
                currentVersion = "1.0.0",
                remoteVersionCode = 10000L,
                currentVersionCode = 10000L
            )
        )

        // Semantic version name takes precedence over versionCode
        assertTrue(
            VersionComparator.isNewer(
                remoteVersion = "1.0.1",
                currentVersion = "1.0.0",
                remoteVersionCode = 9999L,
                currentVersionCode = 10000L
            )
        )
    }

    @Test
    fun testExtractVersionCode() {
        assertEquals(10005L, VersionComparator.extractVersionCode("GhostScreen_v1.0.0_c10005_release.apk"))
        assertEquals(10100L, VersionComparator.extractVersionCode("v1.1.0+10100"))
        assertEquals(10002L, VersionComparator.extractVersionCode("GhostScreen v1.0.0 (10002)"))
        assertEquals(205L, VersionComparator.extractVersionCode("v1.0.0-build205"))
        assertNull(VersionComparator.extractVersionCode("GhostScreen_v1.0.0_release.apk"))
    }

    @Test
    fun testBetaToFinalReleaseComparison() {
        // Currently on beta 1.0.0-b2, remote published final 1.0.0 release
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-b2"))
        assertTrue(VersionComparator.isNewer("v1.0.0", "1.0.0-b3"))
        // Running final 1.0.0, remote older beta should not be newer
        assertFalse(VersionComparator.isNewer("1.0.0-b2", "1.0.0"))
    }
}
