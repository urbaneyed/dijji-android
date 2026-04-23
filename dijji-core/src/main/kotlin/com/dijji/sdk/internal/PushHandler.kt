package com.dijji.sdk.internal

import android.content.Context as AndroidContext

/**
 * Push token plumbing. The dijji-push module (FCM-aware) calls [registerToken]
 * whenever FirebaseMessagingService.onNewToken fires. If the host app doesn't
 * pull dijji-push in as a dependency, this is never called and push silently
 * no-ops — which is the desired behavior for apps that don't ship pushes.
 */
internal class PushHandler(
    private val appCtx: AndroidContext,
    private val api: Api,
    private val ids: Ids,
) {
    fun registerToken(token: String) {
        if (token.isBlank()) return
        api.postToken(token)
    }
}
