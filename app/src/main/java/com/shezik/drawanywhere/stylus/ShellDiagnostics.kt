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
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Shell helpers for the diagnostics panel. Own-process logcat needs no
 * permission; the `su` variants need a rooted device and exist to find out
 * where the pen gestures surface (raw input layer / system logs).
 */
object ShellDiagnostics {
    private const val TAG = "ShellDiag"
    private const val PROC_INPUT_DEVICES = "/proc/bus/input/devices"

    data class InputDevice(
        val name: String,
        val vendor: String,
        val product: String,
        val handlers: List<String>,
    ) {
        val eventNode: String? get() = handlers.firstOrNull { it.startsWith("event") }
        val looksLikePen: Boolean
            get() = name.contains("pen", ignoreCase = true) ||
                name.contains("stylus", ignoreCase = true) ||
                vendor.equals("0022", ignoreCase = true)
    }

    suspend fun readOwnLogcat(): String? = runText(
        listOf(
            "logcat", "-d", "-v", "time",
            "FocusPenLink:V", "PencilEngineClient:V", "DrawAnywhereDiag:V", "ShellDiag:V",
            "MI-PencilManager:V", "MiuiTouchFilmUtils:V", "MiuiAuthHelper:V", "PenEngine:V",
            "*:S",
        ),
        timeoutMs = 8_000L,
    )?.lines()?.takeLast(120)?.joinToString("\n")

    /** Read-only dump of the system stylus settings, for diagnostics only. */
    fun readStylusSettings(context: Context): String {
        val resolver = context.contentResolver
        val keys = listOf(
            "stylus_pinch_status", "stylus_double_click_status", "stylus_hover_enable",
            "stylus_brush_preview_enable", "stylus_pencil_engine_version",
        )
        return keys.joinToString("\n") { key ->
            val system = runCatching { Settings.System.getString(resolver, key) }.getOrNull()
            val secure = runCatching { Settings.Secure.getString(resolver, key) }.getOrNull()
            val global = runCatching { Settings.Global.getString(resolver, key) }.getOrNull()
            "$key: system=$system secure=$secure global=$global"
        }
    }

    suspend fun rootListInputDevices(): List<InputDevice>? {
        val text = runText(listOf("su", "-c", "cat $PROC_INPUT_DEVICES"), timeoutMs = 8_000L) ?: return null
        return parseInputDevices(text)
    }

    /**
     * Reads the raw evdev stream of every pen-looking device for [seconds]
     * (root, `cat /dev/input/eventN`, unbuffered) and decodes EV_KEY / EV_MSC
     * records. The user performs the pen gestures meanwhile.
     */
    suspend fun rootCapturePenEvents(seconds: Int): String? {
        val devices = rootListInputDevices() ?: return null
        val summary = devices.joinToString("\n") {
            "${it.eventNode ?: "?"}: \"${it.name}\" vendor=${it.vendor} product=${it.product}${if (it.looksLikePen) "  <- pen?" else ""}"
        }
        DiagnosticLog.logBlock(TAG, "input devices", summary)
        val targets = devices.filter { it.looksLikePen && it.eventNode != null }.take(4)
        if (targets.isEmpty()) return "no pen-looking input device found; devices:\n$summary"

        val results = coroutineScope {
            targets.map { device ->
                async {
                    val node = "/dev/input/${device.eventNode}"
                    val bytes = runBinary(
                        listOf("su", "-c", "timeout $seconds cat $node"),
                        timeoutMs = seconds * 1000L + 4_000L,
                    )
                    val decoded = bytes?.let(::decodeEvdev).orEmpty()
                    "$node (\"${device.name}\"): ${decoded.size} key/msc events" +
                        if (decoded.isEmpty()) "" else "\n" + decoded.joinToString("\n")
                }
            }.map { it.await() }
        }
        return results.joinToString("\n")
    }

    suspend fun rootReadSystemLogcat(): String? = runText(
        listOf(
            "su", "-c",
            "logcat -d -v time | grep -iE 'stylus|pencil|touchfilm|penengine|touchservice|MiuiTouchFilm|MI-Pencil|FocusPen' | tail -n 200",
        ),
        timeoutMs = 20_000L,
        acceptNonZeroExit = true,
    )

    /** `dumpsys input` excerpt for the pen plus the key layout files it uses. */
    suspend fun rootPenInputDeviceInfo(): String? = runText(
        listOf(
            "su", "-c",
            "dumpsys input | grep -i -B2 -A16 'focus pen' | head -120; " +
                "for f in \$(dumpsys input | grep -i -A16 'focus pen' | grep -oE '/[^ ]+\\.k[lc]m?' | sort -u); do " +
                "echo \"== \$f\"; cat \"\$f\"; done",
        ),
        timeoutMs = 20_000L,
        acceptNonZeroExit = true,
    )

    /**
     * Copies every framework jar that contains MiuiStylusTouchFilmManager (the
     * system_server class that decides who gets pen gestures) to
     * /sdcard/Download/DrawAnywhere-diag so it can be decompiled off-device.
     */
    suspend fun rootExportStylusFrameworkJars(): String? = runText(
        listOf(
            "su", "-c",
            "mkdir -p /sdcard/Download/DrawAnywhere-diag; " +
                "for j in \$(grep -la MiuiStylusTouchFilmManager /system_ext/framework/*.jar /system/system_ext/framework/*.jar " +
                "/system/framework/*.jar /product/framework/*.jar 2>/dev/null | sort -u); do " +
                "echo \"found: \$j\"; cp \"\$j\" /sdcard/Download/DrawAnywhere-diag/; done; " +
                "getprop ro.mi.os.version.name; getprop ro.build.version.release; getprop ro.product.model; " +
                "ls -la /sdcard/Download/DrawAnywhere-diag",
        ),
        timeoutMs = 60_000L,
        acceptNonZeroExit = true,
    )

