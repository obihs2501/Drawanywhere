package com.shezik.drawanywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPenGestureTest {

    @Test
    fun gesturesAreTheFourPenBodyGestures() {
        assertEquals(
            listOf(
                FocusPenGesture.Squeeze,
                FocusPenGesture.MultiTap,
                FocusPenGesture.SlideUp,
                FocusPenGesture.SlideDown,
            ),
            FocusPenGesture.entries.toList(),
        )
    }

    @Test
    fun stylusActionsIncludeSwitchPreviousPen() {
        assertTrue(StylusButtonAction.entries.contains(StylusButtonAction.SwitchPreviousPen))
    }

    @Test
    fun schemesIncludeXiaomiFocusPen() {
        assertTrue(StylusButtonScheme.entries.contains(StylusButtonScheme.XiaomiFocusPen))
    }
}
