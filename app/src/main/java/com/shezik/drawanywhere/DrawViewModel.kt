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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shezik.drawanywhere.model.FocusPenGesture
import com.shezik.drawanywhere.model.FocusPenLinkMode
import com.shezik.drawanywhere.model.FocusPenLinkState
import com.shezik.drawanywhere.model.PenConfig
import com.shezik.drawanywhere.model.PenType
import com.shezik.drawanywhere.model.PRESET_COLORS
import com.shezik.drawanywhere.model.StrokeModifier
import com.shezik.drawanywhere.model.StylusButtonAction
import com.shezik.drawanywhere.model.StylusButtonScheme
import com.shezik.drawanywhere.model.StrokeSample
import com.shezik.drawanywhere.model.TOOLBAR_COLORS
import com.shezik.drawanywhere.view.canvas.CanvasViewport
import com.shezik.drawanywhere.view.canvas.LockMode
import com.shezik.drawanywhere.view.toolbar.ToolbarOrientation
import com.shezik.drawanywhere.view.toolbar.ToolbarOrientationMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val DEFAULT_EXPORT_RELATIVE_PATH = "Pictures/DrawAnywhere"

data class ServiceState(
    val toolbarPosition: Offset = Offset(32f, 64f),
    val toolbarActive: Boolean = true,
    val toolbarPositionInitialized: Boolean = false,
)

data class UiState(
    val canvasVisible: Boolean = true,
    val canvasPassthrough: Boolean = false,
    val autoClearCanvas: Boolean = false,
    val visibleOnStart: Boolean = true,
    val toolbarMinimized: Boolean = false,
    val fingerDrawingEnabled: Boolean = true,
    val stylusButtonScheme: StylusButtonScheme = StylusButtonScheme.XiaomiSmartPen,
    val stylusPrimaryButtonAction: StylusButtonAction = StylusButtonAction.CyclePresetColor,
    val stylusSecondaryButtonAction: StylusButtonAction = StylusButtonAction.ToggleStrokeEraser,
    // Xiaomi Focus Pen barrel gestures (scheme XiaomiFocusPen). Mapped in-app only.
    val focusPenSqueezeAction: StylusButtonAction = StylusButtonAction.CyclePresetColor,
    val focusPenDoubleTapAction: StylusButtonAction = StylusButtonAction.ToggleStrokeEraser,
    val focusPenSlideUpAction: StylusButtonAction = StylusButtonAction.IncreaseStrokeWidth,
    val focusPenSlideDownAction: StylusButtonAction = StylusButtonAction.DecreaseStrokeWidth,
    /**
     * A double tap is two squeezes; the second must start within this many ms
     * after the first is released. A lone squeeze fires once the window passes.
     */
    val focusPenDoubleTapWindowMs: Int = 400,
    /** Show a toast for every key event the canvas receives (troubleshooting). */
    val keyDiagnosticsEnabled: Boolean = false,
    /** Keep the MediaProjection session alive so screen saves ask for consent once per run. */
    val keepScreenCaptureSession: Boolean = true,
    /** Try `su screencap` first for screen-background saves (rooted devices only). */
    val rootScreenshotEnabled: Boolean = false,
    /** Which handshake implementation claims the Focus Pen gesture stream. */
    val focusPenLinkMode: FocusPenLinkMode = FocusPenLinkMode.Direct,
    /** Transient: keep the handshake alive even while the canvas is hidden/passthrough (test page). */
    val focusPenLinkForced: Boolean = false,
    val pressureEraserEnabled: Boolean = false,
    val pressureEraserThreshold: Float = 0.85f,
    val recentColors: List<Color> = emptyList(),
    val savedCustomColors: List<Color> = emptyList(),
    val savedPenWidths: List<Float> = emptyList(),
    val savedShapeWidths: List<Float> = emptyList(),
    val savedLaserWidths: List<Float> = emptyList(),
    val savedEraserSizes: List<Float> = emptyList(),
    val exportTreeUri: String? = null,
    val stylusCycleColors: List<Color> = PRESET_COLORS,

    val currentPenType: PenType = PenType.Pen,
    val penConfigs: Map<PenType, PenConfig> = defaultPenConfigs(),

    val toolbarOrientation: ToolbarOrientation = ToolbarOrientation.HORIZONTAL,
    val toolbarOrientationMode: ToolbarOrientationMode = ToolbarOrientationMode.HORIZONTAL,
    val firstDrawerOpen: Boolean = canvasVisible,
    val secondDrawerOpen: Boolean = false,

    val firstDrawerButtons: Set<String> = setOf(
        "undo", "clear", "tool_controls", "color_picker", "zoom_lock"
    ),
    val secondDrawerButtons: Set<String> = setOf(
        "passthrough", "redo", "minimize", "settings"
    ),
    val secondDrawerPinnedButtons: List<String> = defaultQuickLaunchActions()
) {
    val currentPenConfig: PenConfig
        get() = penConfigs[currentPenType] ?: PenConfig()

    fun actionForFocusPenGesture(gesture: FocusPenGesture): StylusButtonAction = when (gesture) {
        FocusPenGesture.Squeeze -> focusPenSqueezeAction
        FocusPenGesture.DoubleTap -> focusPenDoubleTapAction
        FocusPenGesture.SlideUp -> focusPenSlideUpAction
        FocusPenGesture.SlideDown -> focusPenSlideDownAction
    }
}

