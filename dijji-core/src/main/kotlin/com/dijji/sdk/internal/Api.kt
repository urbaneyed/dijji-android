package com.dijji.sdk.internal

import com.dijji.sdk.DijjiConfig
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client over OkHttp. One instance per [Dijji.init].
 *
 * All endpoints:
 *  - POST application/json (we're not trying to dodge CORS on Android — direct)
 *  - Include X-Dijji-Sdk-Platform + X-Dijji-Sdk-Version headers
 *  - Silent on failure: caller decides whether to retry / requeue
 */
internal class Api(
    private val config: DijjiConfig,
    private val context: Context,
    private val ids: Ids,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val moshi: Moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    /** Collect — batched events. Returns true on 2xx, false otherwise. */
    fun postCollect(
        events: List<Map<String, Any?>>,
        sessionId: String?,
        superProperties: Map<String, Any?>? = null,
    ): Boolean {
        if (events.isEmpty()) return true
        val body = mapOf<String, Any?>(
            "site" to config.siteKey,
            "visitor_id" to ids.visitorId(),
            "user_id" to ids.userId(),
            "session_id" to sessionId,
            "app_id" to context.appId,
            "app_version" to context.appVersion,
            "context" to context.snapshot(),
            "super_properties" to superProperties,
            "events" to events,
        )
        return post("/t/app/collect", body)
    }

    fun postInstall(referrer: String, clickTs: Long?, installTs: Long?): Boolean {
        val body = mapOf<String, Any?>(
            "site" to config.siteKey,
            "visitor_id" to ids.visitorId(),
            "app_id" to context.appId,
            "install_referrer" to referrer,
            "click_ts" to clickTs,
            "install_ts" to installTs,
            "app_version" to context.appVersion,
            "os_version" to context.osVersion,
            "device_model" to context.deviceModel,
        )
        return post("/t/app/install", body)
    }

    fun postToken(token: String): Boolean {
        if (token.isBlank()) return false
        val body = mapOf<String, Any?>(
            "site" to config.siteKey,
            "visitor_id" to ids.visitorId(),
            "user_id" to ids.userId(),
            "app_id" to context.appId,
            "app_version" to context.appVersion,
            "token" to token,
        )
        return post("/t/app/token", body)
    }

    fun postSession(
        sessionId: String,
        startedAt: Long?,
        endedAt: Long?,
        durationSeconds: Int,
        screensCount: Int,
        eventsCount: Int,
        crashed: Boolean,
        fromPush: Boolean,
        deepLink: String?,
    ): Boolean {
        val body = mapOf<String, Any?>(
            "site" to config.siteKey,
            "session_id" to sessionId,
            "visitor_id" to ids.visitorId(),
            "user_id" to ids.userId(),
            "app_id" to context.appId,
            "app_version" to context.appVersion,
            "started_at" to startedAt,
            "ended_at" to endedAt,
            "duration_seconds" to durationSeconds,
            "screens_count" to screensCount,
            "events_count" to eventsCount,
            "crashed" to crashed,
            "from_push" to fromPush,
            "deep_link" to deepLink,
        )
        return post("/t/app/session", body)
    }

    fun postCrash(
        crashType: String,
        reason: String?,
        stackTrace: String,
        breadcrumbs: List<Map<String, Any?>>?,
        state: Map<String, Any?>?,
        sessionId: String?,
    ): Boolean {
        val body = mapOf<String, Any?>(
            "site" to config.siteKey,
            "visitor_id" to ids.visitorId(),
            "user_id" to ids.userId(),
            "session_id" to sessionId,
            "app_id" to context.appId,
            "app_version" to context.appVersion,
            "os_version" to context.osVersion,
            "device_model" to context.deviceModel,
            "crash_type" to crashType,
            "reason" to reason,
            "stack_trace" to stackTrace,
            "breadcrumbs" to breadcrumbs,
            "state" to state,
            "ts" to System.currentTimeMillis() / 1000,
        )
        return post("/t/app/crash", body)
    }

    /** GET /t/rules?site=…&platform=android — returns kill switch + rollout gate. */
    fun getRules(): Map<String, Any?>? {
        val url = "${config.endpoint}/t/rules?site=${config.siteKey}&platform=android"
        val req = baseRequestBuilder(url).get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty()
                mapAdapter.fromJson(raw)
            }
        }.getOrNull()
    }

    /** GET /t/inbox?site=…&platform=android&visitor=… — pending in-app messages. */
    fun getInbox(): List<Map<String, Any?>> {
        val url = "${config.endpoint}/t/inbox?site=${config.siteKey}" +
                "&platform=android&visitor=${ids.visitorId()}"
        val req = baseRequestBuilder(url).get().build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val raw = resp.body?.string().orEmpty()
                val parsed = mapAdapter.fromJson(raw) ?: return@use emptyList()
                @Suppress("UNCHECKED_CAST")
                (parsed["pushes"] as? List<Map<String, Any?>>) ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun post(path: String, body: Map<String, Any?>): Boolean {
        val json = mapAdapter.toJson(body)
        val reqBody = json.toRequestBody(JSON)
        val req = baseRequestBuilder("${config.endpoint}$path")
            .post(reqBody)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) Log.d("POST $path → ${resp.code}")
                ok
            }
        } catch (io: IOException) {
            Log.d("POST $path failed: ${io.message}")
            false
        }
    }

    private fun baseRequestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("X-Dijji-Sdk-Platform", "android")
            .header("X-Dijji-Sdk-Version", config.sdkVersion)
            .header("User-Agent", "Dijji-Android-SDK/${config.sdkVersion}")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
