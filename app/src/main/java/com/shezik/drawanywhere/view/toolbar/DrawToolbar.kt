package com.shezik.drawanywhere.view.toolbar

import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.shezik.drawanywhere.DrawViewModel
import com.shezik.drawanywhere.R
import com.shezik.drawanywhere.UiState
import com.shezik.drawanywhere.model.PenConfig
import com.shezik.drawanywhere.model.PenType
import com.shezik.drawanywhere.model.TOOLBAR_COLORS
import com.shezik.drawanywhere.ui.theme.DrawAnywhereTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class MainTool(
    val icon: ImageVector,
    val accent: Color,
) {
    Pen(PenNib24Px, Color(0xFF7FB1FF)),
    Eraser(InkEraser24Px, Color(0xFFFF8A80)),
    Shape(Icons.Default.CropSquare, Color(0xFF8FC4FF)),
    Laser(Icons.Default.FlashOn, Color(0xFF69D2E7)),
}

private val toolbarColors = TOOLBAR_COLORS

private val penWidthsMm = listOf(0.10f, 0.30f, 0.50f)
private val shapeWidthsMm = listOf(0.30f, 0.50f, 0.80f)
private val laserWidthsMm = listOf(0.10f, 0.30f, 0.50f)
private val eraserSizesMm = listOf(3f, 6f, 10f)

