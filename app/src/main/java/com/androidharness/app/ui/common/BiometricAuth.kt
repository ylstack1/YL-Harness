package com.androidharness.app.ui.common

import com.androidharness.app.R
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuth {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String = activity.getString(R.string.ui_confirm_your_fingerprint_face_or_pin),
        onSuccess: () -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        val manager = BiometricManager.from(activity)
        val canAuth = manager.canAuthenticate(AUTHENTICATORS)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            if (canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.ui_set_up_screen_lock_pin_fingerprint_in),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.ui_biometric_authentication_not_supported_on_this_device),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onError?.invoke(activity.getString(R.string.ui_biometrics_unavailable))
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError?.invoke(errString.toString())
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError?.invoke(e.message ?: activity.getString(R.string.ui_authentication_failed))
        }
    }

    /**
     * Gate for anything that enables [com.androidharness.app.agent.PermissionMode.FULL_ACCESS].
     * The device owner must prove ownership with biometrics or the lock-screen
     * PIN/pattern/password; cancelling leaves the previous mode in place. When
     * no activity can host the prompt, the change is refused rather than allowed.
     */
    fun requireFullAccessApproval(
        context: Context,
        title: String,
        subtitle: String,
        onApproved: () -> Unit,
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            Toast.makeText(
                context,
                R.string.ui_biometric_authentication_not_supported_on_this_device,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        prompt(activity, title = title, subtitle = subtitle, onSuccess = onApproved)
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
