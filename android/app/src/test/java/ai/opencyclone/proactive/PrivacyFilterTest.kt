package ai.opencyclone.proactive

import org.junit.Assert.*
import org.junit.Test

class PrivacyFilterTest {
    private fun incoming(text: String) = Incoming("app", "key", "chat", "", text, 0)
    @Test fun dropsCodesAndAuthenticationLinks() {
        listOf(
            "Your verification code is 482913", "OTP: 1234", "Passcode 12345678", "Verify with A1B2C3",
            "Your one-time password is 918273", "PIN: 9876", "2fa 384756", "Security code AB12CD78",
            "Tu código es 482913", "ओटीपी: 482913", "कोड 482913", "验证码：482913", "验证码482913",
            "Open https://example.com/reset/token", "Open https://example.com/verify?id=1",
            "Open https://example.com/signin/a", "Open https://example.com/login", "Open https://example.com/magic/a",
            "Code one two three four five six 123456", "123456 is your verification code"
        ).forEach { assertTrue(it, PrivacyFilter.shouldDrop(incoming(it), emptySet())) }
    }
    @Test fun keepsOrdinaryNotifications() {
        listOf(
            "Meeting moved to 4:30, room 482913", "Your order 482913 shipped", "Code review tomorrow",
            "The code is readable", "PIN the message", "Verification complete", "OTP expires shortly",
            "code 123", "code 123456789", "code abcdef", "code one two three four five six seven 123456",
            "https://example.com/news?next=login", "https://login.example.com/news"
        ).forEach { assertFalse(it, PrivacyFilter.shouldDrop(incoming(it), emptySet())) }
    }
    @Test fun blockedPackagesAndSensitiveTitlesAreDropped() {
        assertTrue(PrivacyFilter.shouldDrop(incoming("hello"), setOf("app")))
        assertTrue(PrivacyFilter.shouldDrop(incoming("").copy(title = "OTP 123456"), emptySet()))
    }
}