@Composable
fun DrawToolbar(
    viewModel: DrawViewModel,
    canRedo: Boolean,
    onRedo: () -> Unit,
    passthroughEnabled: Boolean,
    onTogglePassthrough: () -> Unit,
    onSaveTransparent: () -> Unit,
    onSaveWithBackdrop: () -> Unit,
    onSaveWithScreenBackdrop: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val haptics = LocalHapticFeedback.current
    val metrics = LocalContext.current.resources.displayMetrics

    var colorPopupFor by remember { mutableStateOf<MainTool?>(null) }
    var widthPopupFor by remember { mutableStateOf<MainTool?>(null) }
    var expandedWidthPx by remember { mutableStateOf(0) }
    val collapsedWidthPx = with(LocalDensity.current) { 64.dp.roundToPx() }
    var lastToolbarMinimized by remember { mutableStateOf(uiState.toolbarMinimized) }

    if (uiState.toolbarMinimized != lastToolbarMinimized) {
        val widthDelta = (expandedWidthPx - collapsedWidthPx).coerceAtLeast(0)
        if (widthDelta > 0) {
            val deltaX = if (uiState.toolbarMinimized) widthDelta.toFloat() else -widthDelta.toFloat()
            viewModel.updateToolbarPosition(Offset(deltaX, 0f))
            viewModel.saveToolbarPosition()
        }
        lastToolbarMinimized = uiState.toolbarMinimized
    }

    DrawAnywhereTheme {
        BoxWithConstraints {
            DraggableToolbarCard(
                modifier = modifier
                    .widthIn(max = maxWidth)
                    .padding(0.dp),
                haptics = haptics,
                onPositionChange = viewModel::updateToolbarPosition,
                onPositionSaved = viewModel::saveToolbarPosition,
                onToolbarInteracted = viewModel::resetToolbarTimer,
            ) {
                ToolbarShell {
                    if (uiState.toolbarMinimized) {
                        CollapsedToolbar(
                            uiState = uiState,
                            haptics = haptics,
                            onPositionChange = viewModel::updateToolbarPosition,
                            onPositionSaved = viewModel::saveToolbarPosition,
                        ) {
                            viewModel.setToolbarMinimized(false)
                        }
                    } else {
                        val selectMainTool: (MainTool) -> Unit = { tool ->
                            when (tool) {
                                MainTool.Pen -> viewModel.switchToPen(PenType.Pen)
                                MainTool.Eraser -> viewModel.switchToLastEraser()
                                MainTool.Shape -> {
                                    if (uiState.currentPenType != PenType.Ellipse) viewModel.switchToPen(PenType.Rectangle)
                                    else viewModel.switchToPen(PenType.Ellipse)
                                }
                                MainTool.Laser -> viewModel.switchToPen(PenType.Laser)
                            }
                        }
                        val detail: @Composable (Boolean) -> Unit = { vertical ->
                            ToolDetailArea(
                                uiState = uiState,
                                metrics = metrics,
                                vertical = vertical,
                                onColorSelected = viewModel::setPresetColor,
                                onStrokeEraserSelected = { viewModel.switchToPen(PenType.StrokeEraser) },
                                onPixelEraserSelected = { viewModel.switchToPen(PenType.PixelEraser) },
                                onRectangleSelected = { viewModel.switchToPen(PenType.Rectangle) },
                                onEllipseSelected = { viewModel.switchToPen(PenType.Ellipse) },
                                onWidthSelected = { mm -> viewModel.setStrokeWidth(mmToPx(mm, metrics)) },
                                onClearCanvas = viewModel::clearCanvas,
                                onOpenColorPopup = { colorPopupFor = it },
                                onOpenWidthPopup = { widthPopupFor = it },
                            )
                        }
                        val utilities: @Composable () -> Unit = {
                            ToolbarUtilitySegment(
                                canRedo = canRedo,
                                onRedo = onRedo,
                                canvasVisible = uiState.canvasVisible,
                                onToggleCanvasVisibility = viewModel::toggleCanvasVisibility,
                                passthroughEnabled = passthroughEnabled,
                                onTogglePassthrough = onTogglePassthrough,
                                onSaveTransparent = onSaveTransparent,
                                onSaveWithBackdrop = onSaveWithBackdrop,
                                onSaveWithScreenBackdrop = onSaveWithScreenBackdrop,
                                onOpenSettings = onOpenSettings,
                                onQuit = onQuit,
                                onToggleOrientation = viewModel::toggleToolbarOrientation,
                                quickLaunchActionIds = uiState.secondDrawerPinnedButtons,
                                onSetQuickLaunchActionOrder = viewModel::setQuickLaunchActionOrder,
                            )
                        }
                        if (uiState.toolbarOrientation == ToolbarOrientation.VERTICAL) {
                            Column(
                                modifier = Modifier
                                    .onSizeChanged { expandedWidthPx = it.width }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                MainToolColumn(uiState = uiState, onSelectMainTool = selectMainTool)
                                HorizontalSeparator(width = 58.dp)
                                detail(true)
                                HorizontalSeparator(width = 58.dp)
                                utilities()
                                DragCollapseHandle(
                                    haptics = haptics,
                                    onPositionChange = viewModel::updateToolbarPosition,
                                    onPositionSaved = viewModel::saveToolbarPosition,
                                    onTap = { viewModel.setToolbarMinimized(true) },
                                    compact = true,
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .onSizeChanged { expandedWidthPx = it.width }
                                    .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                MainToolRow(uiState = uiState, onSelectMainTool = selectMainTool)
                                VerticalSeparator(height = 42.dp)
                                detail(false)
                                VerticalSeparator(height = 42.dp)
                                utilities()
                                VerticalSeparator(height = 42.dp)
                                DragCollapseHandle(
                                    haptics = haptics,
                                    onPositionChange = viewModel::updateToolbarPosition,
                                    onPositionSaved = viewModel::saveToolbarPosition,
                                    onTap = { viewModel.setToolbarMinimized(true) },
                                )
                            }
                        }
                    }

                    colorPopupFor?.let { tool ->
                        SmallColorPopup(
                            tool = tool,
                            currentColor = colorForTool(uiState, tool).copy(alpha = alphaForTool(uiState, tool)),
                            savedCustomColors = uiState.savedCustomColors,
                            onDismiss = { colorPopupFor = null },
                            onApply = { color ->
                                switchToolIfNeeded(viewModel, uiState, tool)
                                viewModel.setPenColor(color.copy(alpha = 1f))
                                viewModel.setStrokeAlpha(color.alpha)
                            },
                            onSaveCustom = { color ->
                                switchToolIfNeeded(viewModel, uiState, tool)
                                viewModel.setPenColor(color.copy(alpha = 1f))
                                viewModel.setStrokeAlpha(color.alpha)
                                viewModel.saveCurrentCustomColor(color)
                            },
                        )
                    }

                    widthPopupFor?.let { tool ->
                        SmallWidthPopup(
                            tool = tool,
                            currentWidthPx = currentWidthForTool(uiState, tool),
                            savedCustomWidths = savedWidthsForTool(uiState, tool),
                            metrics = metrics,
                            onDismiss = { widthPopupFor = null },
                            onApply = { px ->
                                switchToolIfNeeded(viewModel, uiState, tool)
                                viewModel.setStrokeWidth(px)
                            },
                            onSaveCurrent = {
                                switchToolIfNeeded(viewModel, uiState, tool)
                                viewModel.saveCurrentWidthPreset()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarShell(
    content: @Composable () -> Unit,
) {
    FloatingToolbar(
        color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.16f),
        cornerRadius = 18.dp,
        outSidePadding = PaddingValues(0.dp),
        showDivider = false,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.24f),
                            MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.14f),
                        )
                    )
                ),
        ) { content() }
    }
}

@Composable
private fun CollapsedToolbar(
    uiState: UiState,
    modifier: Modifier = Modifier,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onPositionChange: (Offset) -> Unit,
    onPositionSaved: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        modifier = modifier
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainToolButton(
            tool = mainToolForPenType(uiState.currentPenType),
            selected = true,
            accent = colorForTool(uiState, mainToolForPenType(uiState.currentPenType)),
            compact = true,
            onClick = onExpand,
        )
        DragCollapseHandle(
            haptics = haptics,
            onPositionChange = onPositionChange,
            onPositionSaved = onPositionSaved,
            onTap = onExpand,
            compact = true,
        )
    }
}

private const val MAX_QUICK_ACTIONS = 3

private data class UtilityActionSpec(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun ToolbarUtilitySegment(
    canRedo: Boolean,
    onRedo: () -> Unit,
    canvasVisible: Boolean,
    onToggleCanvasVisibility: () -> Unit,
    passthroughEnabled: Boolean,
    onTogglePassthrough: () -> Unit,
    onSaveTransparent: () -> Unit,
    onSaveWithBackdrop: () -> Unit,
    onSaveWithScreenBackdrop: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit,
    onToggleOrientation: () -> Unit,
    quickLaunchActionIds: List<String>,
    onSetQuickLaunchActionOrder: (List<String>) -> Unit,
) {
    var saveMenuExpanded by remember { mutableStateOf(false) }
    var launcherExpanded by remember { mutableStateOf(false) }
    val passiveBg = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    val activeBg = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    val iconColor = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.88f)
    val popupAlignment = Alignment.TopCenter
    val popupOffset = IntOffset(0, -66)
    val actions = listOf(
        UtilityActionSpec(
            id = "redo",
            icon = Icons.AutoMirrored.Filled.Redo,
            label = stringResource(R.string.redo),
            enabled = canRedo,
            onClick = onRedo,
        ),
        UtilityActionSpec(
            id = "save",
            icon = Icons.Default.SaveAlt,
            label = stringResource(R.string.save_drawing),
            selected = saveMenuExpanded,
            onClick = {
                saveMenuExpanded = !saveMenuExpanded
                launcherExpanded = false
            },
        ),
        UtilityActionSpec(
            id = "visibility",
            icon = if (canvasVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            label = if (canvasVisible) stringResource(R.string.hide_canvas) else stringResource(R.string.show_canvas),
            selected = !canvasVisible,
            onClick = {
                saveMenuExpanded = false
                launcherExpanded = false
                onToggleCanvasVisibility()
            },
        ),
        UtilityActionSpec(
            id = "passthrough",
            icon = if (passthroughEnabled) Icons.Default.DoNotTouch else Icons.Default.TouchApp,
            label = if (passthroughEnabled) {
                stringResource(R.string.disable_passthrough)
            } else {
                stringResource(R.string.enable_passthrough)
            },
            selected = passthroughEnabled,
            onClick = {
                saveMenuExpanded = false
                launcherExpanded = false
                onTogglePassthrough()
            },
        ),
        UtilityActionSpec(
            id = "orientation",
            icon = Icons.Default.ScreenRotation,
            label = stringResource(R.string.toggle_toolbar_orientation),
            onClick = {
                saveMenuExpanded = false
                launcherExpanded = false
                onToggleOrientation()
            },
        ),
        UtilityActionSpec(
            id = "settings",
            icon = Icons.Default.Settings,
            label = stringResource(R.string.settings),
            onClick = {
                saveMenuExpanded = false
                launcherExpanded = false
                onOpenSettings()
            },
        ),
        UtilityActionSpec(
            id = "quit",
            icon = Icons.Default.Close,
            label = stringResource(R.string.quit),
            danger = true,
            onClick = {
                saveMenuExpanded = false
                launcherExpanded = false
                onQuit()
            },
        ),
    )
    val actionMap = actions.associateBy(UtilityActionSpec::id)
    val orderedActionIds = remember(quickLaunchActionIds, saveMenuExpanded, canvasVisible, passthroughEnabled, canRedo) {
        val normalized = quickLaunchActionIds.distinct().filter { actionMap.containsKey(it) }
        normalized + actions.map(UtilityActionSpec::id).filterNot(normalized::contains)
    }
    val orderedActions = remember(orderedActionIds, saveMenuExpanded, canvasVisible, passthroughEnabled, canRedo) {
        orderedActionIds.mapNotNull(actionMap::get)
    }
    val visibleQuickActions = remember(orderedActions) {
        orderedActions.take(MAX_QUICK_ACTIONS)
    }

    Box(
        contentAlignment = Alignment.Center,
    ) {
        val dockModifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.20f))
            .padding(horizontal = 4.dp, vertical = 3.dp)
        QuickLaunchDock(
            actions = visibleQuickActions,
            launcherExpanded = launcherExpanded,
            passiveBg = passiveBg,
            activeBg = activeBg,
            iconColor = iconColor,
            modifier = dockModifier,
            onOpenLauncher = {
                launcherExpanded = true
                saveMenuExpanded = false
            },
        )

        if (saveMenuExpanded) {
            ToolbarMenuPopup(
                alignment = popupAlignment,
                offset = popupOffset,
                onDismiss = { saveMenuExpanded = false },
            ) {
                SaveMenuCard(
                    onSaveTransparent = {
                        saveMenuExpanded = false
                        onSaveTransparent()
                    },
                    onSaveWithBackdrop = {
                        saveMenuExpanded = false
                        onSaveWithBackdrop()
                    },
                    onSaveWithScreenBackdrop = {
                        saveMenuExpanded = false
                        onSaveWithScreenBackdrop()
                    },
                )
            }
        }
    }

    if (launcherExpanded) {
        ToolbarMenuPopup(
            alignment = popupAlignment,
            offset = IntOffset(0, -20),
            onDismiss = { launcherExpanded = false },
        ) {
            QuickLaunchMenuCard(
                actions = orderedActions,
                onActionClick = { action ->
                    launcherExpanded = false
                    actionMap[action]?.onClick?.invoke()
                },
                onActionOrderChange = onSetQuickLaunchActionOrder,
            )
        }
    }
}

