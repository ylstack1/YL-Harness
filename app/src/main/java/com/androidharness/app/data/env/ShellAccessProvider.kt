package com.androidharness.app.data.env

/** Shizuku-compatible managers. Package evidence is only used to name a live connection. */
enum class ShellAccessProvider(val packageName: String, val label: String) {
    SHIZUKU("moe.shizuku.privileged.api", "Shizuku"),
    SHEVERY("com.hamondev.shevery", "Shevery"),
}
