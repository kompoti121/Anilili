package com.miruronative.data.settings

import com.miruronative.ui.watch.applyCaptionStyleJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStyleTest {

    @Test
    fun testBottomMarginFractionCalculation() {
        val style = CaptionStyle(bottomMarginPercent = 25)
        assertEquals(0.25f, style.bottomPaddingFraction, 0.001f)
    }

    @Test
    fun testApplyCaptionStyleJsIncludesMarginAndControlsOffset() {
        val style = CaptionStyle(bottomMarginPercent = 25)
        val jsWithoutControls = applyCaptionStyleJs(style, controlsVisible = false)
        assertTrue(jsWithoutControls.contains("bottom: 25% !important"))

        val jsWithControls = applyCaptionStyleJs(style, controlsVisible = true)
        assertTrue(jsWithControls.contains("bottom: 35% !important"))
    }
}
