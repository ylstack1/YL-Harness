package com.androidharness.app.data.env.adb

internal open class AdbException(message: String) : Exception(message)
internal class AdbInvalidPairingCodeException : AdbException("The pairing code is wrong.")
internal class AdbKeyException(cause: Throwable) : AdbException("The ADB key could not be prepared: ${cause.message}")
