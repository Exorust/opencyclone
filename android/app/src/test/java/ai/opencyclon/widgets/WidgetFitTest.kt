package ai.opencyclon.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFitTest {
    @Test fun dataWidgetsMustReadAtLeastOneApprovedSourceButClocksNeedNone() {
        assertTrue(WidgetTester.missingSourceReads(setOf("app:3", "history"), emptySet()))
        assertTrue(WidgetTester.missingSourceReads(setOf("app:3", "clock"), setOf("clock")))
        org.junit.Assert.assertFalse(WidgetTester.missingSourceReads(setOf("clock"), emptySet()))
        assertTrue(WidgetTester.missingSourceReads(setOf("app:3"), setOf("notifications")))
        org.junit.Assert.assertFalse(WidgetTester.missingSourceReads(setOf("app:3", "history"), setOf("app:3")))
        org.junit.Assert.assertFalse(WidgetTester.missingSourceReads(emptySet(), emptySet()))
    }

    @Test fun contentWithinFourPixelsFits() {
        assertTrue(WidgetTester.fitProblems(150, 380, 150, 380).isEmpty())
        assertTrue(WidgetTester.fitProblems(154, 384, 150, 380).isEmpty())
        assertTrue(WidgetTester.fitProblems(100, 200, 150, 380).isEmpty())
    }

    @Test fun verticalOverflowIncludesExactExcessAndViewport() {
        assertEquals(listOf("The content is 96 px taller than the widget. Everything must be visible without scrolling in 380 by 150 px."),
            WidgetTester.fitProblems(246, 380, 150, 380))
    }

    @Test fun horizontalOverflowAndBothAxesAreReported() {
        assertEquals(listOf("The content is 5 px wider than the widget. Everything must be visible without scrolling in 380 by 150 px."),
            WidgetTester.fitProblems(150, 385, 150, 380))
        assertEquals(listOf("The content is 96 px taller and 20 px wider than the widget. Everything must be visible without scrolling in 380 by 150 px."),
            WidgetTester.fitProblems(246, 400, 150, 380))
    }

    @Test fun tallHeightFollowsContentWithinBounds() {
        assertEquals(120, WidgetDimensions.height(WidgetSize.TALL, 60))
        assertEquals(210, WidgetDimensions.height(WidgetSize.TALL, 210))
        assertEquals(320, WidgetDimensions.height(WidgetSize.TALL, 500))
        assertEquals(120, WidgetDimensions.height(WidgetSize.TALL, 120))
        assertEquals(320, WidgetDimensions.height(WidgetSize.TALL, 320))
    }

    @Test fun smallAndWideKeepTheirHeight() {
        for (size in listOf(WidgetSize.SMALL, WidgetSize.WIDE)) {
            assertEquals(160, WidgetDimensions.height(size, 60))
            assertEquals(160, WidgetDimensions.height(size, 500))
        }
    }

    @Test fun cardWidthsUseTheSameGapAndContentArea() {
        assertEquals(172, WidgetDimensions.width(WidgetSize.SMALL, 360, 16))
        assertEquals(360, WidgetDimensions.width(WidgetSize.WIDE, 360, 16))
        assertEquals(360, WidgetDimensions.width(WidgetSize.TALL, 360, 16))
    }
}
