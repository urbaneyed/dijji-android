package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import android.content.SharedPreferences
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Persistent user-property store — the "super properties" layer. Whatever
 * gets registered here attaches to every outgoing /t/app/collect batch's
 * `super_properties` field, and the server merges them into the user profile
 * row's trait bag.
 *
 * Typical use:
 *     Dijji.setUserProperty("plan", "pro")
 *     Dijji.setUserProperty("signup_date", "2026-03-12")
 *
 * After that, every event for this install carries plan=pro in its trait
 * bag without the app having to re-send it. Marketer segments like
 * "users on plan=pro AND sessions_count >= 10" become one-row-per-user
 * queries against dijji_app_users, not multi-event joins.
 */
internal class Properties(appCtx: AndroidContext) {

    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences("dijji.props", AndroidContext.MODE_PRIVATE)

    private val moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    @Synchronized
    fun set(key: String, value: Any?) {
        val current = snapshot().toMutableMap()
        if (value == null) current.remove(key) else current[key] = value
        persist(current)
    }

    @Synchronized
    fun setAll(props: Map<String, Any?>) {
        val current = snapshot().toMutableMap()
        for ((k, v) in props) {
            if (v == null) current.remove(k) else current[k] = v
        }
        persist(current)
    }

    @Synchronized
    fun unset(key: String) = set(key, null)

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_BAG).apply()
    }

    fun snapshot(): Map<String, Any?> {
        val raw = prefs.getString(KEY_BAG, null) ?: return emptyMap()
        return runCatching { mapAdapter.fromJson(raw) ?: emptyMap() }.getOrDefault(emptyMap())
    }

    private fun persist(bag: Map<String, Any?>) {
        val json = mapAdapter.toJson(bag)
        prefs.edit().putString(KEY_BAG, json).apply()
    }

    private companion object { const val KEY_BAG = "bag" }
}
