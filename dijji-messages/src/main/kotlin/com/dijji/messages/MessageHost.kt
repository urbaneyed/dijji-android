package com.dijji.messages

import android.app.Activity
import androidx.compose.runtime.Composable

/**
 * Renders an in-app message onto the current foreground activity without
 * the host app writing any UI code.
 *
 * Implementation note — to be written next pass:
 *
 *   1. Hook [androidx.activity.ComponentActivity.addOnNewIntentListener] /
 *      ActivityLifecycleCallbacks to always know the current activity.
 *   2. On a new in-app message from Dijji's inbox poll, grab the activity's
 *      root decor view, find or create a `ComposeView` overlay, and
 *      `setContent { … }` the message composable.
 *   3. Support variants: [Banner] (top/bottom strip), [BottomSheet] (cards),
 *      [Modal] (full-screen). All tokens come from Material 3's theme so the
 *      host app's theme colors carry through.
 *   4. Fire back a /t/fire event with the outcome (dismissed, clicked, etc.).
 *
 * For v0 this is a placeholder so the module compiles and downstream code
 * can reference [show] without actually rendering anything.
 */
public object MessageHost {

    @Composable
    public fun Banner(title: String, body: String) {
        // TODO: Compose implementation.
    }

    @Composable
    public fun BottomSheet(title: String, body: String, ctaLabel: String? = null) {
        // TODO: Compose implementation.
    }

    @Composable
    public fun Modal(title: String, body: String) {
        // TODO: Compose implementation.
    }

    /** Render a message by config — called by the core module's inbox poller. */
    public fun show(activity: Activity, messageConfig: Map<String, Any?>) {
        // TODO: dispatch to Banner/BottomSheet/Modal based on messageConfig["action_type"]
    }
}
