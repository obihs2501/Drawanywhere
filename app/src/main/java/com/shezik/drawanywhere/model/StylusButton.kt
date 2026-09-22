package com.shezik.drawanywhere.model

enum class StylusButtonScheme {
    Disabled,
    XiaomiSmartPen,

    /**
     * Xiaomi Focus Pen (焦点触控笔二代 / Pro): squeeze, double-tap and barrel
     * slide gestures. HyperOS reports them as KeyEvents 194–197 once the app
     * has registered itself with the system pencil-engine service.
     */
    XiaomiFocusPen,
}

enum class StylusButtonAction {
    None,
    CyclePresetColor,
    ToggleStrokeEraser,
    TogglePixelEraser,
    Undo,
    Redo,
    ToggleCanvasVisibility,
    ToggleCanvasPassthrough,
    ToggleLaser,
    SwitchPreviousPen,
    ClearCanvas,
    IncreaseStrokeWidth,
    DecreaseStrokeWidth,
    ToggleToolbarMinimized,
}

/** Barrel gestures of the Xiaomi Focus Pen. Mapping to actions is app-local. */
enum class FocusPenGesture {
    Squeeze,
    DoubleTap,
    SlideUp,
    SlideDown,
}

/**
 * Runtime state of the link to the system pencil-engine service
 * (`com.xiaomi.touchservice`). Purely informational; shown in settings so the
 * user can tell whether HyperOS has handed gestures over to this app.
 */
data class FocusPenLinkState(
    val phase: Phase = Phase.Idle,
    /** Whether the pen service package is installed (null = not checked yet). */
    val touchServiceInstalled: Boolean? = null,
    /** Whether the system pencil-engine jar the SDK requires is present. */
    val engineJarPresent: Boolean? = null,
    /** Whether the SDK's init() returned true. */
    val sdkInitOk: Boolean? = null,
    /** Return value of the touch-film enable command, when known. */
    val enableResult: Int? = null,
    val detail: String? = null,
) {
    enum class Phase { Idle, ServiceMissing, Binding, Connected, Disconnected, Failed }
}