@Composable
private fun QuickLaunchDock(
    actions: List<UtilityActionSpec>,
    launcherExpanded: Boolean,
    passiveBg: Color,
    activeBg: Color,
    iconColor: Color,
    modifier: Modifier,
    onOpenLauncher: () -> Unit,
) {
    val actionSlots = actions.take(MAX_QUICK_ACTIONS) + List((MAX_QUICK_ACTIONS - actions.size).coerceAtLeast(0)) { null }
    val launcher: @Composable () -> Unit = {
        FixedQuickActionButton(
            icon = Icons.Default.Tune,
            contentDescription = stringResource(R.string.quick_launch_actions),
            selected = launcherExpanded,
            backgroundColor = if (launcherExpanded) activeBg else passiveBg,
            tint = if (launcherExpanded) MiuixTheme.colorScheme.onPrimaryContainer else iconColor,
            onClick = onOpenLauncher,
        )
    }
    val slot: @Composable (UtilityActionSpec?) -> Unit = { action ->
        if (action == null) {
            Spacer(modifier = Modifier.size(30.dp))
        } else {
            QuickLaunchButton(
                action = action,
                passiveBg = passiveBg,
                activeBg = activeBg,
                iconColor = iconColor,
            )
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            slot(actionSlots.getOrNull(0))
            slot(actionSlots.getOrNull(1))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            slot(actionSlots.getOrNull(2))
            launcher()
        }
    }
}

@Composable
private fun ToolbarMenuPopup(
    alignment: Alignment,
    offset: IntOffset,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        content()
    }
}

