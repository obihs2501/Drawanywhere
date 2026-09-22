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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.provider.Settings

/**
 * Direct client for the HyperOS pencil-engine service, mirroring the binder
 * protocol of PenEngine SDK 0.3.2 (`com.miui.penengine.g.a` / `g.b`):
 *
 * ```
 * interface com.xiaomi.touchservice.pencilengine.IPencilEngine {
 *   1: void registerListener(IPencilEngineCallback cb)
 *   2: void unregisterListener()
 *   3: int  setEnable(int feature, int enable)   // feature 1 = posture, 2 = touch film
 *   4: void registerListener(IPencilEngineCallback cb, String packageName)
 * }
 * interface com.xiaomi.touchservice.pencilengine.IPencilEngineCallback {
 *   1: void onColorPicked(int what, int colorSpace, int color)
 * }
 * ```
 *
 * Unlike the SDK, this always registers with the package name (transaction 4,
 * falling back to 1) regardless of the `stylus_pencil_engine_version` string,
 * and it reports every return value to [DiagnosticLog].
 */
class PencilEngineClient(
    context: Context,
    private val onStateChanged: (bound: Boolean, connected: Boolean, enableResult: Int?, detail: String?) -> Unit,
) {
    companion object {
        private const val TAG = "PencilEngineClient"
        private const val DESCRIPTOR = "com.xiaomi.touchservice.pencilengine.IPencilEngine"
        private const val CALLBACK_DESCRIPTOR = "com.xiaomi.touchservice.pencilengine.IPencilEngineCallback"
        private const val TX_REGISTER = 1
        private const val TX_UNREGISTER = 2
        private const val TX_SET_ENABLE = 3
        private const val TX_REGISTER_WITH_PACKAGE = 4
        const val FEATURE_STYLUS_POSTURE = 1
        const val FEATURE_TOUCH_FILM = 2
        private const val ENGINE_VERSION_SETTING = "stylus_pencil_engine_version"
        private const val RECONNECT_DELAY_MS = 500L

        val SERVICE_COMPONENT = ComponentName(
            "com.xiaomi.touchservice",
            "com.xiaomi.touchservice.pencilengine.PencilEngineManagerService",
        )

        /** Version string the system publishes; read-only, shown for diagnostics. */
        fun readEngineVersion(context: Context): String? = runCatching {
            Settings.System.getString(context.contentResolver, ENGINE_VERSION_SETTING)
        }.getOrNull()
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var bound = false
    private var wantConnected = false
    private var lastEnableResult: Int? = null

    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code == 1) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                val what = data.readInt()
                val colorSpace = data.readInt()
                val color = data.readInt()
                DiagnosticLog.log(TAG, "callback onColorPicked(what=$what, colorSpace=$colorSpace, color=$color)")
                reply?.writeNoException()
                return true
            }
            DiagnosticLog.log(TAG, "callback: unexpected transaction $code")
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        DiagnosticLog.log(TAG, "pen service died")
        remote = null
        onStateChanged(bound, false, lastEnableResult, "service died")
        if (wantConnected) handler.postDelayed({ rebind() }, RECONNECT_DELAY_MS)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            runCatching { service.linkToDeath(deathRecipient, 0) }
            val descriptor = runCatching { service.interfaceDescriptor }.getOrNull()
            DiagnosticLog.log(TAG, "connected: descriptor=$descriptor")
            performHandshake()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            DiagnosticLog.log(TAG, "disconnected")
            remote = null
            onStateChanged(bound, false, lastEnableResult, "disconnected")
        }
    }

    fun connect() {
        if (wantConnected) return
        wantConnected = true
        lastEnableResult = null
        DiagnosticLog.log(TAG, "engine version setting = ${readEngineVersion(appContext)}")
        rebind()
    }

    private fun rebind() {
        if (!wantConnected) return
        val intent = Intent().setComponent(SERVICE_COMPONENT)
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { DiagnosticLog.log(TAG, "bindService threw: $it") }.getOrDefault(false)
        DiagnosticLog.log(TAG, "bindService(${SERVICE_COMPONENT.flattenToShortString()}) -> $bound")
        onStateChanged(bound, false, null, if (bound) null else "bindService returned false")
    }

    fun disconnect() {
        if (!wantConnected) return
        wantConnected = false
        remote?.let { binder ->
            transactSetEnable(binder, FEATURE_TOUCH_FILM, 0)
            transactVoid(binder, TX_UNREGISTER) { }
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        remote = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
                .onFailure { DiagnosticLog.log(TAG, "unbindService threw: $it") }
        }
        bound = false
        DiagnosticLog.log(TAG, "released")
        onStateChanged(false, false, null, null)
    }

    /** Re-sends the enable command on an existing connection; returns its result. */
    fun resendEnable(): Int? {
        val binder = remote ?: return null
        return transactSetEnable(binder, FEATURE_TOUCH_FILM, 1).also { lastEnableResult = it }
    }

    private fun performHandshake() {
        val binder = remote ?: return
        val packageName = appContext.packageName
        val registeredWithPackage = transactVoid(binder, TX_REGISTER_WITH_PACKAGE) { data ->
            data.writeStrongBinder(callback)
            data.writeString(packageName)
        }
        DiagnosticLog.log(TAG, "registerListener(callback, \"$packageName\") [tx4] -> $registeredWithPackage")
        if (!registeredWithPackage) {
            val registered = transactVoid(binder, TX_REGISTER) { data -> data.writeStrongBinder(callback) }
            DiagnosticLog.log(TAG, "registerListener(callback) [tx1] -> $registered")
        }
        val enableResult = transactSetEnable(binder, FEATURE_TOUCH_FILM, 1)
        lastEnableResult = enableResult
        onStateChanged(bound, true, enableResult, null)
    }

    private fun transactSetEnable(binder: IBinder, feature: Int, enable: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(feature)
            data.writeInt(enable)
            val ok = binder.transact(TX_SET_ENABLE, data, reply, 0)
            if (!ok) {
                DiagnosticLog.log(TAG, "setEnable($feature, $enable): transaction not handled by service")
                null
            } else {
                reply.readException()
                reply.readInt().also { DiagnosticLog.log(TAG, "setEnable($feature, $enable) -> $it") }
            }
        } catch (error: RemoteException) {
            DiagnosticLog.log(TAG, "setEnable($feature, $enable) failed: $error")
            null
        } catch (error: RuntimeException) {
            DiagnosticLog.log(TAG, "setEnable($feature, $enable) failed: $error")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactVoid(binder: IBinder, code: Int, write: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            val ok = binder.transact(code, data, reply, 0)
            if (ok) reply.readException()
            ok
        } catch (error: RemoteException) {
            DiagnosticLog.log(TAG, "transaction $code failed: $error")
            false
        } catch (error: RuntimeException) {
            DiagnosticLog.log(TAG, "transaction $code failed: $error")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
