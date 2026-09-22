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

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of stylus / capture diagnostics, shown in settings so
 * the user can copy it without adb. Everything is mirrored to logcat.
 */
object DiagnosticLog {
    private const val MAX_LINES = 300
    private const val TAG = "DrawAnywhereDiag"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        val line = "${timeFormat.format(Date())} [$tag] $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    @Synchronized
    fun logBlock(tag: String, header: String, body: String) {
        val bodyLines = body.lines().filter { it.isNotBlank() }
        Log.i(TAG, "[$tag] $header (${bodyLines.size} lines)")
        val stamped = "${timeFormat.format(Date())} [$tag] $header"
        _lines.value = (_lines.value + stamped + bodyLines.map { "    $it" }).takeLast(MAX_LINES)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String = lines.value.joinToString("\n")
}
