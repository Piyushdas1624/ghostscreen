package com.etaegord.ghostscreen

import com.etaegord.ghostscreen.updater.ChecksumVerifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ChecksumVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testChecksumCalculationAndVerification() {
        val testFile = tempFolder.newFile("sample.apk")
        val content = "GhostScreen Test Update Content 123456789".toByteArray(Charsets.UTF_8)
        testFile.writeBytes(content)

        // Compute expected SHA-256
        val md = MessageDigest.getInstance("SHA-256")
        val expectedBytes = md.digest(content)
        val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }

        val actualHex = ChecksumVerifier.calculateSha256(testFile)
        assertEquals(expectedHex, actualHex)

        // Test verifySha256
        assertTrue(ChecksumVerifier.verifySha256(testFile, expectedHex))
        assertTrue(ChecksumVerifier.verifySha256(testFile, "SHA256:$expectedHex"))
        assertTrue(ChecksumVerifier.verifySha256(testFile, expectedHex.uppercase()))

        // Wrong hash
        assertFalse(ChecksumVerifier.verifySha256(testFile, "0000000000000000000000000000000000000000000000000000000000000000"))

        // Null / blank hash
        assertTrue(ChecksumVerifier.verifySha256(testFile, null, strict = false))
        assertFalse(ChecksumVerifier.verifySha256(testFile, null, strict = true))
    }

    @Test
    fun testEmptyFileSha256() {
        val emptyFile = tempFolder.newFile("empty.apk")
        // Known SHA-256 of empty content
        val expectedEmptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val actualHash = ChecksumVerifier.calculateSha256(emptyFile)
        assertEquals(expectedEmptyHash, actualHash)
        assertTrue(ChecksumVerifier.verifySha256(emptyFile, expectedEmptyHash))
        assertTrue(ChecksumVerifier.verifySha256(emptyFile, "SHA-256: $expectedEmptyHash"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNonExistentFileThrows() {
        val missingFile = File(tempFolder.root, "does_not_exist.apk")
        ChecksumVerifier.calculateSha256(missingFile)
    }

    @Test
    fun testCorruptedFileFailsVerification() {
        val validFile = tempFolder.newFile("valid.apk")
        validFile.writeText("Good APK")
        val correctHash = ChecksumVerifier.calculateSha256(validFile)

        // Corrupt file contents
        validFile.writeText("Tampered APK")
        assertFalse(ChecksumVerifier.verifySha256(validFile, correctHash))
    }

    @Test
    fun testExtractSha256ForAssetSingleHash() {
        val hash = "3a7bd3e2360a3d29eea436cdfb27d374d09cf9b75fc61f033fd9e0ce3f79f005"
        val singleLine = "$hash  GhostScreen_v1.1.0_release.apk"
        assertEquals(hash, ChecksumVerifier.extractSha256ForAsset(singleLine, "GhostScreen_v1.1.0_release.apk"))
        assertEquals(hash, ChecksumVerifier.extractSha256ForAsset(hash))
    }

    @Test
    fun testExtractSha256ForAssetMultiLineChecksumsFile() {
        val hash1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val hash2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val hash3 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

        val checksumsTxt = """
            $hash1  sources.zip
            $hash2  GhostScreen_v1.2.0_release.apk
            $hash3  documentation.pdf
        """.trimIndent()

        val extracted = ChecksumVerifier.extractSha256ForAsset(checksumsTxt, "GhostScreen_v1.2.0_release.apk")
        assertEquals(hash2, extracted)
    }
}
