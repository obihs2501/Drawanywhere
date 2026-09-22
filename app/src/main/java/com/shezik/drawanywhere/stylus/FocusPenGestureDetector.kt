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

import com.shezik.drawanywhere.model.FocusPenGesture

/**
 * Turns the raw key codes HyperOS emits for Xiaomi Focus Pen barrel gestures
 * into [FocusPenGesture]s.
 *
 * Key codes (from the decompiled PenEngine SDK, `com.miui.penengine.e.j`):
 *
 * | code | Android name       | gesture    |
 * |------|--------------------|------------|
 * | 194  | KEYCODE_BUTTON_7   | squeeze    |
 * | 195  | KEYCODE_BUTTON_8   | double tap |
 * | 196  | KEYCODE_BUTTON_9   | slide up   |
 * | 197  | KEYCODE_BUTTON_10  | slide down |
 *
 * The SDK fires every gesture on ACTION_DOWN. Squeeze additionally latches
 * until its ACTION_UP so a held squeeze cannot repeat. The class is plain
 * Kotlin (no android.view.KeyEvent) so it can be unit-tested on the JVM.
 */
class FocusPenGestureDetector {

    companion object {
        const val KEYCODE_SQUEEZE = 194
        const val KEYCODE_DOUBLE_TAP = 195
        const val KEYCODE_SLIDE_UP = 196
        const val KEYCODE_SLIDE_DOWN = 197

        /** Mirrors android.view.KeyEvent.ACTION_DOWN / ACTION_UP. */
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        fun isGestureKeyCode(keyCode: Int): Boolean =
            keyCode in KEYCODE_SQUEEZE..KEYCODE_SLIDE_DOWN

        fun gestureForKeyCode(keyCode: Int): FocusPenGesture? = when (keyCode) {
            KEYCODE_SQUEEZE -> FocusPenGesture.Squeeze
            KEYCODE_DOUBLE_TAP -> FocusPenGesture.DoubleTap
            KEYCODE_SLIDE_UP -> FocusPenGesture.SlideUp
            KEYCODE_SLIDE_DOWN -> FocusPenGesture.SlideDown
            else -> null
        }
    }

    private var squeezeLatched = false

    /**
     * Feed one key event.
     *
     * @return the gesture to trigger, or null when the event only needs to be
     *   consumed (key-up, key repeat, latched squeeze). Callers should consume
     *   every event whose key code passes [isGestureKeyCode] regardless of the
     *   return value so nothing leaks to windows underneath.
     */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int = 0): FocusPenGesture? {
        val gesture = gestureForKeyCode(keyCode) ?: return null
        if (gesture == FocusPenGesture.Squeeze) {
            return when (action) {
                ACTION_DOWN -> if (squeezeLatched || repeatCount > 0) {
                    null
                } else {
                    squeezeLatched = true
                    FocusPenGesture.Squeeze
                }
                ACTION_UP -> {
                    squeezeLatched = false
                    null
                }
                else -> null
            }
        }
        return if (action == ACTION_DOWN && repeatCount == 0) gesture else null
    }

    fun reset() {
        squeezeLatched = false
    }
}
