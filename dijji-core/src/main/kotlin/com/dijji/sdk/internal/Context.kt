package com.dijji.sdk.internal

import android.content.Context as AndroidContext
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import java.util.Locale
import java.util.TimeZone

/**
 * Device / app / network / display / power context — the full "rich user
 * data" bag sent with every /t/app/collect batch. Two tiers:
 *
 *   Static (computed once):   appId, appVersion, osVersion, deviceModel,
 *                             deviceMfr, installSource, firstRunTimestamp
 *
 *   Live (read on every snapshot): networkType, carrier, darkMode, orientation,
 *                             battery level/charging, free memory, free disk,
 *                             screen dimensions, fontScale, timezone,
 *                             sessionSequence, daysSinceInstall
 *
 * Marketers segment against these downstream. Targeting a "low-memory Xiaomi
 * user on 3G at 15% battery" becomes a one-line query instead of impossible.
 */
internal class Context(private val appCtx: AndroidContext) {

    private val prefs: SharedPreferences =
        appCtx.getSharedPreferences("dijji.context", AndroidContext.MODE_PRIVATE)

    // ── Static (set once per process) ─────────────────────────────

    val appId: String = appCtx.packageName

    val appVersion: String by lazy {
        runCatching {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    val osVersion: String = Build.VERSION.RELEASE ?: ""
    val deviceModel: String = Build.MODEL ?: ""
    val deviceMfr: String = Build.MANUFACTURER ?: ""
    val locale: String = runCatching { Locale.getDefault().toString() }.getOrDefault("")

    /** Best-effort install source. "play_store" for anything installed via Play. */
    val installSource: String by lazy {
        runCatching {
            val pm = appCtx.packageManager
            val installerPackage: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(appCtx.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(appCtx.packageName)
            }
            when {
                installerPackage == null -> "sideload"
                installerPackage.contains("vending") -> "play_store"
                installerPackage.contains("amazon")  -> "amazon"
                installerPackage.contains("huawei")  -> "huawei"
                installerPackage.contains("samsung") -> "samsung"
                else -> installerPackage
            }
        }.getOrDefault("unknown")
    }

    /** First-launch epoch seconds, persisted. Basis for daysSinceInstall. */
    val firstRunTimestamp: Long by lazy {
        val t = prefs.getLong(KEY_FIRST_RUN, 0L)
        if (t > 0L) t
        else (System.currentTimeMillis() / 1000).also {
            prefs.edit().putLong(KEY_FIRST_RUN, it).apply()
        }
    }

    val carrier: String by lazy {
        runCatching {
            val tm = appCtx.getSystemService(AndroidContext.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkOperatorName ?: ""
        }.getOrDefault("")
    }

    // ── Session sequence — "this is session #N for this install" ─
    // Incremented by [Session] at start(). Read into every event batch so
    // segmentation queries like "users in session >= 5" work natively.

    fun bumpSessionSequence(): Int {
        val next = prefs.getInt(KEY_SESSION_SEQ, 0) + 1
        prefs.edit().putInt(KEY_SESSION_SEQ, next).apply()
        return next
    }

    fun sessionSequence(): Int = prefs.getInt(KEY_SESSION_SEQ, 0)

    fun daysSinceInstall(): Int {
        val secondsSince = (System.currentTimeMillis() / 1000) - firstRunTimestamp
        return (secondsSince / 86400).toInt().coerceAtLeast(0)
    }

    // ── Live readings (recomputed per flush) ──────────────────────

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

    fun hasNetwork(): Boolean {
        val cm = appCtx.getSystemService(AndroidContext.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isDarkMode(): Boolean {
        val mode = appCtx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    fun orientation(): String {
        return when (appCtx.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT  -> "portrait"
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "unknown"
        }
    }

    fun fontScale(): Float = appCtx.resources.configuration.fontScale

    fun timezone(): String = runCatching { TimeZone.getDefault().id }.getOrDefault("")

    fun screenMetrics(): DisplayMetrics = appCtx.resources.displayMetrics

    /** Battery percent 0–100. -1 if unknown. */
    fun batteryLevel(): Int {
        return runCatching {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent: Intent? = appCtx.registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        }.getOrDefault(-1)
    }

    fun isCharging(): Boolean {
        return runCatching {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent: Intent? = appCtx.registerReceiver(null, filter)
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false)
    }

    /** Total device RAM in MB. */
    fun memoryTotalMb(): Int {
        return runCatching {
            val am = appCtx.getSystemService(AndroidContext.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            ((info.totalMem) / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    /** Available RAM in MB. */
    fun memoryFreeMb(): Int {
        return runCatching {
            val am = appCtx.getSystemService(AndroidContext.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            ((info.availMem) / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    /** Free space on the internal storage in MB. */
    fun diskFreeMb(): Int {
        return runCatching {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            (stat.availableBytes / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
    }

    /**
     * The big rich-data snapshot sent with every /t/app/collect call. Grouped
     * keys mirror the columns on dijji_app_users so the server's upsert is
     * a 1:1 pass.
     */
    fun snapshot(): Map<String, Any?> {
        val dm = screenMetrics()
        return mapOf(
            // static
            "os_version"     to osVersion,
            "device_model"   to deviceModel,
            "device_mfr"     to deviceMfr,
            "install_source" to installSource,
            "locale"         to locale,
            "carrier"        to carrier,
            // display
            "screen_width_px"  to dm.widthPixels,
            "screen_height_px" to dm.heightPixels,
            "screen_density"   to dm.density,
            "dark_mode"        to isDarkMode(),
            "font_scale"       to fontScale(),
            "orientation"      to orientation(),
            // live
            "network_type"     to networkType(),
            "timezone"         to timezone(),
            "battery_level"    to batteryLevel(),
            "battery_charging" to isCharging(),
            "memory_total_mb"  to memoryTotalMb(),
            "memory_free_mb"   to memoryFreeMb(),
            "disk_free_mb"     to diskFreeMb(),
            // lifecycle
            "session_sequence"   to sessionSequence(),
            "days_since_install" to daysSinceInstall(),
        )
    }

    private companion object {
        const val KEY_FIRST_RUN   = "first_run_ts"
        const val KEY_SESSION_SEQ = "session_seq"
    }
}
