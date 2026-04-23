package com.dijji.sdk.internal

import java.util.UUID

/**
 * Session lifecycle — rotates session_id after [DijjiConfig.sessionTimeout]
 * of inactivity. The timer is driven by [Lifecycle]'s foreground/background
 * callbacks; Session itself is just the state holder.
 *
 * A fresh session emits `session_start` on the first event; `session_end`
 * is emitted by [Lifecycle] on background + on timeout.
 */
internal class Session(
    private val ids: Ids,
    private val queue: EventQueue,
) {
    @Volatile
    private var currentId: String? = null

    @Volatile
    private var startedAtSeconds: Long = 0

    @Volatile
    private var lastEventAtSeconds: Long = 0

    @Volatile
    private var screensCount: Int = 0

    @Volatile
    private var eventsCount: Int = 0

    /** Returns the active session id, starting a new one if none / expired. */
    @Synchronized
    fun touch(timeoutSeconds: Long): String {
        val now = System.currentTimeMillis() / 1000
        val expired = currentId == null || (now - lastEventAtSeconds) > timeoutSeconds
        if (expired) {
            start()
        }
        lastEventAtSeconds = now
        eventsCount += 1
        return currentId!!
    }

    @Synchronized
    fun start() {
        val id = UUID.randomUUID().toString()
        currentId = id
        startedAtSeconds = System.currentTimeMillis() / 1000
        lastEventAtSeconds = startedAtSeconds
        screensCount = 0
        eventsCount = 0
        queue.bindSession(id)
        queue.enqueue(
            eventName = "session_start",
            properties = null,
            screen = null,
        )
    }

    fun incrementScreens() {
        screensCount += 1
    }

    @Synchronized
    fun end() {
        val id = currentId ?: return
        queue.enqueue(
            eventName = "session_end",
            properties = mapOf(
                "duration_seconds" to (lastEventAtSeconds - startedAtSeconds),
                "screens_count" to screensCount,
                "events_count" to eventsCount,
            ),
            screen = null,
        )
        currentId = null
    }

    fun currentId(): String? = currentId
    fun startedAt(): Long = startedAtSeconds
    fun lastEventAt(): Long = lastEventAtSeconds
    fun screensCount(): Int = screensCount
    fun eventsCount(): Int = eventsCount
}
