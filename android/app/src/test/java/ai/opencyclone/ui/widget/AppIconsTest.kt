package ai.opencyclone.ui.widget

import ai.opencyclone.R
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.content.pm.PackageInfo
import android.content.pm.ApplicationInfo
import android.graphics.drawable.ColorDrawable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun installedIconIsRoundedAt28Percent() {
        AppIcons.clearCache()
        val pkg = "com.example.chat"
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        pm.setApplicationIcon(pkg, ColorDrawable(Color.RED))
        val d = AppIcons.load(context, pkg, 100)
        assertTrue(d is RoundedBitmapDrawable)
        assertEquals(28f, (d as RoundedBitmapDrawable).cornerRadius, 0.01f)
        assertEquals(100, d.bitmap!!.width)
        assertEquals(Color.RED, d.bitmap!!.getPixel(50, 50))
        // the second load comes from the cache with the same pixels
        assertEquals(Color.RED, (AppIcons.load(context, pkg, 100) as RoundedBitmapDrawable).bitmap!!.getPixel(50, 50))
    }

    @Test fun missingPackageGivesNull() {
        assertNull(AppIcons.load(context, "com.example.not.installed", 48))
    }

    @Test fun letterTileHasAccentAndCorners() {
        val accent = Color.parseColor("#D3306B")
        val d = AppIcons.letterTile(context, "spanish", accent, 44) as BitmapDrawable
        val b = d.bitmap
        assertEquals(44, b.width)
        // the corner is cut away, an edge midpoint is the accent
        assertEquals(0, Color.alpha(b.getPixel(0, 0)))
        assertEquals(accent, b.getPixel(1, 22))
        // the letter is white somewhere in the middle
        assertTrue((10 until 34).any { x -> (10 until 34).any { y -> b.getPixel(x, y) == Color.WHITE } })
    }

    @Test fun accentParsingFallsBackToDefault() {
        assertEquals(Color.parseColor("#2F6FDB"), AppIcons.parseAccent(context, "#2F6FDB"))
        val def = ContextCompat.getColor(context, R.color.accent_default)
        assertEquals(def, AppIcons.parseAccent(context, "not a colour"))
        assertEquals(def, AppIcons.parseAccent(context, null))
    }
}
