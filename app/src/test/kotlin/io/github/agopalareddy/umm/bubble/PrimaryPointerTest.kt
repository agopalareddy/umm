package io.github.agopalareddy.umm.bubble

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import io.github.agopalareddy.umm.bubble.PrimaryPointer.Step.DOWN
import io.github.agopalareddy.umm.bubble.PrimaryPointer.Step.MOVE
import io.github.agopalareddy.umm.bubble.PrimaryPointer.Step.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryPointerTest {
    private val p = PrimaryPointer()
    private val none = emptyList<PrimaryPointer.Step>()

    @Test fun singleFingerPress() {
        assertEquals(listOf(DOWN), p.onEvent(ACTION_DOWN, 0))
        assertEquals(0, p.id)
        assertEquals(listOf(MOVE), p.onEvent(ACTION_MOVE, 0))
        assertEquals(listOf(UP), p.onEvent(ACTION_UP, 0))
        assertNull(p.id)
    }

    @Test fun cancelCountsAsUp() {
        p.onEvent(ACTION_DOWN, 0)
        assertEquals(listOf(UP), p.onEvent(ACTION_CANCEL, 0))
        assertNull(p.id)
    }

    @Test fun secondFingerIsIgnored() {
        p.onEvent(ACTION_DOWN, 0)
        assertEquals(none, p.onEvent(ACTION_POINTER_DOWN, 1))
        assertEquals(none, p.onEvent(ACTION_POINTER_UP, 1))
        assertEquals(0, p.id)
        assertEquals(listOf(UP), p.onEvent(ACTION_UP, 0))
    }

    @Test fun firstFingerLiftingFirstEndsThePressOnce() {
        p.onEvent(ACTION_DOWN, 0)
        p.onEvent(ACTION_POINTER_DOWN, 1)
        assertEquals(listOf(UP), p.onEvent(ACTION_POINTER_UP, 0))
        // The other finger's moves and final up belong to no press.
        assertEquals(none, p.onEvent(ACTION_MOVE, 1))
        assertEquals(none, p.onEvent(ACTION_UP, 1))
    }

    @Test fun downWithAPressStillOpenClosesItFirst() {
        p.onEvent(ACTION_DOWN, 0)
        assertEquals(listOf(UP, DOWN), p.onEvent(ACTION_DOWN, 3))
        assertEquals(3, p.id)
    }

    @Test fun eventsWithoutAPressDoNothing() {
        assertEquals(none, p.onEvent(ACTION_MOVE, 0))
        assertEquals(none, p.onEvent(ACTION_UP, 0))
        assertEquals(none, p.onEvent(ACTION_CANCEL, 0))
        assertEquals(none, p.onEvent(ACTION_HOVER_MOVE, 0))
    }
}
