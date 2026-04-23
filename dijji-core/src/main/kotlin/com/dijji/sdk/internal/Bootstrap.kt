package com.dijji.sdk.internal

import com.dijji.sdk.DijjiScope

/**
 * One-shot boot tasks fired from [Dijji.init]. Kept out of Dijji.kt so the
 * public facade stays readable — this is all the async work that has to
 * happen on startup.
 */
internal object Bootstrap {
    fun run(scope: DijjiScope) {
        // Prefetch rules so kill switch / rollout are enforced from the very
        // first event (not eventually-consistent).
        scope.rules.refreshAsync()

        // Capture install referrer if this is the first-ever launch on this
        // device. No-op on subsequent launches.
        if (scope.config.captureInstallReferrer) {
            scope.install.captureIfFirstRun()
        }

        // Log verbose mode so Log.d output appears when debugLogging = true
        Log.verbose = scope.config.debugLogging
    }
}
