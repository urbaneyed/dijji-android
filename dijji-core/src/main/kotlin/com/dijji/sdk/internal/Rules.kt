package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pulls /t/rules on foreground + on init. Caches:
 *  - Mobile kill switch (enabled / disabled)
 *  - Rollout percentage
 *  - SDK polling cadence overrides
 *  - The trigger rule set (delivered on-device for low-latency evaluation)
 *
 * The SDK gates itself against the kill switch + rollout before every
 * ingest POST; the server re-enforces the same thing as a backstop.
 */
internal class Rules(
    private val appCtx: AndroidContext,
    private val api: Api,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var mobileEnabled: Boolean = true
    @Volatile var rolloutPct: Int = 100
    @Volatile var pollSeconds: Int = 30
    @Volatile var flushSeconds: Int = 30
    @Volatile var sessionTimeoutMinutes: Int = 30

    fun refreshAsync() {
        scope.launch {
            val payload = api.getRules() ?: return@launch
            @Suppress("UNCHECKED_CAST")
            val mobile = payload["mobile"] as? Map<String, Any?> ?: return@launch
            mobileEnabled = (mobile["enabled"] as? Boolean) ?: true
            rolloutPct   = (mobile["rollout_pct"] as? Number)?.toInt() ?: 100
            pollSeconds  = (mobile["poll_seconds"] as? Number)?.toInt() ?: 30
            flushSeconds = (mobile["flush_seconds"] as? Number)?.toInt() ?: 30
            sessionTimeoutMinutes = (mobile["session_timeout_minutes"] as? Number)?.toInt() ?: 30
            Log.d("Rules refreshed: enabled=$mobileEnabled rollout=$rolloutPct poll=$pollSeconds")
        }
    }
}
