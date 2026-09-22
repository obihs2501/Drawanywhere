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
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPenGestureDetectorTest {

    /** Manual scheduler: tasks fire only when the test advances time. */
    private class FakeScheduler {
        private var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        val scheduler = FocusPenGestureDetector.Scheduler { delay, block ->
            val entry = (now + delay) to block
            tasks += entry
            FocusPenGestureDetector.Cancellable { tasks.remove(entry) }
        }

        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.first <= now }.sortedBy { it.first }
            tasks.removeAll(due)
            due.forEach { it.second() }
        }

        val pendingCount get() = tasks.size
    }

    private fun newDetector(windowMs: Long = 400L): Triple<FocusPenGestureDetector, FakeScheduler, MutableList<FocusPenGesture>> {
        val scheduler = FakeScheduler()
        val fired = mutableListOf<FocusPenGesture>()
        val detector = FocusPenGestureDetector(scheduler.scheduler, { windowMs }, fired::add)
        return Triple(detector, scheduler, fired)
    }

    private fun FocusPenGestureDetector.squeeze(scan: Int = 189) {
        onKey(KEYCODE_SQUEEZE, ACTION_DOWN, scanCode = scan)
        onKey(KEYCODE_SQUEEZE, ACTION_UP, scanCode = scan)
    }

    @Test
    fun keyCodeRangeMatchesSdkConstants() {
        assertTrue(FocusPenGestureDetector.isGestureKeyCode(194))
        assertTrue(FocusPenGestureDetector.isGestureKeyCode(197))
        assertFalse(FocusPenGestureDetector.isGestureKeyCode(193))
        assertFalse(FocusPenGestureDetector.isGestureKeyCode(198))
        assertTrue(FocusPenGestureDetector.isGestureKey(keyCode = 0, scanCode = 189))
        assertFalse(FocusPenGestureDetector.isGestureKey(keyCode = 0, scanCode = 188))
    }

    @Test
    fun loneSqueezeFiresAfterWindow() {
        val (d, s, fired) = newDetector()
        d.squeeze()
        assertTrue(fired.isEmpty())
        s.advance(399)
        assertTrue(fired.isEmpty())
        s.advance(1)
        assertEquals(listOf(FocusPenGesture.Squeeze), fired)
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun twoSqueezesWithinWindowAreOneDoubleTap() {
        val (d, s, fired) = newDetector()
        d.squeeze()
        s.advance(250)
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN, scanCode = 189)
        assertEquals(listOf(FocusPenGesture.DoubleTap), fired)
        d.onKey(KEYCODE_SQUEEZE, ACTION_UP, scanCode = 189)
        s.advance(1_000)
        assertEquals(listOf(FocusPenGesture.DoubleTap), fired)
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun squeezesOutsideWindowAreTwoSqueezes() {
        val (d, s, fired) = newDetector()
        d.squeeze()
        s.advance(450)
        d.squeeze()
        s.advance(450)
        assertEquals(listOf(FocusPenGesture.Squeeze, FocusPenGesture.Squeeze), fired)
    }

    @Test
    fun zeroWindowFiresSqueezeOnDownImmediately() {
        val (d, s, fired) = newDetector(windowMs = 0L)
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN)
        assertEquals(listOf(FocusPenGesture.Squeeze), fired)
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN, repeatCount = 1)
        d.onKey(KEYCODE_SQUEEZE, ACTION_UP)
        assertEquals(1, fired.size)
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun repeatsAndDuplicateDownsAreIgnored() {
        val (d, s, fired) = newDetector()
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN)
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN, repeatCount = 1)
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN, repeatCount = 2)
        d.onKey(KEYCODE_SQUEEZE, ACTION_UP)
        s.advance(400)
        assertEquals(listOf(FocusPenGesture.Squeeze), fired)
    }

    @Test
    fun systemDoubleTapKeySupersedesTapCounting() {
        val (d, s, fired) = newDetector()
        d.squeeze()
        d.onKey(KEYCODE_SQUEEZE, ACTION_DOWN)
        // Hypothetical system-synthesised 195 while the second squeeze is held.
        d.onKey(KEYCODE_DOUBLE_TAP, ACTION_DOWN)
        d.onKey(KEYCODE_DOUBLE_TAP, ACTION_UP)
        d.onKey(KEYCODE_SQUEEZE, ACTION_UP)
        s.advance(1_000)
        assertEquals(listOf(FocusPenGesture.DoubleTap), fired)
    }

    @Test
    fun slidesFireOnDownOnly() {
        val (d, _, fired) = newDetector()
        d.onKey(KEYCODE_SLIDE_UP, ACTION_DOWN)
        d.onKey(KEYCODE_SLIDE_UP, ACTION_UP)
        d.onKey(KEYCODE_SLIDE_DOWN, ACTION_DOWN)
        d.onKey(KEYCODE_SLIDE_DOWN, ACTION_DOWN, repeatCount = 3)
        d.onKey(KEYCODE_SLIDE_DOWN, ACTION_UP)
        assertEquals(listOf(FocusPenGesture.SlideUp, FocusPenGesture.SlideDown), fired)
    }

    @Test
    fun unrelatedKeysAreNotConsumed() {
        val (d, _, fired) = newDetector()
        assertFalse(d.onKey(92, ACTION_DOWN))
        assertFalse(d.onKey(4, ACTION_DOWN))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun resetDropsPendingSqueeze() {
        val (d, s, fired) = newDetector()
        d.squeeze()
        d.reset()
        s.advance(1_000)
        assertTrue(fired.isEmpty())
        d.squeeze()
        s.advance(400)
        assertEquals(listOf(FocusPenGesture.Squeeze), fired)
    }
}
