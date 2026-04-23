package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import android.content.SharedPreferences
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * One-shot Play Install Referrer connection. Only runs once per install — a
 * flag lives in SharedPreferences so we don't re-POST the same referrer on
 * every app_open. The referrer is also the cheapest / most accurate
 * attribution signal Android ships natively (vs. iOS SKAdNetwork).
 */
internal class InstallReferrer(
    private val appCtx: AndroidContext,
    private val api: Api,
    private val ids: Ids,
) {
    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences("dijji.referrer", AndroidContext.MODE_PRIVATE)

    fun captureIfFirstRun() {
        if (prefs.getBoolean(KEY_CAPTURED, false)) return

        val client = InstallReferrerClient.newBuilder(appCtx).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching {
                        val details = client.installReferrer
                        val sent = api.postInstall(
                            referrer = details.installReferrer,
                            clickTs = details.referrerClickTimestampSeconds,
                            installTs = details.installBeginTimestampSeconds,
                        )
                        if (sent) prefs.edit().putBoolean(KEY_CAPTURED, true).apply()
                    }.onFailure { Log.d("Install referrer capture failed: ${it.message}") }
                }
                runCatching { client.endConnection() }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Let the next foreground retry. No explicit reconnect logic —
                // Play Install Referrer is idempotent from the server's
                // perspective (INSERT IGNORE on visitor_id).
            }
        })
    }

    companion object {
        private const val KEY_CAPTURED = "captured"
    }
}
