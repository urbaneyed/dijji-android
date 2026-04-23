package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import android.os.Handler
import android.os.Looper
import com.dijji.sdk.Dijji
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Polls /t/inbox when the app is foregrounded and dispatches in-app messages
 * to the dijji-messages module via reflection (so dijji-core has no hard
 * dependency on dijji-messages — host apps that don't use in-app messages
 * don't pull in Material Components).
 *
 * Reflection is one-time per init (MessageHost class is resolved once, cached).
 */
internal class InAppHandler(
    private val appCtx: AndroidContext,
    private val api: Api,
    private val ids: Ids,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollJob: Job? = null

    // Reflection cache — resolved once on first delivered message
    private var messageHostShow: java.lang.reflect.Method? = null
    private var messageHostResolved = false

    fun start(pollSeconds: Int = 30) {
        if (pollJob?.isActive == true) return
        pollJob = coroutineScope.launch {
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
        pushes.forEach { p ->
            val actionType = p["action_type"]?.toString() ?: return@forEach
            val messageId  = p["id"]?.toString() ?: ""
            @Suppress("UNCHECKED_CAST")
            val cfg = (p["action_config"] as? Map<String, Any?>) ?: emptyMap()
            dispatchToMessageHost(messageId, actionType, cfg)
        }
    }

    /**
     * Reflection-call into com.dijji.messages.MessageHost.show(). Soft-fail if
     * the dependency isn't present — app didn't install dijji-messages, we
     * just log and move on. For 'push' actions the server handles FCM
     * delivery separately, so no UI render needed here.
     */
    private fun dispatchToMessageHost(
        messageId: String,
        actionType: String,
        cfg: Map<String, Any?>,
    ) {
        if (!actionType.startsWith("in_app_")) {
            Log.d("Skip dispatch: action=$actionType is server-side")
            return
        }

        if (!messageHostResolved) {
            messageHostResolved = true
            messageHostShow = try {
                val cls = Class.forName("com.dijji.messages.MessageHost")
                val instance = cls.getField("INSTANCE").get(null) // Kotlin object singleton
                cls.methods.firstOrNull { it.name == "show" && it.parameterCount == 4 }
                    ?.also { /* instance method — will invoke on INSTANCE */ }
                    ?: run {
                        Log.w("dijji-messages found but MessageHost.show(4-arg) not visible")
                        null
                    }
            } catch (_: ClassNotFoundException) {
                Log.d("dijji-messages not installed — in-app message skipped ($actionType)")
                null
            } catch (e: Exception) {
                Log.w("dijji-messages resolve failed: ${e.message}")
                null
            }
        }

        val show = messageHostShow ?: return
        mainHandler.post {
            val activity = Dijji.currentActivity()
            if (activity == null) {
                Log.d("No foreground activity — message $messageId deferred")
                return@post
            }
            try {
                val hostInstance = Class.forName("com.dijji.messages.MessageHost")
                    .getField("INSTANCE").get(null)
                show.invoke(hostInstance, activity, messageId, actionType, cfg)
            } catch (e: Exception) {
                Log.w("MessageHost.show threw: ${e.message}")
            }
        }
    }
}
