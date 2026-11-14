package ai.opencyclone.tasks

import ai.opencyclone.proactive.HideApp
import ai.opencyclone.proactive.NotificationLogEntity
import ai.opencyclone.proactive.NotificationSummary
import ai.opencyclone.proactive.Rules
import ai.opencyclone.proactive.StopHidingApp
import ai.opencyclone.proactive.Summary
import ai.opencyclone.proactive.TextRule
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RequestRouterTest {
    private val coordinator = mockk<TaskCoordinator>(relaxed = true)
    private val rules = mockk<Rules>(relaxed = true)
    private val summary = mockk<Summary>()
    private val said = mutableListOf<String>()
    private val time = 1_700_000_000_000L
    private val apps = mapOf("NordVPN" to "com.nordvpn.android", "WhatsApp" to "com.whatsapp")

    private fun router(handlers: List<suspend (String) -> Boolean> = emptyList(), installed: Map<String, String> = apps) =
        RequestRouter(handlers, coordinator, rules, summary, { installed }, { said.add(it) }, { time },
            inactiveWindowSentence = "I am not handling your notifications now.")

    @Test fun `wake name and one hour window`() = runBlocking {
        router().route("Cyclone, handle my notifications for the next hour")
        coVerify { coordinator.startWindow(60) }
        assertEquals(listOf("Handling notifications until ${RequestRouter.formatTime(time + 3_600_000L)}"), said)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `thirty minute window`() = runBlocking {
        router().route("handle my notifications for the next 30 minutes")
        coVerify { coordinator.startWindow(30) }
        assertEquals(listOf("Handling notifications until ${RequestRouter.formatTime(time + 30 * 60_000L)}"), said)
    }

    @Test fun `please and plural hours`() = runBlocking {
        router().route("Please handle my notifications for the next 2 hours.")
        coVerify { coordinator.startWindow(120) }
    }

    @Test fun `stop window says the three counts`() = runBlocking {
        coEvery { coordinator.stopWindow() } returns WindowSummary(4, 2, 1)
        router().route("Stop handling notifications")
        coVerify { coordinator.stopWindow() }
        assertEquals(1, said.size)
        assertEquals("Stopped handling notifications.\nHandled 4.\nHidden 2.\nNeeds you 1.", said[0])
    }

    @Test fun `stop without an active window answers before widget handlers`() = runBlocking {
        coEvery { coordinator.stopWindow() } returns null
        router(handlers = listOf({ error("A local stop must bypass pending builders") }))
            .route("stop handling my notifications")
        assertEquals(listOf("I am not handling your notifications now."), said)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `what did I miss lists three groups`() = runBlocking {
        fun entry(title: String, pkg: String, result: String, rule: String? = null) =
            NotificationLogEntity(packageName = pkg, title = title, result = result, ruleText = rule, at = time)
        coEvery { summary.since(any()) } returns NotificationSummary(
            listOf(entry("Alex Test", "com.whatsapp", "proposed")),
            listOf(entry("News", "com.news", "summarize")),
            listOf(entry("VPN on", "com.nordvpn.android", "hide", "Hide NordVPN notifications"),
                entry("VPN off", "com.nordvpn.android", "hide", "Hide NordVPN notifications")))
        router().route("What did I miss?")
        val text = said.single()
        assertTrue(text, text.contains("Needs a reply (1)") && text.contains("Alex Test (WhatsApp)"))
        assertTrue(text, text.contains("For information (1)") && text.contains("News"))
        assertTrue(text, text.contains("Hidden (2)") && text.contains("your rule: hide NordVPN notifications"))
    }

    @Test fun `hide app with a matching app saves the rule`() = runBlocking {
        router().route("Hide NordVPN notifications")
        coVerify { rules.apply(HideApp("com.nordvpn.android", "Hide NordVPN notifications"), time) }
        assertEquals(listOf("Rule saved: hide NordVPN notifications"), said)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `hide app without a matching app saves nothing`() = runBlocking {
        router(installed = mapOf("WhatsApp" to "com.whatsapp")).route("Hide NordVPN notifications")
        coVerify(exactly = 0) { rules.apply(any(), any()) }
        assertEquals(listOf("No app named NordVPN"), said)
    }

    @Test fun `stop hiding removes the rule`() = runBlocking {
        router().route("Stop hiding NordVPN notifications")
        coVerify { rules.apply(StopHidingApp("com.nordvpn.android"), time) }
        assertEquals(listOf("Rule saved: stop hiding NordVPN notifications"), said)
    }

    @Test fun `other hide sentence becomes a text rule`() = runBlocking {
        router().route("Hide promotions from shopping apps")
        coVerify { rules.apply(TextRule("Hide promotions from shopping apps"), time) }
        assertEquals(listOf("Rule saved: hide promotions from shopping apps"), said)
    }

    @Test fun `handler that returns true stops routing`() = runBlocking {
        val seen = mutableListOf<String>()
        val second: suspend (String) -> Boolean = { seen.add("second:$it"); true }
        router(listOf<suspend (String) -> Boolean>({ seen.add("first:$it"); true }, second)).route("Hide NordVPN notifications")
        assertEquals(listOf("first:Hide NordVPN notifications"), seen)
        assertTrue(said.isEmpty())
        coVerify(exactly = 0) { rules.apply(any(), any()) }
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `handler that returns false passes on`() = runBlocking {
        router(listOf<suspend (String) -> Boolean>({ false })).route("Send hello to Alex")
        coVerify { coordinator.submitUserRequest("Send hello to Alex") }
    }

    @Test fun `plain sentence goes to the coordinator`() = runBlocking {
        router().route("Send 'gate-test-1' to Alex")
        coVerify(exactly = 1) { coordinator.submitUserRequest("Send 'gate-test-1' to Alex") }
        coVerify(exactly = 0) { coordinator.startWindow(any()) }
        assertTrue(said.isEmpty())
    }

    @Test fun `summary time survives a restart`() = runBlocking {
        coEvery { summary.since(any()) } returns NotificationSummary(emptyList(), emptyList(), emptyList())
        val stored = LastSummaryStore.InMemory() // stands in for the preferences file
        RequestRouter(emptyList(), coordinator, rules, summary, { apps }, { said.add(it) }, { time },
            lastSummary = stored).route("What did I miss?")
        // A new router, as after a process restart, reads the stored time.
        RequestRouter(emptyList(), coordinator, rules, summary, { apps }, { said.add(it) }, { time + 5_000 },
            lastSummary = stored).route("What did I miss?")
        coVerifyOrder {
            summary.since(0L)
            summary.since(time)
        }
        assertEquals(time + 5_000, stored.get())
    }

    @Test fun `connect my service at address opens the integrations screen`() = runBlocking {
        val opened = mutableListOf<Pair<String, String>>()
        RequestRouter(emptyList(), coordinator, rules, summary, { apps }, { said.add(it) }, { time },
            onConnectService = { name, address -> opened += name to address })
            .route("Connect my notes service at https://mcp.example.com/notes")
        assertEquals(listOf("notes" to "https://mcp.example.com/notes"), opened)
        assertEquals(listOf("Enter the token for notes on the Integrations screen"), said)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `connect without service word`() = runBlocking {
        val opened = mutableListOf<Pair<String, String>>()
        RequestRouter(emptyList(), coordinator, rules, summary, { apps }, { said.add(it) }, { time },
            onConnectService = { name, address -> opened += name to address })
            .route("connect my calendar at http://192.168.1.5:3000/mcp")
        assertEquals(listOf("calendar" to "http://192.168.1.5:3000/mcp"), opened)
    }

    @Test fun `open our own screens never starts a task`() = runBlocking {
        val opened = mutableListOf<OwnScreen>()
        val handler: suspend (String) -> Boolean = { error("a handler must not see $it") }
        val r = RequestRouter(listOf(handler), coordinator, rules, summary, { apps }, { said.add(it) }, { time },
            onOpenScreen = { opened.add(it) })
        r.route("open settings")
        r.route("Please open History.")
        r.route("OPEN RULES")
        r.route("please, open privacy")
        r.route("Open integrations")
        r.route("open set up")
        assertEquals(listOf(OwnScreen.SETTINGS, OwnScreen.HISTORY, OwnScreen.RULES, OwnScreen.PRIVACY,
            OwnScreen.INTEGRATIONS, OwnScreen.SET_UP), opened)
        assertEquals(listOf("Opened Settings.", "Opened History.", "Opened Rules.", "Opened Privacy.",
            "Opened Integrations.", "Opened Set up."), said)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `other open sentences still go to the agent`() = runBlocking {
        val opened = mutableListOf<OwnScreen>()
        RequestRouter(emptyList(), coordinator, rules, summary, { apps }, { said.add(it) }, { time },
            onOpenScreen = { opened.add(it) }).route("open settings in WhatsApp")
        assertTrue(opened.isEmpty())
        coVerify { coordinator.submitUserRequest("open settings in WhatsApp") }
    }

    @Test fun `the wake name is removed before every handler and the agent`() = runBlocking {
        val seen = mutableListOf<String>()
        val r = router(listOf<suspend (String) -> Boolean>({ seen.add(it); false }))
        r.route("Open Cyclone, build me an app to learn Spanish")
        r.route("hey cyclone please send hello to Alex")
        r.route("Cyclone: Add a note titled pm-test")
        r.route("Phone Alex")
        assertEquals(listOf("build me an app to learn Spanish", "send hello to Alex", "Add a note titled pm-test", "Phone Alex"), seen)
        coVerify { coordinator.submitUserRequest("build me an app to learn Spanish") }
        coVerify { coordinator.submitUserRequest("send hello to Alex") }
        coVerify { coordinator.submitUserRequest("Add a note titled pm-test") }
        coVerify { coordinator.submitUserRequest("Phone Alex") }
    }

    @Test fun `model phrases are answered before the own screens and never reach a handler`() = runBlocking {
        val phrases = mutableListOf<ai.opencyclone.config.ModelPhrase>()
        val handler: suspend (String) -> Boolean = { error("a handler must not see $it") }
        val r = RequestRouter(listOf(handler), coordinator, rules, summary, { apps }, { said.add(it) }, { time },
            onModelPhrase = { phrase -> phrases += phrase; if (phrase is ai.opencyclone.config.ModelPhrase.Use) "The next task uses x." else "I use x." })
        r.route("Use model GPT 5 point 6 Terra.")
        r.route("Cyclone, which model?")
        assertEquals(listOf(ai.opencyclone.config.ModelPhrase.Use("GPT 5 point 6 Terra"), ai.opencyclone.config.ModelPhrase.Which), phrases)
        assertEquals(listOf("The next task uses x.", "I use x."), said)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

    @Test fun `the type input and the router share one list of screen names`() {
        assertEquals(OwnScreen.entries.map { it.phrase }.toSet(), ai.opencyclone.ui.home.AppSearch.RESERVED)
    }
    @Test fun `suggestion phrases stay local and request confirmation before enabling`() = runBlocking {
        val changes = mutableListOf<Boolean>()
        val r = RequestRouter(listOf({ error("must not reach a model handler") }), coordinator, rules, summary,
            { apps }, { said.add(it) }, onSuggestions = { changes.add(it) })
        r.route("Suggest tasks from my notifications.")
        r.route("Please turn on suggestions")
        r.route("Stop suggesting tasks")
        r.route("TURN OFF SUGGESTIONS")
        assertEquals(listOf(true, true, false, false), changes)
        coVerify(exactly = 0) { coordinator.submitUserRequest(any()) }
    }

}
