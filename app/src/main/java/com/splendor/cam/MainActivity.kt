package com.splendor.cam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/**
 * SplendorCam v1 — RAW burst capture scaffold.
 *
 * Proven design (Camera Probe on the Redmi 15C, 2026-11-12): both cameras are
 * LEVEL_3 with RAW + manual sensor + burst. Back RAW is 4080x3072, front
 * 3264x2448. A RAW frame is ~25MB, so the burst streams one frame at a time:
 * each Image is written to a DNG and closed as soon as it arrives, never
 * held as a whole burst in memory. Max exposure is 0.4s/frame.
 *
 * v1 scope: live preview, back/front switch, 5-frame RAW burst saved as DNG
 * files in Pictures/SplendorCam, manual ISO + exposure sliders, auto mode.
 * No processing pipeline yet — that is the next round, built on this.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "SplendorCam"
        private const val REQUEST_CAMERA = 1
        private const val BURST_FRAMES = 5
    }

    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView
    private lateinit var isoLabel: TextView
    private lateinit var expLabel: TextView
    private lateinit var isoSeek: SeekBar
    private lateinit var expSeek: SeekBar
    private lateinit var shutterButton: Button
    private lateinit var modeButton: Button

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String? = null
    private var facing = CameraCharacteristics.LENS_FACING_BACK

    private var isoRange: Range<Int>? = null
    private var expRange: Range<Long>? = null
    private var rawSize: Size? = null
    private var previewSize: Size? = null
    private var imageReader: ImageReader? = null
    private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF

    private var manualMode = false
    private var isoValue = 400
    private var expValueNs = 10_000_000L // 10 ms default
    private var capturing = false
    private var burstSaved = 0
    private var burstFinished = 0
    private var burstId = 0
    private val pendingImages = ArrayDeque<Image>()
    private val pendingResults = ArrayDeque<TotalCaptureResult>()
    private val lock = Any()

    // ---------- processing state (round 1) ----------
    private lateinit var processButton: Button
    private val lookButtons = ArrayList<Button>()
    private var selectedLook: Look = Looks.NATURAL
    private val lastBurstUris = ArrayList<Uri>()
    private var lastBurstFacing = CameraCharacteristics.LENS_FACING_BACK
    private var lastBurstIso = 400
    private var processing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textureView = TextureView(this)
        textureView.surfaceTextureListener = textureListener

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }
        shutterButton = Button(this).apply {
            text = "Capture RAW burst (5)"
            setOnClickListener { captureBurst() }
        }
        val switchButton = Button(this).apply {
            text = "Switch camera"
            setOnClickListener { switchCamera() }
        }
        modeButton = Button(this).apply {
            text = "Mode: Auto"
            setOnClickListener { toggleMode() }
        }
        isoLabel = TextView(this).apply { textSize = 14f }
        expLabel = TextView(this).apply { textSize = 14f }
        isoSeek = SeekBar(this).apply {
            max = 100
            isEnabled = false
            setOnSeekBarChangeListener(seekListener(isIso = true))
        }
        expSeek = SeekBar(this).apply {
            max = 100
            isEnabled = false
            setOnSeekBarChangeListener(seekListener(isIso = false))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shutterButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(modeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Round 1: look selector + process button, enabled after a burst.
        val lookRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (look in Looks.ALL) {
            val b = Button(this).apply {
                text = look.id.replaceFirstChar { it.uppercase() }
                isEnabled = false
                setOnClickListener {
                    selectedLook = look
                    updateLookButtons()
                }
            }
            lookButtons.add(b)
            lookRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        processButton = Button(this).apply {
            text = "Process burst"
            isEnabled = false
            setOnClickListener { processBurst() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(textureView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(statusView)
            addView(buttonRow)
            addView(isoLabel)
            addView(isoSeek)
            addView(expLabel)
            addView(expSeek)
            addView(lookRow)
            addView(processButton)
        }
        setContentView(layout)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            setStatus("Camera permission denied — SplendorCam cannot run without it.")
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && cameraDevice == null && cameraId != null
        ) {
            openCamera()
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // ---------- camera setup ----------

    private fun setupCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager = manager
        val id = chooseCamera(manager, facing)
        if (id == null) {
            setStatus("No camera found for this facing.")
            return
        }
        cameraId = id
        val c = try {
            manager.getCameraCharacteristics(id)
        } catch (e: CameraAccessException) {
            setStatus("Cannot read camera: ${e.message}")
            return
        }
        characteristics = c
        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull()
        previewSize = choosePreviewSize(map)
        afMode = chooseAfMode(c)
        isoRange?.let { isoValue = isoValue.coerceIn(it.lower, it.upper) }
        expRange?.let { expValueNs = expValueNs.coerceIn(it.lower, it.upper) }
        updateLabels()
        syncSeekPositions()
        setStatus(
            "Camera ${if (facing == CameraCharacteristics.LENS_FACING_BACK) "back" else "front"} ready — " +
                "RAW ${rawSize ?: "unavailable"}, ISO ${isoRange ?: "?"}, " +
                "exposure ${expRange ?: "?"} ns"
        )
        if (textureView.isAvailable) openCamera()
    }

    private fun chooseCamera(manager: CameraManager, wantFacing: Int): String? {
        val ids = try {
            manager.cameraIdList
        } catch (e: CameraAccessException) {
            return null
        }
        for (id in ids) {
            val c = try {
                manager.getCameraCharacteristics(id)
            } catch (e: CameraAccessException) {
                continue
            }
            if (c.get(CameraCharacteristics.LENS_FACING) == wantFacing) return id
        }
        return ids.firstOrNull()
    }

    private fun choosePreviewSize(map: StreamConfigurationMap?): Size? {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return null
        // Largest size with width <= 1920 keeps the preview light on 4GB RAM.
        return sizes.firstOrNull { it.width <= 1920 } ?: sizes.firstOrNull()
    }

    private fun chooseAfMode(c: CameraCharacteristics): Int {
        val modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return CaptureRequest.CONTROL_AF_MODE_OFF
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    private fun openCamera() {
        val manager = cameraManager ?: return
        val id = cameraId ?: return
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(id, deviceCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            setStatus("Cannot open camera: ${e.message}")
        } catch (e: SecurityException) {
            setStatus("Camera permission missing.")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            closeCamera()
            setStatus("Camera disconnected.")
        }

        override fun onError(device: CameraDevice, error: Int) {
            closeCamera()
            setStatus("Camera error $error.")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val pSize = previewSize ?: return
        val rSize = rawSize
        if (rSize == null) {
            setStatus("This camera reports no RAW output size.")
            return
        }
        texture.setDefaultBufferSize(pSize.width, pSize.height)
        val previewSurface = Surface(texture)
        val reader = ImageReader.newInstance(rSize.width, rSize.height, ImageFormat.RAW_SENSOR, BURST_FRAMES)
        reader.setOnImageAvailableListener(imageListener, backgroundHandler)
        imageReader = reader
        try {
            device.createCaptureSession(
                listOf(previewSurface, reader.surface),
                sessionCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            setStatus("Cannot create session: ${e.message}")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            startPreview()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            setStatus("Camera session failed to configure.")
        }
    }

    private fun startPreview() {
        val session = captureSession ?: return
        try {
            val builder = previewRequestBuilder()
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            setStatus("Preview failed: ${e.message}")
        } catch (e: IllegalStateException) {
            setStatus("Preview failed: ${e.message}")
        }
    }

    private fun previewRequestBuilder(): CaptureRequest.Builder {
        val device = cameraDevice ?: throw IllegalStateException("no camera device")
        val texture = textureView.surfaceTexture ?: throw IllegalStateException("no preview surface")
        val builder = device.createCaptureRequest(
            if (manualMode) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_PREVIEW
        )
        builder.addTarget(Surface(texture))
        if (manualMode) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValueNs)
            applyFrameDuration(builder)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        return builder
    }

    /** In manual mode the frame must last at least as long as the exposure. */
    private fun applyFrameDuration(builder: CaptureRequest.Builder) {
        val maxFrame = characteristics?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L
        if (maxFrame > 0) {
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, expValueNs.coerceAtMost(maxFrame))
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        synchronized(lock) {
            pendingImages.forEach { try { it.close() } catch (_: Exception) {} }
            pendingImages.clear()
            pendingResults.clear()
        }
    }

    // ---------- capture ----------

    private fun captureBurst() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        if (capturing) return
        capturing = true
        burstSaved = 0
        burstFinished = 0
        burstId++
        val thisBurst = burstId
        lastBurstUris.clear()
        lastBurstFacing = facing
        synchronized(lock) {
            pendingImages.clear()
            pendingResults.clear()
        }
        setStatus("Capturing $BURST_FRAMES RAW frames…")
        shutterButton.isEnabled = false
        try {
            val requests = ArrayList<CaptureRequest>(BURST_FRAMES)
            repeat(BURST_FRAMES) {
                val builder = device.createCaptureRequest(
                    if (manualMode) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE
                )
                builder.addTarget(reader.surface)
                if (manualMode) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValueNs)
                    applyFrameDuration(builder)
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                requests.add(builder.build())
            }
            session.captureBurst(requests, burstCallback, backgroundHandler)
            // Safety net: if a frame's result never arrives, do not leave the
            // shutter disabled forever — close leftovers and finish the burst.
            backgroundHandler?.postDelayed(
                { finishStuckBurst(thisBurst) },
                10_000L
            )
        } catch (e: CameraAccessException) {
            capturing = false
            shutterButton.isEnabled = true
            setStatus("Capture failed: ${e.message}")
        }
    }

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            synchronized(lock) {
                pendingResults.addLast(result)
                drainLocked()
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            runOnUiThread { setStatus("Frame failed: ${failure.reason}") }
        }

        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            runOnUiThread { setStatus("Burst captured — saving DNGs…") }
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image: Image = try {
            reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return@OnImageAvailableListener
        synchronized(lock) {
            pendingImages.addLast(image)
            drainLocked()
        }
    }

    /** Pairs each arrived image with its capture result, in order, and saves it.
     * Runs on the background thread; saves one frame at a time so a 25MB RAW
     * frame never sits beside the rest of the burst in memory. */
    private fun drainLocked() {
        while (pendingImages.isNotEmpty() && pendingResults.isNotEmpty()) {
            val image = pendingImages.removeFirst()
            val result = pendingResults.removeFirst()
            saveDng(image, result)
        }
    }

    private fun finishStuckBurst(id: Int) {
        if (id != burstId || !capturing) return
        synchronized(lock) {
            pendingImages.forEach { try { it.close() } catch (_: Exception) {} }
            pendingImages.clear()
            pendingResults.clear()
        }
        capturing = false
        runOnUiThread {
            shutterButton.isEnabled = true
            setStatus("Saved $burstSaved of $BURST_FRAMES DNGs — ${BURST_FRAMES - burstSaved} frame(s) had no result.")
            if (burstSaved > 0) enableProcessing()
        }
    }

    private fun saveDng(image: Image, result: TotalCaptureResult) {
        val chars = characteristics
        var ok = false
        try {
            if (chars == null) return
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "splendorcam_${stamp}_${burstFinished + 1}.dng"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SplendorCam")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "MediaStore insert failed")
                return
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                DngCreator(chars, result).use { dng -> dng.writeImage(out, image) }
            }
            ok = true
            lastBurstUris.add(uri)
            lastBurstIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: lastBurstIso
        } catch (e: Exception) {
            Log.e(TAG, "DNG save failed", e)
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
            burstFinished++
            if (ok) burstSaved++
            val done = burstFinished
            val saved = burstSaved
            runOnUiThread {
                if (done >= BURST_FRAMES) {
                    capturing = false
                    shutterButton.isEnabled = true
                    setStatus("Saved $saved of $BURST_FRAMES DNGs to Pictures/SplendorCam")
                    if (saved < BURST_FRAMES) {
                        Toast.makeText(
                            this,
                            "Saved $saved of $BURST_FRAMES — check logcat for the failed frame(s)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (saved > 0) enableProcessing()
                } else {
                    setStatus("Saving DNG $done of $BURST_FRAMES…")
                }
            }
        }
    }

    // ---------- processing (round 1) ----------

    private fun updateLookButtons() {
        for ((i, b) in lookButtons.withIndex()) {
            val look = Looks.ALL[i]
            b.text = (if (look == selectedLook) "✓ " else "") + look.id.replaceFirstChar { it.uppercase() }
        }
        processButton.text = "Process burst (${selectedLook.displayName})"
    }

    /** Enables the look selector + process button after a burst saved >= 1 DNG.
     * Default look: night when the burst ISO is high (>= 800, indoor/low light),
     * natural otherwise. */
    private fun enableProcessing() {
        selectedLook = Looks.defaultFor(lastBurstIso >= 800)
        for (b in lookButtons) b.isEnabled = true
        updateLookButtons()
        processButton.isEnabled = true
    }

    private fun processBurst() {
        if (processing) return
        val uris = ArrayList(lastBurstUris)
        if (uris.isEmpty()) {
            setStatus("No burst DNGs to process — capture a burst first.")
            return
        }
        val look = selectedLook
        val isFront = lastBurstFacing == CameraCharacteristics.LENS_FACING_FRONT
        processing = true
        processButton.isEnabled = false
        setStatus("Processing ${uris.size} frames (${look.displayName})…")
        Thread {
            try {
                val outName = processBurstBlocking(uris, look, isFront)
                runOnUiThread {
                    processing = false
                    processButton.isEnabled = true
                    setStatus("Saved $outName to Pictures/SplendorCam")
                    Toast.makeText(this, "Saved $outName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                runOnUiThread {
                    processing = false
                    processButton.isEnabled = true
                    setStatus("Processing failed: ${e.message}")
                }
            }
        }.start()
    }

    /** Reads the burst DNGs one at a time, merges, processes, orients and
     * saves a JPEG next to the DNGs. Runs off the UI thread. Frames are
     * streamed into one merged RAW, so a 5-frame burst never sits in
     * memory as 5 full RAWs (25MB each). */
    private fun processBurstBlocking(uris: List<Uri>, look: Look, isFront: Boolean): String {
        var info: DngInfo? = null
        var merged: FloatArray? = null
        var n = 0
        for (uri in uris) {
            val pair = contentResolver.openInputStream(uri)?.use { DngReader.read(it) }
                ?: throw IllegalStateException("cannot open $uri")
            val (i, raw) = pair
            if (info == null) {
                info = i
                merged = FloatArray(i.width * i.height)
            }
            if (i.width != info.width || i.height != info.height ||
                !i.cfaPattern.contentEquals(info.cfaPattern)
            ) {
                Log.w(TAG, "Skipping frame with mismatched size/CFA: $uri")
                continue
            }
            RawProcessor.accumulate(merged!!, raw, info)
            n++
        }
        if (info == null || merged == null || n == 0) {
            throw IllegalStateException("no usable DNG frames")
        }
        RawProcessor.scaleAcc(merged, 1f / n)
        val dng = info
        val sensor = Bitmap.createBitmap(dng.width, dng.height, Bitmap.Config.ARGB_8888)
        val stats = RawProcessor.process(merged, dng, look, RawProcessor.TileSink { x, y, w, h, argb ->
            sensor.setPixels(argb, 0, w, x, y, w, h)
        })
        Log.i(TAG, "Processed ${dng.width}x${dng.height} look=${look.id} frames=$n " +
            "baseGain=${stats.baseGain} totalGain=${stats.totalGain}")
        // Orientation: the sensor frame is landscape, the phone was held
        // portrait. FRONT = 90 deg CCW + horizontal mirror; BACK = 90 deg CW.
        // (Verified against the stock JPEGs in the sandbox; same mapping as
        // np.rot90 + fliplr in the reference processor.)
        val matrix = Matrix()
        if (isFront) {
            matrix.postRotate(-90f)
            matrix.postScale(-1f, 1f)
        } else {
            matrix.postRotate(90f)
        }
        val oriented = Bitmap.createBitmap(sensor, 0, 0, dng.width, dng.height, matrix, true)
        sensor.recycle()
        val stem = contentResolver.query(
            uris[0], arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.removeSuffix(".dng") else null
        } ?: "splendorcam_processed"
        val name = "${stem}_${look.id}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SplendorCam")
        }
        val outUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed for JPEG")
        contentResolver.openOutputStream(outUri)?.use { out ->
            if (!oriented.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                throw IllegalStateException("JPEG compress failed")
            }
        } ?: throw IllegalStateException("cannot open output stream for JPEG")
        oriented.recycle()
        return name
    }

    // ---------- controls ----------

    private fun toggleMode() {
        manualMode = !manualMode
        modeButton.text = if (manualMode) "Mode: Manual" else "Mode: Auto"
        isoSeek.isEnabled = manualMode
        expSeek.isEnabled = manualMode
        updateLabels()
        startPreview()
    }

    private fun switchCamera() {
        if (capturing) return
        facing = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        closeCamera()
        setupCamera()
    }

    private fun seekListener(isIso: Boolean) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val fraction = progress / 100.0
            if (isIso) {
                isoRange?.let { isoValue = (it.lower + (it.upper - it.lower) * fraction).toInt() }
            } else {
                expRange?.let {
                    val ratio = it.upper.toDouble() / it.lower.toDouble()
                    expValueNs = (it.lower.toDouble() * ratio.pow(fraction)).toLong()
                }
            }
            updateLabels()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            if (manualMode) startPreview()
        }
    }

    private fun updateLabels() {
        isoLabel.text = "ISO $isoValue" + (isoRange?.let { "  (range ${it.lower}–${it.upper})" } ?: "")
        expLabel.text = "Exposure ${formatExposure(expValueNs)}" +
            (expRange?.let { "  (range ${formatExposure(it.lower)}–${formatExposure(it.upper)})" } ?: "")
    }

    private fun formatExposure(ns: Long): String {
        return if (ns >= 1_000_000L) {
            "%.1f ms".format(ns / 1_000_000.0)
        } else {
            "%d µs".format(ns / 1_000L)
        }
    }

    private fun syncSeekPositions() {
        isoRange?.let {
            if (it.upper > it.lower) {
                isoSeek.progress = ((isoValue - it.lower) * 100 / (it.upper - it.lower))
            }
        }
        expRange?.let {
            if (it.upper > it.lower) {
                val ratio = it.upper.toDouble() / it.lower.toDouble()
                val fraction = kotlin.math.ln(expValueNs.toDouble() / it.lower.toDouble()) / kotlin.math.ln(ratio)
                expSeek.progress = (fraction * 100).toInt().coerceIn(0, 100)
            }
        }
    }

    // ---------- threads, texture, status ----------

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("SplendorCamBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (cameraId == null) setupCamera() else openCamera()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }
}