fun defaultQuickLaunchActions(): List<String> = listOf(
    "redo", "visibility", "passthrough", "save", "orientation", "settings", "quit"
)

fun defaultPenConfigs(): Map<PenType, PenConfig> = PenType.entries.associateWith { type ->
    if (type.isEraser) {
        val w = if (type == PenType.StrokeEraser) 50f else 30f
        PenConfig(penType = type, width = w, color = Color.LightGray)
    } else {
        PenConfig(penType = type)
    }
}

@OptIn(FlowPreview::class)
class DrawViewModel(
    private val controller: DrawController,
    private val preferencesManager: PreferencesManager,
    initialUiState: UiState,
    initialServiceState: ServiceState,
    private val stopService: () -> Unit,
    private val containsDismissTarget: (Int, Int) -> Boolean,
) : ViewModel() {
    companion object {
        const val TOOLBAR_DIM_DELAY_MS = 3_000L
        const val TOOLBAR_DIM_ALPHA = 0.5f
        const val TOOLBAR_DIM_DURATION_MS = 300L

        /** Multiplicative step used by the thicker/thinner stylus actions. */
        const val STROKE_WIDTH_STEP = 1.25f
        const val MIN_STROKE_WIDTH_PX = 0.5f
        const val MAX_STROKE_WIDTH_PX = 400f
    }
    private val _uiState = MutableStateFlow(initialUiState)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _focusPenLinkState = MutableStateFlow(FocusPenLinkState())
    /** Link to the HyperOS pen service; updated by the service that owns it. */
    val focusPenLinkState: StateFlow<FocusPenLinkState> = _focusPenLinkState.asStateFlow()

    /** Set by the owning service so settings can re-run the system handshake. */
    var onRetryFocusPenLink: (() -> Unit)? = null

    /** Set by the owning service: re-sends setEnable(touchFilm, 1) on the live link. */
    var onResendFocusPenEnable: (() -> Unit)? = null

    fun updateFocusPenLinkState(state: FocusPenLinkState) {
        _focusPenLinkState.value = state
    }

    fun retryFocusPenLink() {
        onRetryFocusPenLink?.invoke()
    }

    fun resendFocusPenEnable() {
        onResendFocusPenEnable?.invoke()
    }

    fun setFocusPenLinkMode(mode: FocusPenLinkMode) =
        _uiState.update { it.copy(focusPenLinkMode = mode) }

    fun setFocusPenLinkForced(forced: Boolean) =
        _uiState.update { it.copy(focusPenLinkForced = forced) }

    private val _serviceState = MutableStateFlow(initialServiceState)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _viewport = MutableStateFlow(CanvasViewport())
    val viewport: StateFlow<CanvasViewport> = _viewport.asStateFlow()

    private val _lockMode = MutableStateFlow(LockMode.NONE)
    val lockMode: StateFlow<LockMode> = _lockMode.asStateFlow()

    private val _dismissTarget = MutableStateFlow<DismissTarget>(DismissTarget.Hidden)
    val dismissTarget: StateFlow<DismissTarget> = _dismissTarget.asStateFlow()

    val canUndo: StateFlow<Boolean> = controller.canUndo
    val canRedo: StateFlow<Boolean> = controller.canRedo
    val canClearCanvas: StateFlow<Boolean> = controller.canClearStrokes

    init {

        _uiState
            .debounce(300)
            .onEach { state -> preferencesManager.saveUiState(state) }
            .launchIn(viewModelScope)

        resetToolbarTimer()
    }

    override fun onCleared() {
        super.onCleared()
        dimmingJob?.cancel()
    }

    fun switchToPen(type: PenType) {
        if (type.isEraser) lastEraserPenType = type
        _uiState.update { it.copy(currentPenType = type) }
        controller.setPenConfig(uiState.value.currentPenConfig)
    }

    fun switchToLastEraser() {
        switchToPen(lastEraserPenType.takeIf { it.isEraser } ?: PenType.StrokeEraser)
    }

    fun stepStrokeWidth(factor: Float) {
        val current = uiState.value.currentPenConfig.width
        setStrokeWidth((current * factor).coerceIn(MIN_STROKE_WIDTH_PX, MAX_STROKE_WIDTH_PX))
    }

    fun resolvePenType(modifier: StrokeModifier) =
        when (modifier) {
            StrokeModifier.PrimaryButton   -> PenType.StrokeEraser
            StrokeModifier.SecondaryButton -> PenType.StrokeEraser
            StrokeModifier.Both            -> PenType.StrokeEraser
            StrokeModifier.None            -> uiState.value.currentPenType
        }

    private var previousPenType: PenType? = null
    private var isStrokeDown: Boolean = false
    private var stylusEraserReturnPenType: PenType = PenType.Pen
    private var stylusLaserReturnPenType: PenType = PenType.Pen
    private var lastEraserPenType: PenType = PenType.StrokeEraser

    fun startStroke(point: Offset, modifier: StrokeModifier) {
        startStroke(StrokeSample(point), modifier)
    }

    fun startStroke(sample: StrokeSample, modifier: StrokeModifier) {
        finishStroke()

        val newPenType = resolvePenType(modifier)
        if (newPenType != uiState.value.currentPenType) {
            previousPenType = uiState.value.currentPenType
            switchToPen(newPenType)
        }

        controller.createStroke(sample)
        isStrokeDown = true
    }

    fun updateStroke(point: Offset) {
        updateStroke(StrokeSample(point))
    }

    fun updateStroke(sample: StrokeSample) {
        if (!isStrokeDown) return
        controller.updateLatestStroke(sample)
    }

    fun finishStroke() {
        if (!isStrokeDown) return

        controller.finishStroke()

        previousPenType?.let {
            switchToPen(it)
            previousPenType = null
        }
        isStrokeDown = false
    }

    fun toggleCanvasVisibility() =
        setCanvasVisibility(!uiState.value.canvasVisible)

    fun setCanvasVisibility(visible: Boolean) {
        var currentPassthrough = uiState.value.canvasPassthrough

        if (uiState.value.autoClearCanvas && !visible) {
            clearCanvas()
            currentPassthrough = false
        }

        val currentFirstOpen = uiState.value.firstDrawerOpen
        _uiState.update { it.copy(
            canvasVisible = visible,
            canvasPassthrough = currentPassthrough,
            firstDrawerOpen = !currentFirstOpen
        ) }
    }

    fun toggleCanvasPassthrough() =
        setCanvasPassthrough(!uiState.value.canvasPassthrough)

    fun setCanvasPassthrough(passthrough: Boolean) {
        _uiState.update { it.copy(canvasPassthrough = passthrough) }
    }

    fun setPenColor(color: Color, trackRecent: Boolean = true) {
        updateCurrentPenConfig { copy(color = color) }
        if (trackRecent) addRecentColor(color)
    }

    fun setPresetColor(color: Color) = setPenColor(color, trackRecent = false)

    fun cyclePresetColor() = cycleThroughColors(uiState.value.stylusCycleColors.ifEmpty { PRESET_COLORS })

    /** Steps through the ten swatches shown on the toolbar. */
    fun cycleToolbarColor() = cycleThroughColors(TOOLBAR_COLORS)

    private fun cycleThroughColors(colors: List<Color>) {
        if (colors.isEmpty()) return
        val currentType = uiState.value.currentPenType
        if (currentType.isEraser) {
            switchToPen(stylusEraserReturnPenType.takeUnless { it.isEraser } ?: PenType.Pen)
        }

        val currentColor = uiState.value.currentPenConfig.color.toArgb()
        val currentIndex = colors.indexOfFirst { it.toArgb() == currentColor }
        setPresetColor(colors[(currentIndex + 1).floorMod(colors.size)])
    }

    fun toggleStrokeEraser() {
        toggleTool(PenType.StrokeEraser)
    }

    fun togglePixelEraser() {
        toggleTool(PenType.PixelEraser)
    }

    fun toggleLaser() {
        val currentType = uiState.value.currentPenType
        if (currentType == PenType.Laser) {
            switchToPen(stylusLaserReturnPenType.takeUnless { it == PenType.Laser } ?: PenType.Pen)
        } else {
            stylusLaserReturnPenType = currentType
            switchToPen(PenType.Laser)
        }
    }

    fun performStylusButtonAction(action: StylusButtonAction) {
        when (action) {
            StylusButtonAction.None -> {}
            StylusButtonAction.CyclePresetColor -> cyclePresetColor()
            StylusButtonAction.CycleToolbarColor -> cycleToolbarColor()
            StylusButtonAction.ToggleStrokeEraser -> toggleStrokeEraser()
            StylusButtonAction.TogglePixelEraser -> togglePixelEraser()
            StylusButtonAction.Undo -> undo()
            StylusButtonAction.Redo -> redo()
            StylusButtonAction.ToggleCanvasVisibility -> toggleCanvasVisibility()
            StylusButtonAction.ToggleCanvasPassthrough -> toggleCanvasPassthrough()
            StylusButtonAction.ToggleLaser -> toggleLaser()
            StylusButtonAction.ClearCanvas -> clearCanvas()
            StylusButtonAction.IncreaseStrokeWidth -> stepStrokeWidth(STROKE_WIDTH_STEP)
            StylusButtonAction.DecreaseStrokeWidth -> stepStrokeWidth(1f / STROKE_WIDTH_STEP)
            StylusButtonAction.ToggleToolbarMinimized -> setToolbarMinimized(!uiState.value.toolbarMinimized)
        }
    }

    fun performFocusPenGesture(gesture: FocusPenGesture) {
        performStylusButtonAction(uiState.value.actionForFocusPenGesture(gesture))
    }

    fun setFocusPenGestureAction(gesture: FocusPenGesture, action: StylusButtonAction) =
        _uiState.update { state ->
            when (gesture) {
                FocusPenGesture.Squeeze -> state.copy(focusPenSqueezeAction = action)
                FocusPenGesture.DoubleTap -> state.copy(focusPenDoubleTapAction = action)
                FocusPenGesture.SlideUp -> state.copy(focusPenSlideUpAction = action)
                FocusPenGesture.SlideDown -> state.copy(focusPenSlideDownAction = action)
            }
        }

    fun setKeyDiagnosticsEnabled(state: Boolean) =
        _uiState.update { it.copy(keyDiagnosticsEnabled = state) }

    fun setFocusPenDoubleTapWindowMs(windowMs: Int) =
        _uiState.update { it.copy(focusPenDoubleTapWindowMs = windowMs.coerceIn(150, 1_000)) }

    fun setKeepScreenCaptureSession(state: Boolean) =
        _uiState.update { it.copy(keepScreenCaptureSession = state) }

    fun setRootScreenshotEnabled(state: Boolean) =
        _uiState.update { it.copy(rootScreenshotEnabled = state) }

    private fun toggleTool(toolType: PenType) {
        val currentType = uiState.value.currentPenType
        if (currentType == toolType) {
            switchToPen(stylusEraserReturnPenType.takeUnless { it.isEraser } ?: PenType.Pen)
        } else {
            stylusEraserReturnPenType = currentType
            switchToPen(toolType)
        }
    }

    private fun addRecentColor(color: Color) {
        val current = uiState.value.recentColors
        if (current.contains(color)) return
        val updated = listOf(color) + current
        _uiState.update { it.copy(recentColors = updated.take(6)) }
    }

    fun saveCurrentCustomColor(color: Color = uiState.value.currentPenConfig.color) {
        val current = uiState.value.savedCustomColors.filterNot { it.toArgb() == color.toArgb() }
        _uiState.update { it.copy(savedCustomColors = (listOf(color) + current).take(6)) }
    }

    fun saveCurrentWidthPreset() {
        val width = uiState.value.currentPenConfig.width
        _uiState.update { state ->
            when (state.currentPenType) {
                PenType.Pen -> state.copy(savedPenWidths = saveWidthToList(state.savedPenWidths, width))
                PenType.Rectangle, PenType.Ellipse -> state.copy(savedShapeWidths = saveWidthToList(state.savedShapeWidths, width))
                PenType.Laser -> state.copy(savedLaserWidths = saveWidthToList(state.savedLaserWidths, width))
                PenType.StrokeEraser, PenType.PixelEraser -> state.copy(savedEraserSizes = saveWidthToList(state.savedEraserSizes, width))
            }
        }
    }

    private fun saveWidthToList(current: List<Float>, width: Float): List<Float> {
        val deduped = current.filterNot { kotlin.math.abs(it - width) < 0.5f }
        return (listOf(width) + deduped).take(4)
    }

    fun setStrokeWidth(width: Float) = updateCurrentPenConfig { copy(width = width) }

    fun setStrokeAlpha(alpha: Float) = updateCurrentPenConfig { copy(alpha = alpha) }

    private fun updateCurrentPenConfig(transform: PenConfig.() -> PenConfig) {
        _uiState.update { state ->
            val configs = state.penConfigs.toMutableMap()
            val current = configs[state.currentPenType] ?: PenConfig(penType = state.currentPenType)
            configs[state.currentPenType] = current.transform()
            state.copy(penConfigs = configs)
        }
        controller.setPenConfig(uiState.value.currentPenConfig)
    }

    fun setToolbarPosition(position: Offset) =
        _serviceState.update { it.copy(toolbarPosition = position) }

    fun markToolbarPositionInitialized() =
        _serviceState.update { it.copy(toolbarPositionInitialized = true) }

    fun updateToolbarPosition(offset: Offset) =
        setToolbarPosition(serviceState.value.toolbarPosition + offset)

    fun saveToolbarPosition() = viewModelScope.launch {
        val state = serviceState.value.let {
            if (it.toolbarPositionInitialized) it else it.copy(toolbarPositionInitialized = true)
        }
        _serviceState.value = state
        preferencesManager.saveServiceState(state)
    }

    fun clearCanvas() = controller.clearStrokes()
    fun undo() = controller.undo()
    fun redo() = controller.redo()

    private var dimmingJob: Job? = null

    fun resetToolbarTimer() {
        dimmingJob?.cancel()
        setToolbarActive(true)
        dimmingJob = viewModelScope.launch {
            delay(TOOLBAR_DIM_DELAY_MS)
            setToolbarActive(false)
        }
    }

    fun setToolbarActive(state: Boolean) =
        _serviceState.update { it.copy(toolbarActive = state) }

    fun toggleToolbarOrientation() =
        setToolbarOrientationMode(
            when (uiState.value.toolbarOrientation) {
                ToolbarOrientation.VERTICAL -> ToolbarOrientation.HORIZONTAL
                ToolbarOrientation.HORIZONTAL -> ToolbarOrientation.VERTICAL
            }.toMode()
        )

    fun setToolbarOrientation(orientation: ToolbarOrientation) =
        _uiState.update { it.copy(toolbarOrientation = orientation) }

    fun setToolbarOrientationMode(mode: ToolbarOrientationMode) {
        _uiState.update { state ->
            state.copy(
                toolbarOrientationMode = mode,
                toolbarOrientation = when (mode) {
                    ToolbarOrientationMode.HORIZONTAL -> ToolbarOrientation.HORIZONTAL
                    ToolbarOrientationMode.VERTICAL -> ToolbarOrientation.VERTICAL
                }
            )
        }
    }

    fun toggleFirstDrawer() =
        setFirstDrawerOpen(!uiState.value.firstDrawerOpen)

    fun setFirstDrawerOpen(state: Boolean) =
        _uiState.update { it.copy(firstDrawerOpen = state) }

    fun toggleSecondDrawer() =
        setSecondDrawerOpen(!uiState.value.secondDrawerOpen)

    fun setSecondDrawerOpen(state: Boolean) =
        _uiState.update { it.copy(secondDrawerOpen = state) }

    fun setQuickLaunchActionOrder(actionIds: List<String>) =
        _uiState.update { it.copy(secondDrawerPinnedButtons = normalizeQuickLaunchActionOrder(actionIds)) }

    fun moveQuickLaunchAction(fromIndex: Int, toIndex: Int) {
        val current = uiState.value.secondDrawerPinnedButtons
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val reordered = current.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        setQuickLaunchActionOrder(reordered)
    }

    private fun normalizeQuickLaunchActionOrder(actionIds: List<String>): List<String> {
        val distinct = actionIds.map(String::trim).filter(String::isNotEmpty).distinct()
        return if (distinct.isEmpty()) defaultQuickLaunchActions() else distinct
    }

    fun setAutoClearCanvas(state: Boolean) =
        _uiState.update { it.copy(autoClearCanvas = state) }

    fun setVisibleOnStart(state: Boolean) =
        _uiState.update { it.copy(visibleOnStart = state) }

    fun setToolbarMinimized(state: Boolean) =
        _uiState.update { it.copy(toolbarMinimized = state) }

    fun setFingerDrawingEnabled(state: Boolean) =
        _uiState.update { it.copy(fingerDrawingEnabled = state) }

    fun setStylusButtonScheme(scheme: StylusButtonScheme) =
        _uiState.update { it.copy(stylusButtonScheme = scheme) }

    fun setStylusPrimaryButtonAction(action: StylusButtonAction) =
        _uiState.update { it.copy(stylusPrimaryButtonAction = action) }

    fun setStylusSecondaryButtonAction(action: StylusButtonAction) =
        _uiState.update { it.copy(stylusSecondaryButtonAction = action) }

    fun setPressureEraserEnabled(state: Boolean) =
        _uiState.update { it.copy(pressureEraserEnabled = state) }

    fun setPressureEraserThreshold(threshold: Float) =
        _uiState.update { it.copy(pressureEraserThreshold = threshold.coerceIn(0f, 1f)) }

    fun setExportTreeUri(uri: String?) =
        _uiState.update { it.copy(exportTreeUri = uri?.takeIf(String::isNotBlank)) }

    fun resetExportLocation() =
        setExportTreeUri(null)

    fun addStylusCycleColor(color: Color) {
        val normalized = color.copy(alpha = 1f)
        _uiState.update { state ->
            val updated = state.stylusCycleColors.filterNot { it.toArgb() == normalized.toArgb() } + normalized
            state.copy(stylusCycleColors = updated.takeLast(24))
        }
    }

    fun removeStylusCycleColor(color: Color) {
        _uiState.update { state ->
            val updated = state.stylusCycleColors.filterNot { it.toArgb() == color.toArgb() }
            state.copy(stylusCycleColors = updated.ifEmpty { PRESET_COLORS })
        }
    }

    fun resetStylusCycleColors() =
        _uiState.update { it.copy(stylusCycleColors = PRESET_COLORS) }

    private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

    // --- Viewport ---

    fun setViewport(viewport: CanvasViewport) {
        _viewport.value = viewport
    }

    fun cycleLockMode() {
        _lockMode.update { current ->
            when (current) {
                LockMode.NONE -> LockMode.ZOOM
                LockMode.ZOOM -> LockMode.ALL
                LockMode.ALL -> LockMode.NONE
            }
        }
    }

    fun setLockMode(mode: LockMode) {
        _lockMode.value = mode
    }

    fun resetViewport(screenCenter: Offset) {
        _viewport.value = _viewport.value.resetAt(screenCenter)
    }

    fun onDismissDragStart() { _dismissTarget.value = DismissTarget.Visible(false) }

    fun onDismissDragMove(fingerPosInToolbar: Offset) {
        val pos = serviceState.value.toolbarPosition
        val active = containsDismissTarget(
            (pos.x + fingerPosInToolbar.x).toInt(),
            (pos.y + fingerPosInToolbar.y).toInt()
        )
        _dismissTarget.value = DismissTarget.Visible(active)
    }

    /** @return false if the drag ended in a dismiss — caller should skip saving position. */
    fun onDismissDragEnd(): Boolean {
        val target = _dismissTarget.value
        if (target is DismissTarget.Visible && target.active) {
            quitApplication(savePosition = false)
            return false
        }
        _dismissTarget.value = DismissTarget.Hidden
        return true
    }

    fun quitApplication(savePosition: Boolean = true) {
        viewModelScope.launch {
            preferencesManager.saveUiState(uiState.value)
            if (savePosition) preferencesManager.saveServiceState(serviceState.value)
            stopService()
        }
    }
}

private fun ToolbarOrientation.toMode(): ToolbarOrientationMode =
    when (this) {
        ToolbarOrientation.HORIZONTAL -> ToolbarOrientationMode.HORIZONTAL
        ToolbarOrientation.VERTICAL -> ToolbarOrientationMode.VERTICAL
    }

sealed class DismissTarget {
    object Hidden : DismissTarget()
    data class Visible(val active: Boolean) : DismissTarget()
}
