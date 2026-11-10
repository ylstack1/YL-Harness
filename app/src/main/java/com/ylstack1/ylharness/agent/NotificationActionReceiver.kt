package com.ylstack1.ylharness.agent

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ylstack1.ylharness.AgentService
import com.ylstack1.ylharness.HarnessApp

/**
 * Resolves agent prompts straight from notification action buttons by calling
 * the exact [RunManager] completors the in-app cards use, so resolution
 * semantics (stale-tap no-ops, remember-for-session allowlist, environment
 * install flow) stay identical wherever the answer comes from.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(AgentService.EXTRA_SESSION_ID) ?: return
        // Drop the alert up front so a resolved prompt can't linger; if this
        // broadcast lost a race with an in-app answer, cancelling is still right.
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(sessionId, AgentService.ALERT_NOTIFICATION_ID)

        val runManager = (context.applicationContext as? HarnessApp)?.container?.runManager ?: return

        when (intent.action) {
            ACTION_APPROVE -> runManager.approve(sessionId, rememberForSession = false)
            ACTION_APPROVE_ALWAYS -> runManager.approve(sessionId, rememberForSession = true)
            ACTION_DENY -> runManager.deny(sessionId)
            ACTION_ENV_INSTALL -> runManager.approveEnvironmentInstall(sessionId)
            ACTION_ENV_SKIP -> runManager.denyEnvironmentInstall(sessionId)
            ACTION_ANSWER -> {
                val fromQuickReply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim().orEmpty()
                val fromButton = intent.getStringExtra(EXTRA_ANSWER_TEXT)?.trim().orEmpty()
                val answer = fromQuickReply.ifEmpty { fromButton }
                if (answer.isNotEmpty()) runManager.answerQuestion(sessionId, answer)
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.ylstack1.ylharness.action.APPROVE"
        const val ACTION_APPROVE_ALWAYS = "com.ylstack1.ylharness.action.APPROVE_ALWAYS"
        const val ACTION_DENY = "com.ylstack1.ylharness.action.DENY"
        const val ACTION_ENV_INSTALL = "com.ylstack1.ylharness.action.ENV_INSTALL"
        const val ACTION_ENV_SKIP = "com.ylstack1.ylharness.action.ENV_SKIP"
        const val ACTION_ANSWER = "com.ylstack1.ylharness.action.ANSWER"

        /** Predefined ask_user option carried verbatim on the button's intent. */
        const val EXTRA_ANSWER_TEXT = "answer_text"

        /** RemoteInput key for free-text answers typed into the notification. */
        const val KEY_REPLY_TEXT = "reply_text"
    }
}
