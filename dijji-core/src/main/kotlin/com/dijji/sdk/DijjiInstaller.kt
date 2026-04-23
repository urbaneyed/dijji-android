package com.dijji.sdk

import android.content.Context
import androidx.startup.Initializer
import com.dijji.sdk.internal.Log

/**
 * Auto-run before the first activity. Purpose: stash the application context
 * so the SDK's process-level hooks (crash handler, network monitor) can
 * attach BEFORE the dev calls [Dijji.init]. The actual init still requires
 * a siteKey and happens via the dev's Application.onCreate.
 *
 * Why this exists: an unhandled crash in the splash activity before init()
 * runs would otherwise miss our capture. Hooking at Startup means we're
 * already listening. If the dev never calls init(), we never send anything —
 * the handler just no-ops.
 */
public class DijjiInstaller : Initializer<Unit> {
    override fun create(context: Context) {
        Log.d("DijjiInstaller running — pre-init process hook attached")
        // Placeholder — real process-hook wiring lands with CrashHandler module.
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
