package com.shezik.drawanywhere.stylus

import com.shezik.drawanywhere.model.FocusPenGesture
import com.shezik.drawanywhere.stylus.FocusPenGestureDetector.Companion.ACTION_DOWN
import com.shezik.drawanywhere.stylus.FocusPenGestureDetector.Companion.ACTION_UP
import com.shezik.drawanywhere.stylus.FocusPenGestureDetector.Companion.KEYCODE_DOUBLE_TAP
import com.shezik.drawanywhere.stylus.FocusPenGestureDetector.Companion.KEYCODE_SLIDE_DOWN
import com.shezik.drawanywhere.stylus.FocusPenGestureDetector.Companion.KEYCODE_SLIDE_UP
import com.shezik.drawanywhere.stylus.FocusPenGestureDetector.Companion.KEYCODE_SQUEEZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPenGestureDetectorTest {

    @Test
    fun keyCodeRangeMatchesSdkConstants() {
        assertTrue(FocusPenGestureDetector.isGestureKeyCode(194))
        assertTrue(FocusPenGestureDetector.isGestureKeyCode(195))
        assertTrue(FocusPenGestureDetector.isGestureKeyCode(196))
        assertTrue(FocusPenGestureDetector.isGestureKeyCode(197))
        assertFalse(FocusPenGestureDetector.isGestureKeyCode(193))
        assertFalse(FocusPenGestureDetector.isGestureKeyCode(198))
        assertFalse(FocusPenGestureDetector.isGestureKeyCode(92)) // PAGE_UP
    }

    @Test
    fun squeezeFiresOnceUntilReleased() {
        val d = FocusPenGestureDetector()
        assertEquals(FocusPenGesture.Squeeze, d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN))
        assertNull(d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN, repeatCount = 1))
        assertNull(d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN))
        assertNull(d.onKey(KEYCODE_SQUEEZE, ACTION_UP))
        assertEquals(FocusPenGesture.Squeeze, d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN))
    }

    @Test
    fun otherGesturesFireOnDownOnly() {
        val d = FocusPenGestureDetector()
        assertEquals(FocusPenGesture.DoubleTap, d.onKey(KEYCODE_DOUBLE_TAP, ACTION_DOWN))
        assertNull(d.onKey(KEYCODE_DOUBLE_TAP, ACTION_UP))
        assertEquals(FocusPenGesture.SlideUp, d.onKey(KEYCODE_SLIDE_UP, ACTION_DOWN))
        assertNull(d.onKey(KEYCODE_SLIDE_UP, ACTION_UP))
        assertEquals(FocusPenGesture.SlideDown, d.onKey(KEYCODE_SLIDE_DOWN, ACTION_DOWN))
        assertNull(d.onKey(KEYCODE_SLIDE_DOWN, ACTION_DOWN, repeatCount = 2))
    }

    @Test
    fun unrelatedKeysAreIgnored() {
        val d = FocusPenGestureDetector()
        assertNull(d.onKey(92, ACTION_DOWN))
        assertNull(d.onKey(4, ACTION_DOWN))
    }

    @Test
    fun resetClearsSqueezeLatch() {
        val d = FocusPenGestureDetector()
        assertEquals(FocusPenGesture.Squeeze, d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN))
        d.reset()
        assertEquals(FocusPenGesture.Squeeze, d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN))
    }
}
