package com.dijji.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dijji.sdk.Dijji
import com.google.firebase.messaging.RemoteMessage

/**
 * Chain-friendly push delegate. Host apps with an existing
 * FirebaseMessagingService pass their callbacks through here so Dijji and
 * the app coexist.
 *
 * Usage:
 *
 * ```kotlin
 * class MyAppFmsService : FirebaseMessagingService() {
 *     override fun onNewToken(token: String) {
 *         super.onNewToken(token)
 *         DijjiPushDelegate.onNewToken(this, token)
 *         // ...your own code
 *     }
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         super.onMessageReceived(message)
 *         if (DijjiPushDelegate.onMessage(this, message)) return  // Dijji handled it
 *         // ...your own handling
 *     }
 * }
 * ```
 *
 * Apps without an existing FMS can declare [DijjiMessagingService] directly
 * in their manifest and get token registration + notification rendering for
 * free.
 */
public object DijjiPushDelegate {

    private const val CHANNEL_ID = "dijji_default"
    private const val CHANNEL_NAME = "Notifications"

    /** Register the FCM token with Dijji — called from FMS.onNewToken. */
    @JvmStatic
    public fun onNewToken(context: Context, token: String) {
        Dijji.registerPushToken(token)
    }

    /**
     * Handle an FCM message. Returns true if Dijji consumed it — host should
     * short-circuit its own handling in that case. Returns false if the
     * payload is not a Dijji push (host's responsibility).
     *
     * Dijji pushes carry `data["dijji"] = "1"` — anything else is ignored so
     * Dijji only claims messages we sent.
     */
    @JvmStatic
    public fun onMessage(context: Context, message: RemoteMessage): Boolean {
        val data = message.data
        if (data["dijji"] != "1") return false

        val pushId = data["push_id"]
        val triggerId = data["trigger_id"]

        // Fire push_received immediately — before user has tapped
        Dijji.trackPushEvent("push_received", pushId = pushId, triggerId = triggerId)

        // Prefer the notification block from FCM. If the push was data-only
        // (silent), FCM won't auto-render it — so we show one from the data map.
        val title = message.notification?.title ?: data["title"] ?: ""
        val body  = message.notification?.body  ?: data["body"]  ?: ""
        val deepLink = data["deep_link"]

        // If the server sent us a notification block, FCM already drew it.
        // We only draw one ourselves for data-only pushes.
        if (message.notification == null && (title.isNotBlank() || body.isNotBlank())) {
            showNotification(context, title, body, deepLink, pushId)
        }

        return true
    }

    private fun showNotification(
        context: Context,
        title: String,
        body: String,
        deepLink: String?,
        pushId: String?,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }

        // Tap intent — opens the host app or the deep link target if provided.
        // We wrap in our own "push_opened" event firing via a BroadcastReceiver
        // or just fire at tap-time via a start-activity intent that the host
        // will route. Simplest path: launch the deep link or the launcher.
        val tapIntent: Intent = if (!deepLink.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        }
        // Stamp push metadata on the intent so the host app (or Dijji's activity
        // lifecycle hook) can fire push_opened on receipt.
        tapIntent.putExtra("dijji_push_id", pushId ?: "")
        tapIntent.putExtra("dijji_from_push", true)
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            context, (pushId?.hashCode() ?: System.currentTimeMillis().toInt()), tapIntent, flags
        )

        val iconRes = context.applicationInfo.icon
            .takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notifId = (pushId?.hashCode() ?: System.currentTimeMillis().toInt()) and 0x7FFFFFFF
        nm.notify(notifId, notification)
    }
}
