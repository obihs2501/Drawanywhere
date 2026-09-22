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

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.shezik.drawanywhere.model.FocusPenGesture
import com.shezik.drawanywhere.model.FocusPenLinkMode
import com.shezik.drawanywhere.model.PenConfig
import com.shezik.drawanywhere.model.PenType
import com.shezik.drawanywhere.model.StylusButtonAction
import com.shezik.drawanywhere.model.StylusButtonScheme
import com.shezik.drawanywhere.view.toolbar.ToolbarOrientation
import com.shezik.drawanywhere.view.toolbar.ToolbarOrientationMode
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {
    private object PreferencesKeys {
        val CURRENT_PEN_TYPE = stringPreferencesKey("current_pen_type")
        val TOOLBAR_POSITION_X = floatPreferencesKey("toolbar_position_x")
        val TOOLBAR_POSITION_Y = floatPreferencesKey("toolbar_position_y")
        val TOOLBAR_POSITION_INITIALIZED = booleanPreferencesKey("toolbar_position_initialized")
        val TOOLBAR_ORIENTATION = stringPreferencesKey("toolbar_orientation")
        val TOOLBAR_ORIENTATION_MODE = stringPreferencesKey("toolbar_orientation_mode")
        val AUTO_CLEAR_CANVAS = booleanPreferencesKey("auto_clear_canvas")
        val VISIBLE_ON_START = booleanPreferencesKey("visible_on_start")
        val TOOLBAR_MINIMIZED = booleanPreferencesKey("toolbar_minimized")
        val FINGER_DRAWING_ENABLED = booleanPreferencesKey("finger_drawing_enabled")
        val STYLUS_BUTTON_CAPTURE_ENABLED = booleanPreferencesKey("stylus_button_capture_enabled")
        val STYLUS_BUTTON_SCHEME = stringPreferencesKey("stylus_button_scheme")
        val STYLUS_PRIMARY_BUTTON_ACTION = stringPreferencesKey("stylus_primary_button_action")
        val STYLUS_SECONDARY_BUTTON_ACTION = stringPreferencesKey("stylus_secondary_button_action")
        val KEY_DIAGNOSTICS_ENABLED = booleanPreferencesKey("key_diagnostics_enabled")
        val KEEP_SCREEN_CAPTURE_SESSION = booleanPreferencesKey("keep_screen_capture_session")
        val ROOT_SCREENSHOT_ENABLED = booleanPreferencesKey("root_screenshot_enabled")
        val FOCUS_PEN_LINK_MODE = stringPreferencesKey("focus_pen_link_mode")
        val PRESSURE_ERASER_ENABLED = booleanPreferencesKey("pressure_eraser_enabled")
        val PRESSURE_ERASER_THRESHOLD = floatPreferencesKey("pressure_eraser_threshold")
        val RECENT_COLORS = stringPreferencesKey("recent_colors")
        val SAVED_CUSTOM_COLORS = stringPreferencesKey("saved_custom_colors")
        val SAVED_PEN_WIDTHS = stringPreferencesKey("saved_pen_widths")
        val SAVED_SHAPE_WIDTHS = stringPreferencesKey("saved_shape_widths")
        val SAVED_LASER_WIDTHS = stringPreferencesKey("saved_laser_widths")
        val SAVED_ERASER_SIZES = stringPreferencesKey("saved_eraser_sizes")
        val EXPORT_TREE_URI = stringPreferencesKey("export_tree_uri")
        val STYLUS_CYCLE_COLORS = stringPreferencesKey("stylus_cycle_colors")
        val QUICK_LAUNCH_ACTIONS = stringPreferencesKey("quick_launch_actions")

        // Pen-specific keys (for saving multiple pens)
        fun penColorKey(penType: PenType) = intPreferencesKey("${penType.name}_color")
        fun penWidthKey(penType: PenType) = floatPreferencesKey("${penType.name}_width")
        fun penAlphaKey(penType: PenType) = floatPreferencesKey("${penType.name}_alpha")

        fun focusPenActionKey(gesture: FocusPenGesture) =
            stringPreferencesKey("focus_pen_${gesture.name.lowercase()}_action")
    }

    private inline fun <reified T : Enum<T>> getEnumValueOrDefault(
        value: String?,
        defaultValue: T
    ): T {
        if (value == null) return defaultValue
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            defaultValue
        }
    }

    suspend fun getSavedUiState(): UiState {
        val preferences = context.dataStore.data.first()
        val defaultUiState = UiState()

        val currentPenType = getEnumValueOrDefault<PenType>(
            preferences[PreferencesKeys.CURRENT_PEN_TYPE],
            defaultUiState.currentPenType)

        // Reconstruct pen configurations
        val penConfigs = defaultUiState.penConfigs.toMutableMap()
        for (penType in PenType.entries) {
            val color = preferences[PreferencesKeys.penColorKey(penType)]
            val width = preferences[PreferencesKeys.penWidthKey(penType)]
            val alpha = preferences[PreferencesKeys.penAlphaKey(penType)]

            val defaultConfig = defaultUiState.penConfigs[penType] ?: PenConfig(penType = penType)
            if (penType.isEraser) {
                // Eraser has no visual appearance — always use default color/alpha, only persist width
                if (width != null) {
                    penConfigs[penType] = defaultConfig.copy(width = width)
                }
            } else if (color != null || width != null || alpha != null) {
                penConfigs[penType] = PenConfig(
                    penType = penType,
                    color = color?.let { Color(it) } ?: defaultConfig.color,
                    width = width ?: defaultConfig.width,
                    alpha = alpha ?: defaultConfig.alpha
                )
            }
        }

        val visibleOnStart = preferences[PreferencesKeys.VISIBLE_ON_START] ?: defaultUiState.visibleOnStart
        val toolbarMinimized = preferences[PreferencesKeys.TOOLBAR_MINIMIZED] ?: defaultUiState.toolbarMinimized
        val fingerDrawingEnabled = preferences[PreferencesKeys.FINGER_DRAWING_ENABLED] ?: defaultUiState.fingerDrawingEnabled
        val legacyStylusButtonCaptureEnabled = preferences[PreferencesKeys.STYLUS_BUTTON_CAPTURE_ENABLED]
        val stylusButtonScheme = preferences[PreferencesKeys.STYLUS_BUTTON_SCHEME]?.let {
            getEnumValueOrDefault<StylusButtonScheme>(it, defaultUiState.stylusButtonScheme)
        } ?: if (legacyStylusButtonCaptureEnabled == false) {
            StylusButtonScheme.Disabled
        } else {
            defaultUiState.stylusButtonScheme
        }
        val stylusPrimaryButtonAction = getEnumValueOrDefault<StylusButtonAction>(
            preferences[PreferencesKeys.STYLUS_PRIMARY_BUTTON_ACTION],
            defaultUiState.stylusPrimaryButtonAction
        )
        val stylusSecondaryButtonAction = getEnumValueOrDefault<StylusButtonAction>(
            preferences[PreferencesKeys.STYLUS_SECONDARY_BUTTON_ACTION],
            defaultUiState.stylusSecondaryButtonAction
        )
        fun focusPenAction(gesture: FocusPenGesture, default: StylusButtonAction) =
            getEnumValueOrDefault(preferences[PreferencesKeys.focusPenActionKey(gesture)], default)
        val focusPenSqueezeAction = focusPenAction(FocusPenGesture.Squeeze, defaultUiState.focusPenSqueezeAction)
        val focusPenDoubleTapAction = focusPenAction(FocusPenGesture.DoubleTap, defaultUiState.focusPenDoubleTapAction)
        val focusPenSlideUpAction = focusPenAction(FocusPenGesture.SlideUp, defaultUiState.focusPenSlideUpAction)
        val focusPenSlideDownAction = focusPenAction(FocusPenGesture.SlideDown, defaultUiState.focusPenSlideDownAction)
        val keyDiagnosticsEnabled = preferences[PreferencesKeys.KEY_DIAGNOSTICS_ENABLED]
            ?: defaultUiState.keyDiagnosticsEnabled
        val keepScreenCaptureSession = preferences[PreferencesKeys.KEEP_SCREEN_CAPTURE_SESSION]
            ?: defaultUiState.keepScreenCaptureSession
        val rootScreenshotEnabled = preferences[PreferencesKeys.ROOT_SCREENSHOT_ENABLED]
            ?: defaultUiState.rootScreenshotEnabled
        val focusPenLinkMode = getEnumValueOrDefault<FocusPenLinkMode>(
            preferences[PreferencesKeys.FOCUS_PEN_LINK_MODE],
            defaultUiState.focusPenLinkMode
        )
        val pressureEraserEnabled = preferences[PreferencesKeys.PRESSURE_ERASER_ENABLED]
            ?: defaultUiState.pressureEraserEnabled
        val pressureEraserThreshold = preferences[PreferencesKeys.PRESSURE_ERASER_THRESHOLD]
            ?: defaultUiState.pressureEraserThreshold
        val recentColorsStr = preferences[PreferencesKeys.RECENT_COLORS] ?: ""
        val recentColors = if (recentColorsStr.isNotEmpty())
            recentColorsStr.split(",").mapNotNull { s ->
                try { Color(s.toLong(16).toInt()) } catch (_: Exception) { null }
            }
        else emptyList()
        val savedCustomColors = decodeColorList(preferences[PreferencesKeys.SAVED_CUSTOM_COLORS])
        val savedPenWidths = decodeFloatList(preferences[PreferencesKeys.SAVED_PEN_WIDTHS])
        val savedShapeWidths = decodeFloatList(preferences[PreferencesKeys.SAVED_SHAPE_WIDTHS])
        val savedLaserWidths = decodeFloatList(preferences[PreferencesKeys.SAVED_LASER_WIDTHS])
        val savedEraserSizes = decodeFloatList(preferences[PreferencesKeys.SAVED_ERASER_SIZES])
        val exportTreeUri = preferences[PreferencesKeys.EXPORT_TREE_URI]
        val stylusCycleColors = decodeColorList(preferences[PreferencesKeys.STYLUS_CYCLE_COLORS])
            .ifEmpty { defaultUiState.stylusCycleColors }
        val quickLaunchActions = decodeStringList(preferences[PreferencesKeys.QUICK_LAUNCH_ACTIONS])
            .ifEmpty { defaultUiState.secondDrawerPinnedButtons }

        return UiState(
            currentPenType = currentPenType,
            penConfigs = penConfigs,
            toolbarOrientation = getEnumValueOrDefault<ToolbarOrientation>(
                preferences[PreferencesKeys.TOOLBAR_ORIENTATION],
                defaultUiState.toolbarOrientation),
            toolbarOrientationMode = getEnumValueOrDefault<ToolbarOrientationMode>(
                preferences[PreferencesKeys.TOOLBAR_ORIENTATION_MODE],
                defaultUiState.toolbarOrientationMode
            ),
            autoClearCanvas = preferences[PreferencesKeys.AUTO_CLEAR_CANVAS] ?: defaultUiState.autoClearCanvas,

            visibleOnStart = visibleOnStart,
            toolbarMinimized = toolbarMinimized,
            fingerDrawingEnabled = fingerDrawingEnabled,
            stylusButtonScheme = stylusButtonScheme,
            stylusPrimaryButtonAction = stylusPrimaryButtonAction,
            stylusSecondaryButtonAction = stylusSecondaryButtonAction,
            focusPenSqueezeAction = focusPenSqueezeAction,
            focusPenDoubleTapAction = focusPenDoubleTapAction,
            focusPenSlideUpAction = focusPenSlideUpAction,
            focusPenSlideDownAction = focusPenSlideDownAction,
            keyDiagnosticsEnabled = keyDiagnosticsEnabled,
            keepScreenCaptureSession = keepScreenCaptureSession,
            rootScreenshotEnabled = rootScreenshotEnabled,
            focusPenLinkMode = focusPenLinkMode,
            pressureEraserEnabled = pressureEraserEnabled,
            pressureEraserThreshold = pressureEraserThreshold,
            recentColors = recentColors,
            savedCustomColors = savedCustomColors,
            savedPenWidths = savedPenWidths,
            savedShapeWidths = savedShapeWidths,
            savedLaserWidths = savedLaserWidths,
            savedEraserSizes = savedEraserSizes,
            exportTreeUri = exportTreeUri,
            stylusCycleColors = stylusCycleColors,
            secondDrawerPinnedButtons = quickLaunchActions,
            canvasVisible = visibleOnStart,
            firstDrawerOpen = visibleOnStart
        )
    }

    suspend fun saveUiState(uiState: UiState) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CURRENT_PEN_TYPE] = uiState.currentPenType.name
            preferences[PreferencesKeys.TOOLBAR_ORIENTATION] = uiState.toolbarOrientation.name
            preferences[PreferencesKeys.TOOLBAR_ORIENTATION_MODE] = uiState.toolbarOrientationMode.name
            preferences[PreferencesKeys.AUTO_CLEAR_CANVAS] = uiState.autoClearCanvas
            preferences[PreferencesKeys.VISIBLE_ON_START] = uiState.visibleOnStart
            preferences[PreferencesKeys.TOOLBAR_MINIMIZED] = uiState.toolbarMinimized
            preferences[PreferencesKeys.FINGER_DRAWING_ENABLED] = uiState.fingerDrawingEnabled
            preferences[PreferencesKeys.STYLUS_BUTTON_SCHEME] = uiState.stylusButtonScheme.name
            preferences[PreferencesKeys.STYLUS_PRIMARY_BUTTON_ACTION] = uiState.stylusPrimaryButtonAction.name
            preferences[PreferencesKeys.STYLUS_SECONDARY_BUTTON_ACTION] = uiState.stylusSecondaryButtonAction.name
            preferences[PreferencesKeys.focusPenActionKey(FocusPenGesture.Squeeze)] = uiState.focusPenSqueezeAction.name
            preferences[PreferencesKeys.focusPenActionKey(FocusPenGesture.DoubleTap)] = uiState.focusPenDoubleTapAction.name
            preferences[PreferencesKeys.focusPenActionKey(FocusPenGesture.SlideUp)] = uiState.focusPenSlideUpAction.name
            preferences[PreferencesKeys.focusPenActionKey(FocusPenGesture.SlideDown)] = uiState.focusPenSlideDownAction.name
            preferences[PreferencesKeys.KEY_DIAGNOSTICS_ENABLED] = uiState.keyDiagnosticsEnabled
            preferences[PreferencesKeys.KEEP_SCREEN_CAPTURE_SESSION] = uiState.keepScreenCaptureSession
            preferences[PreferencesKeys.ROOT_SCREENSHOT_ENABLED] = uiState.rootScreenshotEnabled
            preferences[PreferencesKeys.FOCUS_PEN_LINK_MODE] = uiState.focusPenLinkMode.name
            preferences[PreferencesKeys.PRESSURE_ERASER_ENABLED] = uiState.pressureEraserEnabled
            preferences[PreferencesKeys.PRESSURE_ERASER_THRESHOLD] = uiState.pressureEraserThreshold
            preferences[PreferencesKeys.RECENT_COLORS] = uiState.recentColors.joinToString(",") { it.toArgb().toString(16).padStart(8, '0') }
            preferences[PreferencesKeys.SAVED_CUSTOM_COLORS] = encodeColorList(uiState.savedCustomColors)
            preferences[PreferencesKeys.SAVED_PEN_WIDTHS] = encodeFloatList(uiState.savedPenWidths)
            preferences[PreferencesKeys.SAVED_SHAPE_WIDTHS] = encodeFloatList(uiState.savedShapeWidths)
            preferences[PreferencesKeys.SAVED_LASER_WIDTHS] = encodeFloatList(uiState.savedLaserWidths)
            preferences[PreferencesKeys.SAVED_ERASER_SIZES] = encodeFloatList(uiState.savedEraserSizes)
            uiState.exportTreeUri?.let {
                preferences[PreferencesKeys.EXPORT_TREE_URI] = it
            } ?: preferences.remove(PreferencesKeys.EXPORT_TREE_URI)
            preferences[PreferencesKeys.STYLUS_CYCLE_COLORS] = encodeColorList(uiState.stylusCycleColors)
            preferences[PreferencesKeys.QUICK_LAUNCH_ACTIONS] = encodeStringList(uiState.secondDrawerPinnedButtons)

            // Save each pen's configuration
            for ((penType, config) in uiState.penConfigs) {
                if (!penType.isEraser) {
                    preferences[PreferencesKeys.penColorKey(penType)] = config.color.toArgb()
                    preferences[PreferencesKeys.penAlphaKey(penType)] = config.alpha
                }
                preferences[PreferencesKeys.penWidthKey(penType)] = config.width
            }
        }
    }

    suspend fun getSavedServiceState(): ServiceState {
        val preferences = context.dataStore.data.first()
        val defaultServiceState = ServiceState()

        return ServiceState(
            toolbarPosition = Offset(
                x = preferences[PreferencesKeys.TOOLBAR_POSITION_X] ?: defaultServiceState.toolbarPosition.x,
                y = preferences[PreferencesKeys.TOOLBAR_POSITION_Y] ?: defaultServiceState.toolbarPosition.y
            ),
            toolbarPositionInitialized = preferences[PreferencesKeys.TOOLBAR_POSITION_INITIALIZED]
                ?: defaultServiceState.toolbarPositionInitialized
        )
    }

    suspend fun saveServiceState(serviceState: ServiceState) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOOLBAR_POSITION_X] = serviceState.toolbarPosition.x
            preferences[PreferencesKeys.TOOLBAR_POSITION_Y] = serviceState.toolbarPosition.y
            preferences[PreferencesKeys.TOOLBAR_POSITION_INITIALIZED] =
                serviceState.toolbarPositionInitialized
        }
    }

    private fun decodeColorList(raw: String?): List<Color> =
        raw?.takeIf { it.isNotEmpty() }
            ?.split(",")
            ?.mapNotNull { value ->
                try { Color(value.toLong(16).toInt()) } catch (_: Exception) { null }
            }
            ?: emptyList()

    private fun decodeFloatList(raw: String?): List<Float> =
        raw?.takeIf { it.isNotEmpty() }
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?: emptyList()

    private fun encodeColorList(colors: List<Color>): String =
        colors.joinToString(",") { it.toArgb().toString(16).padStart(8, '0') }

    private fun encodeFloatList(values: List<Float>): String =
        values.joinToString(",") { it.toString() }

    private fun decodeStringList(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?: emptyList()

    private fun encodeStringList(values: List<String>): String =
        values.joinToString(",")
}