@Composable
private fun QuickLaunchButton(
    action: UtilityActionSpec,
    passiveBg: Color,
    activeBg: Color,
    iconColor: Color,
) {
    FixedQuickActionButton(
        icon = action.icon,
        contentDescription = action.label,
        selected = action.selected,
        enabled = action.enabled,
        backgroundColor = when {
            action.selected -> activeBg
            action.danger -> passiveBg.copy(alpha = 0.18f)
            else -> passiveBg
        },
        tint = when {
            !action.enabled -> iconColor.copy(alpha = 0.34f)
            action.selected -> MiuixTheme.colorScheme.onPrimaryContainer
            action.danger -> Color(0xFFD96C6C)
            else -> iconColor
        },
        onClick = action.onClick,
    )
}

@Composable
private fun FixedQuickActionButton(
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            backgroundColor = backgroundColor,
            holdDownState = selected,
            cornerRadius = 11.dp,
            minWidth = 30.dp,
            minHeight = 30.dp,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun QuickLaunchMenuCard(
    actions: List<UtilityActionSpec>,
    onActionClick: (String) -> Unit,
    onActionOrderChange: (List<String>) -> Unit,
) {
    var orderedActions by remember(actions) { mutableStateOf(actions) }
    var draggingActionId by remember { mutableStateOf<String?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { 34.dp.toPx() }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.quick_launch_actions),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            orderedActions.forEachIndexed { index, action ->
                QuickLaunchMenuItem(
                    action = action,
                    currentIndex = index,
                    itemCount = orderedActions.size,
                    rank = index + 1,
                    showing = index < MAX_QUICK_ACTIONS,
                    dragging = draggingActionId == action.id,
                    dragOffsetY = if (draggingActionId == action.id) draggingOffsetY else 0f,
                    onActionClick = { onActionClick(action.id) },
                    onDragStart = {
                        draggingActionId = action.id
                        draggingOffsetY = 0f
                    },
                    onDrag = { deltaY ->
                        draggingOffsetY += deltaY
                        val from = orderedActions.indexOfFirst { it.id == action.id }
                        if (from == -1) return@QuickLaunchMenuItem
                        val target = (from + (draggingOffsetY / rowHeightPx).roundToInt())
                            .coerceIn(0, orderedActions.lastIndex)
                        if (target != from) {
                            draggingOffsetY -= (target - from) * rowHeightPx
                            orderedActions = orderedActions.toMutableList().apply {
                                add(target, removeAt(from))
                            }
                            onActionOrderChange(orderedActions.map(UtilityActionSpec::id))
                        }
                    },
                    onDragEnd = {
                        draggingActionId = null
                        draggingOffsetY = 0f
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickLaunchMenuItem(
    action: UtilityActionSpec,
    currentIndex: Int,
    itemCount: Int,
    rank: Int,
    showing: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    onActionClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .offset { IntOffset(0, if (dragging) dragOffsetY.roundToInt() else 0) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                alpha = if (dragging) 0.92f else 1f
                scaleX = if (dragging) 1.01f else 1f
                scaleY = if (dragging) 1.01f else 1f
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (showing) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
                else Color.Transparent
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QuickLaunchRankBadge(rank = rank, showing = showing)
        Surface(
            onClick = onActionClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            contentColor = if (action.danger) Color(0xFFD96C6C) else MiuixTheme.colorScheme.onSurfaceContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.label,
                    tint = if (action.danger) Color(0xFFD96C6C) else MiuixTheme.colorScheme.onSurfaceContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = action.label,
                    color = if (action.danger) Color(0xFFD96C6C) else MiuixTheme.colorScheme.onSurfaceContainer,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
        DragHandle(
            dragging = dragging,
            currentIndex = currentIndex,
            itemCount = itemCount,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
        )
    }
}

@Composable
private fun QuickLaunchRankBadge(
    rank: Int,
    showing: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (showing) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer,
        contentColor = if (showing) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
    ) {
        Text(
            text = rank.toString(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            color = if (showing) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

@Composable
private fun DragHandle(
    dragging: Boolean,
    currentIndex: Int,
    itemCount: Int,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(width = 28.dp, height = 34.dp)
            .pointerInput(itemCount, currentIndex) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragCancel = onDragEnd,
                    onDragEnd = onDragEnd,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount.y != 0f) {
                            onDrag(dragAmount.y)
                        }
                    },
                )
            },
        shape = RoundedCornerShape(10.dp),
        color = if (dragging) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
        else MiuixTheme.colorScheme.surfaceContainer,
        contentColor = if (dragging) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(2.dp)
                            .background(
                                if (dragging) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.72f),
                                RoundedCornerShape(50)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveMenuCard(
    onSaveTransparent: () -> Unit,
    onSaveWithBackdrop: () -> Unit,
    onSaveWithScreenBackdrop: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f),
        contentColor = MiuixTheme.colorScheme.onSurfaceContainerHighest,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SaveMenuItem(text = stringResource(R.string.save_transparent_png), onClick = onSaveTransparent)
            SaveMenuItem(text = stringResource(R.string.save_backdrop_png), onClick = onSaveWithBackdrop)
            SaveMenuItem(text = stringResource(R.string.save_screen_backdrop_png), onClick = onSaveWithScreenBackdrop)
        }
    }
}

@Composable
private fun SaveMenuItem(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 172.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
        )
    }
}

@Composable
private fun CompactActionButton(
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    MiuixIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(30.dp),
        backgroundColor = backgroundColor,
        holdDownState = selected,
        cornerRadius = 11.dp,
        minWidth = 30.dp,
        minHeight = 30.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun MainToolRow(
    uiState: UiState,
    onSelectMainTool: (MainTool) -> Unit,
) {
    val selectedMainTool = mainToolForPenType(uiState.currentPenType)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(MainTool.Pen, MainTool.Eraser, MainTool.Shape, MainTool.Laser).forEach { tool ->
            MainToolButton(
                tool = tool,
                selected = selectedMainTool == tool,
                accent = colorForTool(uiState, tool),
                compact = false,
                vertical = false,
                onClick = { onSelectMainTool(tool) },
            )
        }
    }
}

@Composable
private fun MainToolColumn(
    uiState: UiState,
    onSelectMainTool: (MainTool) -> Unit,
) {
    val selectedMainTool = mainToolForPenType(uiState.currentPenType)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(MainTool.Pen, MainTool.Eraser, MainTool.Shape, MainTool.Laser).forEach { tool ->
            MainToolButton(
                tool = tool,
                selected = selectedMainTool == tool,
                accent = colorForTool(uiState, tool),
                compact = false,
                vertical = true,
                onClick = { onSelectMainTool(tool) },
            )
        }
    }
}

