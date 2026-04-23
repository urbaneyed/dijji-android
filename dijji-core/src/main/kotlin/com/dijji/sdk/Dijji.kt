package com.dijji.sdk

import android.app.Application
import android.content.Context
import com.dijji.sdk.internal.Api
import com.dijji.sdk.internal.Bootstrap
import com.dijji.sdk.internal.Context as DjContext
import com.dijji.sdk.internal.EventQueue
import com.dijji.sdk.internal.Ids
import com.dijji.sdk.internal.InAppHandler
import com.dijji.sdk.internal.InstallReferrer
import com.dijji.sdk.internal.Lifecycle
import com.dijji.sdk.internal.Log
import com.dijji.sdk.internal.Properties
import com.dijji.sdk.internal.PushHandler
import com.dijji.sdk.internal.Rules
import com.dijji.sdk.internal.Session
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dijji — the only entry point a host app needs.
 *
 * Integration (verbatim):
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Dijji.init(this, siteKey = "ws_abc123")
 *     }
 * }
 * ```
 *
 * That's it. Everything else — screen auto-capture, session tracking, crash
 * capture, install attribution, push token registration, in-app message
 * rendering — activates automatically. The rest of this object's API (track,
 * identify, reset, etc.) is opt-in.
 *
 * Thread-safety: all public methods are safe to call from any thread. Heavy
 * work is marshalled onto a dedicated dispatcher internally.
 */
public object Dijji {

    private val initialized: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    private var scope: DijjiScope? = null

    /**
     * Initialize the SDK. Safe to call multiple times — subsequent calls no-op.
     *
     * @param context usually `this` from Application.onCreate (any Context works;
     *                we store the application context internally).
     * @param siteKey opaque key from your Dijji dashboard, looks like `ws_abc123`.
     * @param configure optional block for advanced overrides. Most apps pass nothing.
     */
    @JvmStatic
    @JvmOverloads
    public fun init(
        context: Context,
        siteKey: String,
        configure: (DijjiConfig.Builder.() -> Unit)? = null
    ) {
        if (!initialized.compareAndSet(false, true)) {
            Log.d("init() called more than once — ignoring")
            return
        }

        val config = DijjiConfig.Builder(siteKey).also { builder ->
            configure?.invoke(builder)
        }.build()

        val appCtx = context.applicationContext
        val ids = Ids(appCtx)
        val djContext = DjContext(appCtx)
        val api = Api(config, djContext, ids)
        val properties = Properties(appCtx)
        val queue = EventQueue(appCtx, api, properties)
        val session = Session(ids, queue)
        val rules = Rules(appCtx, api)
        val inbox = InAppHandler(appCtx, api, ids)
        val push = PushHandler(appCtx, api, ids)
        val install = InstallReferrer(appCtx, api, ids)
        val lifecycle = Lifecycle(session, queue, rules, inbox)

        scope = DijjiScope(
            config = config,
            ids = ids,
            context = djContext,
            api = api,
            queue = queue,
            session = session,
            rules = rules,
            inbox = inbox,
            push = push,
            install = install,
            lifecycle = lifecycle,
            properties = properties,
        )

        // Wire into process + activity lifecycle so app_open / app_background /
        // screen_view / session_start / session_end all fire without dev help.
        if (appCtx is Application) {
            lifecycle.attach(appCtx)
        }

        // One-shot boot tasks — referrer capture, rules prefetch, token refresh.
        Bootstrap.run(scope!!)

        Log.i("Dijji initialized for site=${config.siteKey} sdk=${config.sdkVersion}")
    }

    /**
     * Attach a user identity to subsequent events. Typically called after login.
     * Traits (optional) are persistent key/value pairs about the user — role,
     * plan, signup date, etc. Previously-anonymous events for this device are
     * NOT retroactively re-attributed; Dijji links them via visitor_id on the
     * server side.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) {
        val s = required() ?: return
        s.ids.setUserId(userId)
        s.queue.enqueue(
            eventName = "\$identify",
            properties = traits,
            screen = null
        )
    }

    /**
     * Fire a custom event. Properties are free-form — use primitives or one-level-
     * nested maps. Strings are truncated server-side at 500 chars per value.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?>? = null) {
        val s = required() ?: return
        s.queue.enqueue(eventName = event, properties = properties, screen = null)
    }

    /**
     * Manually report a screen view. Most apps don't need this — auto-capture
     * from Activity + Navigation destinations covers 95% of cases. Call this
     * when the auto-captured name isn't right.
     */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?>? = null) {
        val s = required() ?: return
        s.queue.enqueue(eventName = "screen_view", properties = properties, screen = name)
    }

    /**
     * Logout. Clears the user_id, rotates visitor_id, drops cached traits.
     * Any queued-but-unsent events are still sent with the OLD visitor_id so
     * we don't lose attribution for the session that just ended.
     */
    @JvmStatic
    public fun reset() {
        val s = required() ?: return
        s.queue.flushNow()
        s.ids.reset()
    }

    /** Force the offline queue to flush to the server now. Useful before a known app exit. */
    @JvmStatic
    public fun flush() {
        required()?.queue?.flushNow()
    }

    /**
     * Per-user privacy kill. When disabled, no events are queued, no rules are
     * fetched, no tokens are sent. State persists across app restarts until
     * re-enabled. The remote kill switch (site-wide, in the Dijji dashboard)
     * is independent and overrides this one.
     */
    @JvmStatic
    public fun setEnabled(enabled: Boolean) {
        val s = required() ?: return
        s.ids.setUserOptedOut(!enabled)
    }

    /**
     * Register a persistent user property — a.k.a. super-property. Attached
     * to every event for this install until cleared. Typical use after login:
     *
     *     Dijji.setUserProperty("plan", "pro")
     *     Dijji.setUserProperty("onboarded_on", "2026-03-12")
     *
     * Marketers can then segment in the Dijji dashboard by those fields
     * without needing the app to re-send them each event.
     */
    @JvmStatic
    public fun setUserProperty(key: String, value: Any?) {
        required()?.properties?.set(key, value)
    }

    /** Bulk version of [setUserProperty]. */
    @JvmStatic
    public fun setUserProperties(properties: Map<String, Any?>) {
        required()?.properties?.setAll(properties)
    }

    /** Remove a previously-registered user property. */
    @JvmStatic
    public fun unsetUserProperty(key: String) {
        required()?.properties?.unset(key)
    }

    /** The opaque per-install visitor id. Useful for server-side correlation. */
    @JvmStatic
    public fun visitorId(): String = required()?.ids?.visitorId() ?: ""

    // ── Internal access for sibling modules (dijji-push, dijji-messages) ──
    internal fun scope(): DijjiScope? = scope

    private fun required(): DijjiScope? {
        val s = scope
        if (s == null) {
            Log.w("SDK call before Dijji.init — ignoring")
        }
        return s
    }
}
