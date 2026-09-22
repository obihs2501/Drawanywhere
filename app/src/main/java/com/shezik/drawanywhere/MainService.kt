/*
DrawAnywhere: An Android application that lets you draw on top of other apps.
Copyright (C) 2025-2026 shezik

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free Software
Foundation, either version 3 of the License, or any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.shezik.drawanywhere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.content.pm.ServiceInfo
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.round
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.shezik.drawanywhere.view.DismissTargetView
import com.shezik.drawanywhere.view.ToolbarLifecycleOwner
import com.shezik.drawanywhere.view.canvas.NativeDrawCanvasView
import com.shezik.drawanywhere.view.toolbar.DrawToolbar
import com.shezik.drawanywhere.view.toolbar.FloatingSettingsWindow
import com.shezik.drawanywhere.model.StylusButtonScheme
import com.shezik.drawanywhere.ui.theme.DrawAnywhereTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 100
        private const val CHANNEL_ID = "default_channel"
        const val ACTION_SCREEN_CAPTURE_PERMISSION_RESULT =
            "com.shezik.drawanywhere.action.SCREEN_CAPTURE_PERMISSION_RESULT"
        const val ACTION_SAVE_LOCATION_RESULT =
            "com.shezik.drawanywhere.action.SAVE_LOCATION_RESULT"
        const val EXTRA_SCREEN_CAPTURE_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_SCREEN_CAPTURE_DATA = "screen_capture_data"
        const val EXTRA_SAVE_LOCATION_URI = "save_location_uri"

        /** 持有 MediaProjection 直到服务停止，避免每次保存都弹「允许录制或投放」。 */
        private const val PROJECTION_IDLE_RELEASE_MS = 30 * 60 * 1000L

        var isRunning: Boolean = false
            private set
    }

    private enum class ExportMode(val suffix: String) {
        Transparent("transparent"),
        Paper("paper"),
        Screen("screen")
    }

    private val toolbarLifecycleOwner = ToolbarLifecycleOwner()
    private lateinit var drawController: DrawController
    private lateinit var windowManager: WindowManager
    private lateinit var canvasView: NativeDrawCanvasView
    private lateinit var toolbarView: ComposeView
    private lateinit var toolbarDialog: Dialog
    private var settingsView: ComposeView? = null
    private var settingsDialog: Dialog? = null
    private lateinit var dismissTargetView: DismissTargetView
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: DrawViewModel
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingExportMode: ExportMode? = null

    // ── MediaProjection session cache (avoid re-prompting on every screen save) ──
    private var cachedProjection: MediaProjection? = null
    private var cachedProjectionCode: Int? = null
    private var cachedProjectionData: Intent? = null
    private var cachedProjectionCallback: MediaProjection.Callback? = null
    private var projectionReleaseJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        preferencesManager = PreferencesManager(this)
        val (initialUiState, initialServiceState) = runBlocking {
            preferencesManager.getSavedUiState() to preferencesManager.getSavedServiceState()
        }
        drawController = DrawController(initialUiState.currentPenConfig)
        viewModel = DrawViewModel(
            controller = drawController,
            preferencesManager = preferencesManager,
            initialUiState = initialUiState,
            initialServiceState = initialServiceState,
            stopService = { stopSelf() },
            containsDismissTarget = { x, y -> dismissTargetView.containsScreenPoint(x, y) },
        )
        DrawSessionBridge.viewModel = viewModel

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        // -------- Setup native canvas --------
        canvasView = NativeDrawCanvasView(this, drawController, viewModel)
        drawController.onStrokesChanged = { canvasView.invalidate() }

        val canvasParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        applyCanvasInputMode(canvasParams, initialUiState)

        // -------- Setup toolbar (Compose) --------
        toolbarLifecycleOwner.start()
        toolbarView = ComposeView(this).apply {
            setContent {
                val canRedo by viewModel.canRedo.collectAsState()
                val uiState by viewModel.uiState.collectAsState()
                DrawToolbar(
                    viewModel = viewModel,
                    canRedo = canRedo,
                    onRedo = viewModel::redo,
                    passthroughEnabled = uiState.canvasPassthrough,
                    onTogglePassthrough = viewModel::toggleCanvasPassthrough,
                    onSaveTransparent = { saveCurrentDrawing(ExportMode.Transparent) },
                    onSaveWithBackdrop = { saveCurrentDrawing(ExportMode.Paper) },
                    onSaveWithScreenBackdrop = { saveCurrentDrawing(ExportMode.Screen) },
                    onOpenSettings = ::openSettings,
                    onQuit = viewModel::quitApplication,
                )
            }
        }
        toolbarLifecycleOwner.attachTo(toolbarView)

        val toolbarParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        applyToolbarPosition(toolbarParams, initialServiceState)
        // ---------------------------------------

        // -------- Setup dismiss target (shown when dragging toolbar) --------
        dismissTargetView = DismissTargetView(this)
        val dismissSize = (DismissTargetView.SIZE_DP * resources.displayMetrics.density).toInt()
        val dismissParams = LayoutParams(
            dismissSize, dismissSize,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (DismissTargetView.BOTTOM_OFFSET_DP * resources.displayMetrics.density).toInt()
        }
        dismissTargetView.visibility = View.GONE
        // --------------------------------------------------------------------

        windowManager.addView(canvasView, canvasParams)
        showToolbarWindow(toolbarParams)
        windowManager.addView(dismissTargetView, dismissParams)

        // Defer toolbar position validation until layout is complete
        toolbarView.post {
            initializeToolbarPositionIfNeeded(toolbarParams)
            applyToolbarPosition(toolbarParams, viewModel.serviceState.value)
            updateToolbarWindowLayout(toolbarParams)
        }
        toolbarView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyToolbarPosition(toolbarParams, viewModel.serviceState.value)
            updateToolbarWindowLayout(toolbarParams)
        }

        // Observe UI state changes
        serviceScope.launch {
            viewModel.uiState.collect { state ->
                applyCanvasInputMode(canvasParams, state)
                windowManager.updateViewLayout(canvasView, canvasParams)
                canvasView.visibility = if (state.canvasVisible) View.VISIBLE else View.GONE
                updateToolbarWindowLayout(toolbarParams)
                updateToolbarAlpha()
                if (state.canvasVisible &&
                    state.stylusButtonScheme != StylusButtonScheme.Disabled &&
                    !state.canvasPassthrough
                ) {
                    canvasView.requestStylusKeyFocus()
                }
            }
        }

        // Observe service state changes
        serviceScope.launch {
            viewModel.serviceState.collect { state ->
                applyToolbarPosition(toolbarParams, state)
                updateToolbarWindowLayout(toolbarParams)
                updateToolbarAlpha()
            }
        }

        // Observe dismiss target
        serviceScope.launch {
            viewModel.dismissTarget.collect { target ->
                when (target) {
                    is DismissTarget.Hidden -> dismissTargetView.hide()
                    is DismissTarget.Visible -> {
                        dismissTargetView.active = target.active
                        dismissTargetView.show()
                    }
                }
            }
        }

        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCREEN_CAPTURE_PERMISSION_RESULT -> handleScreenCapturePermissionResult(intent)
            ACTION_SAVE_LOCATION_RESULT -> handleSaveLocationResult(intent)
        }
        return START_STICKY
    }

    private fun applyCanvasInputMode(params: LayoutParams, state: UiState) {
        var flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (state.canvasPassthrough) flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE
        if (state.stylusButtonScheme == StylusButtonScheme.Disabled) {
            flags = flags or LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.flags = flags
    }

    private fun applyToolbarPosition(params: LayoutParams, state: ServiceState) {
        val rounded = state.toolbarPosition.round()
        val (screenWidth, screenHeight) = getUsableScreenSize(windowManager)
        params.x = rounded.x.coerceIn(0, screenWidth - toolbarView.width)
        params.y = rounded.y.coerceIn(0, screenHeight - toolbarView.height)
        val clamped = Offset(params.x.toFloat(), params.y.toFloat())
        if (clamped != state.toolbarPosition) {
            viewModel.setToolbarPosition(clamped)
        }
    }

    private fun updateToolbarAlpha() {
        val serviceState = viewModel.serviceState.value
        val uiState = viewModel.uiState.value
        val targetAlpha = if (!serviceState.toolbarActive && uiState.toolbarMinimized) {
            DrawViewModel.TOOLBAR_DIM_ALPHA
        } else {
            1.0f
        }
        toolbarView.animate()
            .alpha(targetAlpha)
            .setDuration(DrawViewModel.TOOLBAR_DIM_DURATION_MS)
            .start()
    }

    private fun showToolbarWindow(params: LayoutParams) {
        toolbarDialog = Dialog(this, R.style.Theme_DrawAnywhere_ToolbarWindow).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setContentView(toolbarView)
            toolbarView.layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        toolbarDialog.window?.applyToolbarWindowAttributes(params)
        toolbarDialog.show()
        toolbarDialog.window?.applyToolbarWindowAttributes(params)
    }

    private fun updateToolbarWindowLayout(params: LayoutParams) {
        if (::toolbarDialog.isInitialized) {
            toolbarDialog.window?.applyToolbarWindowAttributes(params)
        }
    }

    private fun Window.applyToolbarWindowAttributes(params: LayoutParams) {
        setBackgroundDrawable(toolbarWindowBackground())
        clearFlags(LayoutParams.FLAG_DIM_BEHIND or LayoutParams.FLAG_BLUR_BEHIND)
        setDimAmount(0f)
        setType(LayoutParams.TYPE_APPLICATION_OVERLAY)
        decorView.setPadding(0, 0, 0, 0)
        attributes = params
        setLayout(params.width, params.height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setBackgroundBlurRadius(dpToPx(24f))
        }
    }

    private fun toolbarWindowBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20f).toFloat()
            setColor(AndroidColor.argb(48, 245, 247, 250))
        }

    private fun Window.applySettingsWindowAttributes(params: LayoutParams) {
        setBackgroundDrawable(settingsWindowBackground())
        clearFlags(LayoutParams.FLAG_DIM_BEHIND or LayoutParams.FLAG_BLUR_BEHIND)
        setDimAmount(0f)
        setType(LayoutParams.TYPE_APPLICATION_OVERLAY)
        decorView.setPadding(0, 0, 0, 0)
        attributes = params
        setLayout(params.width, params.height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setBackgroundBlurRadius(dpToPx(36f))
        }
    }

    private fun settingsWindowBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(24f).toFloat()
            setColor(AndroidColor.argb(72, 245, 247, 250))
        }

    private fun initializeToolbarPositionIfNeeded(params: LayoutParams) {
        if (viewModel.serviceState.value.toolbarPositionInitialized) return
        val (screenWidth, screenHeight) = getUsableScreenSize(windowManager)
        val x = ((screenWidth - toolbarView.width) / 2).coerceAtLeast(0)
        val y = (screenHeight - toolbarView.height - dpToPx(28f)).coerceAtLeast(0)
        viewModel.setToolbarPosition(Offset(x.toFloat(), y.toFloat()))
        viewModel.markToolbarPositionInitialized()
        applyToolbarPosition(params, viewModel.serviceState.value)
        viewModel.saveToolbarPosition()
    }

    private fun openSettings() {
        if (settingsDialog?.isShowing == true) return

        val view = ComposeView(this).apply {
            setContent {
                DrawAnywhereTheme {
                    FloatingSettingsWindow(
                        viewModel = viewModel,
                        onChooseSaveLocation = ::requestSaveLocation,
                        onClose = ::closeSettings,
                    )
                }
            }
        }
        view.layoutParams = FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
        toolbarLifecycleOwner.attachTo(view)

        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        settingsView = view
        settingsDialog = Dialog(this, R.style.Theme_DrawAnywhere_ToolbarWindow).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setContentView(view)
            window?.applySettingsWindowAttributes(params)
            show()
            window?.applySettingsWindowAttributes(params)
        }
    }

    private fun closeSettings() {
        settingsDialog?.dismiss()
        settingsDialog = null
        settingsView = null
    }

    private fun requestScreenCapturePermission() {
        startActivity(
            Intent(this, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun requestSaveLocation() {
        startActivity(
            Intent(this, SaveLocationPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun handleSaveLocationResult(intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_SAVE_LOCATION_URI)
        if (uri.isNullOrBlank()) return
        viewModel.setExportTreeUri(uri)
        Toast.makeText(
            this,
            getString(R.string.save_location_updated),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleScreenCapturePermissionResult(intent: Intent) {
        val mode = pendingExportMode
        pendingExportMode = null
        if (mode != ExportMode.Screen) return

        val resultCode = intent.getIntExtra(EXTRA_SCREEN_CAPTURE_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_SCREEN_CAPTURE_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Toast.makeText(
                this,
                getString(R.string.screen_capture_permission_denied),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Cache the grant so later screen saves in this session skip the consent dialog.
        if (!rememberScreenCaptureGrant(resultCode, resultData)) {
            Toast.makeText(
                this,
                getString(R.string.export_failed),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        saveCurrentDrawing(mode)
    }

    /**
     * 是否已有可复用的屏幕捕获授权。
     * API 34+：授权 Intent 一次性，必须持有 MediaProjection 实例复用。
     * API 33：可缓存 resultCode/data 复用 token。
     */
    private fun hasReusableScreenCaptureGrant(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cachedProjection != null
        } else {
            cachedProjectionCode != null && cachedProjectionData != null
        }
    }

    /**
     * 记住本次授权。API 34+ 立刻换取并持有 MediaProjection。
     * @return 是否成功建立可复用授权
     */
    private fun rememberScreenCaptureGrant(resultCode: Int, resultData: Intent): Boolean {
        cachedProjectionCode = resultCode
        cachedProjectionData = resultData
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (cachedProjection == null) {
                elevateForegroundForScreenCapture()
                val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                    ?: run {
                        restoreRegularForegroundType()
                        return false
                    }
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        // User revoked from the system indicator — drop the cache.
                        clearScreenCaptureGrant(stopProjection = false)
                    }
                }
                val handler = Handler(Looper.getMainLooper())
                runCatching { projection.registerCallback(callback, handler) }
                cachedProjectionCallback = callback
                cachedProjection = projection
            }
        }
        return hasReusableScreenCaptureGrant()
    }

    private fun clearScreenCaptureGrant(stopProjection: Boolean = true) {
        projectionReleaseJob?.cancel()
        projectionReleaseJob = null
        cachedProjectionCallback?.let { callback ->
            runCatching { cachedProjection?.unregisterCallback(callback) }
        }
        cachedProjectionCallback = null
        if (stopProjection) {
            runCatching { cachedProjection?.stop() }
        }
        cachedProjection = null
        cachedProjectionCode = null
        cachedProjectionData = null
        restoreRegularForegroundType()
    }

    private fun scheduleProjectionIdleRelease() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        projectionReleaseJob?.cancel()
        projectionReleaseJob = serviceScope.launch {
            delay(PROJECTION_IDLE_RELEASE_MS)
            clearScreenCaptureGrant(stopProjection = true)
        }
    }

    /** 取出用于本次截屏的 MediaProjection。API 34+ 复用持有实例；API 33 用缓存 token 换取。 */
    private fun obtainProjectionForCapture(): MediaProjection? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return cachedProjection
        }
        val code = cachedProjectionCode ?: return null
        val data = cachedProjectionData ?: return null
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        return mediaProjectionManager.getMediaProjection(code, data)
    }

    private fun elevateForegroundForScreenCapture() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun restoreRegularForegroundType() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun saveCurrentDrawing(
        mode: ExportMode,
        screenCaptureResultCode: Int? = null,
        screenCaptureResultData: Intent? = null,
    ) {
        if (mode == ExportMode.Screen) {
            // Prefer an already-granted session so the user only consents once.
            if (!hasReusableScreenCaptureGrant()) {
                if (screenCaptureResultCode == null || screenCaptureResultData == null) {
                    pendingExportMode = mode
                    requestScreenCapturePermission()
                    return
                }
                if (!rememberScreenCaptureGrant(screenCaptureResultCode, screenCaptureResultData)) {
                    pendingExportMode = mode
                    requestScreenCapturePermission()
                    return
                }
            }
        }

        serviceScope.launch {
            val result = runCatching {
                val bitmap = when (mode) {
                    ExportMode.Transparent -> canvasView.renderToBitmap(backgroundColor = null)
                    ExportMode.Paper -> canvasView.renderToBitmap(backgroundColor = 0xFFF7F0E5.toInt())
                    ExportMode.Screen -> renderScreenBackdropExport()
                }
                withContext(Dispatchers.IO) {
                    saveBitmap(bitmap, mode).getOrThrow()
                }
            }
            result
                .onSuccess { displayName ->
                    Toast.makeText(
                        this@MainService,
                        getString(R.string.export_success, displayName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure {
                    Toast.makeText(
                        this@MainService,
                        getString(R.string.export_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private suspend fun renderScreenBackdropExport(): Bitmap {
        val drawingBitmap = canvasView.renderToBitmap(backgroundColor = null)
        val backgroundBitmap = try {
            captureBackgroundBitmap()
        } catch (error: Throwable) {
            drawingBitmap.recycle()
            // Token/projection may have gone stale — force a re-prompt next time.
            clearScreenCaptureGrant()
            throw error
        }

        return try {
            val mergedBitmap = Bitmap.createBitmap(
                backgroundBitmap.width,
                backgroundBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(mergedBitmap)
            canvas.drawBitmap(backgroundBitmap, 0f, 0f, null)
            canvas.drawBitmap(drawingBitmap, 0f, 0f, null)
            mergedBitmap
        } finally {
            backgroundBitmap.recycle()
            drawingBitmap.recycle()
        }
    }

    private suspend fun captureBackgroundBitmap(): Bitmap {
        val canvasWasVisible = canvasView.visibility == View.VISIBLE
        val toolbarViewWasVisible = toolbarView.visibility == View.VISIBLE
        val dismissWasVisible = dismissTargetView.visibility == View.VISIBLE
        val toolbarDialogWasShowing = ::toolbarDialog.isInitialized && toolbarDialog.isShowing
        val settingsDialogWasShowing = settingsDialog?.isShowing == true

        // Hide every overlay window — including Dialog window backgrounds —
        // otherwise the translucent blurred toolbar frame shows up in the capture.
        canvasView.visibility = View.INVISIBLE
        dismissTargetView.visibility = View.INVISIBLE
        toolbarView.visibility = View.INVISIBLE
        if (toolbarDialogWasShowing) {
            toolbarDialog.window?.decorView?.visibility = View.INVISIBLE
            toolbarDialog.hide()
        }
        settingsDialog?.let { dialog ->
            dialog.window?.decorView?.visibility = View.INVISIBLE
            dialog.hide()
        }

        return try {
            // Wait long enough for SurfaceFlinger to drop the hidden windows from composition.
            delay(250)
            withTimeout(5_000) {
                captureScreenBitmap()
            }
        } finally {
            if (toolbarDialogWasShowing) {
                toolbarDialog.show()
                toolbarDialog.window?.decorView?.visibility = View.VISIBLE
            }
            if (settingsDialogWasShowing) {
                settingsDialog?.show()
                settingsDialog?.window?.decorView?.visibility = View.VISIBLE
            }
            toolbarView.visibility = if (toolbarViewWasVisible) View.VISIBLE else View.INVISIBLE
            canvasView.visibility = if (canvasWasVisible) View.VISIBLE else View.INVISIBLE
            dismissTargetView.visibility = if (dismissWasVisible) View.VISIBLE else View.GONE
        }
    }

    private suspend fun captureScreenBitmap(): Bitmap = suspendCancellableCoroutine { continuation ->
        elevateForegroundForScreenCapture()
        val width = canvasView.width.coerceAtLeast(1)
        val height = canvasView.height.coerceAtLeast(1)
        val densityDpi = resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val reuseCachedProjection =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cachedProjection != null
        val mediaProjection = obtainProjectionForCapture()
            ?: run {
                imageReader.close()
                restoreRegularForegroundType()
                continuation.resumeWithException(IllegalStateException("MediaProjection was unavailable"))
                return@suspendCancellableCoroutine
            }
        val handler = Handler(Looper.getMainLooper())
        var virtualDisplay: VirtualDisplay? = null
        var cleanedUp = false
        var localCallback: MediaProjection.Callback? = null

        fun cleanUp() {
            if (cleanedUp) return
            cleanedUp = true
            imageReader.setOnImageAvailableListener(null, null)
            runCatching { virtualDisplay?.release() }
            localCallback?.let { cb -> runCatching { mediaProjection.unregisterCallback(cb) } }
            // Cached projections stay alive so the next save skips the consent dialog.
            // Released on service destroy or after a long idle window.
            if (!reuseCachedProjection) {
                runCatching { mediaProjection.stop() }
                restoreRegularForegroundType()
            } else {
                scheduleProjectionIdleRelease()
            }
            runCatching { imageReader.close() }
        }
        // Only watch for unexpected stops on non-cached captures (or when no cache callback exists).
        if (!reuseCachedProjection || cachedProjectionCallback == null) {
            val projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("MediaProjection stopped"))
                    }
                    cleanUp()
                    if (reuseCachedProjection) {
                        clearScreenCaptureGrant(stopProjection = false)
                    }
                }
            }
            localCallback = projectionCallback
            mediaProjection.registerCallback(projectionCallback, handler)
        }

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val rawBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(buffer)
                val croppedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                rawBitmap.recycle()
                if (continuation.isActive) {
                    continuation.resume(croppedBitmap)
                } else {
                    croppedBitmap.recycle()
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                image.close()
                cleanUp()
            }
        }, handler)

        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "DrawAnywhereScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
        } catch (error: Throwable) {
            cleanUp()
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        continuation.invokeOnCancellation { cleanUp() }
    }

    private fun saveBitmap(bitmap: Bitmap, mode: ExportMode): Result<String> = runCatching {
        bitmap.use { image ->
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val displayName = "drawanywhere_${timestamp}_${mode.suffix}.png"
            val exportTreeUri = viewModel.uiState.value.exportTreeUri

            if (!exportTreeUri.isNullOrBlank()) {
                saveBitmapToSafTree(image, Uri.parse(exportTreeUri), displayName)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, DEFAULT_EXPORT_RELATIVE_PATH)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = requireNotNull(
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                )
                resolver.openOutputStream(uri)?.use { stream ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val directory = File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "DrawAnywhere"
                ).apply { mkdirs() }
                FileOutputStream(File(directory, displayName)).use { stream ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }

            displayName
        }
    }

    private fun saveBitmapToSafTree(bitmap: Bitmap, treeUri: Uri, displayName: String) {
        val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val imageUri = requireNotNull(
            DocumentsContract.createDocument(
                contentResolver,
                treeDocumentUri,
                "image/png",
                displayName
            )
        )
        contentResolver.openOutputStream(imageUri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        } ?: error("Unable to open export output stream")
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name)
        }

    private fun Bitmap.use(block: (Bitmap) -> String): String =
        try {
            block(this)
        } finally {
            recycle()
        }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun getUsableScreenSize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
            val b = metrics.bounds
            (b.width() - insets.left - insets.right) to (b.height() - insets.top - insets.bottom)
        } else {
            val size = Point()
            wm.defaultDisplay.getSize(size)
            size.x to size.y
        }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        clearScreenCaptureGrant(stopProjection = true)
        com.shezik.drawanywhere.stylus.FocusPenGestureDetector.reset()
        if (DrawSessionBridge.viewModel === viewModel) {
            DrawSessionBridge.viewModel = null
        }
        serviceScope.cancel()
        if (::toolbarDialog.isInitialized && toolbarDialog.isShowing)
            toolbarDialog.dismiss()
        settingsDialog?.dismiss()
        settingsDialog = null
        settingsView = null
        if (::canvasView.isInitialized && canvasView.isAttachedToWindow)
            windowManager.removeView(canvasView)
        if (::dismissTargetView.isInitialized && dismissTargetView.isAttachedToWindow)
            windowManager.removeView(dismissTargetView)
        toolbarLifecycleOwner.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
