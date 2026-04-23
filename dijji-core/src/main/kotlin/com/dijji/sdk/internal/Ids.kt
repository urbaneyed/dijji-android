package com.dijji.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Identity store — visitor_id (stable per install), user_id (post-identify),
 * session_id (ephemeral, rotates on [Session]'s timeout), opt-out flag.
 *
 * Stored in a private SharedPreferences file keyed with a short name to keep
 * the app's XML dir tidy. No PII lands here — visitor_id is a random UUID,
 * user_id is whatever the dev chose to pass to identify().
 */
internal class Ids(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dijji.ids", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_VISITOR_ID)) {
            prefs.edit().putString(KEY_VISITOR_ID, UUID.randomUUID().toString()).apply()
        }
    }

    fun visitorId(): String = prefs.getString(KEY_VISITOR_ID, null)
        ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_VISITOR_ID, it).apply()
        }

    fun userId(): String? = prefs.getString(KEY_USER_ID, null)

    fun setUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun userOptedOut(): Boolean = prefs.getBoolean(KEY_OPT_OUT, false)

    fun setUserOptedOut(optedOut: Boolean) {
        prefs.edit().putBoolean(KEY_OPT_OUT, optedOut).apply()
    }

    /** Clear user + rotate visitor. Called from Dijji.reset() on logout. */
    fun reset() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .putString(KEY_VISITOR_ID, UUID.randomUUID().toString())
            .apply()
    }

    companion object {
        private const val KEY_VISITOR_ID = "visitor_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_OPT_OUT = "opt_out"
    }
}
