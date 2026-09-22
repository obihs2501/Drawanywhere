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

package com.shezik.drawanywhere.stylus

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.miui.penengine.touchfilm.MiuiTouchFilmUtils
import com.shezik.drawanywhere.model.FocusPenLinkMode
import com.shezik.drawanywhere.model.FocusPenLinkState
import com.shezik.drawanywhere.model.FocusPenLinkState.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import com.miui.penengine.a.b as PenEngineFacade

/**
 * Claims the Focus Pen barrel-gesture stream from HyperOS.
 *
 * Reading KeyEvents alone is not enough: `MiuiStylusTouchFilmManager` in
 * system_server decides, per focused window package, whether a pen key is
 * handled by the system (shortcut wheel) or queued for the app. The app has
 * to be registered with the pencil-engine service (`com.xiaomi.touchservice`)
 * and have "touch film" enabled:
 *
 *  1. `bindService` to `com.xiaomi.touchservice/.pencilengine.PencilEngineManagerService`
 *  2. `IPencilEngine.registerListener(callback, packageName)`
 *  3. `IPencilEngine.setEnable(2 /* touch film */, 1)`
 *
 * Two implementations are available ([FocusPenLinkMode]): our own binder
 * client ([PencilEngineClient], always registers with the package name and
 * reports every return value) and the bundled PenEngine SDK
 * ([MiuiTouchFilmUtils.init], which only passes the package name when the
 * system's engine version string equals the SDK's). Neither path uses the
 * SDK's key-event callback: key codes are mapped in-app
 * ([FocusPenGestureDetector]) so the system stylus settings stay untouched.
 */
class FocusPenSystemLink(
    context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "FocusPenLink"
        const val TOUCH_SERVICE_PACKAGE = "com.xiaomi.touchservice"
        private const val ENGINE_JAR_PATH = "/system_ext/framework/xiaomi-pencilengine-pad.jar"
        private const val FEATURE_TOUCH_FILM = 2
        private const val POLL_WHILE_CONNECTING_MS = 400L
        private const val POLL_WHILE_CONNECTED_MS = 3_000L
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(FocusPenLinkState())
    val state: StateFlow<FocusPenLinkState> = _state.asStateFlow()

    private var active = false
    private var activeMode: FocusPenLinkMode? = null
    private var pollJob: Job? = null
    private var sdkEnableRecorded = false

    private val directClient = PencilEngineClient(appContext) client@{ bound, connected, enableResult, detail ->
        if (activeMode != FocusPenLinkMode.Direct) return@client
        val phase = when {
            connected -> Phase.Connected
            bound -> Phase.Binding
            detail == "disconnected" || detail == "service died" -> Phase.Disconnected
            else -> Phase.Failed
        }
        _state.value = _state.value.copy(
            phase = phase,
            sdkInitOk = bound,
            enableResult = enableResult ?: _state.value.enableResult,
            detail = detail,
        )
    }

    /** Only needed so the SDK's init() has a listener; its callbacks are ignored. */
    private val sdkListener = object : MiuiTouchFilmUtils.TouchFilmListener {
        override fun onTouchFilmTriggered(function: Int) {
            DiagnosticLog.log(TAG, "SDK onTouchFilmTriggered($function) ignored: gestures are mapped in-app")
        }

        override fun onBrushPreviewChanged(enabled: Boolean) = Unit
    }

    val isActive: Boolean get() = active

    fun enable(mode: FocusPenLinkMode) {
        if (active && activeMode == mode) return
        if (active) disable()
        active = true
        activeMode = mode
        sdkEnableRecorded = false

        val serviceInstalled = isTouchServiceInstalled()
        val jarPresent = runCatching { File(ENGINE_JAR_PATH).exists() }.getOrDefault(false)
        val engineVersion = PencilEngineClient.readEngineVersion(appContext)
        DiagnosticLog.log(
            TAG,
            "enable(mode=$mode): touchservice=$serviceInstalled engineJar=$jarPresent engineVersion=$engineVersion",
        )
        if (!serviceInstalled) {
            _state.value = FocusPenLinkState(
                phase = Phase.ServiceMissing,
                mode = mode,
                touchServiceInstalled = false,
                engineJarPresent = jarPresent,
                engineVersion = engineVersion,
            )
            return
        }
        _state.value = FocusPenLinkState(
            phase = Phase.Binding,
            mode = mode,
            touchServiceInstalled = true,
            engineJarPresent = jarPresent,
            engineVersion = engineVersion,
        )
        when (mode) {
            FocusPenLinkMode.Direct -> directClient.connect()
            FocusPenLinkMode.Sdk -> enableSdk()
        }
    }

    private fun enableSdk() {
        runCatching { MiuiTouchFilmUtils.init(appContext, sdkListener) }
            .onSuccess { ok ->
                DiagnosticLog.log(TAG, "MiuiTouchFilmUtils.init -> $ok")
                _state.value = _state.value.copy(
                    sdkInitOk = ok,
                    phase = if (ok) Phase.Binding else Phase.Failed,
                    detail = if (ok) null else "init() returned false",
                )
            }
            .onFailure { error ->
                Log.e(TAG, "MiuiTouchFilmUtils.init threw", error)
                DiagnosticLog.log(TAG, "MiuiTouchFilmUtils.init threw: $error")
                _state.value = _state.value.copy(sdkInitOk = false, phase = Phase.Failed, detail = error.toString())
            }
        startSdkPolling()
    }

    fun disable() {
        if (!active) return
        active = false
        pollJob?.cancel()
        pollJob = null
        when (activeMode) {
            FocusPenLinkMode.Direct -> directClient.disconnect()
            FocusPenLinkMode.Sdk -> runCatching { MiuiTouchFilmUtils.onDestroy() }
                .onFailure { DiagnosticLog.log(TAG, "MiuiTouchFilmUtils.onDestroy threw: $it") }
            null -> Unit
        }
        DiagnosticLog.log(TAG, "disabled (mode=$activeMode)")
        activeMode = null
        _state.value = FocusPenLinkState()
    }

    fun restart() {
        val mode = activeMode ?: return
        disable()
        enable(mode)
    }

    /** Re-issues setEnable(touchFilm, 1) on the live connection, for the diagnostics page. */
    fun resendEnable() {
        when (activeMode) {
            FocusPenLinkMode.Direct -> {
                val result = directClient.resendEnable()
                _state.value = _state.value.copy(enableResult = result ?: _state.value.enableResult)
            }
            FocusPenLinkMode.Sdk -> {
                sdkEnableRecorded = false
                refreshSdkConnection()
            }
            null -> DiagnosticLog.log(TAG, "resendEnable ignored: link inactive")
        }
    }

    private fun isTouchServiceInstalled(): Boolean =
        try {
            appContext.packageManager.getPackageInfo(TOUCH_SERVICE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: Throwable) {
            Log.w(TAG, "Package lookup failed", error)
            false
        }

    private fun startSdkPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (active && activeMode == FocusPenLinkMode.Sdk) {
                refreshSdkConnection()
                delay(
                    if (_state.value.phase == Phase.Connected) POLL_WHILE_CONNECTED_MS
                    else POLL_WHILE_CONNECTING_MS
                )
            }
        }
    }

    /**
     * Peeks at the SDK singleton (obfuscated members of PenEngine 0.3.2:
     * `b.c()` singleton, `.d` bound flag, `.h` IPencilEngine proxy) and records
     * the touch-film enable result once.
     */
    private fun refreshSdkConnection() {
        if (!active || activeMode != FocusPenLinkMode.Sdk) return
        val current = _state.value
        if (current.sdkInitOk != true) return

        val peek = runCatching {
            val facade = PenEngineFacade.c()
            facade.d to facade.h
        }
        val (bound, proxy) = peek.getOrElse { error ->
            DiagnosticLog.log(TAG, "SDK state peek failed: $error")
            _state.value = current.copy(detail = "state peek failed: $error")
            return
        }

        if (proxy == null) {
            val phase = if (bound) Phase.Binding else Phase.Disconnected
            if (current.phase != phase) _state.value = current.copy(phase = phase)
            return
        }

        var enableResult = current.enableResult
        if (!sdkEnableRecorded) {
            sdkEnableRecorded = true
            enableResult = runCatching { proxy.a(FEATURE_TOUCH_FILM, 1) }
                .onSuccess { DiagnosticLog.log(TAG, "SDK proxy setEnable(touchFilm, 1) -> $it") }
                .onFailure { DiagnosticLog.log(TAG, "SDK proxy setEnable(touchFilm, 1) threw: $it") }
                .getOrNull()
        }
        if (current.phase != Phase.Connected) DiagnosticLog.log(TAG, "SDK binder connected")
        _state.value = current.copy(phase = Phase.Connected, enableResult = enableResult, detail = null)
    }
}
