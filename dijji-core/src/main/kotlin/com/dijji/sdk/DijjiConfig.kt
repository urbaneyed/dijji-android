package com.dijji.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime configuration. All values have sensible defaults; a host app only
 * needs to pass the site key. Advanced users can override via the `configure`
 * block on `Dijji.init`.
 */
public class DijjiConfig internal constructor(
    public val siteKey: String,
    public val endpoint: String,
    public val autoCaptureScreens: Boolean,
    public val sessionTimeout: Duration,
    public val flushInterval: Duration,
    public val inboxPollInterval: Duration,
    public val maxQueueSize: Int,
    public val captureCrashes: Boolean,
    public val captureInstallReferrer: Boolean,
    public val debugLogging: Boolean,
    public val sdkVersion: String,
) {

    public class Builder internal constructor(
        /** Opaque site key from the Dijji dashboard (ws_abc123). */
        public val siteKey: String
    ) {
        /** Override the ingest endpoint. Useful for on-prem; defaults to https://dijji.com. */
        public var endpoint: String = BuildConfig.DEFAULT_ENDPOINT

        /** Screen-view auto-capture via Activity lifecycle + NavController. */
        public var autoCaptureScreens: Boolean = true

        /** Time-of-inactivity that ends a session. Default 30 min (industry norm). */
        public var sessionTimeout: Duration = 30.minutes

        /** How often the queue flushes while the app is in foreground. */
        public var flushInterval: Duration = 30.seconds

        /** How often we poll /t/inbox for admin-triggered in-app messages. */
        public var inboxPollInterval: Duration = 30.seconds

        /** Offline cap — once queue reaches this size, we drop oldest events. */
        public var maxQueueSize: Int = 500

        /** Install the UncaughtExceptionHandler chain. Safe to keep on. */
        public var captureCrashes: Boolean = true

        /** Connect to Play Install Referrer on first launch. One-time work. */
        public var captureInstallReferrer: Boolean = true

        /** Verbose logcat output. Turn off for release builds. */
        public var debugLogging: Boolean = false

        internal fun build(): DijjiConfig = DijjiConfig(
            siteKey = siteKey,
            endpoint = endpoint.trimEnd('/'),
            autoCaptureScreens = autoCaptureScreens,
            sessionTimeout = sessionTimeout,
            flushInterval = flushInterval,
            inboxPollInterval = inboxPollInterval,
            maxQueueSize = maxQueueSize.coerceIn(50, 5000),
            captureCrashes = captureCrashes,
            captureInstallReferrer = captureInstallReferrer,
            debugLogging = debugLogging,
            sdkVersion = BuildConfig.SDK_VERSION,
        )
    }
}
