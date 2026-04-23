package com.dijji.demo

import android.app.Application
import com.dijji.sdk.Dijji

/**
 * Full Dijji integration. Two lines. Everything else auto-captures.
 *
 * Replace the placeholder site key with your own from
 * https://dijji.com (Site Settings → Mobile).
 */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Dijji.init(this, siteKey = "ws_abc123") {
            debugLogging = true
        }
    }
}
