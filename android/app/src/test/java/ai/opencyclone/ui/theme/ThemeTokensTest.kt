package ai.opencyclone.ui.theme

import ai.opencyclone.R
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeTokensTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun resDir(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun themed(theme: Int) = ContextThemeWrapper(context, theme)

    private fun boolAttr(ctx: Context, attr: Int): Boolean {
        val a = ctx.obtainStyledAttributes(intArrayOf(attr))
        try { return a.getBoolean(0, false) } finally { a.recycle() }
    }

    private fun colorAttr(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        assertTrue(ctx.theme.resolveAttribute(attr, tv, true))
        return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
    }

    @Test fun tokensHaveTheirUiValues() {
        val expected = mapOf(
            R.color.ink to "#15173A", R.color.ink_2 to "#5B5E7A", R.color.ink_3 to "#8A8DA8",
            R.color.primary to "#4733D6", R.color.on_primary to "#FFFFFF", R.color.primary_disabled to "#CFC9F5",
            R.color.page to "#FFFFFF", R.color.surface to "#FFFFFF", R.color.surface_2 to "#F4F4FB",
            R.color.bubble_you to "#ECEBFB", R.color.on_ink to "#FFFFFF", R.color.ok to "#1F9D6B",
            R.color.accent_default to "#4733D6", R.color.btn_pale to "#ECEBFB",
            R.color.panel_body to "#F4F4FB", R.color.scrim to "#26000000", R.color.quiet to "#F1F1F8",
        )
        expected.forEach { (id, hex) ->
            assertEquals(context.resources.getResourceEntryName(id), android.graphics.Color.parseColor(hex), ContextCompat.getColor(context, id))
        }
    }

    @Test fun noNightResourcesExist() {
        val night = resDir().listFiles()!!.filter { it.name.contains("night") }
        assertTrue("night folders: $night", night.isEmpty())
    }

    @Test @Config(qualifiers = "night") fun appThemeIsLightInNightMode() {
        assertEquals(Configuration.UI_MODE_NIGHT_YES, context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        for (theme in listOf(R.style.Theme_OpenCyclone, R.style.Theme_OpenCyclone_NoActionBar, R.style.Theme_OpenCyclone_Settings, R.style.Theme_OpenCyclone_Dialog)) {
            val ctx = themed(theme)
            val name = context.resources.getResourceEntryName(theme)
            assertTrue("$name isLightTheme", boolAttr(ctx, com.google.android.material.R.attr.isLightTheme))
            assertTrue("$name light status bar", boolAttr(ctx, android.R.attr.windowLightStatusBar))
            assertEquals("$name primary", ContextCompat.getColor(ctx, R.color.primary), colorAttr(ctx, androidx.appcompat.R.attr.colorPrimary))
        }
        val app = themed(R.style.Theme_OpenCyclone)
        assertEquals(ContextCompat.getColor(app, R.color.page), colorAttr(app, android.R.attr.windowBackground))
        assertTrue(boolAttr(app, android.R.attr.windowLightNavigationBar))
        assertFalse(boolAttr(app, androidx.appcompat.R.attr.windowActionBar))
        assertEquals(android.graphics.Color.TRANSPARENT, colorAttr(app, android.R.attr.statusBarColor))
        // tokens do not change at night
        assertEquals(android.graphics.Color.parseColor("#15173A"), ContextCompat.getColor(context, R.color.ink))
        assertEquals(android.graphics.Color.parseColor("#FFFFFF"), ContextCompat.getColor(context, R.color.surface))
    }

    @Test fun theLegacyColourAliasesAreGone() {
        // Lane L8 replaced every old colour name with its token and deleted the alias file.
        assertFalse(File(resDir(), "values/colors_legacy.xml").exists())
        val res = context.resources
        listOf("color_accent", "color_danger", "on_surface", "surface_card", "text_link", "dialog_action").forEach {
            assertEquals("old colour name $it", 0, res.getIdentifier(it, "color", context.packageName))
        }
    }

    @Test fun themeUsesPrimaryForTheMainActionAndWhiteForThePage() {
        val app = themed(R.style.Theme_OpenCyclone)
        fun c(id: Int) = ContextCompat.getColor(context, id)
        assertEquals(c(R.color.on_primary), colorAttr(app, com.google.android.material.R.attr.colorOnPrimary))
        assertEquals(c(R.color.btn_pale), colorAttr(app, com.google.android.material.R.attr.colorSecondaryContainer))
        assertEquals(c(R.color.primary), colorAttr(app, com.google.android.material.R.attr.colorOnSecondaryContainer))
        assertEquals(c(R.color.page), colorAttr(app, com.google.android.material.R.attr.colorSurface))
        assertEquals(c(R.color.ink), colorAttr(app, com.google.android.material.R.attr.colorOnSurface))
        assertEquals(c(R.color.ink_2), colorAttr(app, com.google.android.material.R.attr.colorOnSurfaceVariant))
        assertEquals(c(R.color.surface_2), colorAttr(app, com.google.android.material.R.attr.colorOutline))
        assertEquals(c(R.color.page), colorAttr(app, android.R.attr.colorBackground))
        assertEquals(c(R.color.primary), colorAttr(app, android.R.attr.colorControlActivated))
    }

    @Test fun buttonStylesUsePrimary() {
        fun textColor(style: Int): Int {
            val a = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.textColor))
            try { return a.getColor(0, 0) } finally { a.recycle() }
        }
        assertEquals(ContextCompat.getColor(context, R.color.on_primary), textColor(R.style.Widget_App_Button_Primary))
        assertEquals(ContextCompat.getColor(context, R.color.primary), textColor(R.style.Widget_App_Button_Pale))
        val res = resDir()
        assertTrue(File(res, "drawable/bg_btn_primary.xml").readText().let {
            it.contains("@color/primary\"") && it.contains("@color/primary_disabled") && it.contains("state_enabled=\"false\"")
        })
        assertTrue(File(res, "drawable/bg_btn_pale.xml").readText().contains("@color/btn_pale"))
        assertTrue(File(res, "drawable/bg_bubble_ink.xml").readText().contains("@color/primary\""))
    }

    /** WCAG 2 relative luminance and contrast ratio. */
    private fun luminance(color: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(android.graphics.Color.red(color)) + 0.7152 * ch(android.graphics.Color.green(color)) + 0.0722 * ch(android.graphics.Color.blue(color))
    }

    private fun contrast(fg: Int, bg: Int): Double {
        val a = luminance(ContextCompat.getColor(context, fg))
        val b = luminance(ContextCompat.getColor(context, bg))
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    @Test fun paletteMeetsContrast() {
        val checks = listOf(
            Triple(R.color.ink, R.color.page, 7.0),
            Triple(R.color.ink_2, R.color.page, 4.5),
            Triple(R.color.ink_3, R.color.page, 3.0),
            Triple(R.color.on_primary, R.color.primary, 4.5),
            Triple(R.color.primary, R.color.btn_pale, 4.5),
            Triple(R.color.ink, R.color.surface_2, 7.0),
        )
        checks.forEach { (fg, bg, min) ->
            val r = contrast(fg, bg)
            val name = "${context.resources.getResourceEntryName(fg)} on ${context.resources.getResourceEntryName(bg)}"
            assertTrue("$name is $r, needs $min", r >= min)
        }
    }

    @Test fun textAppearancesHaveTheirSizes() {
        val dm = context.resources.displayMetrics
        fun sizeSp(style: Int): Float {
            val a = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.textSize))
            try { return a.getDimension(0, 0f) / dm.scaledDensity } finally { a.recycle() }
        }
        assertEquals(40f, sizeSp(R.style.TextAppearance_App_Display), 0.01f)
        assertEquals(30f, sizeSp(R.style.TextAppearance_App_Greeting), 0.01f)
        assertEquals(28f, sizeSp(R.style.TextAppearance_App_Transcript), 0.01f)
        assertEquals(25f, sizeSp(R.style.TextAppearance_App_Transcript_Speaking), 0.01f)
        assertEquals(22f, sizeSp(R.style.TextAppearance_App_Heading), 0.01f)
        assertEquals(18f, sizeSp(R.style.TextAppearance_App_PhoneLine), 0.01f)
        assertEquals(17f, sizeSp(R.style.TextAppearance_App_Body), 0.01f)
        assertEquals(15f, sizeSp(R.style.TextAppearance_App_Status), 0.01f)
        assertEquals(15f, sizeSp(R.style.TextAppearance_App_Button), 0.01f)
        assertEquals(15f, sizeSp(R.style.TextAppearance_App_Label), 0.01f)
        assertEquals(13f, sizeSp(R.style.TextAppearance_App_Small), 0.01f)
    }

    @Test fun interFontFilesAreTrueType() {
        val fonts = File(resDir(), "font")
        listOf("inter_regular", "inter_medium", "inter_semibold", "inter_bold").forEach { name ->
            val f = File(fonts, "$name.ttf")
            assertTrue("$name exists", f.isFile && f.length() > 100_000)
            val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            assertTrue("$name is TrueType", head.contentEquals(byteArrayOf(0, 1, 0, 0)))
        }
    }
}
