package ai.opencyclone.action

import ai.opencyclone.accessibility.VdAccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home mode makes our own app a HOME candidate; the hidden display's home helpers must never pick it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherPackageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun addHome(pkg: String, system: Boolean) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Home"
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), info)
    }

    @Test fun `launcher package skips our own package when it is a HOME candidate`() {
        addHome(context.packageName, system = false)
        addHome("com.android.launcher3", system = true)
        assertEquals("com.android.launcher3", ActionExecutor.launcherPackage(context))
    }

    @Test fun `launcher package skips our own package even when it is listed first and is the only other choice`() {
        addHome(context.packageName, system = true)
        addHome("com.example.launcher", system = false)
        assertEquals("com.example.launcher", ActionExecutor.launcherPackage(context))
    }

    @Test fun `launcher package is null when only our own app is a HOME candidate`() {
        addHome(context.packageName, system = false)
        assertNull(ActionExecutor.launcherPackage(context))
    }

    @Test fun `home packages of the accessibility service leave out our package and the resolver`() {
        val own = context.packageName
        assertEquals(
            setOf("com.android.launcher3"),
            VdAccessibilityService.homePackagesOf(listOf(own, "android", "com.android.launcher3", own), own)
        )
    }
}
