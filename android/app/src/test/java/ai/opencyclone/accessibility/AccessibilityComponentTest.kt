package ai.opencyclone.accessibility

import ai.opencyclone.ui.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityComponentTest {
    @Test fun `standard build uses the real class name, not the application id`() {
        assertEquals("ai.opencyclone.standard/ai.opencyclone.accessibility.VdAccessibilityService",
            VdAccessibilityService.componentName("ai.opencyclone.standard"))
    }

    @Test fun `system build`() {
        assertEquals("ai.opencyclone/ai.opencyclone.accessibility.VdAccessibilityService",
            VdAccessibilityService.componentName("ai.opencyclone"))
    }

    @Test fun `the permission row recognises the name the service writes`() {
        val written = VdAccessibilityService.componentName("ai.opencyclone.standard")
        assertTrue(PermissionStatus.containsComponent("other/.X:$written", "ai.opencyclone.standard", PermissionStatus.ACCESSIBILITY_CLASS))
    }
}
