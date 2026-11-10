package com.androidharness.app.data.update

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UpdateInstallPolicyTest {
    @Test fun installSuccessRequiresAnUnambiguousCompletedResult() {
        assertTrue(UpdateInstallPolicy.succeeded(0, false, "Performing Streamed Install\nSuccess\n"))
        for ((exit, timed, output) in listOf(Triple(1, false, "Success"), Triple(0, true, "Success"), Triple(0, false, "Failure [bad]"), Triple(0, false, "No Success"), Triple(-1, false, "")))
            assertFalse(UpdateInstallPolicy.succeeded(exit, timed, output))
    }
    @Test fun onlyThisRepositoriesHttpsReleaseAssetsCanBeDownloaded() {
        assertTrue(UpdateInstallPolicy.trustedAsset("https://github.com/Sanuu7/AndroidHarness/releases/download/v3/app.apk"))
        for (url in listOf("http://github.com/Sanuu7/AndroidHarness/releases/download/a/a.apk", "https://github.com.evil/Sanuu7/AndroidHarness/releases/download/a/a.apk", "https://user@github.com/Sanuu7/AndroidHarness/releases/download/a/a.apk", "https://github.com/other/app/releases/download/a/a.apk", "file:///tmp/app.apk", "https://github.com/Sanuu7/AndroidHarness/releases/download/a/a.apk?token=secret")) assertFalse(url, UpdateInstallPolicy.trustedAsset(url))
    }
    @Test fun packageVersionAndSigningIdentityMustBeCompatibleIncludingValidRotationHistory() {
        fun valid(pkg: String = "app", version: Long = 20, signers: Set<String> = setOf("current"), history: Set<String> = signers) =
            UpdateInstallPolicy.compatible("app", pkg, 20, version, setOf("current"), signers, history)
        assertTrue(valid()); assertTrue(valid(version = 21)); assertTrue(valid(signers = setOf("next"), history = setOf("current", "next")))
        assertFalse(valid(pkg = "app.debug")); assertFalse(valid(version = 19)); assertFalse(valid(signers = setOf("foreign")))
        assertFalse(valid(signers = emptySet(), history = setOf("current")))
        assertFalse(UpdateInstallPolicy.compatible("app", "app", 1, 2, setOf("a", "b"), setOf("a"), setOf("a", "b")))
    }
    @Test fun installCommandStreamsTheApkUsesTheRightAndroidUserAndQuotesPaths() {
        val directory = Files.createTempDirectory("update quote'").toFile()
        try {
            val apk = java.io.File(directory, "update.apk").apply { writeText("apk") }
            val command = UpdateInstallPolicy.command(apk, 10)
            assertEquals("/system/bin/sh", command[0])
            assertTrue(command[2].startsWith("/system/bin/cat '"))
            assertTrue(command[2].endsWith("| /system/bin/pm install -r --user 10 -S 3"))
            assertTrue(command[2].contains("'\\''")); assertFalse(command[2].contains("su "))
        } finally { directory.deleteRecursively() }
    }
}
