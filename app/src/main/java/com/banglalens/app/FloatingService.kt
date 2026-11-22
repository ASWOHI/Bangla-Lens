package com.banglalens.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Foreground service that owns: the floating magnifier, the region-selection overlay,
 * the MediaProjection capture session and the result panel.
 *
 * Nothing is captured, translated or spoken until the user taps the magnifier AND drags a region.
 * Speech only starts from the Speaker / Repeat buttons.
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_START = "com.banglalens.app.START"
        const val ACTION_STOP = "com.banglalens.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "bangla_lens"
        private const val NOTIF_ID = 1
        private const val TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pipeline = TextPipeline()
    private lateinit var speech: SpeechController
    private var destroyed = false
    private var busy = false

    // capture
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latestImage: Image? = null
    private var latestTime = 0L
    private var screenW = 0
    private var screenH = 0
    private var dpi = 0

    // views
    private var magnifier: ImageView? = null
    private var selection: SelectionView? = null
    private var panel: View? = null
    private lateinit var pProgress: ProgressBar
    private lateinit var pStatus: TextView
    private lateinit var pScroll: MaxHeightScrollView
    private lateinit var pBengali: TextView
    private lateinit var pOriginal: TextView
    private lateinit var pPause: Button
    private var speechButtons: List<Button> = emptyList()
    private var currentBengali = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        speech = SpeechController(
            this,
            onChange = { updateSpeechUi() },
            onMessage = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                if (projection == null) {
                    val code = intent.getIntExtra(EXTRA_CODE, 0)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                    if (data == null || !startProjection(code, data)) {
                        stopSelf(); return START_NOT_STICKY
                    }
                    showMagnifier()
                    ResultStore.setServiceRunning(true)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ foreground + projection

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop), stopPi)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )
    }

    private fun startProjection(code: Int, data: Intent): Boolean {
        return try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: return false
            projection = p
            // Required on Android 14+ before creating a VirtualDisplay.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { if (!destroyed) stopSelf() } }
            }, main)
            readSize()
            val r = newReader()
            reader = r
            display = p.createVirtualDisplay(
                "BanglaLens", screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels; screenH = m.heightPixels
        }
        dpi = resources.displayMetrics.densityDpi
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                latestImage?.close()      // keep only the newest frame
                latestImage = img
                latestTime = SystemClock.uptimeMillis()
            }
        }, main)
        return r
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) return
        dismissSelection()
        hideLensFrame()
        magnifier?.visibility = View.VISIBLE
        readSize()
        latestImage?.close(); latestImage = null
        val old = reader
        val r = newReader()
        display?.resize(screenW, screenH, dpi)
        display?.surface = r.surface
        reader = r
        old?.close()
        panel?.let { p ->
            pScroll.maxHeightPx = (screenH * 0.34).toInt()
            val lp = p.layoutParams as WindowManager.LayoutParams
            lp.width = (screenW * 0.94).toInt()
            try { wm.updateViewLayout(p, lp) } catch (_: Exception) { }
        }
        magnifier?.let { m ->
            val lp = m.layoutParams as WindowManager.LayoutParams
            lp.x = lp.x.coerceIn(0, (screenW - dp(60)).coerceAtLeast(0))
            lp.y = lp.y.coerceIn(0, (screenH - dp(60)).coerceAtLeast(0))
            try { wm.updateViewLayout(m, lp) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ floating magnifier

    private fun showMagnifier() {
        val size = dp(60)
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_float)
            contentDescription = getString(R.string.magnifier_desc)
        }
        val lp = WindowManager.LayoutParams(
            size, size, TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(220)
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        iv.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx; val dy = e.rawY - sy
                        if (!moved && hypot(dx, dy) > slop) moved = true
                        if (moved) {
                            lp.x = (ox + dx).toInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
                            lp.y = (oy + dy).toInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
                            wm.updateViewLayout(v, lp)
                            showLensFrame(lp, size)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) onLensDropped(lp, size)
                        else { v.performClick(); onMagnifierTap() }   // plain tap = drag-select mode
                    }
                    MotionEvent.ACTION_CANCEL -> hideLensFrame()
                }
                return true
            }
        })
        wm.addView(iv, lp)
        magnifier = iv
    }

    // ------------------------------------------------------------------ drag-the-lens mode

    private var lensFrame: View? = null
    private var lensParams: WindowManager.LayoutParams? = null

    /** Area read when the magnifier is dropped: a band centred on the magnifier. */
    private fun lensRect(lp: WindowManager.LayoutParams, size: Int): Rect {
        val cx = lp.x + size / 2
        val cy = lp.y + size / 2
        val fw = minOf(screenW - dp(16), dp(340))
        val fh = dp(110)
        val l = (cx - fw / 2).coerceIn(0, (screenW - fw).coerceAtLeast(0))
        val t = (cy - fh / 2).coerceIn(0, (screenH - fh).coerceAtLeast(0))
        return Rect(l, t, l + fw, t + fh)
    }

    private fun showLensFrame(lp: WindowManager.LayoutParams, size: Int) {
        if (busy || selection != null) return
        val r = lensRect(lp, size)
        val existing = lensFrame
        if (existing == null) {
            val v = LensFrameView(this)
            val fp = WindowManager.LayoutParams(
                r.width(), r.height(), TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = r.left; y = r.top }
            wm.addView(v, fp)
            lensFrame = v; lensParams = fp
        } else {
            lensParams?.let { fp ->
                fp.x = r.left; fp.y = r.top
                try { wm.updateViewLayout(existing, fp) } catch (_: Exception) { }
            }
        }
    }

    private fun hideLensFrame() {
        lensFrame?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        lensFrame = null; lensParams = null
    }

    private fun onLensDropped(lp: WindowManager.LayoutParams, size: Int) {
        val r = lensRect(lp, size)
        hideLensFrame()
        if (busy || selection != null) return
        // Dropping at the far left/right edge just parks the magnifier (no reading).
        val park = dp(12)
        if (lp.x < park || lp.x > screenW - size - park) return
        speech.stop()
        panel?.visibility = View.INVISIBLE
        magnifier?.visibility = View.INVISIBLE
        onRegionSelected(r)
    }

    private fun onMagnifierTap() {
        if (busy || selection != null) return
        speech.stop()
        panel?.visibility = View.INVISIBLE
        magnifier?.visibility = View.INVISIBLE
        val sv = SelectionView(this, onSelected = { onRegionSelected(it) }, onCancel = { onSelectionCancelled() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        selection = sv
        wm.addView(sv, lp)
    }

    private fun dismissSelection() {
        selection?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        selection = null
    }

    private fun onSelectionCancelled() {
        dismissSelection()
        magnifier?.visibility = View.VISIBLE
        panel?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ capture + processing

    private fun onRegionSelected(rect: Rect) {
        dismissSelection()
        busy = true
        val since = SystemClock.uptimeMillis()
        // Wait for the dim overlay to disappear from the captured frames.
        main.postDelayed({ awaitFrame(since, rect, 0) }, 400)
    }

    private fun awaitFrame(since: Long, rect: Rect, attempt: Int) {
        if (destroyed) return
        val img = latestImage
        when {
            img != null && (latestTime >= since || attempt >= 12) -> {
                val bmp = try { cropFrame(img, rect) } catch (e: Exception) { null }
                afterCapture(bmp)
            }
            attempt >= 12 -> afterCapture(null)
            else -> main.postDelayed({ awaitFrame(since, rect, attempt + 1) }, 100)
        }
    }

    private fun cropFrame(img: Image, rect: Rect): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = img.width
        val h = img.height
        val padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        val x = rect.left.coerceIn(0, w - 1)
        val y = rect.top.coerceIn(0, h - 1)
        val cw = (rect.right.coerceIn(0, w) - x).coerceAtLeast(1)
        val ch = (rect.bottom.coerceIn(0, h) - y).coerceAtLeast(1)
        val out = Bitmap.createBitmap(padded, x, y, cw.coerceAtMost(w - x), ch.coerceAtMost(h - y))
        if (out !== padded) padded.recycle()
        return out
    }

    private fun looksBlack(b: Bitmap): Boolean {
        for (i in 0 until 15) for (j in 0 until 15) {
            val px = b.getPixel(((b.width - 1) * i) / 14, ((b.height - 1) * j) / 14)
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val bl = px and 0xFF
            if (r > 10 || g > 10 || bl > 10) return false
        }
        return true
    }

    private fun afterCapture(bmp: Bitmap?) {
        if (destroyed) return
        magnifier?.visibility = View.VISIBLE
        if (bmp == null) {
            busy = false
            showError(getString(R.string.err_capture))
            return
        }
        showLoading()
        scope.launch {
            try {
                val text = pipeline.recognize(bmp)
                if (text.isBlank()) {
                    showError(getString(if (looksBlack(bmp)) R.string.err_protected else R.string.err_no_text))
                } else {
                    val o = pipeline.toBengali(text)
                    ResultStore.setResult(ResultStore.Result(o.original, o.bengali))
                    showResult(o)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NetworkNeededException) {
                showError(getString(R.string.err_network))
            } catch (e: Exception) {
                showError(getString(R.string.err_generic, e.message ?: e.javaClass.simpleName))
            } finally {
                bmp.recycle()
                busy = false
            }
        }
    }

    // ------------------------------------------------------------------ result panel

    private fun ensurePanel(): View {
        panel?.let { return it }
        val themed = ContextThemeWrapper(this, R.style.Theme_BanglaLens)
        val v = LayoutInflater.from(themed).inflate(R.layout.panel_result, null)
        pProgress = v.findViewById(R.id.progress)
        pStatus = v.findViewById(R.id.tvStatus)
        pScroll = v.findViewById(R.id.scroll)
        pBengali = v.findViewById(R.id.tvBengali)
        pOriginal = v.findViewById(R.id.tvOriginal)
        pPause = v.findViewById(R.id.btnPause)
        pScroll.maxHeightPx = (screenH * 0.34).toInt()

        val speaker = v.findViewById<Button>(R.id.btnSpeaker)
        val stop = v.findViewById<Button>(R.id.btnStop)
        val repeat = v.findViewById<Button>(R.id.btnRepeat)
        val copy = v.findViewById<Button>(R.id.btnCopy)
        speechButtons = listOf(speaker, pPause, stop, repeat, copy)

        // Voice starts ONLY from these buttons.
        speaker.setOnClickListener { speech.play(currentBengali) }
        pPause.setOnClickListener {
            if (speech.state == SpeechController.State.PAUSED) speech.resume() else speech.pause()
        }
        stop.setOnClickListener { speech.stop() }
        repeat.setOnClickListener { speech.repeat() }
        copy.setOnClickListener {
            if (currentBengali.isNotBlank()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Bangla Lens", currentBengali))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        v.findViewById<Button>(R.id.btnAgain).setOnClickListener { onMagnifierTap() }
        v.findViewById<View>(R.id.btnClose).setOnClickListener { closePanel() }

        val lp = WindowManager.LayoutParams(
            (screenW * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        wm.addView(v, lp)
        panel = v
        return v
    }

    private fun closePanel() {
        speech.stop()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        currentBengali = ""
    }

    private fun setSpeechEnabled(on: Boolean) {
        speechButtons.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
    }

    private fun showLoading() {
        if (destroyed) return
        ensurePanel().visibility = View.VISIBLE
        pProgress.visibility = View.VISIBLE
        pStatus.setTextColor(0xFF5F6B63.toInt())
        pStatus.text = getString(R.string.working)
        pStatus.visibility = View.VISIBLE
        pScroll.visibility = View.GONE
        currentBengali = ""
        setSpeechEnabled(false)
    }

    private fun showError(msg: String) {
        if (destroyed) return
        ensurePanel().visibility = View.VISIBLE
        pProgress.visibility = View.GONE
        pStatus.setTextColor(0xFFC62828.toInt())
        pStatus.text = msg
        pStatus.visibility = View.VISIBLE
        pScroll.visibility = View.GONE
        currentBengali = ""
        setSpeechEnabled(false)
    }

    private fun showResult(o: TextPipeline.Outcome) {
        if (destroyed) return
        ensurePanel().visibility = View.VISIBLE
        pProgress.visibility = View.GONE
        pStatus.visibility = View.GONE
        pScroll.visibility = View.VISIBLE
        pBengali.text = o.bengali
        pOriginal.text = o.original
        pScroll.scrollTo(0, 0)
        currentBengali = o.bengali
        setSpeechEnabled(true)     // enabled, but NOTHING is spoken until Speaker is pressed
        updateSpeechUi()
    }

    private fun updateSpeechUi() {
        if (panel == null) return
        pPause.setText(
            if (speech.state == SpeechController.State.PAUSED) R.string.btn_resume else R.string.btn_pause
        )
    }

    // ------------------------------------------------------------------ cleanup

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        hideLensFrame()
        dismissSelection()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        magnifier?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null; magnifier = null
        speech.shutdown()
        pipeline.close()
        latestImage?.close(); latestImage = null
        display?.release()
        reader?.close()
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        ResultStore.setServiceRunning(false)
        super.onDestroy()
    }
}
