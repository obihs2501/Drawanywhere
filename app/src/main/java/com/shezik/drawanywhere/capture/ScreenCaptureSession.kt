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
import com.shezik.drawanywhere.stylus.DiagnosticLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A MediaProjection session that survives across saves.
 *
 * Android 14+ makes the consent token single-use and ties a MediaProjection to
 * exactly one VirtualDisplay, so the only way to avoid the "start recording or
 * casting?" dialog on every save is to keep that one VirtualDisplay alive for
 * the whole service run. The display mirrors the screen into an [ImageReader]
 * continuously; frames are drained and dropped until a capture is requested,
 * at which point the first frame produced after the request is returned.
 * (Pausing via a null surface looked cheaper but Android does not reliably
 * resume such a display, which made every save after the first fail.)
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
    private var reader: ImageReader,
    private var width: Int,
    private var height: Int,
    private var dpi: Int,
) {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val DISPLAY_NAME = "DrawAnywhereScreenCapture"
        private const val MAX_IMAGES = 2

        /** Delay before [captureFrame] pokes the compositor if no frame arrived. */
        private const val NUDGE_DELAY_MS = 450L

        /**
         * Frames are normally matched by timestamp; if the producer's clock is not
         * comparable to System.nanoTime(), any frame this long after the request
         * is accepted instead (overlays were hidden well before the request).
         */
        private const val FRESHNESS_GRACE_NS = 300_000_000L

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
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val d = dpi.coerceAtLeast(1)
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val projection = runCatching { manager.getMediaProjection(resultCode, resultData) }
                .onFailure {
                    Log.w(TAG, "getMediaProjection failed", it)
                    DiagnosticLog.log(TAG, "getMediaProjection failed: $it")
                }
                .getOrNull() ?: return null

            val handler = Handler(Looper.getMainLooper())
            var session: ScreenCaptureSession? = null
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    DiagnosticLog.log(TAG, "MediaProjection.onStop (system or user ended the session)")
                    session?.markStopped()
                    onStopped()
                }
            }
            // Android 14+ requires a callback to be registered before createVirtualDisplay.
            projection.registerCallback(callback, handler)

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
            val display = runCatching {
                projection.createVirtualDisplay(
                    DISPLAY_NAME,
                    w,
                    h,
                    d,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                )
            }.onFailure {
                Log.w(TAG, "createVirtualDisplay failed", it)
                DiagnosticLog.log(TAG, "createVirtualDisplay failed: $it")
            }.getOrNull()

            if (display == null) {
                runCatching { reader.close() }
                runCatching { projection.unregisterCallback(callback) }
                runCatching { projection.stop() }
                return null
            }
            DiagnosticLog.log(TAG, "session started ${w}x${h}@$d")
            return ScreenCaptureSession(projection, display, callback, handler, reader, w, h, d)
                .also {
                    session = it
                    it.attachListener()
                }
        }
    }

    private class CaptureRequest(
        val startedAtNs: Long,
        val continuation: CancellableContinuation<Bitmap>,
    )

    @Volatile
    var isStopped: Boolean = false
        private set

    private var pendingRequest: CaptureRequest? = null
    private var framesSeen = 0L

    private val imageListener = ImageReader.OnImageAvailableListener { r ->
        val image = r.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            framesSeen++
            val request = pendingRequest ?: return@OnImageAvailableListener
            val fresh = image.timestamp >= request.startedAtNs ||
                System.nanoTime() - request.startedAtNs >= FRESHNESS_GRACE_NS
            if (!fresh) return@OnImageAvailableListener
            pendingRequest = null
            runCatching { image.toBitmap(width, height) }
                .onSuccess { bitmap ->
                    if (request.continuation.isActive) request.continuation.resume(bitmap) else bitmap.recycle()
                }
                .onFailure { error ->
                    if (request.continuation.isActive) request.continuation.resumeWithException(error)
                }
        } finally {
            image.close()
        }
    }

    private fun attachListener() {
        reader.setOnImageAvailableListener(imageListener, handler)
    }

    private fun markStopped() {
        isStopped = true
        pendingRequest?.let { request ->
            pendingRequest = null
            if (request.continuation.isActive) {
                request.continuation.resumeWithException(IllegalStateException("MediaProjection stopped"))
            }
        }
    }

    /**
     * Returns the first frame of the whole display produced after this call.
     * Call from the main thread; wrap in `withTimeout` at the call site.
     *
     * @param nudge invoked once if no frame arrived after a short delay; should
     *   make any on-screen change so the compositor produces a frame.
     */
    suspend fun captureFrame(width: Int, height: Int, dpi: Int, nudge: () -> Unit): Bitmap {
        check(!isStopped) { "MediaProjection session is stopped" }
        check(pendingRequest == null) { "capture already in progress" }
        ensureSize(width.coerceAtLeast(1), height.coerceAtLeast(1), dpi.coerceAtLeast(1))

        val framesBefore = framesSeen
        val nudgeRunnable = Runnable {
            DiagnosticLog.log(TAG, "no fresh frame after ${NUDGE_DELAY_MS}ms (frames seen: ${framesSeen - framesBefore}); nudging compositor")
            runCatching(nudge)
        }
        try {
            return suspendCancellableCoroutine { continuation ->
                pendingRequest = CaptureRequest(System.nanoTime(), continuation)
                continuation.invokeOnCancellation {
                    pendingRequest = null
                    handler.removeCallbacks(nudgeRunnable)
                }
                handler.postDelayed(nudgeRunnable, NUDGE_DELAY_MS)
            }.also {
                DiagnosticLog.log(TAG, "frame captured ${it.width}x${it.height} (frames seen: ${framesSeen - framesBefore})")
            }
        } finally {
            pendingRequest = null
            handler.removeCallbacks(nudgeRunnable)
        }
    }

    private fun ensureSize(w: Int, h: Int, d: Int) {
        if (w == width && h == height && d == dpi) return
        DiagnosticLog.log(TAG, "resize ${width}x${height} -> ${w}x${h}@$d")
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        newReader.setOnImageAvailableListener(imageListener, handler)
        virtualDisplay.resize(w, h, d)
        virtualDisplay.surface = newReader.surface
        val old = reader
        reader = newReader
        old.setOnImageAvailableListener(null, null)
        runCatching { old.close() }
        width = w
        height = h
        dpi = d
    }

    fun release() {
        isStopped = true
        pendingRequest?.let { request ->
            pendingRequest = null
            if (request.continuation.isActive) {
                request.continuation.resumeWithException(IllegalStateException("session released"))
            }
        }
        reader.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay.surface = null }
        runCatching { virtualDisplay.release() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        runCatching { reader.close() }
        DiagnosticLog.log(TAG, "session released")
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
