package ai.opencyclone.proactive

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun parsesOwnerRulesWithoutModel() {
        val apps = mapOf("NordVPN" to "vpn.app")
        assertEquals(HideApp("vpn.app", "hide nordvpn notifications"), parseRule("hide nordvpn notifications", apps))
        assertEquals(StopHidingApp("vpn.app"), parseRule("Stop hiding NordVPN notifications", apps))
        assertEquals(UnknownApp("Zzqx"), parseRule("Hide Zzqx notifications", apps))
        assertEquals(TextRule("Hide promotions from shopping apps"), parseRule("Hide promotions from shopping apps", apps))
    }
    @Test fun packageMatchesExactlyAndRemovalUsesResolvedPackage() = runBlocking {
        val dao = mockk<RuleDao>(relaxed = true)
        val rule = RuleEntity(kind = "package", packageName = "vpn.app", text = "hide VPN notifications", createdAt = 0)
        coEvery { dao.all() } returns listOf(rule, RuleEntity(kind = "text", text = "hide sales", createdAt = 0))
        val cancelled = mutableListOf<String>()
        val rules = Rules(dao) { cancelled.add(it) }
        assertNull(rules.hide(Incoming("vpn.app.other", "a", "c", "", "", 0)))
        assertEquals(rule, rules.hide(Incoming("vpn.app", "b", "c", "", "", 0)))
        assertEquals(listOf("b"), cancelled)
        assertEquals(listOf("hide sales"), rules.textRules())
        rules.apply(StopHidingApp("vpn.app"), 0)
        coVerify { dao.removePackage("vpn.app") }
    }
}
