package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.UUID

/**
 * Event queue — coalesces events in memory and ships them in batches of up to 50.
 *
 * v0 is in-memory only: events are lost if the process is killed before flush.
 * v1 will add Room-backed persistence (already declared in build.gradle.kts)
 * so offline-queued events survive crashes/reboots. The public API surface
 * (enqueue / flushNow) won't change when persistence lands.
 *
 * Hard cap at [DijjiConfig.maxQueueSize] — when reached, oldest events drop
 * so memory can't grow unbounded during a network outage.
 */
internal class EventQueue(
    private val appCtx: AndroidContext,
    private val api: Api,
    private val properties: Properties,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val buffer: ArrayDeque<Map<String, Any?>> = ArrayDeque()
    private val maxSize: Int = 500 // config read via Dijji on wire; hardcoded for v0

    @Volatile
    private var currentSessionId: String? = null

    fun bindSession(sessionId: String) {
        currentSessionId = sessionId
    }

    fun enqueue(
        eventName: String,
        properties: Map<String, Any?>?,
        screen: String?,
    ) {
        val event = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "name" to eventName,
            "screen" to screen,
            "properties" to properties,
            "ts" to System.currentTimeMillis() / 1000,
        )

        scope.launch {
            mutex.withLock {
                buffer.add(event)
                while (buffer.size > maxSize) buffer.pollFirst()
            }
            // For v0, flush on every enqueue. v1 will switch to timer-based batching.
            flushInternal()
        }
    }

    fun flushNow() {
        scope.launch { flushInternal() }
    }

    private suspend fun flushInternal() {
        val batch: List<Map<String, Any?>> = mutex.withLock {
            if (buffer.isEmpty()) return@withLock emptyList()
            val take = buffer.toList().take(50)
            repeat(take.size) { buffer.pollFirst() }
            take
        }
        if (batch.isEmpty()) return

        val superProps = properties.snapshot().takeIf { it.isNotEmpty() }
        val ok = api.postCollect(batch, currentSessionId, superProps)
        if (!ok) {
            // Requeue at head so order is preserved. If we keep failing, the
            // maxSize cap in enqueue() eventually drops oldest.
            mutex.withLock {
                val reInserted = ArrayDeque<Map<String, Any?>>(batch)
                reInserted.addAll(buffer)
                buffer.clear()
                buffer.addAll(reInserted)
            }
        }
    }
}
