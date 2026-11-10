package com.androidharness.app.data.update

import java.io.File

/** Values are displayed in settings; AUTO still obeys the configured device-access policy. */
enum class UpdateInstallMethod { AUTO, ROOT, SHIZUKU, SYSTEM }

internal object UpdateInstallPolicy {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    fun command(apk: File, user: Int): Array<String> {
        require(apk.isFile && apk.length() in 1..MAX_APK_BYTES && user >= 0) { "Update APK is unavailable." }
        val path = "'" + apk.absolutePath.replace("'", "'\\''") + "'"
        // A pipe is required: Android can reject a shared-storage file descriptor sent over Binder.
        return arrayOf("/system/bin/sh", "-c", "/system/bin/cat $path | /system/bin/pm install -r --user $user -S ${apk.length()}")
    }
    fun succeeded(exit: Int, timedOut: Boolean, output: String) = exit == 0 && !timedOut && output.lineSequence().any { it.trim() == "Success" }
    fun trustedAsset(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port in listOf(-1, 443) && uri.userInfo == null &&
            uri.path.startsWith("/Sanuu7/AndroidHarness/releases/download/") && uri.query == null && uri.fragment == null
    }.getOrDefault(false)
    fun compatible(installedPackage: String, candidatePackage: String, installedVersion: Long, candidateVersion: Long,
        currentSigners: Set<String>, candidateSigners: Set<String>, candidateHistory: Set<String>): Boolean =
        installedPackage == candidatePackage && candidateVersion >= installedVersion && currentSigners.isNotEmpty() &&
            if (currentSigners.size > 1 || candidateSigners.size > 1) currentSigners == candidateSigners
            else candidateSigners.isNotEmpty() && currentSigners.all { it in candidateHistory }
}
