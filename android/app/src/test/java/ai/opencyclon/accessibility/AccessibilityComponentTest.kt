package ai.opencyclon.accessibility

import ai.opencyclon.ui.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityComponentTest {
    @Test fun `standard build uses the real class name, not the application id`() {
        assertEquals("ai.opencyclon.standard/ai.opencyclon.accessibility.VdAccessibilityService",
            VdAccessibilityService.componentName("ai.opencyclon.standard"))
    }

    @Test fun `system build`() {
        assertEquals("ai.opencyclon/ai.opencyclon.accessibility.VdAccessibilityService",
            VdAccessibilityService.componentName("ai.opencyclon"))
    }

    @Test fun `the permission row recognises the name the service writes`() {
        val written = VdAccessibilityService.componentName("ai.opencyclon.standard")
        assertTrue(PermissionStatus.containsComponent("other/.X:$written", "ai.opencyclon.standard", PermissionStatus.ACCESSIBILITY_CLASS))
    }
}
