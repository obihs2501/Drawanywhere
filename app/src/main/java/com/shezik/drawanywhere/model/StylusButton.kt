package com.shezik.drawanywhere.model

enum class StylusButtonScheme {
    Disabled,
    XiaomiSmartPen,
    /** 小米焦点触控笔二代/Pro：轻捏、连击、上下滑（系统以 KeyEvent 194–197 上报） */
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
}

/**
 * 焦点触控笔笔身手势。系统直接以独立 KeyCode 上报，
 * 应用内自定义映射，不读写系统手写笔设置项。
 */
enum class FocusPenGesture {
    /** 轻捏（捏一下笔身） */
    Squeeze,

    /** 连击 / 双击笔身 */
    MultiTap,

    /** 笔身上滑 */
    SlideUp,

    /** 笔身下滑 */
    SlideDown,
}
