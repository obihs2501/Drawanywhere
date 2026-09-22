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
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.content.pm.ServiceInfo
import android.net.Uri
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.round
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.shezik.drawanywhere.capture.RootScreenshot
import com.shezik.drawanywhere.capture.ScreenCaptureSession
import com.shezik.drawanywhere.stylus.FocusPenSystemLink
import com.shezik.drawanywhere.view.DismissTargetView
import com.shezik.drawanywhere.view.ToolbarLifecycleOwner
import com.shezik.drawanywhere.view.canvas.NativeDrawCanvasView
import com.shezik.drawanywhere.view.toolbar.DrawToolbar
import com.shezik.drawanywhere.view.toolbar.FloatingSettingsWindow
import com.shezik.drawanywhere.model.StylusButtonScheme
import com.shezik.drawanywhere.ui.theme.DrawAnywhereTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainService : Service() {
    companion object {
        private const val TAG = "MainService"
        private const val NOTIFICATION_ID = 100
        private const val CHANNEL_ID = "default_channel"
        const val ACTION_SCREEN_CAPTURE_PERMISSION_RESULT =
            "com.shezik.drawanywhere.action.SCREEN_CAPTURE_PERMISSION_RESULT"
        const val ACTION_SAVE_LOCATION_RESULT =
            "com.shezik.drawanywhere.action.SAVE_LOCATION_RESULT"
        const val EXTRA_SCREEN_CAPTURE_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_SCREEN_CAPTURE_DATA = "screen_capture_data"
        const val EXTRA_SAVE_LOCATION_URI = "save_location_uri"

        /** Time for WindowManager to apply the hidden overlays before a frame is taken. */
        private const val OVERLAY_HIDE_SETTLE_MS = 160L
        private const val CAPTURE_TIMEOUT_MS = 6_000L
        private const val PAPER_BACKGROUND_COLOR = 0xFFF7F0E5.toInt()

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
    private lateinit var canvasParams: LayoutParams
    private lateinit var toolbarView: ComposeView
    private lateinit var toolbarDialog: Dialog
    private var settingsView: ComposeView? = null
    private var settingsDialog: Dialog? = null
    private lateinit var dismissTargetView: DismissTargetView
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: DrawViewModel
    private lateinit var focusPenLink: FocusPenSystemLink
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingExportMode: ExportMode? = null
    private var exportJob: Job? = null

    /** Kept alive across saves so the consent dialog appears once per service run. */
    private var captureSession: ScreenCaptureSession? = null
    private var foregroundHasProjectionType = false

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

        focusPenLink = FocusPenSystemLink(this, serviceScope)
        viewModel.onRetryFocusPenLink = { focusPenLink.restart() }
        serviceScope.launch {
            focusPenLink.state.collect { linkState -> viewModel.updateFocusPenLinkState(linkState) }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        // -------- Setup native canvas --------
        canvasView = NativeDrawCanvasView(this, drawController, viewModel)
        canvasView.onKeyDiagnostic = ::reportKeyEvent
        drawController.onStrokesChanged = { canvasView.invalidate() }

        canvasParams = LayoutParams(
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
                // Keep the window visible (but drawing nothing) while the canvas is
                // hidden. Setting the root view to View.GONE would make the window
                // transparent to touches even though passthrough is off.
                canvasView.canvasHidden = !state.canvasVisible
                updateToolbarWindowLayout(toolbarParams)
                updateToolbarAlpha()
                syncCanvasKeyFocus(state)
                syncFocusPenLink(state)
                if (!state.keepScreenCaptureSession && exportJob?.isActive != true) {
                    releaseCaptureSession()
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
        if (state.canvasPassthrough) {
            flags = flags or LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (state.canvasPassthrough) flags = flags or LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = flags
    }

    private fun syncCanvasKeyFocus(state: UiState) {
        if (state.canvasVisible &&
            !state.canvasPassthrough &&
            state.stylusButtonScheme != StylusButtonScheme.Disabled
        ) {
            canvasView.requestStylusKeyFocus()
        } else {
            canvasView.clearFocus()
        }
    }

    /**
     * Claim the Focus Pen gesture stream only while this app's canvas is the
     * window that should receive it; hand it back to the system otherwise so
     * the shortcut wheel keeps working in other apps.
     */
    private fun syncFocusPenLink(state: UiState) {
        val wanted = state.stylusButtonScheme == StylusButtonScheme.XiaomiFocusPen &&
            state.canvasVisible &&
            !state.canvasPassthrough
        if (wanted) focusPenLink.enable() else focusPenLink.disable()
    }

    private fun reportKeyEvent(event: KeyEvent) {
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "action=${event.action}"
        }
        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        val device = event.device?.name ?: "unknown device"
        Log.i(TAG, "KeyEvent ${event.keyCode} ($keyName) $actionName from $device")
        if (event.action == KeyEvent.ACTION_DOWN) {
            showToast(R.string.key_diagnostics_toast, event.keyCode, keyName, actionName, device)
        }
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
        showToast(R.string.save_location_updated)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Screen capture session (MediaProjection)
    // ═══════════════════════════════════════════════════════════════

    private fun handleScreenCapturePermissionResult(intent: Intent) {
        val mode = pendingExportMode
        pendingExportMode = null

        val resultCode = intent.getIntExtra(EXTRA_SCREEN_CAPTURE_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_SCREEN_CAPTURE_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            showToast(R.string.screen_capture_permission_denied)
            return
        }
        if (!startCaptureSession(resultCode, resultData)) {
            showToast(R.string.export_failed)
            return
        }
        if (mode == ExportMode.Screen) saveCurrentDrawing(mode)
    }

    /**
     * Exchanges a fresh consent for a long-lived session. The foreground
     * service must carry the mediaProjection type before getMediaProjection()
     * and for as long as the session lives (Android 14+ stops it otherwise).
     */
    private fun startCaptureSession(resultCode: Int, resultData: Intent): Boolean {
        releaseCaptureSession()
        elevateForegroundForScreenCapture()
        val (displayWidth, displayHeight) = getDisplaySize()
        val session = ScreenCaptureSession.start(
            context = this,
            resultCode = resultCode,
            resultData = resultData,
            width = displayWidth,
            height = displayHeight,
            dpi = resources.displayMetrics.densityDpi,
            onStopped = ::onCaptureSessionStopped,
        )
        if (session == null) {
            restoreRegularForegroundType()
            return false
        }
        captureSession = session
        Log.i(TAG, "Screen capture session started (${displayWidth}x${displayHeight})")
        return true
    }

    private fun onCaptureSessionStopped() {
        // Invoked on the main thread when the user/system ends the projection
        // (status-bar chip, screen lock policy, ...). Next save re-prompts.
        if (captureSession != null) {
            captureSession = null
            restoreRegularForegroundType()
        }
    }

    private fun releaseCaptureSession(restoreForegroundType: Boolean = true) {
        val session = captureSession ?: return
        captureSession = null
        session.release()
        if (restoreForegroundType) restoreRegularForegroundType()
    }

    private fun hasLiveCaptureSession(): Boolean =
        captureSession?.isStopped == false

    private fun elevateForegroundForScreenCapture() {
        if (foregroundHasProjectionType) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        foregroundHasProjectionType = true
    }

    private fun restoreRegularForegroundType() {
        if (!foregroundHasProjectionType) return
        foregroundHasProjectionType = false
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  Export
    // ═══════════════════════════════════════════════════════════════

    private fun saveCurrentDrawing(mode: ExportMode) {
        if (exportJob?.isActive == true) return
        if (mode == ExportMode.Screen &&
            !hasLiveCaptureSession() &&
            !viewModel.uiState.value.rootScreenshotEnabled
        ) {
            pendingExportMode = mode
            requestScreenCapturePermission()
            return
        }
        exportJob = serviceScope.launch { runExport(mode) }
    }

    private suspend fun runExport(mode: ExportMode) {
        var background: Bitmap? = null
        if (mode == ExportMode.Screen) {
            background = try {
                captureBackground()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Screen capture failed", error)
                showToast(R.string.export_failed)
                return
            }
            if (background == null) {
                // No usable session (root failed / projection stopped): ask once, then retry.
                pendingExportMode = mode
                requestScreenCapturePermission()
                return
            }
        }

        val result = runCatching {
            val bitmap = when (mode) {
                ExportMode.Transparent -> canvasView.renderToBitmap(backgroundColor = null)
                ExportMode.Paper -> canvasView.renderToBitmap(backgroundColor = PAPER_BACKGROUND_COLOR)
                ExportMode.Screen -> composeScreenExport(requireNotNull(background))
            }
            withContext(Dispatchers.IO) {
                saveBitmap(bitmap, mode).getOrThrow()
            }
        }
        result
            .onSuccess { displayName -> showToast(R.string.export_success, displayName) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Export failed", error)
                showToast(R.string.export_failed)
            }

        if (mode == ExportMode.Screen && !viewModel.uiState.value.keepScreenCaptureSession) {
            releaseCaptureSession()
        }
    }

    /**
     * Grabs what is behind the canvas with every DrawAnywhere window hidden.
     *
     * @return the background cropped to the canvas, or null when a consent
     *   dialog is required before anything can be captured.
     */
    private suspend fun captureBackground(): Bitmap? = withOverlaysHidden {
        val state = viewModel.uiState.value
        if (state.rootScreenshotEnabled) {
            val rootShot = RootScreenshot.capture()
            if (rootShot != null) return@withOverlaysHidden cropToCanvas(rootShot)
            showToast(R.string.root_screenshot_failed)
        }
        val session = captureSession?.takeIf { !it.isStopped } ?: return@withOverlaysHidden null
        val (displayWidth, displayHeight) = getDisplaySize()
        val fullScreen = withTimeout(CAPTURE_TIMEOUT_MS) {
            session.captureFrame(
                width = displayWidth,
                height = displayHeight,
                dpi = resources.displayMetrics.densityDpi,
                nudge = ::nudgeComposition,
            )
        }
        cropToCanvas(fullScreen)
    }

    /**
     * Hides every window this service owns. The toolbar and settings are
     * Dialogs whose window background (translucent color + blur) is drawn by
     * the window itself, so making the content view INVISIBLE is not enough:
     * the whole Dialog has to be hidden or its frame ends up in the capture.
     */
    private suspend fun <T> withOverlaysHidden(block: suspend () -> T): T {
        val toolbarShowing = ::toolbarDialog.isInitialized && toolbarDialog.isShowing
        val settingsShowing = settingsDialog?.isShowing == true
        val canvasVisibility = canvasView.visibility
        val dismissVisibility = dismissTargetView.visibility

        if (toolbarShowing) toolbarDialog.hide()
        if (settingsShowing) settingsDialog?.hide()
        canvasView.visibility = View.INVISIBLE
        dismissTargetView.visibility = View.GONE
        try {
            delay(OVERLAY_HIDE_SETTLE_MS)
            return block()
        } finally {
            canvasView.visibility = canvasVisibility
            dismissTargetView.visibility = dismissVisibility
            if (canvasParams.alpha != 1f) {
                canvasParams.alpha = 1f
                runCatching { windowManager.updateViewLayout(canvasView, canvasParams) }
            }
            if (toolbarShowing && ::toolbarDialog.isInitialized) runCatching { toolbarDialog.show() }
            if (settingsShowing) runCatching { settingsDialog?.show() }
        }
    }

    /**
     * Forces the compositor to produce a frame if attaching the capture
     * surface alone did not. The canvas view is INVISIBLE at this point, so a
     * window-alpha change is invisible on screen but still a WindowManager
     * transaction.
     */
    private fun nudgeComposition() {
        if (!canvasView.isAttachedToWindow) return
        canvasParams.alpha = if (canvasParams.alpha < 1f) 1f else 0.99f
        runCatching { windowManager.updateViewLayout(canvasView, canvasParams) }
    }

    /** Maps a full-display frame onto the canvas window's screen rectangle, 1:1. */
    private fun cropToCanvas(frame: Bitmap): Bitmap {
        val (displayWidth, displayHeight) = getDisplaySize()
        val normalized = if (frame.width != displayWidth || frame.height != displayHeight) {
            Log.w(TAG, "Capture is ${frame.width}x${frame.height}, display is ${displayWidth}x${displayHeight}; scaling")
            Bitmap.createScaledBitmap(frame, displayWidth, displayHeight, true).also {
                if (it !== frame) frame.recycle()
            }
        } else frame

        val targetWidth = canvasView.width.coerceAtLeast(1)
        val targetHeight = canvasView.height.coerceAtLeast(1)
        val location = IntArray(2).also(canvasView::getLocationOnScreen)
        val left = location[0].coerceIn(0, (normalized.width - 1).coerceAtLeast(0))
        val top = location[1].coerceIn(0, (normalized.height - 1).coerceAtLeast(0))
        val cropWidth = minOf(targetWidth, normalized.width - left)
        val cropHeight = minOf(targetHeight, normalized.height - top)

        if (left == 0 && top == 0 && cropWidth == targetWidth && cropHeight == targetHeight &&
            normalized.width == targetWidth && normalized.height == targetHeight
        ) return normalized

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        if (cropWidth > 0 && cropHeight > 0) {
            Canvas(result).drawBitmap(
                normalized,
                Rect(left, top, left + cropWidth, top + cropHeight),
                Rect(0, 0, cropWidth, cropHeight),
                null
            )
        }
        normalized.recycle()
        return result
    }

    private fun composeScreenExport(background: Bitmap): Bitmap {
        val drawing = canvasView.renderToBitmap(backgroundColor = null)
        return try {
            Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888).also { merged ->
                val canvas = Canvas(merged)
                canvas.drawBitmap(background, 0f, 0f, null)
                canvas.drawBitmap(drawing, 0f, 0f, null)
            }
        } finally {
            background.recycle()
            drawing.recycle()
        }
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

    private fun showToast(@StringRes message: Int, vararg formatArgs: Any) {
        Toast.makeText(this, getString(message, *formatArgs), Toast.LENGTH_LONG).show()
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    /** Full display bounds in the current rotation, system bars included. */
    private fun getDisplaySize(): Pair<Int, Int> {
        val bounds = windowManager.maximumWindowMetrics.bounds
        return bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
    }

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
        if (::focusPenLink.isInitialized) focusPenLink.disable()
        // The service is going away; do not call startForeground() again from here.
        releaseCaptureSession(restoreForegroundType = false)
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
