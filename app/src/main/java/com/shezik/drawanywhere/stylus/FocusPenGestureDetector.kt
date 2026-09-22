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
 * Turns the key events HyperOS emits for Xiaomi Focus Pen barrel gestures
 * into [FocusPenGesture]s.
 *
 * Two shapes of event exist:
 *
 * 1. Translated codes the system injects for a registered touch-film client
 *    (decompiled PenEngine SDK, `com.miui.penengine.e.j`):
 *    194 squeeze, 195 double tap, 196 slide up, 197 slide down.
 * 2. The raw pen key as seen in `system_server` when nothing translates it
 *    (FocusPenProX `CompatProfile`): squeeze = scan code 189 (KEY_F19) with
 *    whatever key code the layout assigns; slides keep 196 / 197.
 *
 * Every gesture fires on ACTION_DOWN. Squeeze latches until ACTION_UP so a
 * held squeeze cannot repeat. Plain Kotlin (no android.view.KeyEvent) so it
 * can be unit-tested on the JVM.
 */
class FocusPenGestureDetector {

    companion object {
        const val KEYCODE_SQUEEZE = 194
        const val KEYCODE_DOUBLE_TAP = 195
        const val KEYCODE_SLIDE_UP = 196
        const val KEYCODE_SLIDE_DOWN = 197

        /** Linux KEY_F19: raw squeeze scan code of the Focus Pen. */
        const val SCANCODE_SQUEEZE_RAW = 189

        /** Mirrors android.view.KeyEvent.ACTION_DOWN / ACTION_UP. */
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1

        fun isGestureKeyCode(keyCode: Int): Boolean =
            keyCode in KEYCODE_SQUEEZE..KEYCODE_SLIDE_DOWN

        fun isGestureKey(keyCode: Int, scanCode: Int): Boolean =
            isGestureKeyCode(keyCode) || scanCode == SCANCODE_SQUEEZE_RAW

        fun gestureForKey(keyCode: Int, scanCode: Int = 0): FocusPenGesture? = when (keyCode) {
            KEYCODE_SQUEEZE -> FocusPenGesture.Squeeze
            KEYCODE_DOUBLE_TAP -> FocusPenGesture.DoubleTap
            KEYCODE_SLIDE_UP -> FocusPenGesture.SlideUp
            KEYCODE_SLIDE_DOWN -> FocusPenGesture.SlideDown
            else -> if (scanCode == SCANCODE_SQUEEZE_RAW) FocusPenGesture.Squeeze else null
        }
    }

    private var squeezeLatched = false

    /**
     * Feed one key event.
     *
     * @return the gesture to trigger, or null when the event only needs to be
     *   consumed (key-up, key repeat, latched squeeze). Callers should consume
     *   every event whose codes pass [isGestureKey] regardless of the return
     *   value so nothing leaks to windows underneath.
     */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int = 0, scanCode: Int = 0): FocusPenGesture? {
        val gesture = gestureForKey(keyCode, scanCode) ?: return null
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