    internal fun parseInputDevices(text: String): List<InputDevice> {
        val devices = mutableListOf<InputDevice>()
        var name = ""
        var vendor = ""
        var product = ""
        var handlers = emptyList<String>()
        fun flush() {
            if (name.isNotEmpty() || handlers.isNotEmpty()) {
                devices += InputDevice(name, vendor, product, handlers)
            }
            name = ""; vendor = ""; product = ""; handlers = emptyList()
        }
        for (line in text.lines()) {
            when {
                line.startsWith("I:") -> {
                    flush()
                    vendor = Regex("Vendor=([0-9a-fA-F]+)").find(line)?.groupValues?.get(1) ?: ""
                    product = Regex("Product=([0-9a-fA-F]+)").find(line)?.groupValues?.get(1) ?: ""
                }
                line.startsWith("N:") -> name = line.substringAfter("Name=").trim().trim('"')
                line.startsWith("H:") -> handlers = line.substringAfter("Handlers=").trim().split(' ').filter { it.isNotBlank() }
            }
        }
        flush()
        return devices
    }

    /** struct input_event: 64-bit = 16-byte timeval + u16 type + u16 code + s32 value. */
    internal fun decodeEvdev(bytes: ByteArray): List<String> {
        val recordSize = when {
            bytes.size % 24 == 0 -> 24
            bytes.size % 16 == 0 -> 16
            else -> 24
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<String>()
        var firstSec = -1L
        while (buffer.remaining() >= recordSize) {
            val sec: Long
            val usec: Long
            if (recordSize == 24) {
                sec = buffer.long; usec = buffer.long
            } else {
                sec = buffer.int.toLong(); usec = buffer.int.toLong()
            }
            val type = buffer.short.toInt() and 0xFFFF
            val code = buffer.short.toInt() and 0xFFFF
            val value = buffer.int
            if (firstSec < 0) firstSec = sec
            val t = "+%d.%03d".format(sec - firstSec, usec / 1000)
            when (type) {
                1 -> out += "$t EV_KEY code=$code${keyName(code)} value=$value"
                4 -> out += "$t EV_MSC code=$code value=0x${Integer.toHexString(value)}"
            }
        }
        return out
    }

    private fun keyName(code: Int): String = when (code) {
        183 -> " (KEY_F13)"; 184 -> " (KEY_F14)"; 185 -> " (KEY_F15)"; 186 -> " (KEY_F16)"
        187 -> " (KEY_F17)"; 188 -> " (KEY_F18)"; 189 -> " (KEY_F19)"; 190 -> " (KEY_F20)"
        191 -> " (KEY_F21)"; 192 -> " (KEY_F22)"; 193 -> " (KEY_F23)"; 194 -> " (KEY_F24)"
        272 -> " (BTN_LEFT)"; 273 -> " (BTN_RIGHT)"; 320 -> " (BTN_TOOL_PEN)"; 321 -> " (BTN_TOOL_RUBBER)"
        329 -> " (BTN_STYLUS3)"; 330 -> " (BTN_TOUCH)"; 331 -> " (BTN_STYLUS)"; 332 -> " (BTN_STYLUS2)"
        else -> ""
    }

    private suspend fun runText(
        command: List<String>,
        timeoutMs: Long,
        acceptNonZeroExit: Boolean = false,
    ): String? = runBinary(command, timeoutMs, acceptNonZeroExit)?.toString(Charsets.UTF_8)

    private suspend fun runBinary(
        command: List<String>,
        timeoutMs: Long,
        acceptNonZeroExit: Boolean = true,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val process = runCatching { ProcessBuilder(command).start() }
            .onFailure { DiagnosticLog.log(TAG, "cannot start ${command.first()}: $it") }
            .getOrNull() ?: return@withContext null
        try {
            val output = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Pair<ByteArray, Int>?> { continuation ->
                    continuation.invokeOnCancellation { runCatching { process.destroyForcibly() } }
                    thread(name = "shell-diag", isDaemon = true) {
                        val errThread = thread(name = "shell-diag-err", isDaemon = true) {
                            val err = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
                            if (err.isNotBlank()) DiagnosticLog.logBlock(TAG, "${command.joinToString(" ")} stderr", err.take(600))
                        }
                        val bytes = runCatching { process.inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
                        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
                        runCatching { errThread.join(500) }
                        if (continuation.isActive) continuation.resume(bytes to exit)
                    }
                }
            }
            if (output == null) {
                DiagnosticLog.log(TAG, "${command.joinToString(" ")}: timed out")
                return@withContext null
            }
            val (bytes, exit) = output
            if (exit != 0 && !acceptNonZeroExit) {
                DiagnosticLog.log(TAG, "${command.first()} exited $exit")
                return@withContext null
            }
            bytes
        } finally {
            runCatching { process.destroy() }
        }
    }
}
