package com.dijji.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Wires ActivityLifecycleCallbacks + ProcessLifecycleOwner so the SDK knows
 * when the app is foregrounded, backgrounded, and when a new screen appears.
 *
 * - Process onStart → app_open, starts the inbox poller
 * - Process onStop  → app_background, flushes queue, stops poller, ends session
 * - Each Activity onResume → screen_view with the activity's simple class name
 *   (Navigation integration lands in a follow-up so fragment routes replace this)
 */
internal class Lifecycle(
    private val session: Session,
    private val queue: EventQueue,
    private val rules: Rules,
    private val inbox: InAppHandler,
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private var foregroundActivity: Activity? = null

    /** Current foreground activity, or null if app is backgrounded. */
    fun foregroundActivity(): Activity? = foregroundActivity

    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // ── ProcessLifecycleOwner (single event per app foreground/background) ──

    override fun onStart(owner: LifecycleOwner) {
        // Foreground. Bump session, fire app_open, prefetch rules, start poll.
        session.touch(timeoutSeconds = 30 * 60)
        queue.enqueue(
            eventName = "app_open",
            properties = null,
            screen = foregroundActivity?.javaClass?.simpleName,
        )
        rules.refreshAsync()
        inbox.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        inbox.stop()
        queue.enqueue(
            eventName = "app_background",
            properties = null,
            screen = foregroundActivity?.javaClass?.simpleName,
        )
        queue.flushNow()
        // Session doesn't end on background — ends on idle timeout instead.
        // For "session = one foreground" semantics, call session.end() here.
    }

    // ── ActivityLifecycleCallbacks (per-screen) ──

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (foregroundActivity === activity) foregroundActivity = null
    }

    override fun onActivityResumed(activity: Activity) {
        foregroundActivity = activity
        session.incrementScreens()

        // push_opened — fired ONCE per activity start if this activity was
        // launched from a Dijji notification tap. dijji-push writes these
        // extras on the PendingIntent; we consume them here so subsequent
        // resumes of the same activity don't re-fire.
        val intent = activity.intent
        if (intent != null && intent.getBooleanExtra("dijji_from_push", false)) {
            val pushId = intent.getStringExtra("dijji_push_id")?.takeIf { it.isNotEmpty() }
            queue.enqueue(
                eventName = "push_opened",
                properties = pushId?.let { mapOf<String, Any?>("push_id" to it) },
                screen = activity.javaClass.simpleName,
            )
            intent.removeExtra("dijji_from_push")
            intent.removeExtra("dijji_push_id")
        }

        queue.enqueue(
            eventName = "screen_view",
            properties = null,
            screen = activity.javaClass.simpleName,
        )
    }
}
