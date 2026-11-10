package com.androidharness.app.data.env

/** A snapshot of one install request, including its dependency closure. Never implies tool success. */
data class PackageInstallStatus(
    val requested: List<String>,
    val phase: PackageInstallPhase = PackageInstallPhase.WAITING,
    val packages: List<String> = emptyList(),
    val completed: List<String> = emptyList(),
    val currentPackage: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
)

enum class PackageInstallPhase { WAITING, RESOLVING, DOWNLOADING, VERIFYING, UNPACKING, CONFIGURING }