@Composable
private fun MainToolButton(
    tool: MainTool,
    selected: Boolean,
    accent: Color,
    compact: Boolean,
    vertical: Boolean = false,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val width = when {
        compact -> 30.dp
        vertical -> 58.dp
        else -> 34.dp
    }
    val height = when {
        compact -> 48.dp
        vertical -> 28.dp
        else -> 28.dp
    }
    val lift by animateFloatAsState(
        targetValue = if (selected && !compact) -6f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "tool_lift",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "tool_scale",
    )
    val tint = if (selected) accent else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.82f)

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                translationY = with(density) { lift.dp.toPx() }
                scaleX = scale
                scaleY = scale
            },
    ) {
        MiuixIconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            backgroundColor = if (selected) {
                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
            } else {
                Color.Transparent
            },
            cornerRadius = 14.dp,
            minWidth = width,
            minHeight = height,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = stringResource(tool.labelRes()),
                    tint = tint,
                    modifier = Modifier.size(if (compact) 20.dp else 20.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolDetailArea(
    uiState: UiState,
    metrics: DisplayMetrics,
    vertical: Boolean,
    onColorSelected: (Color) -> Unit,
    onStrokeEraserSelected: () -> Unit,
    onPixelEraserSelected: () -> Unit,
    onRectangleSelected: () -> Unit,
    onEllipseSelected: () -> Unit,
    onWidthSelected: (Float) -> Unit,
    onClearCanvas: () -> Unit,
    onOpenColorPopup: (MainTool) -> Unit,
    onOpenWidthPopup: (MainTool) -> Unit,
) {
    when (mainToolForPenType(uiState.currentPenType)) {
        MainTool.Pen -> PenLikeArea(
            tool = MainTool.Pen,
            selectedColor = uiState.penConfigs[PenType.Pen]?.color ?: uiState.currentPenConfig.color,
            currentWidthPx = uiState.penConfigs[PenType.Pen]?.width ?: uiState.currentPenConfig.width,
            widthValuesMm = penWidthsMm,
            metrics = metrics,
            vertical = vertical,
            onColorSelected = onColorSelected,
            onWidthSelected = onWidthSelected,
            onOpenColorPopup = onOpenColorPopup,
            onOpenWidthPopup = onOpenWidthPopup,
        )
        MainTool.Laser -> PenLikeArea(
            tool = MainTool.Laser,
            selectedColor = uiState.penConfigs[PenType.Laser]?.color ?: uiState.currentPenConfig.color,
            currentWidthPx = uiState.penConfigs[PenType.Laser]?.width ?: uiState.currentPenConfig.width,
            widthValuesMm = laserWidthsMm,
            metrics = metrics,
            vertical = vertical,
            onColorSelected = onColorSelected,
            onWidthSelected = onWidthSelected,
            onOpenColorPopup = onOpenColorPopup,
            onOpenWidthPopup = onOpenWidthPopup,
        )
        MainTool.Eraser -> EraserArea(
            currentPenType = uiState.currentPenType,
            currentWidthPx = uiState.currentPenConfig.width,
            metrics = metrics,
            vertical = vertical,
            onStrokeEraserSelected = onStrokeEraserSelected,
            onPixelEraserSelected = onPixelEraserSelected,
            onClearCanvas = onClearCanvas,
            onWidthSelected = onWidthSelected,
            onOpenWidthPopup = onOpenWidthPopup,
        )
        MainTool.Shape -> ShapeArea(
            currentPenType = uiState.currentPenType,
            selectedColor = uiState.currentPenConfig.color,
            currentWidthPx = uiState.currentPenConfig.width,
            metrics = metrics,
            vertical = vertical,
            onRectangleSelected = onRectangleSelected,
            onEllipseSelected = onEllipseSelected,
            onColorSelected = onColorSelected,
            onWidthSelected = onWidthSelected,
            onOpenColorPopup = onOpenColorPopup,
            onOpenWidthPopup = onOpenWidthPopup,
        )
    }
}

@Composable
private fun PenLikeArea(
    tool: MainTool,
    selectedColor: Color,
    currentWidthPx: Float,
    widthValuesMm: List<Float>,
    metrics: DisplayMetrics,
    vertical: Boolean,
    onColorSelected: (Color) -> Unit,
    onWidthSelected: (Float) -> Unit,
    onOpenColorPopup: (MainTool) -> Unit,
    onOpenWidthPopup: (MainTool) -> Unit,
) {
    val content: @Composable () -> Unit = {
        ColorGrid(
            selectedColor = selectedColor,
            vertical = vertical,
            onColorSelected = onColorSelected,
            onMoreClick = { onOpenColorPopup(tool) },
        )
        WidthChoices(
            valuesMm = widthValuesMm,
            currentWidthPx = currentWidthPx,
            metrics = metrics,
            vertical = vertical,
            onSelected = onWidthSelected,
            onMoreClick = { onOpenWidthPopup(tool) },
        )
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun EraserArea(
    currentPenType: PenType,
    currentWidthPx: Float,
    metrics: DisplayMetrics,
    vertical: Boolean,
    onStrokeEraserSelected: () -> Unit,
    onPixelEraserSelected: () -> Unit,
    onClearCanvas: () -> Unit,
    onWidthSelected: (Float) -> Unit,
    onOpenWidthPopup: (MainTool) -> Unit,
) {
    val content: @Composable () -> Unit = {
        ToolModeChoices(
            items = listOf(
                IconChoice(InkEraser24Px, currentPenType == PenType.StrokeEraser, stringResource(R.string.stroke_eraser), onStrokeEraserSelected),
                IconChoice(Icons.Default.BlurOn, currentPenType == PenType.PixelEraser, stringResource(R.string.pixel_eraser), onPixelEraserSelected),
            ),
            horizontal = vertical,
        )
        ClearCanvasButton(vertical = vertical, onClick = onClearCanvas)
        WidthChoices(
            valuesMm = eraserSizesMm,
            currentWidthPx = currentWidthPx,
            metrics = metrics,
            vertical = vertical,
            onSelected = onWidthSelected,
            formatter = { "${it.toInt()}" },
            onMoreClick = { onOpenWidthPopup(MainTool.Eraser) },
        )
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun ClearCanvasButton(
    vertical: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = if (vertical) Modifier.size(width = 66.dp, height = 34.dp)
        else Modifier.size(width = 34.dp, height = 66.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = Color(0xFFD96C6C),
        border = BorderStroke(1.dp, Color(0xFFD96C6C).copy(alpha = 0.56f)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = stringResource(R.string.clear_canvas),
                tint = Color(0xFFD96C6C),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun ShapeArea(
    currentPenType: PenType,
    selectedColor: Color,
    currentWidthPx: Float,
    metrics: DisplayMetrics,
    vertical: Boolean,
    onRectangleSelected: () -> Unit,
    onEllipseSelected: () -> Unit,
    onColorSelected: (Color) -> Unit,
    onWidthSelected: (Float) -> Unit,
    onOpenColorPopup: (MainTool) -> Unit,
    onOpenWidthPopup: (MainTool) -> Unit,
) {
    val content: @Composable () -> Unit = {
        ToolModeChoices(
            items = listOf(
                IconChoice(Icons.Default.CropSquare, currentPenType == PenType.Rectangle, stringResource(R.string.rectangle), onRectangleSelected),
                IconChoice(Icons.Default.RadioButtonUnchecked, currentPenType == PenType.Ellipse, stringResource(R.string.ellipse), onEllipseSelected),
            ),
            horizontal = vertical,
        )
        ColorGrid(
            selectedColor = selectedColor,
            vertical = vertical,
            onColorSelected = onColorSelected,
            onMoreClick = { onOpenColorPopup(MainTool.Shape) },
        )
        WidthChoices(
            valuesMm = shapeWidthsMm,
            currentWidthPx = currentWidthPx,
            metrics = metrics,
            vertical = vertical,
            onSelected = onWidthSelected,
            onMoreClick = { onOpenWidthPopup(MainTool.Shape) },
        )
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

private data class IconChoice(
    val icon: ImageVector,
    val selected: Boolean,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color = Color.Unspecified,
)

@Composable
private fun ToolModeChoices(
    items: List<IconChoice>,
    horizontal: Boolean,
) {
    val defaultTint = MiuixTheme.colorScheme.onSurfaceContainer
    val selectedColor = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    val selectedBorder = MiuixTheme.colorScheme.primary.copy(alpha = 0.64f)
    val normalBorder = MiuixTheme.colorScheme.outline.copy(alpha = 0.42f)
    val content: @Composable () -> Unit = {
        items.forEach { item ->
            val tint = if (item.tint == Color.Unspecified) defaultTint else item.tint
            Surface(
                onClick = item.onClick,
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(9.dp),
                color = if (item.selected) selectedColor else Color.Transparent,
                contentColor = tint,
                border = BorderStroke(
                    1.dp,
                    if (item.selected) selectedBorder else normalBorder
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint.copy(alpha = if (item.selected) 0.96f else 0.78f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
    if (horizontal) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun ColorGrid(
    selectedColor: Color,
    vertical: Boolean,
    onColorSelected: (Color) -> Unit,
    onMoreClick: () -> Unit,
) {
    val colorCells: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            toolbarColors.chunked(if (vertical) 2 else 5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { color ->
                        ColorCell(
                            color = color,
                            selected = color.toArgb() == selectedColor.toArgb(),
                            onClick = { onColorSelected(color) },
                        )
                    }
                }
            }
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            colorCells()
            SmallActionButton(
                icon = Icons.Default.Palette,
                contentDescription = stringResource(R.string.color_picker),
                onClick = onMoreClick,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            colorCells()
            SmallActionButton(
                icon = Icons.Default.Palette,
                contentDescription = stringResource(R.string.color_picker),
                onClick = onMoreClick,
            )
        }
    }
}

@Composable
private fun WidthChoices(
    valuesMm: List<Float>,
    currentWidthPx: Float,
    metrics: DisplayMetrics,
    vertical: Boolean,
    onSelected: (Float) -> Unit,
    formatter: (Float) -> String = { String.format("%.2f", it) },
    onMoreClick: () -> Unit,
) {
    val textColor = MiuixTheme.colorScheme.onSurfaceContainer
    val normalBorder = MiuixTheme.colorScheme.outline.copy(alpha = 0.48f)
    val widthButtons: @Composable () -> Unit = {
        valuesMm.forEach { mm ->
            val selected = abs(currentWidthPx - mmToPx(mm, metrics)) < 1.6f
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    onClick = { onSelected(mm) },
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(
                        2.dp,
                        if (selected) Color(0xFF1A94FF) else normalBorder
                    ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size((mm * 6.2f).coerceIn(3f, 8.5f).dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A94FF))
                        )
                    }
                }
                Text(
                    text = formatter(mm),
                    color = textColor.copy(alpha = 0.92f),
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            widthButtons()
            SmallActionButton(
                icon = Icons.Default.LineWeight,
                contentDescription = stringResource(R.string.width),
                onClick = onMoreClick,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            widthButtons()
            SmallActionButton(
                icon = Icons.Default.LineWeight,
                contentDescription = stringResource(R.string.width),
                onClick = onMoreClick,
            )
        }
    }
}

@Composable
private fun ColorCell(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 20.dp, height = 18.dp),
        shape = RoundedCornerShape(5.dp),
        color = color,
        contentColor = readableContentColor(color),
        border = if (selected) BorderStroke(2.dp, MiuixTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (selected) {
                Text(
                    text = "✓",
                    color = if (color == Color.White) Color.Black else Color.White,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SmallActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    MiuixIconButton(
        onClick = onClick,
        modifier = Modifier.size(26.dp),
        backgroundColor = Color.Transparent,
        cornerRadius = 10.dp,
        minWidth = 26.dp,
        minHeight = 26.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.86f),
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun VerticalSeparator(height: Dp = 64.dp) {
    Box(
        modifier = Modifier
            .height(height)
            .width(1.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.26f))
    )
}

@Composable
private fun HorizontalSeparator(width: Dp = 74.dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.26f))
    )
}

@Composable
private fun DragCollapseHandle(
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onPositionChange: (Offset) -> Unit,
    onPositionSaved: () -> Unit,
    onTap: () -> Unit,
    compact: Boolean = false,
    horizontalGrip: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .width(if (horizontalGrip) 44.dp else 20.dp)
            .height(if (horizontalGrip) 20.dp else 44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                    var dragged = false
                    var keepDragging = true
                    while (keepDragging) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            keepDragging = false
                        } else {
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) {
                                if (!dragged) {
                                    dragged = true
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                                onPositionChange(delta)
                                change.consume()
                            }
                        }
                    }
                    if (dragged) {
                        onPositionSaved()
                    } else {
                        onTap()
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = MiuixTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (horizontalGrip) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(if (compact) 4 else 5) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(12.dp)
                                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.62f), RoundedCornerShape(50))
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(if (compact) 4 else 5) {
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(2.dp)
                                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.62f), RoundedCornerShape(50))
                                .offset(x = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallColorPopup(
    tool: MainTool,
    currentColor: Color,
    savedCustomColors: List<Color>,
    onDismiss: () -> Unit,
    onApply: (Color) -> Unit,
    onSaveCustom: (Color) -> Unit,
) {
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -20),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        var selectedColor by remember(currentColor) { mutableStateOf(currentColor) }
        var colorInput by remember(currentColor) { mutableStateOf(currentColor.toHexString()) }
        val parsedInput = remember(colorInput) { parseColorInput(colorInput) }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.width(240.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = when (tool) {
                        MainTool.Pen -> stringResource(R.string.pen_color)
                        MainTool.Shape -> stringResource(R.string.shape_color)
                        MainTool.Laser -> stringResource(R.string.laser_color)
                        MainTool.Eraser -> stringResource(R.string.color)
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toolbarColors.take(5).forEach { color ->
                        ColorCell(color = color, selected = color.rgbEquals(selectedColor)) {
                            onApply(color.copy(alpha = selectedColor.alpha))
                            onDismiss()
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toolbarColors.drop(5).take(5).forEach { color ->
                        ColorCell(color = color, selected = color.rgbEquals(selectedColor)) {
                            onApply(color.copy(alpha = selectedColor.alpha))
                            onDismiss()
                        }
                    }
                }
                if (savedCustomColors.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        savedCustomColors.take(6).forEach { color ->
                            ColorCell(color = color, selected = color.toArgb() == selectedColor.toArgb()) {
                                onApply(color)
                                onDismiss()
                            }
                        }
                    }
                }
                ColorPalette(
                    color = selectedColor,
                    onColorChanged = {
                        selectedColor = it
                        colorInput = it.toHexString()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    rows = 6,
                    hueColumns = 12,
                    showPreview = true,
                )
                TextField(
                    value = colorInput,
                    onValueChange = {
                        colorInput = it
                        parseColorInput(it)?.let { parsed ->
                            selectedColor = parsed
                        }
                    },
                    label = stringResource(R.string.color_value_input),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                if (colorInput.isNotBlank() && parsedInput == null) {
                    Text(
                        text = stringResource(R.string.color_value_invalid),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveCustom(parsedInput ?: selectedColor)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = colorInput.isBlank() || parsedInput != null,
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.secondaryVariant,
                            contentColor = MiuixTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Text(stringResource(R.string.save_custom_color))
                    }
                    Button(
                        onClick = {
                            onApply(parsedInput ?: selectedColor)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = colorInput.isBlank() || parsedInput != null,
                        colors = ButtonDefaults.buttonColors(
                            color = selectedColor,
                            contentColor = readableContentColor(selectedColor),
                        ),
                    ) {
                        Text(stringResource(R.string.apply_color))
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallWidthPopup(
    tool: MainTool,
    currentWidthPx: Float,
    savedCustomWidths: List<Float>,
    metrics: DisplayMetrics,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
    onSaveCurrent: () -> Unit,
) {
    val rangeMm = when (tool) {
        MainTool.Pen -> 0.05f..2f
        MainTool.Shape -> 0.10f..3f
        MainTool.Laser -> 0.05f..1.5f
        MainTool.Eraser -> 1f..18f
    }
    var widthMm by remember(currentWidthPx) { mutableFloatStateOf(pxToMm(currentWidthPx, metrics).coerceIn(rangeMm.start, rangeMm.endInclusive)) }

    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -20),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.width(220.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = when (tool) {
                        MainTool.Eraser -> stringResource(R.string.custom_eraser_size)
                        else -> stringResource(R.string.custom_width)
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                if (savedCustomWidths.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        savedCustomWidths.take(4).forEach { widthPx ->
                            val mm = pxToMm(widthPx, metrics)
                            WidthPresetChip(
                                label = if (tool == MainTool.Eraser) mm.toInt().toString() else String.format("%.2f", mm),
                                selected = abs(currentWidthPx - widthPx) < 0.5f,
                                onClick = {
                                    onApply(widthPx)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
                Text(
                    text = if (tool == MainTool.Eraser) "${widthMm.toInt()}" else String.format("%.2f mm", widthMm),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                Slider(
                    value = widthMm,
                    onValueChange = {
                        widthMm = it
                        onApply(mmToPx(it, metrics))
                    },
                    valueRange = rangeMm,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveCurrent()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.save_custom_width))
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.secondaryVariant,
                            contentColor = MiuixTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

@Composable
private fun WidthPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = if (selected) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

private fun mainToolForPenType(type: PenType): MainTool =
    when (type) {
        PenType.Pen -> MainTool.Pen
        PenType.StrokeEraser, PenType.PixelEraser -> MainTool.Eraser
        PenType.Rectangle, PenType.Ellipse -> MainTool.Shape
        PenType.Laser -> MainTool.Laser
    }

private fun MainTool.labelRes(): Int =
    when (this) {
        MainTool.Pen -> R.string.pen
        MainTool.Eraser -> R.string.stroke_eraser
        MainTool.Shape -> R.string.rectangle
        MainTool.Laser -> R.string.laser
    }

private fun switchToolIfNeeded(viewModel: DrawViewModel, uiState: UiState, tool: MainTool) {
    when (tool) {
        MainTool.Pen -> if (uiState.currentPenType != PenType.Pen) viewModel.switchToPen(PenType.Pen)
        MainTool.Eraser -> if (uiState.currentPenType != PenType.StrokeEraser && uiState.currentPenType != PenType.PixelEraser) {
            viewModel.switchToPen(PenType.StrokeEraser)
        }
        MainTool.Shape -> if (uiState.currentPenType != PenType.Rectangle && uiState.currentPenType != PenType.Ellipse) {
            viewModel.switchToPen(PenType.Rectangle)
        }
        MainTool.Laser -> if (uiState.currentPenType != PenType.Laser) viewModel.switchToPen(PenType.Laser)
    }
}

private fun colorForTool(uiState: UiState, tool: MainTool): Color =
    when (tool) {
        MainTool.Pen -> uiState.penConfigs[PenType.Pen]?.color ?: MainTool.Pen.accent
        MainTool.Laser -> uiState.penConfigs[PenType.Laser]?.color ?: MainTool.Laser.accent
        MainTool.Shape -> uiState.penConfigs[
            uiState.currentPenType.takeIf { it == PenType.Rectangle || it == PenType.Ellipse } ?: PenType.Rectangle
        ]?.color ?: MainTool.Shape.accent
        MainTool.Eraser -> MainTool.Eraser.accent
    }

private fun currentWidthForTool(uiState: UiState, tool: MainTool): Float =
    when (tool) {
        MainTool.Pen -> uiState.penConfigs[PenType.Pen]?.width ?: 0f
        MainTool.Shape -> uiState.penConfigs[uiState.currentPenType.takeIf { it == PenType.Rectangle || it == PenType.Ellipse } ?: PenType.Rectangle]?.width ?: 0f
        MainTool.Laser -> uiState.penConfigs[PenType.Laser]?.width ?: 0f
        MainTool.Eraser -> uiState.currentPenConfig.width
    }

private fun savedWidthsForTool(uiState: UiState, tool: MainTool): List<Float> =
    when (tool) {
        MainTool.Pen -> uiState.savedPenWidths
        MainTool.Shape -> uiState.savedShapeWidths
        MainTool.Laser -> uiState.savedLaserWidths
        MainTool.Eraser -> uiState.savedEraserSizes
    }

private fun alphaForTool(uiState: UiState, tool: MainTool): Float =
    when (tool) {
        MainTool.Pen -> uiState.penConfigs[PenType.Pen]?.alpha ?: 1f
        MainTool.Shape -> uiState.penConfigs[
            uiState.currentPenType.takeIf { it == PenType.Rectangle || it == PenType.Ellipse } ?: PenType.Rectangle
        ]?.alpha ?: 1f
        MainTool.Laser -> uiState.penConfigs[PenType.Laser]?.alpha ?: 1f
        MainTool.Eraser -> 1f
    }

private fun mmToPx(mm: Float, metrics: DisplayMetrics): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, mm, metrics)

private fun pxToMm(px: Float, metrics: DisplayMetrics): Float =
    px / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1f, metrics)

private fun readableContentColor(background: Color): Color {
    val luminance = background.red * 0.299f + background.green * 0.587f + background.blue * 0.114f
    return if (luminance > 0.58f) Color.Black else Color.White
}
