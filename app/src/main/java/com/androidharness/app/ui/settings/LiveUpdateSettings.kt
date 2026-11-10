package com.androidharness.app.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import com.androidharness.app.R
import java.util.Locale

/**
 * Promoted ongoing notifications ("live activities") are an AOSP API from
 * Android 16, so the card itself works on every OEM that implemented it -
 * Pixel, Samsung, OnePlus, OPPO and friends. Only the name of the surface and
 * the path a user takes to allow it differ per skin, and that is all this file
 * models. Nothing here gates whether the notification is promoted; that stays
 * in [com.androidharness.app.AgentService] and is brand-independent.
 */
internal enum class LiveUpdateVendor(
    @StringRes val label: Int,
    @StringRes val steps: Int,
) {
    SAMSUNG(R.string.settings_live_vendor_samsung, R.string.settings_live_steps_samsung),
    GOOGLE(R.string.settings_live_vendor_google, R.string.settings_live_steps_google),
    XIAOMI(R.string.settings_live_vendor_xiaomi, R.string.settings_live_steps_xiaomi),
    COLOR_OS(R.string.settings_live_vendor_color_os, R.string.settings_live_steps_color_os),
    ORIGIN_OS(R.string.settings_live_vendor_origin_os, R.string.settings_live_steps_origin_os),
    MAGIC_OS(R.string.settings_live_vendor_magic_os, R.string.settings_live_steps_magic_os),
    NEAR_STOCK(R.string.settings_live_vendor_near_stock, R.string.settings_live_steps_near_stock),
    OTHER(R.string.settings_live_vendor_other, R.string.settings_live_steps_other),
}

/**
 * Maps manufacturer/brand strings to the skin whose wording we show. Pure and
 * parameterised so it can be unit-tested without a device.
 */
internal fun detectLiveUpdateVendor(manufacturer: String, brand: String): LiveUpdateVendor {
    val id = "$manufacturer $brand".lowercase(Locale.ROOT)
    fun any(vararg keys: String) = keys.any { it in id }
    return when {
        any("samsung") -> LiveUpdateVendor.SAMSUNG
        any("google") -> LiveUpdateVendor.GOOGLE
        any("xiaomi", "redmi", "poco") -> LiveUpdateVendor.XIAOMI
        any("oneplus", "oppo", "realme") -> LiveUpdateVendor.COLOR_OS
        any("vivo", "iqoo") -> LiveUpdateVendor.ORIGIN_OS
        any("honor") -> LiveUpdateVendor.MAGIC_OS
        any("motorola", "moto", "nothing", "sony", "asus", "nokia", "lenovo") -> LiveUpdateVendor.NEAR_STOCK
        else -> LiveUpdateVendor.OTHER
    }
}

internal val liveUpdateVendor: LiveUpdateVendor
    get() = detectLiveUpdateVendor(Build.MANUFACTURER, Build.BRAND)

/** "Google Pixel 8" style label; avoids repeating a manufacturer the model already starts with. */
internal val liveUpdateDeviceName: String
    get() {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

internal enum class LiveUpdateState(@StringRes val description: Int) {
    READY(R.string.settings_live_state_ready),
    BLOCKED(R.string.settings_live_state_blocked),
    UNSUPPORTED(R.string.settings_live_state_unsupported),
}

/**
 * Whether this install may promote its ongoing notification right now. An appop
 * the user has not allowed yet reports the same as a blocked install, which is
 * exactly what the settings card needs to offer the fix.
 */
internal fun liveUpdateState(context: Context): LiveUpdateState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return LiveUpdateState.UNSUPPORTED
    val allowed = runCatching {
        context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications()
    }.getOrNull()
    return if (allowed == true) LiveUpdateState.READY else LiveUpdateState.BLOCKED
}

/**
 * Opens the per-app Live Update setting. Android 16 QPR1+ has a dedicated
 * promotion page; OEM skins that do not implement it fall through to their
 * notification settings (and Xiaomi's MIUI intent is tried first there),
 * finally to the app details page. These intents are best-effort: any missing
 * activity throws and the next candidate is tried.
 */
internal fun Context.openLiveUpdateSettings() {
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            add(
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
        if (liveUpdateVendor == LiveUpdateVendor.XIAOMI) {
            add(
                Intent("miui.intent.action.APP_NOTIFICATION_SETTINGS")
                    .putExtra("extra_pkgname", packageName),
            )
        }
        add(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
        )
    }
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
}
