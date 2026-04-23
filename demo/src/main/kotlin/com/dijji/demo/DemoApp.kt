package com.dijji.demo

import android.app.Application
import com.dijji.sdk.Dijji

/**
 * Full Dijji integration. Two lines. Everything else auto-captures.
 *
 * Pointed at the Kaabil site key on dijji.com alpha since that's the pilot
 * instance. Swap for your own when copying this demo to a real app.
 */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Dijji.init(this, siteKey = "ws_a2ca27847af21a0bde") {
            debugLogging = true
        }
    }
}
