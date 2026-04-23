package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Polls /t/inbox when the app is foregrounded and dispatches in-app messages
 * to the `dijji-messages` module (if present). v0 logs the action payload;
 * the Compose renderer lands next session.
 */
internal class InAppHandler(
    private val appCtx: AndroidContext,
    private val api: Api,
    private val ids: Ids,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start(pollSeconds: Int = 30) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                pollOnce()
                delay(pollSeconds * 1000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        val pushes = api.getInbox()
        if (pushes.isEmpty()) return
        Log.d("Inbox: ${pushes.size} action(s) pending")
        // TODO: dispatch to MessageHost when dijji-messages is wired.
    }
}
