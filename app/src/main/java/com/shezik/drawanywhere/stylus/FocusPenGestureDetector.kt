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

import android.view.KeyEvent
import com.shezik.drawanywhere.model.FocusPenGesture

/**
 * 小米焦点触控笔二代/Pro 笔身手势的原始 KeyEvent 识别。
 *
 * 系统把笔身手势直接作为独立 KeyCode 送入获得焦点的窗口（见 PenEngine
 * `com.miui.penengine.e.j`：194 轻捏 / 195 连击 / 196 上滑 / 197 下滑）。
 * 这里按手势识别并交给应用内映射，不依赖小米开放平台 App ID / 鉴权，
 * 也不读写系统手写笔设置（stylus_pinch_status 等）。
 */
object FocusPenGestureDetector {

    /** 轻捏 */
    const val KEYCODE_SQUEEZE = 194

    /** 连击（双击） */
    const val KEYCODE_MULTI_TAP = 195

    /** 笔身上滑 */
    const val KEYCODE_SLIDE_UP = 196

    /** 笔身下滑 */
    const val KEYCODE_SLIDE_DOWN = 197

    /** 轻捏是按下/抬起一对事件，只在首次 DOWN 触发，避免连发。 */
    private var squeezeLatched = false

    /** 是否属于焦点笔手势 KeyCode。 */
    fun isGestureKeyCode(keyCode: Int): Boolean =
        keyCode == KEYCODE_SQUEEZE || keyCode == KEYCODE_MULTI_TAP ||
            keyCode == KEYCODE_SLIDE_UP || keyCode == KEYCODE_SLIDE_DOWN

    /**
     * 消费并识别手势事件。
     *
     * @return 应当触发的手势；仅需消费、不触发时返回 null。
     * 调用方对 [isGestureKeyCode] 为 true 的事件一律消费，防止漏给下层窗口。
     */
    fun consume(event: KeyEvent): FocusPenGesture? {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        return when (event.keyCode) {
            KEYCODE_SQUEEZE -> when {
                down && !squeezeLatched -> {
                    squeezeLatched = true
                    FocusPenGesture.Squeeze
                }
                up -> {
                    squeezeLatched = false
                    null
                }
                else -> null
            }
            KEYCODE_MULTI_TAP -> if (down) FocusPenGesture.MultiTap else null
            KEYCODE_SLIDE_UP -> if (down) FocusPenGesture.SlideUp else null
            KEYCODE_SLIDE_DOWN -> if (down) FocusPenGesture.SlideDown else null
            else -> null
        }
    }

    /** 会话结束时复位。 */
    fun reset() {
        squeezeLatched = false
    }
}
