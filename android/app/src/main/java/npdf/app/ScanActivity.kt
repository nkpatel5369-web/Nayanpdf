package npdf.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import npdf.scan.DocDetector
import npdf.scan.Filter
import npdf.scan.Gray
import npdf.scan.Qr
import npdf.scan.Quad
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The document camera. The outline of the page is found live; with
 * auto-capture on, the picture is taken as soon as the page holds steady.
 * Pages go into a scan that is reviewed and edited before saving.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_KIND = "kind"
        const val EXTRA_APPEND = "append"
    }

    private val prefs by lazy { Prefs(this) }
    private lateinit var session: ScanSession
    private var kind = ScanKind.DOCUMENT
    private lateinit var analysisExec: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private lateinit var preview: PreviewView
    private lateinit var overlay: ScanOverlay
    private lateinit var shutter: ShutterButton
    private lateinit var status: TextView
    private lateinit var thumb: ImageView
    private lateinit var count: TextView
    private lateinit var done: TextView
    private lateinit var flashBtn: ImageView
    private lateinit var autoBtn: TextView
    private lateinit var batchBtn: TextView
    private lateinit var qrChip: TextView
    private lateinit var flashCover: View
    private lateinit var modeRow: LinearLayout
    private var blocked: View? = null

    private var auto = true
    private var batch = false
    private var lastQuad: Quad? = null
    private var steadySince = 0L
    private var armed = true
    private var capturing = false
    private var idFront: Part? = null
    private var frames = 0
    private var lastQr: String? = null
    private var analysing = true
    /** When a document was last seen in the picture (for the "No document found" hint). */
    private var lastDocAt = SystemClock.uptimeMillis()
    private lateinit var orderBtn: TextView

    /** For tests: pages captured so far, and whether the camera is ready to take a picture. */
    val pageCount: Int get() = if (::session.isInitialized) session.pages.size else 0
    val cameraReady: Boolean get() = imageCapture != null && camera != null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else showBlocked()
    }

    private val importPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this, darkBars = true)
        analysisExec = Executors.newSingleThreadExecutor()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() })
        auto = prefs.autoCapture
        kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { ScanKind.valueOf(it) }.getOrNull() } ?: ScanKind.DOCUMENT
        buildUi()
        val dir = intent.getStringExtra(EXTRA_SESSION)
        if (dir != null) {
            session = ScanSession.load(File(dir)) ?: ScanSession.create(this)
            begin()
        } else {
            val old = ScanSession.latest(this)?.takeIf { it.pages.isNotEmpty() }
            session = ScanSession.create(this)
            begin()
            if (old != null) lifecycleScope.launch {
                when (Dialogs.ask3(this@ScanActivity, "Continue your last scan?", "${old.pages.size} page(s) from a scan you did not save are still on this phone.",
                    "Continue", "Discard it", "Start new")) {
                    0 -> { session.discard(); session = old; refreshThumb() }
                    1 -> old.discard()
                    else -> {}
                }
            }
        }
    }

    private fun begin() {
        selectMode(kind)
        refreshThumb()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        analysing = false
        analysisExec.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun pill(text: String, onClick: (TextView) -> Unit) = Kit.text(this, text, 14f, 500).apply {
        setTextColor(Color.WHITE)
        background = Kit.rounded(this@ScanActivity, 0x33FFFFFF, 18f)
        minHeight = Kit.dpi(context, 36); gravity = Gravity.CENTER
        setPadding(Kit.dpi(context, 14), Kit.dpi(context, 6), Kit.dpi(context, 14), Kit.dpi(context, 6))
        isClickable = true
        contentDescription = text
        setOnClickListener { Kit.tick(it); onClick(this) }
    }

    private fun buildUi() {
        val ctx = this
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top bar: close, flash, auto/manual, batch.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(ctx, 8), 0, Kit.dpi(ctx, 8), 0) }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(Kit.dpi(ctx, 10), Kit.dpi(ctx, 10), Kit.dpi(ctx, 10), Kit.dpi(ctx, 10))
            contentDescription = "Close"; isClickable = true; setOnClickListener { close() }
        }, LinearLayout.LayoutParams(Kit.dpi(ctx, 48), Kit.dpi(ctx, 48)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        flashBtn = ImageView(this).apply {
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(Kit.dpi(ctx, 11), Kit.dpi(ctx, 11), Kit.dpi(ctx, 11), Kit.dpi(ctx, 11))
            isClickable = true
            setOnClickListener { Kit.tick(it); prefs.flashMode = (prefs.flashMode + 1) % 3; applyFlash() }
        }
        top.addView(flashBtn, LinearLayout.LayoutParams(Kit.dpi(ctx, 48), Kit.dpi(ctx, 48)))
        batchBtn = pill("Batch") { batch = !batch; updatePills(); Kit.banner(this, if (batch) "Batch scanning: turn the pages, N PDF captures each one." else "Batch scanning off") }
        top.addView(batchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Kit.dpi(ctx, 6) })
        orderBtn = pill(if (prefs.bookRightFirst) "Right page first" else "Left page first") {
            prefs.bookRightFirst = !prefs.bookRightFirst
            it.text = if (prefs.bookRightFirst) "Right page first" else "Left page first"
            it.contentDescription = "Page order: " + it.text
        }.apply { visibility = View.GONE; contentDescription = "Page order: $text" }
        top.addView(orderBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Kit.dpi(ctx, 6) })
        autoBtn = pill("Auto") { auto = !auto; prefs.autoCapture = auto; steadySince = 0; updatePills() }
        top.addView(autoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Kit.dpi(ctx, 8) })
        column.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Kit.dpi(ctx, 56)))

        // Camera picture with the outline overlay.
        val stage = FrameLayout(this)
        // PERFORMANCE draws camera frames on their own surface, off the main thread (it falls back to a TextureView only on old camera stacks).
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.PERFORMANCE }
        stage.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = ScanOverlay(this)
        stage.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        qrChip = pill("QR code") { showQr() }.apply { visibility = View.GONE; setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0) }
        stage.addView(qrChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Kit.dpi(ctx, 10) })
        status = Kit.text(this, "Looking for document", 14f, 600).apply {
            setTextColor(Color.WHITE)
            background = Kit.rounded(ctx, 0x8C000000.toInt(), 16f)
            setPadding(Kit.dpi(ctx, 14), Kit.dpi(ctx, 7), Kit.dpi(ctx, 14), Kit.dpi(ctx, 7))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        stage.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Kit.dpi(ctx, 14) })
        preview.setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) focusAt(e.x, e.y); if (e.action == MotionEvent.ACTION_UP) v.performClick(); true }
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Modes.
        val modes = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(Kit.dpi(ctx, 24), 0, Kit.dpi(ctx, 24), 0) }
        for (k in ScanKind.values()) {
            modeRow.addView(Kit.text(this, k.label, 14f, 500).apply {
                tag = k
                setTextColor(0xB3FFFFFF.toInt())
                gravity = Gravity.CENTER; minHeight = Kit.dpi(ctx, 36)
                setPadding(Kit.dpi(ctx, 14), Kit.dpi(ctx, 6), Kit.dpi(ctx, 14), Kit.dpi(ctx, 6))
                contentDescription = k.label
                isClickable = true
                setOnClickListener { Kit.tick(it); selectMode(k) }
            })
        }
        modes.addView(modeRow)
        column.addView(modes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Bottom: last page, shutter, import / done.
        val bottom = FrameLayout(this).apply { setPadding(Kit.dpi(ctx, 24), Kit.dpi(ctx, 8), Kit.dpi(ctx, 24), Kit.dpi(ctx, 18)) }
        val thumbBox = FrameLayout(this).apply {
            isClickable = true; contentDescription = "Review pages"; setOnClickListener { finishToReview() }
            setOnLongClickListener { Kit.tick(it); deleteLast(); true }
        }
        thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Kit.rounded(ctx, 0xFF2C2C2E.toInt(), 6f, Color.WHITE, 1.5f); clipToOutline = true }
        thumbBox.addView(thumb, FrameLayout.LayoutParams(Kit.dpi(ctx, 52), Kit.dpi(ctx, 52), Gravity.CENTER))
        count = Kit.text(this, "", 12f, 700).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = Kit.rounded(ctx, Kit.color(ctx, R.color.tint), 11f)
            minWidth = Kit.dpi(ctx, 22); minHeight = Kit.dpi(ctx, 22)
            setPadding(Kit.dpi(ctx, 5), 0, Kit.dpi(ctx, 5), 0)
        }
        thumbBox.addView(count, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        bottom.addView(thumbBox, FrameLayout.LayoutParams(Kit.dpi(ctx, 64), Kit.dpi(ctx, 64), Gravity.START or Gravity.CENTER_VERTICAL))
        shutter = ShutterButton(this).apply { setOnClickListener { capture() } }
        bottom.addView(shutter, FrameLayout.LayoutParams(Kit.dpi(ctx, 78), Kit.dpi(ctx, 78), Gravity.CENTER))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        right.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_gallery); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(Kit.dpi(ctx, 10), Kit.dpi(ctx, 10), Kit.dpi(ctx, 10), Kit.dpi(ctx, 10))
            contentDescription = "Import photos"; isClickable = true
            setOnClickListener { importPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }, LinearLayout.LayoutParams(Kit.dpi(ctx, 46), Kit.dpi(ctx, 46)))
        done = Kit.text(this, "Done", 14f, 500).apply {
            setTextColor(Color.BLACK); contentDescription = "Done"; isClickable = true
            background = Kit.rounded(ctx, Color.WHITE, 18f); gravity = Gravity.CENTER; minHeight = Kit.dpi(ctx, 36)
            setPadding(Kit.dpi(ctx, 16), Kit.dpi(ctx, 6), Kit.dpi(ctx, 16), Kit.dpi(ctx, 6))
            setOnClickListener { finishToReview() }
        }
        right.addView(done)
        bottom.addView(right, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(bottom)

        flashCover = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false }
        root.addView(flashCover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Ui.fitSystemBars(root)
        updatePills()
        applyFlash()
    }

    private fun updatePills() {
        autoBtn.text = if (auto) "Auto" else "Manual"
        autoBtn.contentDescription = if (auto) "Auto-capture on" else "Auto-capture off"
        batchBtn.background = Kit.rounded(this, if (batch) Color.WHITE else 0x33FFFFFF, 18f)
        batchBtn.setTextColor(if (batch) Color.BLACK else Color.WHITE)
        if (!auto) shutter.progress = 0f
    }

    private fun applyFlash() {
        val m = prefs.flashMode
        flashBtn.setImageResource(when (m) { 0 -> R.drawable.ic_flash_off; 1 -> R.drawable.ic_flash; else -> R.drawable.ic_flash_auto })
        flashBtn.contentDescription = when (m) { 0 -> "Flash off"; 1 -> "Flash on"; else -> "Flash automatic" }
        imageCapture?.flashMode = when (m) { 0 -> ImageCapture.FLASH_MODE_OFF; 1 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_AUTO }
    }

    private fun selectMode(k: ScanKind) {
        kind = k
        for (i in 0 until modeRow.childCount) {
            val v = modeRow.getChildAt(i) as TextView
            v.setTextColor(if (v.tag == k) Color.BLACK else 0xB3FFFFFF.toInt())
            v.background = if (v.tag == k) Kit.rounded(this, Color.WHITE, 18f) else null
        }
        overlay.bookDivider = k == ScanKind.BOOK
        overlay.cardGuide = k == ScanKind.IDCARD
        overlay.invalidate()
        orderBtn.visibility = if (k == ScanKind.BOOK) View.VISIBLE else View.GONE
        lastQuad = null; steadySince = 0; armed = true; lastDocAt = SystemClock.uptimeMillis()
        status.text = when (k) {
            ScanKind.WHITEBOARD -> "The whole picture is captured"
            ScanKind.BOOK -> "Line up the middle of the book with the dotted line"
            ScanKind.IDCARD -> if (idFront == null) "Front of the card" else "Back of the card"
            else -> "Looking for document"
        }
    }

    private fun refreshThumb() {
        val n = session.pages.size + if (idFront != null) 1 else 0
        count.text = n.toString()
        count.visibility = if (n > 0) View.VISIBLE else View.GONE
        done.visibility = if (n > 0) View.VISIBLE else View.INVISIBLE
        val last = session.pages.lastOrNull()
        if (last == null) { thumb.setImageDrawable(null); return }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) { runCatching { ScanProcessor.thumb(session, last) }.getOrNull() }
            bmp?.let { thumb.setImageBitmap(it) }
        }
    }

    private fun showBlocked() {
        val ctx = this
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK); setPadding(Kit.dpi(ctx, 32), 0, Kit.dpi(ctx, 32), 0)
            addView(Kit.text(ctx, "Camera access is off", 22f, 700).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER })
            addView(Kit.text(ctx, "N PDF uses the camera only to photograph documents. Pictures stay on this phone.", 15f).apply {
                setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, Kit.dpi(ctx, 8), 0, Kit.dpi(ctx, 20))
            })
            addView(com.google.android.material.button.MaterialButton(ctx).apply {
                text = "Allow camera"
                setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
            })
            addView(Kit.textButton(ctx, "Import photos instead") { importPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        }
        (window.decorView.findViewById<ViewGroup>(android.R.id.content)).addView(box)
        blocked = box
    }

    override fun onResume() {
        super.onResume()
        // The border editor (opened after a capture) saves the scan itself: take its version.
        if (::session.isInitialized && !capturing) ScanSession.load(session.dir)?.let { if (it.pages.size >= session.pages.size) { session = it; refreshThumb() } }
        if (blocked != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            (blocked?.parent as? ViewGroup)?.removeView(blocked); blocked = null
            startCamera()
        }
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val prev = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()).build()
                prev.setSurfaceProvider(preview.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExec) { analyze(it) }
                val cap = ImageCapture.Builder()
                    // About 12 MP: scans are worked at ScanProcessor.PHOTO_MAX (3200 px), so a 50 or 200 MP
                    // capture would only slow the shutter and use memory.
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = cap
                applyFlash()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis, cap)
            } catch (e: Exception) {
                status.text = "The camera is not available. Import photos instead."
                android.util.Log.w("NPDF-SCAN", "camera: $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
    }

    /** Runs on the analysis thread for each camera frame (only the latest one is kept). */
    private fun analyze(img: ImageProxy) {
        try {
            if (!analysing) return
            val rot = img.imageInfo.rotationDegrees
            val plane = img.planes[0]
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            val w = img.width; val h = img.height
            val k = kind
            val res = if (k == ScanKind.WHITEBOARD) null else DocDetector.detect(Gray.fromLuminance(bytes, w, h, plane.rowStride),
                minArea = if (k == ScanKind.BUSINESSCARD || k == ScanKind.IDCARD) 0.05f else 0.10f, curvedEdges = k == ScanKind.BOOK)
            val upright = res?.let { DocDetector.rotateQuad(it.quad, w, h, rot) }
            val fw = if (rot % 180 == 0) w else h
            val fh = if (rot % 180 == 0) h else w
            val qr = if (k == ScanKind.DOCUMENT && frames++ % 6 == 0) Qr.decode(bytes, w, h, plane.rowStride) else null
            runOnUiThread { onFrame(upright, fw, fh, qr) }
        } catch (e: Exception) {
            android.util.Log.w("NPDF-SCAN", "analysis: $e")
        } finally {
            img.close()
        }
    }

    /** Sets the status line only when it changes: an unchanged setText still re-lays out the screen on every camera frame. */
    private fun say(t: String) { if (status.text.toString() != t) status.text = t }

    private fun onFrame(q: Quad?, fw: Int, fh: Int, qr: String?) {
        if (isFinishing) return
        overlay.frameW = fw; overlay.frameH = fh
        overlay.show(q)
        if (qr != null && qr != lastQr) { lastQr = qr; qrChip.text = if (Qr.isWebLink(qr)) "QR code: link" else "QR code"; qrChip.visibility = View.VISIBLE }
        if (kind == ScanKind.WHITEBOARD) { shutter.progress = 0f; return }
        val now = SystemClock.uptimeMillis()
        if (q == null) {
            lastQuad = null; steadySince = 0L; shutter.progress = 0f
            armed = true
            if (!capturing) say(when {
                kind == ScanKind.IDCARD -> if (idFront == null) "Front of the card" else "Back of the card"
                now - lastDocAt > 8000 -> "No document found. Tap the shutter to capture."
                else -> "Looking for document"
            })
            return
        }
        lastDocAt = now
        val prev = lastQuad
        val tol = DocDetector.stabilityTolerance(fw, fh)
        if (prev != null && q.maxCornerDistance(prev) < tol) { if (steadySince == 0L) steadySince = now }
        else {
            steadySince = 0L
            if (!armed && prev != null && q.maxCornerDistance(prev) > 0.15f * minOf(fw, fh)) armed = true
        }
        lastQuad = q
        if (capturing) return
        if (!auto) { say("Document found: tap the shutter"); return }
        if (!armed) { say(if (batch) "Turn to the next page" else "Captured"); shutter.progress = 0f; return }
        val need = if (batch) 600 else 900
        val held = if (steadySince > 0) now - steadySince else 0
        shutter.progress = held.toFloat() / need
        say(if (held > 0) "Hold steady" else "Document found")
        if (held >= need) capture()
    }

    // ------------------------------------------------------------------ capture

    private fun capture() {
        val ic = imageCapture
        if (capturing) return
        if (ic == null) { Kit.banner(this, "The camera is not ready yet."); return }
        capturing = true
        armed = false
        shutter.progress = 0f
        status.text = "Capturing…"
        Kit.tick(shutter)
        flashCover.alpha = 0.7f
        flashCover.animate().alpha(0f).setDuration(250).start()
        val previewQuad = lastQuad
        val fw = overlay.frameW
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                image.close()
                lifecycleScope.launch {
                    try {
                        val part = withContext(Dispatchers.Default) {
                            val bmp = decodeJpeg(bytes, rot)
                            if (prefs.saveOriginals) {
                                val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                                val stamp = npdf.scan.TextInfo.defaultName(prefs.scanName, java.time.LocalDateTime.now())
                                launch(Dispatchers.IO) { runCatching { ScanStore.saveOriginal(this@ScanActivity, jpeg, stamp) } }
                            }
                            val p = ScanProcessor.addPhoto(session, bmp, kind)
                            if (p.quad == null && previewQuad != null && kind != ScanKind.WHITEBOARD && fw > 0) p.quad = previewQuad.scaled(bmp.width.toFloat() / fw)
                            bmp.recycle()
                            p
                        }
                        added(part)
                    } catch (e: Throwable) {
                        Dialogs.message(this@ScanActivity, "Could not use the picture", Ui.describe(e))
                    } finally {
                        capturing = false
                    }
                }
            }

            override fun onError(e: ImageCaptureException) {
                capturing = false
                Kit.banner(this@ScanActivity, "The picture could not be taken (${e.message ?: "camera error"}).")
            }
        })
    }

    /** Decodes a camera JPEG, turned upright, at most the scan's working size. */
    private fun decodeJpeg(bytes: ByteArray, rotation: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ScanProcessor.PHOTO_MAX) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw npdf.engine.PdfError(npdf.engine.Codes.INVALID, "The picture could not be read.")
        val m = android.graphics.Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val s = ScanProcessor.PHOTO_MAX.toFloat() / maxOf(bmp.width, bmp.height)
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return bmp
    }

    private fun added(part: Part) {
        val filter = runCatching { Filter.valueOf(prefs.defaultFilter) }.getOrDefault(Filter.AUTO)
        var newPage: ScanPage? = null
        when (kind) {
            ScanKind.BOOK -> {
                val left = ScanProcessor.newPage(kind, part, filter, half = 1)
                val right = ScanProcessor.newPage(kind, Part(part.file, part.quad, part.w, part.h), filter, half = 2)
                if (prefs.bookRightFirst) { session.pages.add(right); session.pages.add(left) } else { session.pages.add(left); session.pages.add(right) }
                newPage = left
            }
            ScanKind.IDCARD -> {
                val front = idFront
                if (front == null) {
                    idFront = part
                    status.text = "Now the back of the card"
                    Kit.banner(this, "Front captured. Turn the card over, or tap Done for the front only.")
                } else {
                    val p = ScanPage(java.util.UUID.randomUUID().toString(), kind, mutableListOf(front, part), filter = filter)
                    session.pages.add(p); idFront = null; newPage = p
                    status.text = "Front of the card"
                }
            }
            else -> { newPage = ScanProcessor.newPage(kind, part, filter); session.pages.add(newPage) }
        }
        session.kind = kind
        session.save()
        refreshThumb()
        // As in Adobe Scan: each page opens for review (crop, filters, page order), with Keep scanning to
        // come back for the next one. Batch scanning keeps capturing instead.
        if (newPage != null && prefs.adjustAfterCapture && !batch) finishToReview()
    }

    /** Long press on the pages thumbnail: remove the last capture. */
    private fun deleteLast() = lifecycleScope.launch {
        if (session.pages.isEmpty() && idFront == null) { Kit.banner(this@ScanActivity, "No pages captured yet."); return@launch }
        if (!Dialogs.confirm(this@ScanActivity, "Delete the last page?", "${session.pages.size + (if (idFront != null) 1 else 0)} page(s) captured so far.", "Delete")) return@launch
        if (idFront != null) idFront = null else session.pages.removeAt(session.pages.lastIndex)
        session.save(); refreshThumb()
        Kit.banner(this@ScanActivity, "Last page deleted")
    }

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val filter = runCatching { Filter.valueOf(prefs.defaultFilter) }.getOrDefault(Filter.AUTO)
            status.text = "Importing ${uris.size} photo(s)…"
            try {
                for (u in uris) {
                    val part = withContext(Dispatchers.Default) {
                        val bmp = ScanProcessor.decode({ contentResolver.openInputStream(u) ?: throw java.io.FileNotFoundException(u.toString()) })
                        ScanProcessor.addPhoto(session, bmp, if (kind == ScanKind.WHITEBOARD) kind else ScanKind.DOCUMENT).also { bmp.recycle() }
                    }
                    session.pages.add(ScanProcessor.newPage(if (kind == ScanKind.WHITEBOARD) kind else ScanKind.DOCUMENT, part, filter))
                }
                session.save()
                finishToReview()
            } catch (e: Throwable) {
                Dialogs.message(this@ScanActivity, "Could not import", Ui.describe(e))
            }
        }
    }

    // ------------------------------------------------------------------ QR, leaving

    private fun showQr() = lifecycleScope.launch {
        val v = lastQr ?: return@launch
        val items = if (Qr.isWebLink(v)) listOf("Copy", "Open in browser", "Share") else listOf("Copy", "Share")
        when (items.getOrNull(Dialogs.choose(this@ScanActivity, v, items) ?: return@launch)) {
            "Copy" -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QR code", v)); Kit.banner(this@ScanActivity, "Copied") }
            "Open in browser" -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v))) }
            "Share" -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v), "Share"))
        }
    }

    private fun finishToReview() {
        idFront?.let { f ->
            val filter = runCatching { Filter.valueOf(prefs.defaultFilter) }.getOrDefault(Filter.AUTO)
            session.pages.add(ScanPage(java.util.UUID.randomUUID().toString(), ScanKind.IDCARD, mutableListOf(f), filter = filter))
            idFront = null
        }
        if (session.pages.isEmpty()) { Kit.banner(this, "Capture a page first."); return }
        session.save()
        if (!intent.getBooleanExtra(EXTRA_APPEND, false)) {
            startActivity(Intent(this, ScanReviewActivity::class.java).putExtra(ScanReviewActivity.EXTRA_SESSION, session.dir.absolutePath))
        } else setResult(RESULT_OK)
        finish()
    }

    private fun close() {
        if (session.pages.isEmpty() && idFront == null) { session.discard(); finish(); return }
        if (intent.getBooleanExtra(EXTRA_APPEND, false)) { finishToReview(); return }
        lifecycleScope.launch {
            when (Dialogs.ask3(this@ScanActivity, "Leave the scanner?", "You have ${session.pages.size} page(s) not saved yet.", "Review pages", "Discard", "Cancel")) {
                0 -> finishToReview()
                1 -> { session.discard(); finish() }
            }
        }
    }


    @Suppress("unused")
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
