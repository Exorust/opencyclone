package ai.opencyclon.action

import ai.opencyclon.accessibility.FocusedField
import ai.opencyclon.backend.PrivilegeBackend
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** F18.4 open_link and F18.3 typed-text check in the executor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenLinkExecutorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val backend = mockk<PrivilegeBackend>(relaxed = true).also {
        every { it.injectInputEvent(any(), any(), any()) } returns true
    }
    private var field: FocusedField? = null
    private var canCall = true
    private val blocked = setOf("com.android.settings")
    private val executor = ActionExecutor(app, 0, null, backend = backend, blacklistedPackages = blocked,
        focusedField = { field }, canCall = { canCall })

    private fun handler(intent: Intent, pkg: String) {
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Main"
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
        })
    }

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test fun `a link with an app opens there and nothing is held by the executor`() = runBlocking {
        handler(view("https://example.com"), "com.android.chrome")
        assertEquals(LinkCheck.Opens("com.android.chrome", "com.android.chrome"), executor.checkLink(Action.OpenLink("https://example.com")))
        val result = executor.execute(Action.OpenLink("https://example.com"), 1)
        assertTrue(result.detail, result.success)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com", started.dataString)
        assertEquals("com.android.chrome", started.`package`)
    }

    @Test fun `an sms link opens the composer filled in`() = runBlocking {
        handler(view("sms:+14155550100?body=Hello"), "com.google.android.apps.messaging")
        val result = executor.execute(Action.OpenLink("sms:+14155550100?body=Hello"), 1)
        assertTrue(result.success)
        assertEquals(Intent.ACTION_VIEW, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun `no app for the link`() {
        assertEquals(LinkCheck.Refused("No app on this phone opens that link."), executor.checkLink(Action.OpenLink("zzwalk://x")))
    }

    @Test fun `a link into a protected app is blocked`() {
        handler(view("https://settings.example"), "com.android.settings")
        assertEquals(LinkCheck.Blocked("com.android.settings"), executor.checkLink(Action.OpenLink("https://settings.example")))
    }

    @Test fun `file content and script links are refused`() {
        listOf("file:///sdcard/a.txt", "content://media/1", "javascript:alert(1)").forEach {
            assertEquals(it, LinkCheck.Refused(LinkCheck.REFUSED_SCHEME), executor.checkLink(Action.OpenLink(it)))
        }
        assertEquals(LinkCheck.Refused(LinkCheck.NOT_A_LINK), executor.checkLink(Action.OpenLink("not a link")))
    }

    @Test fun `a call without the permission is refused and does not crash`() = runBlocking {
        canCall = false
        val result = executor.execute(Action.OpenLink("tel:+14155550100", call = true), 1)
        assertFalse(result.success)
        assertEquals("I cannot place calls. Open the dialer instead.", result.detail)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `the real missing permission is refused before resolving a call`() {
        shadowOf(app).denyPermissions(android.Manifest.permission.CALL_PHONE)
        val realPermissionExecutor = ActionExecutor(app, backend = backend)
        assertEquals(LinkCheck.Refused("I cannot place calls. Open the dialer instead."),
            realPermissionExecutor.checkLink(Action.OpenLink("tel:+14155550100", call = true)))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `a call uses ACTION_CALL and only a tel link`() = runBlocking {
        handler(Intent(Intent.ACTION_CALL, Uri.parse("tel:+14155550100")), "com.google.android.dialer")
        assertEquals(LinkCheck.Refused(LinkCheck.CALL_NEEDS_TEL), executor.checkLink(Action.OpenLink("https://x", call = true)))
        val result = executor.execute(Action.OpenLink("tel:+14155550100", call = true), 1)
        assertTrue(result.detail, result.success)
        assertEquals(Intent.ACTION_CALL, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun `an intent link that calls is refused`() {
        assertEquals(LinkCheck.Refused(LinkCheck.CALL_THROUGH_TEL),
            executor.checkLink(Action.OpenLink("intent:tel:5550100#Intent;action=android.intent.action.CALL;end")))
    }

    @Test fun `typed text that arrives is done`() = runBlocking {
        field = FocusedField("héllo", false, false, android.text.InputType.TYPE_CLASS_TEXT)
        assertTrue(executor.execute(Action.TypeText("héllo"), 1).success)
    }

    @Test fun `typed text that does not arrive fails the step with the sentence`() = runBlocking {
        field = FocusedField("", false, true, android.text.InputType.TYPE_CLASS_TEXT)
        val result = executor.execute(Action.TypeText("héllo"), 1)
        assertFalse(result.success)
        assertEquals("The text did not arrive in the field.", result.detail)
    }

    @Test fun `typed text into a password field cannot be checked and counts as done`() = runBlocking {
        field = FocusedField("•••••", true, false, android.text.InputType.TYPE_CLASS_TEXT)
        assertTrue(executor.execute(Action.TypeText("héllo"), 1).success)
    }
}
