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

package com.shezik.drawanywhere.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A MediaProjection session that survives across saves.
 *
 * Android 14+ makes the consent token single-use and ties a MediaProjection to
 * exactly one VirtualDisplay, so the only way to avoid the "start recording or
 * casting?" dialog on every save is to keep that one VirtualDisplay alive for
 * the whole service run. Between captures the display has no surface, which
 * WindowManager treats as a paused recording (no compositing cost, nothing
 * rendered). A capture attaches a fresh [ImageReader] surface, takes the first
 * frame produced for it and detaches again.
 *
 * Callers must have started a foreground service with type
 * `mediaProjection` before calling [start], and keep it that way while the
 * session is alive.
 */
class ScreenCaptureSession private constructor(
    private val projection: MediaProjection,
    private val virtualDisplay: VirtualDisplay,
    private val callback: MediaProjection.Callback,
    private val handler: Handler,
    private var width: Int,
    private var height: Int,
    private var dpi: Int,
) {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val DISPLAY_NAME = "DrawAnywhereScreenCapture"

        /** Delay before [captureFrame] pokes the compositor if no frame arrived. */
        private const val NUDGE_DELAY_MS = 450L

        /**
         * Exchanges a consent result for a live session. Returns null when the
         * system refuses (stale token, missing foreground service type, ...).
         */
        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int,
            height: Int,
            dpi: Int,
            onStopped: () -> Unit,
        ): ScreenCaptureSession? {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val projection = runCatching { manager.getMediaProjection(resultCode, resultData) }
                .onFailure { Log.w(TAG, "getMediaProjection failed", it) }
                .getOrNull() ?: return null

            val handler = Handler(Looper.getMainLooper())
            var session: ScreenCaptureSession? = null
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user")
                    session?.markStopped()
                    onStopped()
                }
            }
            // Android 14+ requires a callback to be registered before createVirtualDisplay.
            projection.registerCallback(callback, handler)

            val display = runCatching {
                projection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                    dpi.coerceAtLeast(1),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null, // no surface yet: recording stays paused until a capture
                    null,
                    handler,
                )
            }.onFailure { Log.w(TAG, "createVirtualDisplay failed", it) }.getOrNull()

            if (display == null) {
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                return null
            }
            return ScreenCaptureSession(projection, display, callback, handler, width, height, dpi)
                .also { session = it }
        }
    }

    @Volatile
    var isStopped: Boolean = false
        private set

    private fun markStopped() {
        isStopped = true
    }

    /**
     * Grabs one frame of the whole display at [width]x[height] pixels.
     * Call from the main thread; wrap in `withTimeout` at the call site.
     *
     * @param nudge invoked once if no frame arrived after a short delay; should
     *   make any on-screen change so the compositor produces a frame.
     */
    suspend fun captureFrame(width: Int, height: Int, dpi: Int, nudge: () -> Unit): Bitmap {
        check(!isStopped) { "MediaProjection session is stopped" }
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w != this.width || h != this.height || dpi != this.dpi) {
            virtualDisplay.resize(w, h, dpi.coerceAtLeast(1))
            this.width = w
            this.height = h
            this.dpi = dpi
        }

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val nudgeRunnable = Runnable {
            Log.d(TAG, "No frame yet; nudging compositor")
            runCatching(nudge)
        }
        try {
            return suspendCancellableCoroutine { continuation ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (!continuation.isActive) return@setOnImageAvailableListener
                        continuation.resume(image.toBitmap(w, h))
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        image.close()
                    }
                }, handler)
                continuation.invokeOnCancellation { handler.removeCallbacks(nudgeRunnable) }
                virtualDisplay.surface = reader.surface
                handler.postDelayed(nudgeRunnable, NUDGE_DELAY_MS)
            }
        } finally {
            handler.removeCallbacks(nudgeRunnable)
            runCatching { virtualDisplay.surface = null }
            reader.setOnImageAvailableListener(null, null)
            runCatching { reader.close() }
        }
    }

    fun release() {
        if (!isStopped) {
            isStopped = true
        }
        runCatching { virtualDisplay.surface = null }
        runCatching { virtualDisplay.release() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return raw
        val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
        raw.recycle()
        return cropped
    }
}
