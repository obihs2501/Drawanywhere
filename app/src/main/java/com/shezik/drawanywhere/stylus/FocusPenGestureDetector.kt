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
 * What the system delivers to a registered touch-film client (verified on
 * HyperOS 4 / Focus Pen Pro):
 *
 *  - squeeze: key code 194 with scan code 189 (KEY_F19), DOWN + UP per squeeze;
 *    a double tap on the pen arrives as two such squeezes, no dedicated key
 *  - slide up / down: key codes 196 / 197
 *  - key code 195 is defined by the PenEngine SDK as "double click"; it is
 *    honoured if it ever shows up and then supersedes our own tap counting
 *
 * Double taps are therefore detected here: a squeeze whose DOWN arrives within
 * [doubleTapWindowMs] after the previous squeeze's UP becomes [FocusPenGesture.DoubleTap]
 * (fired on that second DOWN); a lone squeeze becomes [FocusPenGesture.Squeeze]
 * once the window elapses. A window of 0 disables counting and fires Squeeze
 * on DOWN with no delay. Plain Kotlin (no android.view.KeyEvent) so it can be
 * unit-tested on the JVM; timers go through [Scheduler].
 */
class FocusPenGestureDetector(
    private val scheduler: Scheduler,
    private val doubleTapWindowMs: () -> Long,
    private val onGesture: (FocusPenGesture) -> Unit,
) {
    fun interface Cancellable {
        fun cancel()
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, block: () -> Unit): Cancellable
    }

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

    private var squeezePressed = false
    private var pendingSingle: Cancellable? = null
    private var currentPressIsSecondTap = false
    private var ignoreCurrentPressUp = false
    /** Non-null while a double tap we detected ourselves is still "recent". */
    private var ownDoubleTapCooldown: Cancellable? = null

    /**
     * Feed one key event.
     *
     * @return true when the event belongs to a pen gesture and must be consumed
     *   by the caller (also for key-ups and repeats) so nothing leaks to windows
     *   underneath; false for unrelated keys.
     */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int = 0, scanCode: Int = 0): Boolean {
        val gesture = gestureForKey(keyCode, scanCode) ?: return false
        when (gesture) {
            FocusPenGesture.Squeeze -> onSqueeze(action, repeatCount)
            FocusPenGesture.DoubleTap -> if (action == ACTION_DOWN && repeatCount == 0) {
                // System-synthesised double tap. If we already recognised this
                // double tap from its two squeezes, do not fire it twice.
                cancelPendingSingle()
                if (squeezePressed) ignoreCurrentPressUp = true
                if (ownDoubleTapCooldown == null) onGesture(FocusPenGesture.DoubleTap)
            }
            FocusPenGesture.SlideUp, FocusPenGesture.SlideDown ->
                if (action == ACTION_DOWN && repeatCount == 0) onGesture(gesture)
        }
        return true
    }

    private fun onSqueeze(action: Int, repeatCount: Int) {
        when (action) {
            ACTION_DOWN -> {
                if (squeezePressed || repeatCount > 0) return
                squeezePressed = true
                val window = doubleTapWindowMs()
                if (window <= 0L) {
                    onGesture(FocusPenGesture.Squeeze)
                    return
                }
                if (pendingSingle != null) {
                    cancelPendingSingle()
                    currentPressIsSecondTap = true
                    ownDoubleTapCooldown?.cancel()
                    ownDoubleTapCooldown = scheduler.schedule(window) { ownDoubleTapCooldown = null }
                    onGesture(FocusPenGesture.DoubleTap)
                }
            }
            ACTION_UP -> {
                if (!squeezePressed) return
                squeezePressed = false
                if (currentPressIsSecondTap) {
                    currentPressIsSecondTap = false
                    return
                }
                if (ignoreCurrentPressUp) {
                    ignoreCurrentPressUp = false
                    return
                }
                val window = doubleTapWindowMs()
                if (window <= 0L) return
                cancelPendingSingle()
                pendingSingle = scheduler.schedule(window) {
                    pendingSingle = null
                    onGesture(FocusPenGesture.Squeeze)
                }
            }
        }
    }

    private fun cancelPendingSingle() {
        pendingSingle?.cancel()
        pendingSingle = null
    }

    fun reset() {
        cancelPendingSingle()
        ownDoubleTapCooldown?.cancel()
        ownDoubleTapCooldown = null
        squeezePressed = false
        currentPressIsSecondTap = false
        ignoreCurrentPressUp = false
    }
}
