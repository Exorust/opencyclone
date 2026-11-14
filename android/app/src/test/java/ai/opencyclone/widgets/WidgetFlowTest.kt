package ai.opencyclone.widgets

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Flows F16.2, F16.3, F16.5, F16.6 and F16.7 with a scripted model and a scripted load test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetFlowTest {
    private var fixture: WidgetFixture? = null
    private val sentence = "Make a widget that shows my Lingo streak and how many tasks I ran today"

    @After fun tearDown() { fixture?.close() }

    private fun run(block: suspend kotlinx.coroutines.CoroutineScope.(WidgetFixture) -> Unit) = runBlocking {
        val f = WidgetFixture(this).also { fixture = it }
        block(f)
    }

    @Test fun pendingDraftConsentDoesNotSwallowANewOwnerTask() = run { f ->
        f.replies += listOf(plan("Clock", "small", emptyList()), widgetHtml(sources = "clock"))
        f.flow.handle("Make a widget with the time")
        f.flow.pageOpen = true
        f.replies += plan("Clock", "small", listOf("today"))
        f.flow.changeDraft("Add my running task")
        f.until { f.flow.page.value.confirm != null }
        val coordinator = io.mockk.mockk<ai.opencyclone.tasks.TaskCoordinator>(relaxed = true)
        val router = ai.opencyclone.tasks.RequestRouter(listOf(f.flow::handle), coordinator,
            io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), { emptyMap() }, f.thread::say)
        val request = "Open Gate Test, open the chat, and write the draft text Running late without sending it"
        router.route(request)
        io.mockk.coVerify(exactly = 1) { coordinator.submitUserRequest(request) }
        f.flow.discardDraft()
    }

    @Test fun anotherWidgetRequestWithADraftGetsAnAnswerWithoutAModelCall() = run { f ->
        f.replies += listOf(plan("Clock", "small", emptyList()), widgetHtml(sources = "clock"))
        f.flow.handle("Make a widget with the time")
        val calls = f.prompts.size
        assertTrue(f.flow.handle(sentence))
        assertEquals(listOf("Save or discard the widget draft first."), f.thread.said)
        assertEquals(calls, f.prompts.size)
    }

    @Test fun discardingPendingConsentClosesTheQuestionAndAllowsTheNextBuild() = run { f ->
        f.replies += plan()
        val first = launch { f.flow.handle(sentence) }
        f.until { f.thread.confirms().isNotEmpty() }
        f.flow.discardDraft()
        first.join()
        assertEquals("REJECTED", f.thread.confirms().single().state)
        assertNull(f.flow.page.value.confirm)
        f.replies += listOf(plan("Clock", "small", emptyList()), widgetHtml(sources = "clock"))
        assertTrue(f.flow.handle("Make a widget with the time"))
        assertTrue(f.flow.page.value.draft != null)
    }

    @Test fun spokenWidgetConsentAcknowledgesTheAnswer() = run { f ->
        f.replies += listOf(plan(), widgetHtml())
        val job = launch { f.flow.handle(sentence) }
        f.until { f.thread.confirms().isNotEmpty() }
        assertTrue(f.flow.handle("Yes."))
        assertEquals(listOf("I will build the widget with those sources."), f.thread.said)
        job.join()
        assertEquals("ANSWERED", f.thread.confirms().single().state)
        assertTrue(f.flow.page.value.draft != null)
    }

    @Test fun buildPromptUsesCardDimensionsAndLineLimitsForEverySize() = run { f ->
        for (size in WidgetSize.entries) {
            f.replies += listOf(plan(size = size.id), widgetHtml())
            val job = launch { f.flow.handle(sentence) }
            f.until { f.thread.confirms().any { it.state == "OPEN" } }
            f.flow.onConfirm(f.thread.confirms().last().id, true)
            job.join()
            val prompt = f.prompts.last()
            assertTrue(prompt.contains(f.store.sizeNote(size)))
            val width = WidgetDimensions.widthPx(f.context.resources, size) / f.context.resources.displayMetrics.density
            assertTrue(prompt.contains("${width.toInt()} by ${size.heightDp} CSS pixels"))
            assertTrue(prompt.contains("at most ${if (size == WidgetSize.TALL) 10 else 6} lines of text"))
            assertTrue(prompt.contains("Write labels in sentence case, never in capital letters. Use at most one large number."))
            f.flow.discardDraft()
        }
    }

    @Test fun overflowNumbersReachTheRepairPrompt() = run { f ->
        val problem = WidgetTester.fitProblems(246, 380, 150, 380).single()
        f.testResults += listOf(problem)
        f.replies += listOf(plan(), widgetHtml(), widgetHtml())
        val job = launch { f.flow.handle(sentence) }
        f.until { f.thread.confirms().isNotEmpty() }
        f.flow.onConfirm(f.thread.confirms().single().id, true)
        job.join()
        assertTrue(f.prompts.last().contains(problem))
        assertEquals(2, f.tested.size)
        assertTrue(f.flow.page.value.draft != null)
    }

    @Test fun uppercaseLabelsGoThroughRepairBeforeLoadTest() = run { f ->
        f.replies += listOf(plan(), widgetHtml(body = "<p style='TEXT-TRANSFORM : uppercase'>Tasks</p>"), widgetHtml())
        val job = launch { f.flow.handle(sentence) }
        f.until { f.thread.confirms().isNotEmpty() }
        f.flow.onConfirm(f.thread.confirms().single().id, true)
        job.join()
        assertTrue(f.prompts.last().contains(WidgetHtml.CAPITAL_LABELS))
        assertEquals(1, f.tested.size)
        assertTrue(f.flow.page.value.draft != null)
    }

    @Test fun confirmBuildDraftChangeAndSave() = run { f ->
        f.replies += listOf(plan(), widgetHtml())
        val job = launch { assertTrue(f.flow.handle(sentence)) }
        f.until { f.thread.confirms().isNotEmpty() }
        val question = f.thread.confirms().single()
        assertEquals("This widget will read: your app Lingo, your task history. OK?", question.text)
        assertEquals("Build it", question.actionWord)
        assertEquals(1, f.prompts.size) // only the plan before the owner says yes
        assertTrue(f.thread.progressTexts.isEmpty())
        f.flow.onConfirm(question.id, true)
        job.join()

        assertEquals("ANSWERED", question.state)
        assertEquals(listOf(WidgetFlow.BUILDING), f.thread.progressTexts)
        val card = f.thread.rows.single { it.kind == "RESULT" }
        assertEquals(WidgetFlow.DRAFT_READY, card.text)
        assertEquals(WidgetFlow.DRAFT_CARD, card.actionWord)
        val draft = f.flow.page.value.draft!!
        assertEquals("Lingo streak", draft.name)
        assertEquals(listOf("app:3", "history"), draft.sources)
        assertTrue(draft.file.isFile)
        assertEquals(0, f.store.count())
        // The load test got sample data for each allowed source and the clock.
        assertEquals(setOf("app:3", "history", "clock"), f.tested.single().second.keys)

        // A tap on the card opens the draft page.
        f.flow.onConfirm(card.id, true)
        assertEquals(1, f.opened)

        // A change on the draft page: same sources, no question, a new draft file, the old one deleted.
        f.flow.pageOpen = true
        f.replies += listOf(plan(), widgetHtml(body = "<div class=\"circles\" id=\"s\"><i></i></div>"))
        val firstFile = draft.file
        val version = f.flow.page.value.version
        f.flow.changeDraft("Show the streak as a row of seven circles").join()
        val changed = f.flow.page.value.draft!!
        assertTrue(f.flow.page.value.version > version)
        assertTrue(changed.file.readText().contains("circles"))
        assertFalse(firstFile.exists())
        assertEquals(1, f.thread.confirms().size)
        assertEquals(0, f.store.count())

        val id = f.flow.saveDraft()!!
        assertNull(f.flow.page.value.draft)
        val saved = f.store.get(id)!!
        assertEquals("Lingo streak", saved.name)
        assertEquals(listOf("app:3", "history"), saved.sourceList)
        assertTrue(f.store.file(saved).readText().contains("circles"))
        assertEquals(0, f.draftsDir.listFiles()?.size ?: 0)
    }

    @Test fun notNowBuildsNothing() = run { f ->
        f.replies += listOf(plan())
        val job = launch { f.flow.handle(sentence) }
        f.until { f.thread.confirms().isNotEmpty() }
        f.flow.onConfirm(f.thread.confirms().single().id, false)
        job.join()
        assertEquals("REJECTED", f.thread.confirms().single().state)
        assertEquals(listOf(WidgetFlow.NOT_BUILT), f.thread.said)
        assertEquals(1, f.prompts.size)
        assertTrue(f.thread.rows.none { it.kind == "PROGRESS" || it.kind == "RESULT" })
        assertNull(f.flow.page.value.draft)
    }

    @Test fun clockOnlyNeedsNoQuestion() = run { f ->
        f.replies += listOf(plan("Clock", "small", emptyList()), widgetHtml(sources = "clock"))
        assertTrue(f.flow.handle("Make a widget with the date"))
        assertTrue(f.thread.confirms().isEmpty())
        assertEquals(emptyList<String>(), f.flow.page.value.draft!!.sources)
    }

    @Test fun unknownSourcesAreDropped() = run { f ->
        f.replies += listOf(plan("X", "small", listOf("app:99", "contacts", "history")), widgetHtml(sources = "history"))
        val job = launch { f.flow.handle("Make a widget with my history") }
        f.until { f.thread.confirms().isNotEmpty() }
        assertEquals("This widget will read: your task history. OK?", f.thread.confirms().single().text)
        f.flow.onConfirm(f.thread.confirms().single().id, true)
        job.join()
        assertEquals(listOf("history"), f.flow.page.value.draft!!.sources)
    }

    @Test fun threeFailedRepairsGiveOneSentence() = run { f ->
        f.replies += listOf(plan("Clock", "small", emptyList()))
        repeat(4) { f.replies += "<html><body>broken</body></html>" }
        repeat(4) { f.testResults += listOf("Uncaught TypeError") }
        assertTrue(f.flow.handle("Make a widget with the time"))
        assertEquals(5, f.prompts.size) // the plan, the file, three repairs
        val row = f.thread.rows.single()
        assertEquals("SENTENCE", row.kind)
        assertEquals("I could not build that widget. I tried 3 times.", row.text)
        assertNull(f.flow.page.value.draft)
        assertEquals(0, f.draftsDir.listFiles()?.size ?: 0)
    }

    @Test fun extraDeclaredSourcesAreRemovedAndReported() = run { f ->
        f.replies += listOf(plan(), widgetHtml(sources = "app:3,history,today,web:example.com"))
        val job = launch { f.flow.handle(sentence) }
        f.until { f.thread.confirms().isNotEmpty() }
        f.flow.onConfirm(f.thread.confirms().single().id, true)
        job.join()
        val draft = f.flow.page.value.draft!!
        assertEquals(listOf("app:3", "history"), WidgetHtml.declaredSources(draft.file.readText()))
        assertEquals(listOf("app:3", "history"), draft.sources)
        assertEquals(listOf("${WidgetFlow.ONLY_READS}your app Lingo, your task history."), f.thread.said)
        assertTrue(f.thread.rows.any { it.kind == "RESULT" }) // not a failure
    }

    @Test fun aNinthWidgetIsRefusedBeforeAnyModelCall() = run { f ->
        repeat(8) { f.saved("W$it") }
        assertTrue(f.flow.handle("Make a widget with my history"))
        assertEquals(listOf("You have 8 widgets. Remove one first."), f.thread.said)
        assertTrue(f.prompts.isEmpty())
    }

    @Test fun changingASavedWidgetKeepsItsPlaceUntilSaved() = run { f ->
        val first = f.saved("Lingo streak")
        val second = f.saved("Weather")
        f.replies += listOf(plan("Lingo streak", "wide"), widgetHtml(body = "<div class=\"label\">date</div><div id=\"s\">-</div>"))
        assertTrue(f.flow.handle("Make the streak widget wide and add the date"))
        assertTrue(f.thread.confirms().isEmpty()) // no new source
        assertEquals(listOf(WidgetFlow.CHANGING), f.thread.progressTexts)
        val draft = f.flow.page.value.draft!!
        assertEquals(first, draft.widgetId)
        assertEquals(1, f.store.get(first)!!.version) // unchanged until Save
        val oldFile = f.store.file(f.store.get(first)!!)
        assertEquals(first, f.flow.saveDraft())
        val changed = f.store.get(first)!!
        assertEquals(2, changed.version)
        assertEquals(WidgetSize.WIDE, changed.widgetSize)
        assertEquals(listOf(first, second), f.store.list().map { it.id })
        assertFalse(oldFile.exists())
        assertTrue(f.store.file(changed).isFile)
    }

    @Test fun aChangeOnTheDraftPageThatNeedsANewSourceAsksThere() = run { f ->
        f.replies += listOf(plan("Clock", "small", emptyList()), widgetHtml(sources = "clock"))
        f.flow.handle("Make a widget with the time")
        f.flow.pageOpen = true
        f.replies += listOf(plan("Clock", "small", listOf("today")), widgetHtml(sources = "today"))
        val job = f.flow.changeDraft("Add my running task")
        f.until { f.flow.page.value.confirm != null }
        assertEquals("This widget will read: today's items and the running task. OK?", f.flow.page.value.confirm)
        assertTrue(f.thread.confirms().isEmpty())
        f.flow.answerOnPage(true)
        job.join()
        assertNull(f.flow.page.value.confirm)
        assertEquals(listOf("today"), f.flow.page.value.draft!!.sources)
    }

    @Test fun discardDeletesTheDraftFile() = run { f ->
        f.replies += listOf(plan("Clock", "small", emptyList()), widgetHtml(sources = "clock"))
        f.flow.handle("Make a widget with the time")
        val file: File = f.flow.page.value.draft!!.file
        assertTrue(file.exists())
        f.flow.discardDraft()
        assertFalse(file.exists())
        assertNull(f.flow.page.value.draft)
        assertEquals(0, f.store.count())
        f.flow.onConfirm(f.thread.rows.single { it.kind == "RESULT" }.id, true)
        assertEquals(0, f.opened)
        assertEquals(listOf(WidgetFlow.DRAFT_GONE), f.thread.said)
    }

    @Test fun openSavedMakesADraftOfTheSavedVersion() = run { f ->
        val id = f.saved("Lingo streak")
        assertTrue(f.flow.openSaved(id))
        val draft = f.flow.page.value.draft!!
        assertEquals(id, draft.widgetId)
        assertEquals(1, f.opened)
        assertEquals(f.store.file(f.store.get(id)!!).readText(), draft.file.readText())
    }

    @Test fun sentencesThatAreNotAboutWidgetsPassThrough() = run { f ->
        assertFalse(f.flow.handle("Build me an app to learn Spanish"))
        assertFalse(f.flow.handle("Tell Alex I'm running late"))
        assertTrue(f.prompts.isEmpty())
    }
}
