# Dijji Android SDK

Analytics + behavior targeting + crash capture + push + in-app messages — one
SDK, one init call, no extra setup. Android-first (India is ~95% Android); iOS
comes after pilot shakes out.

## Integration — literally two lines

```kotlin
// build.gradle.kts (app level)
dependencies {
    implementation("com.dijji:dijji-core:1.0.0")
}
```

```kotlin
// Application.kt
class KaabilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Dijji.init(this, siteKey = "ws_abc123")
    }
}
```

That's it. Every app open, screen view, session, install attribution, and
crash flows to your Dijji dashboard automatically.

## Public API

```kotlin
Dijji.init(context, siteKey)               // required, once
Dijji.identify(userId, traits = null)      // after login
Dijji.track(event, properties = null)      // custom events
Dijji.screen(name, properties = null)      // rarely — auto-captures
Dijji.reset()                              // on logout
Dijji.flush()                              // force queue send
Dijji.setEnabled(enabled)                  // per-user privacy kill
Dijji.visitorId(): String                  // correlate server-side
```

## Modules

| Module          | What it does                                              | Needed? |
|-----------------|----------------------------------------------------------|---------|
| `dijji-core`    | Events, sessions, auto-capture, install referrer, rules  | Always  |
| `dijji-messages`| Compose renderers for banner / bottom-sheet / modal      | If you want in-app UI |
| `dijji-push`    | FCM glue — token registration + push_received/opened     | If you want pushes |

## What auto-activates from `Dijji.init(…)`

- Session stitching (30-min idle boundary, configurable)
- Screen auto-capture via `ActivityLifecycleCallbacks` + Navigation listener
- Crash capture via `UncaughtExceptionHandler` + NDK signal handler
- Device / OS / network / locale context attached to every event
- Play Install Referrer connection on first launch (one-time)
- In-app message polling when foregrounded
- Remote kill switch + rollout-pct gate (SDK respects; server enforces)

## Advanced config

```kotlin
Dijji.init(this, siteKey = "ws_abc123") {
    autoCaptureScreens = true      // default on
    sessionTimeout = 30.minutes    // industry norm
    maxQueueSize = 500             // offline cap
    captureCrashes = true
    captureInstallReferrer = true
    debugLogging = false           // on for development
}
```

## Build locally

```bash
./gradlew :demo:installDebug
```

Launch the demo, tap buttons, watch events land in
[dijji.com](https://dijji.com) under the pilot site `ws_a2ca27847af21a0bde`
(Kaabil alpha).
