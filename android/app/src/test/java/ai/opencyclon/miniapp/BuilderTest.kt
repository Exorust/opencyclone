package ai.opencyclon.miniapp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuilderTest {
    private lateinit var f: StoreFixture
    private val prompts = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val progress = mutableListOf<String>()
    private val tested = mutableListOf<String>()

    @Before fun setUp() { f = StoreFixture() }
    @After fun tearDown() = f.close()

    /** A generated file that declares its accent; shadows the shared [ai.opencyclon.miniapp.page]. */
    private fun page(name: String, body: String = "Hola") =
        ai.opencyclon.miniapp.page(name, body).replace("<head>", "<head><meta name=\"accent\" content=\"#2F6FDE\">")

    /** What the builder stores: the file with the style block inserted. */
    private fun stored(name: String, body: String = "Hola") = BuiltAppStyle.apply(page(name, body))

    private fun plan(questions: List<String> = emptyList(), refuse: String? = null) =
        JSONObject().put("questions", JSONArray(questions)).put("refuse", refuse ?: JSONObject.NULL).toString()

    /** [replies] are the model's answers in order: the plan first, then each HTML file. */
    private fun builder(replies: List<String>, answers: List<String> = emptyList(), failTests: Int = 0): Builder {
        val modelReplies = ArrayDeque(replies)
        val ownerAnswers = ArrayDeque(answers)
        var failures = failTests
        return Builder(
            model = { prompt -> prompts += prompt; modelReplies.removeFirst() },
            ask = { question -> asked += question; ownerAnswers.removeFirst() },
            test = { html, _ -> tested += html; if (failures-- > 0) listOf("Uncaught TypeError round ${tested.size}") else emptyList() },
            store = f.store,
            progress = { progress += it }
        )
    }

    @Test fun asksQuestionsAndStopsOnJustBuildIt() = runBlocking {
        val b = builder(
            listOf(plan(listOf("Do you want streaks?", "Which level?", "Audio?")), page("Spanish")),
            answers = listOf("Streaks, please", "Just build it!")
        )
        val result = b.build("Build me an app to learn Spanish")
        assertEquals(listOf("Do you want streaks?", "Which level?"), asked)
        assertTrue(result is BuildResult.Ready)
        assertTrue(prompts[1].contains("Streaks, please"))
        assertTrue(!prompts[1].contains("Just build it"))
    }

    @Test fun justBuildItOnTheFirstQuestionSkipsTheRest() = runBlocking {
        val b = builder(listOf(plan(listOf("Streaks?", "Level?")), page("Spanish")), answers = listOf("just build it"))
        assertTrue(b.build("Build me an app to learn Spanish") is BuildResult.Ready)
        assertEquals(listOf("Streaks?"), asked)
    }

    @Test fun refusalStoresNothing() = runBlocking {
        val reason = "Built apps cannot read messages"
        val b = builder(listOf(plan(refuse = reason)))
        val result = b.build("Build me an app that reads all my text messages")
        assertEquals(BuildResult.Refused(reason), result)
        assertEquals(1, prompts.size)
        assertTrue(asked.isEmpty())
        assertTrue(tested.isEmpty())
        assertTrue(f.store.list().isEmpty())
        assertTrue(f.pins.isEmpty())
        assertTrue(reason in progress)
    }

    @Test fun passOnFirstRoundStoresPinsAndReportsReady() = runBlocking {
        val b = builder(listOf(plan(), "```html\n${page("Spanish")}\n```"))
        val result = b.build("Build me an app to learn Spanish") as BuildResult.Ready
        assertEquals("Spanish", result.name)
        assertEquals(1, result.version)
        assertEquals(stored("Spanish"), f.store.currentHtml(result.appId))
        assertEquals(listOf(result.appId to "Spanish"), f.pins)
        assertEquals(listOf("Building", "Testing", "Spanish is ready"), progress)
        assertTrue(prompts[1].contains("never", ignoreCase = true) && prompts[1].contains("key", ignoreCase = true))
    }

    @Test fun passOnThirdRepairRound() = runBlocking {
        val b = builder(listOf(plan(), page("Spanish", "a"), page("Spanish", "b"), page("Spanish", "c"), page("Spanish", "d")), failTests = 3)
        val result = b.build("Build me an app to learn Spanish") as BuildResult.Ready
        assertEquals(5, prompts.size)
        assertEquals(4, tested.size)
        assertTrue(prompts[4].contains("Uncaught TypeError round 3"))
        assertEquals(stored("Spanish", "d"), f.store.currentHtml(result.appId))
        assertEquals("Spanish is ready", progress.last())
    }

    @Test fun staticCheckProblemsAreRepairedToo() = runBlocking {
        val b = builder(listOf(plan(), "<p>no html</p>", page("Spanish")))
        assertTrue(b.build("Build me an app") is BuildResult.Ready)
        assertEquals(1, tested.size)
        assertTrue(prompts[2].contains(HtmlChecks.NO_HTML))
    }

    @Test fun failureAfterThreeRepairRoundsStoresNothing() = runBlocking {
        val b = builder(listOf(plan()) + List(4) { page("Spanish", "v$it") }, failTests = 99)
        val result = b.build("Build me an app to learn Spanish")
        assertEquals(BuildResult.Failed("Uncaught TypeError round 4"), result)
        assertEquals(4, tested.size)
        assertEquals(5, prompts.size)
        assertTrue(f.store.list().isEmpty())
        assertTrue(f.pins.isEmpty())
        assertTrue(progress.last().startsWith("Could not build"))
        assertTrue(progress.last().contains("Uncaught TypeError round 4"))
    }

    @Test fun changeKeepsOldVersionOnFailure() = runBlocking {
        val (id, _) = f.store.saveNewVersion(null, "Spanish", page("Spanish", "streak"))
        val b = builder(listOf(plan()) + List(4) { page("Spanish", "broken$it") }, failTests = 99)
        val result = b.change(id, "Add Mandarin and make the streaks weekly")
        assertTrue(result is BuildResult.Failed)
        assertEquals(1, f.db.miniAppDao().get(id)!!.currentVersion)
        assertEquals(page("Spanish", "streak"), f.store.currentHtml(id))
        assertTrue(progress.last().startsWith("Could not update"))
        assertTrue(f.pins.isEmpty())
    }

    @Test fun changeIncrementsVersionOnSuccess() = runBlocking {
        val (id, _) = f.store.saveNewVersion(null, "Spanish", page("Spanish", "<script>localStorage.getItem('streak')</script>"))
        val b = builder(listOf(plan(), page("Spanish", "Mandarin")))
        val result = b.change(id, "Add Mandarin and make the streaks weekly") as BuildResult.Ready
        assertEquals(id, result.appId)
        assertEquals(2, result.version)
        assertEquals(stored("Spanish", "Mandarin"), f.store.currentHtml(id))
        assertEquals("Spanish is updated", progress.last())
        assertTrue(f.pins.isEmpty())
        // The model sees the current file and the instruction to keep its storage keys.
        assertTrue(prompts[1].contains("localStorage.getItem('streak')"))
        assertTrue(prompts[1].contains("storage key", ignoreCase = true))
    }

    @Test fun styleBlockIsInsertedOnceAndNotSentBackToTheModel() = runBlocking {
        val (id, _) = f.store.saveNewVersion(null, "Spanish", stored("Spanish", "streak"))
        // The model echoes the current file, style block included, and adds a second copy of the block.
        val echoed = BuiltAppStyle.apply(stored("Spanish", "Mandarin"))
        val b = builder(listOf(plan(), echoed.replace("</head>", "<style id=\"built-app-style\">x{}</style></head>")))
        assertTrue(b.change(id, "Add Mandarin") is BuildResult.Ready)
        val html = f.store.currentHtml(id)!!
        assertEquals(1, Regex("id=\"built-app-style\"").findAll(html).count())
        assertEquals(stored("Spanish", "Mandarin"), html)
        assertTrue(html.contains("--accent:#2F6FDE"))
        assertTrue(!prompts[1].contains(BuiltAppStyle.STYLE_ID))
    }

    @Test fun promptNamesTheStyleClassesAndRules() = runBlocking {
        builder(listOf(plan(), page("Spanish"))).build("Build me an app")
        listOf("top", "tabs", "display", "progress", "accent-card", "chip", "section", "count", "row", "glyph", "chev", "link")
            .forEach { assertTrue(it, prompts[1].contains(it)) }
        assertTrue(prompts[1].contains("<meta name=\"accent\" content=\"#RRGGBB\">"))
        assertTrue(prompts[1].contains("At most 4 rows"))
        assertTrue(prompts[1].contains("No emoji, no dark mode, no external addresses"))
        assertTrue(!prompts[1].contains("@font-face"))
    }

    @Test fun missingAccentIsRepairedThenAcceptedAfterTheLastRound() = runBlocking {
        val bare = List(4) { ai.opencyclon.miniapp.page("Spanish", "v$it") }
        val result = builder(listOf(plan()) + bare).build("Build me an app") as BuildResult.Ready
        assertEquals(5, prompts.size)
        assertTrue(prompts[2].contains(HtmlChecks.NO_ACCENT.substringBefore("<meta")))
        assertEquals(4, tested.size)
        val html = f.store.currentHtml(result.appId)!!
        assertTrue(html.contains("--accent:${BuiltAppStyle.DEFAULT_ACCENT}"))
    }

    @Test fun changeOfUnknownAppFails() = runBlocking {
        val result = builder(emptyList()).change(99, "Add Mandarin")
        assertEquals(BuildResult.Failed(MiniAppStore.MISSING_APP), result)
        assertTrue(prompts.isEmpty())
    }
}
