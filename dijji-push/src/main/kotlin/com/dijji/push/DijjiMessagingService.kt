package com.dijji.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM glue. Host apps that already subclass FirebaseMessagingService should
 * delegate to [DijjiPushDelegate.onNewToken] / [DijjiPushDelegate.onMessage]
 * instead of using this class. Apps without an existing FMS can declare this
 * one directly in their manifest and get Dijji's token registration for free.
 *
 * Implementation next pass:
 *   - On onNewToken, route to Dijji's internal PushHandler.registerToken().
 *   - On onMessageReceived, if the payload carries `dijji: 1` metadata, fire
 *     push_received. Hand the click intent a flag that fires push_opened on
 *     tap (via the activity that handles the deep link).
 *
 * For v0, both methods are stubs so the module compiles without Firebase
 * initialization being required in the demo app.
 */
public open class DijjiMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DijjiPushDelegate.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        DijjiPushDelegate.onMessage(this, message)
    }
}
