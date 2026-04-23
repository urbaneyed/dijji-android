package com.dijji.push

import android.content.Context
import com.google.firebase.messaging.RemoteMessage

/**
 * Chain-friendly push delegate. Host apps with an existing
 * FirebaseMessagingService pass their callbacks through here so Dijji and
 * the app coexist. Usage:
 *
 * ```kotlin
 * class MyAppFmsService : FirebaseMessagingService() {
 *     override fun onNewToken(token: String) {
 *         super.onNewToken(token)
 *         DijjiPushDelegate.onNewToken(this, token)
 *         // ...existing code
 *     }
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         super.onMessageReceived(message)
 *         if (DijjiPushDelegate.onMessage(this, message)) return  // Dijji handled it
 *         // ...existing code
 *     }
 * }
 * ```
 *
 * Returns true from onMessage when Dijji consumed the payload; host should
 * short-circuit to avoid double-handling.
 */
public object DijjiPushDelegate {

    public fun onNewToken(context: Context, token: String) {
        // TODO: Reach into dijji-core's PushHandler to POST the token.
        // Wiring: Dijji.scope()?.push?.registerToken(token)
    }

    /** Returns true if this was a Dijji-authored push and we consumed it. */
    public fun onMessage(context: Context, message: RemoteMessage): Boolean {
        val data = message.data
        val isDijji = data["dijji"] == "1"
        if (!isDijji) return false
        // TODO: fire push_received event; show notification via NotificationCompat
        //       using data["title"] / data["body"] / data["deep_link"] etc.
        return true
    }
}
