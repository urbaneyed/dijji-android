package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Device / app / network context. Captured lazily on each event flush so
 * connection_type reflects current state, not init-time state.
 */
internal class Context(private val appCtx: AndroidContext) {

    val appId: String = appCtx.packageName

    val appVersion: String by lazy {
        runCatching {
            val info = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            info.versionName ?: ""
        }.getOrDefault("")
    }

    val osVersion: String = Build.VERSION.RELEASE ?: ""
    val deviceModel: String = Build.MODEL ?: ""
    val deviceMfr: String = Build.MANUFACTURER ?: ""
    val locale: String = runCatching { Locale.getDefault().toString() }.getOrDefault("")

    val carrier: String by lazy {
        runCatching {
            val tm = appCtx.getSystemService(AndroidContext.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkOperatorName ?: ""
        }.getOrDefault("")
    }

    /** Live connection type — "wifi" / "cellular" / "none". Re-read per flush. */
    fun networkType(): String {
        val cm = appCtx.getSystemService(AndroidContext.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"

        val net = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(net) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    /** Rough "is network available" check — skip flushing when false. */
    fun hasNetwork(): Boolean {
        val cm = appCtx.getSystemService(AndroidContext.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
