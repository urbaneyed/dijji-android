package com.dijji.sdk

import com.dijji.sdk.internal.Api
import com.dijji.sdk.internal.Context
import com.dijji.sdk.internal.EventQueue
import com.dijji.sdk.internal.Ids
import com.dijji.sdk.internal.InAppHandler
import com.dijji.sdk.internal.InstallReferrer
import com.dijji.sdk.internal.Lifecycle
import com.dijji.sdk.internal.Properties
import com.dijji.sdk.internal.PushHandler
import com.dijji.sdk.internal.Rules
import com.dijji.sdk.internal.Session

/**
 * Internal service locator. Held once by [Dijji] post-init; wired once into
 * sibling modules (dijji-push hooks via [Dijji.scope]).
 *
 * NOT public API — not exposed from the SDK surface.
 */
internal class DijjiScope(
    val config: DijjiConfig,
    val ids: Ids,
    val context: Context,
    val api: Api,
    val queue: EventQueue,
    val session: Session,
    val rules: Rules,
    val inbox: InAppHandler,
    val push: PushHandler,
    val install: InstallReferrer,
    val lifecycle: Lifecycle,
    val properties: Properties,
)
