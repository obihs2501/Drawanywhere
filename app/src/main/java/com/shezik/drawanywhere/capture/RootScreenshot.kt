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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Optional root-only screenshot path: `su -c screencap -p`.
 *
 * Needs no MediaProjection consent and shows no casting indicator. Returns
 * null whenever root is unavailable, denied, times out (e.g. the superuser
 * prompt is left unanswered) or the output is not a decodable PNG, so callers
 * can fall back to the regular capture path.
 */
object RootScreenshot {
    private const val TAG = "RootScreenshot"

    suspend fun capture(timeoutMs: Long = 10_000L): Bitmap? = withContext(Dispatchers.IO) {
        val process = runCatching {
            ProcessBuilder("su", "-c", "screencap -p").start()
        }.onFailure { Log.i(TAG, "su unavailable: $it") }.getOrNull() ?: return@withContext null

        try {
            val bytes = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<ByteArray?> { continuation ->
                    continuation.invokeOnCancellation { runCatching { process.destroyForcibly() } }
                    thread(name = "root-screencap", isDaemon = true) {
                        val output = runCatching { process.inputStream.use { it.readBytes() } }.getOrNull()
                        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
                        if (exit != 0) Log.w(TAG, "screencap exited with $exit")
                        if (continuation.isActive) continuation.resume(if (exit == 0) output else null)
                    }
                }
            }
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "No screenshot data from root screencap")
                return@withContext null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                .also { if (it == null) Log.w(TAG, "Could not decode root screenshot") }
        } finally {
            runCatching { process.destroy() }
        }
    }
}
