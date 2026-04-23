package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import com.dijji.sdk.Dijji
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * Uncaught-exception interceptor. Installed by [com.dijji.sdk.DijjiInstaller]
 * at process startup, before the first activity, so a crash during splash
 * still gets captured.
 *
 * Chain behavior: we save the platform's existing default handler (usually
 * the one Android installed to terminate the process) and always invoke it
 * after we've synchronously posted the crash. Dijji does NOT swallow the
 * crash — the app still dies, just with a record on the server.
 *
 * Synchronous POST: we can't queue through EventQueue because the process
 * is about to die; coroutines inside it get killed mid-send. Direct,
 * blocking curl via OkHttp with a short timeout. Worst-case we lose 3s of
 * "dying time" to the crash network call, which matches what Firebase
 * Crashlytics, Bugsnag, Sentry all do.
 */
internal class CrashHandler(
    private val appCtx: AndroidContext,
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private val breadcrumbs: ArrayDeque<Map<String, Any?>> = ArrayDeque()
    private val maxBreadcrumbs = 50

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /** Called by [EventQueue] whenever an event is enqueued — kept as a crumb. */
    @Synchronized
    fun addBreadcrumb(eventName: String, properties: Map<String, Any?>?, screen: String?) {
        breadcrumbs.add(
            mapOf(
                "ts" to System.currentTimeMillis() / 1000,
                "event_name" to eventName,
                "screen" to screen,
                "data" to (properties?.takeIf { it.isNotEmpty() }),
            )
        )
        while (breadcrumbs.size > maxBreadcrumbs) breadcrumbs.removeFirst()
    }

    @Synchronized
    fun breadcrumbsSnapshot(): List<Map<String, Any?>> = breadcrumbs.toList()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            postCrash(throwable)
        } catch (t: Throwable) {
            // Never throw from within the crash handler — that masks the
            // original crash with our own.
            Log.e("CrashHandler post failed: ${t.message}")
        }
        // Chain to the platform handler so the app still terminates
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun postCrash(throwable: Throwable) {
        val scope = Dijji.scope() ?: return

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString().take(60000)

        val crashType = throwable.javaClass.name
        val reason = throwable.message?.take(500)

        val body = mapOf<String, Any?>(
            "site" to scope.config.siteKey,
            "visitor_id" to scope.ids.visitorId(),
            "user_id" to scope.ids.userId(),
            "session_id" to scope.session.currentId(),
            "app_id" to scope.context.appId,
            "app_version" to scope.context.appVersion,
            "os_version" to scope.context.osVersion,
            "device_model" to scope.context.deviceModel,
            "crash_type" to crashType,
            "reason" to reason,
            "stack_trace" to stackTrace,
            "breadcrumbs" to breadcrumbsSnapshot(),
            "state" to mapOf(
                "free_memory_mb" to scope.context.memoryFreeMb(),
                "total_memory_mb" to scope.context.memoryTotalMb(),
                "free_disk_mb" to scope.context.diskFreeMb(),
                "battery_level" to scope.context.batteryLevel(),
                "charging" to scope.context.isCharging(),
                "network" to scope.context.networkType(),
                "thread" to Thread.currentThread().name,
            ),
            "ts" to (System.currentTimeMillis() / 1000),
        )

        val json = mapAdapter.toJson(body)
        val req = Request.Builder()
            .url("${scope.config.endpoint}/t/app/crash")
            .header("X-Dijji-Sdk-Platform", "android")
            .header("X-Dijji-Sdk-Version", scope.config.sdkVersion)
            .header("User-Agent", "Dijji-Android-SDK/${scope.config.sdkVersion}")
            .post(json.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { /* fire-and-forget, body ignored */ }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
