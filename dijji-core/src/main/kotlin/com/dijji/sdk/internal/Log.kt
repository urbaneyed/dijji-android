package com.dijji.sdk.internal

import android.util.Log as AndroidLog

internal object Log {
    private const val TAG = "Dijji"

    @Volatile var verbose: Boolean = false

    fun d(msg: String) { if (verbose) AndroidLog.d(TAG, msg) }
    fun i(msg: String) { AndroidLog.i(TAG, msg) }
    fun w(msg: String) { AndroidLog.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { AndroidLog.e(TAG, msg, t) }
}
