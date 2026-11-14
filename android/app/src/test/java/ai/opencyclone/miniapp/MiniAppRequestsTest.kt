package ai.opencyclone.miniapp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniAppRequestsTest {
    private lateinit var f: StoreFixture
    private val builder = mockk<Builder>()
    private val goals = mutableListOf<String>()
    private val said = mutableListOf<String>()

    @Before fun setUp() {
        f = StoreFixture()
        coEvery { builder.build(any()) } returns BuildResult.Ready(1, "x", 1)
        coEvery { builder.change(any(), any()) } returns BuildResult.Ready(1, "x", 2)
    }
    @After fun tearDown() = f.close()

    private fun handle(sentence: String) = runBlocking {
        MiniAppRequests.tryHandle(sentence, builder, f.store, { goals += it }, { said += it })
    }

    private fun app(name: String) = runBlocking { f.store.saveNewVersion(null, name, page(name)).first }

    private fun ageApps() { f.time += MiniAppStore.RECENT_MS + 1 }

    @Test fun buildPatternsStartABuild() {
        assertTrue(handle("Build me an app to learn Spanish"))
        assertTrue(handle("build an app that shows the weather"))
        assertTrue(handle("Hey Cyclone, make me an app for my workouts"))
        coVerify { builder.build("Build me an app to learn Spanish") }
        coVerify { builder.build("build an app that shows the weather") }
        coVerify { builder.build("make me an app for my workouts") }
    }

    @Test fun shareNamedAppStartsAgentTaskWithReadableCopy() {
        val id = app("Spanish")
        ageApps()
        assertTrue(handle("Share the Spanish app with Adam"))
        val path = f.context.getExternalFilesDir(null)!!.absolutePath + "/miniapps/$id/Spanish.html"
        assertEquals(listOf("Send the file $path to Adam as an attachment"), goals)
        assertTrue(java.io.File(path).isFile)
    }

    @Test fun shareItUsesTheOneRecentApp() {
        app("Weather"); ageApps()
        val id = app("Spanish")
        assertTrue(handle("Can you share it with Adam?"))
        assertEquals(1, goals.size)
        assertTrue(goals.single().contains("/miniapps/$id/Spanish.html to Adam as an attachment"))
    }

    @Test fun shareItWithTwoRecentAppsAsksWhich() {
        app("Spanish"); app("Weather")
        assertTrue(handle("can you share it with Adam"))
        assertTrue(goals.isEmpty())
        assertEquals(listOf("Which app do you mean: Spanish, Weather?"), said)
    }

    @Test fun shareUnknownAppSaysSo() {
        app("Spanish")
        assertTrue(handle("Share the Chess app with Adam"))
        assertTrue(goals.isEmpty())
        assertEquals(listOf("No built app named Chess"), said)
    }

    @Test fun sentenceNamingOneAppChangesIt() {
        val id = app("Spanish"); app("Weather"); ageApps()
        assertTrue(handle("In the Spanish app, add a vocabulary quiz"))
        coVerify { builder.change(id, "In the Spanish app, add a vocabulary quiz") }
    }

    @Test fun changeWordsNeedTheAppsNameOrTheAppOrIt() {
        app("Weather"); ageApps()
        val id = app("Spanish")
        // A change verb alone goes to the agent (F10.1 step 3), even right after a build.
        assertFalse(handle("Add a note titled pm-test"))
        assertFalse(handle("Add Mandarin and make the streaks weekly")) // F8.1's words name no app
        assertFalse(handle("make the font bigger"))
        coVerify(exactly = 0) { builder.change(any(), any()) }
        // "the app", "my app", or "it" within 10 minutes of the build: the recent app.
        listOf("Add Mandarin to the app and make the streaks weekly", "change the colors of my app", "remove the timer from it")
            .forEach { assertTrue(it, handle(it)) }
        coVerify(exactly = 3) { builder.change(id, any()) }
        // The name: always.
        assertTrue(handle("Add Mandarin and make the streaks weekly in Spanish"))
        assertTrue(handle("Add a quiz to the Spanish app"))
        coVerify(exactly = 5) { builder.change(id, any()) }
    }

    @Test fun theAppOrItLongAfterTheBuildIsNotAChange() {
        app("Spanish"); ageApps()
        assertFalse(handle("remove the timer from it"))
        assertFalse(handle("Add a quiz to the app"))
        coVerify(exactly = 0) { builder.change(any(), any()) }
    }

    @Test fun ambiguousChangeAsksWhichAppAndChangesNothing() {
        app("Spanish"); app("Weather")
        assertTrue(handle("Make the streaks in the app weekly"))
        assertEquals(listOf("Which app do you mean: Spanish, Weather?"), said)
        coVerify(exactly = 0) { builder.change(any(), any()) }
    }

    @Test fun flowSentencesGoWhereTheFlowsSay() {
        // F7.1 builds; F10.1 step 3 goes to the agent; F8.1 changes only when it names the app.
        assertTrue(handle("Build me an app to learn Spanish"))
        coVerify { builder.build("Build me an app to learn Spanish") }
        val id = app("Spanish")
        assertFalse(handle("Add a note titled pm-test"))
        assertFalse(handle("Add Mandarin and make the streaks weekly"))
        assertTrue(handle("Add Mandarin to Spanish and make the streaks weekly"))
        coVerify(exactly = 1) { builder.change(id, any()) }
    }

    @Test fun theWakeNameIsRemovedFromTheSharedList() {
        val id = app("Spanish")
        assertTrue(handle("Open Cyclone, add a quiz to the Spanish app"))
        assertTrue(handle("okay cyclone change it to weekly streaks"))
        assertTrue(handle("Phone, remove the timer from it"))
        coVerify { builder.change(id, "add a quiz to the Spanish app") }
        coVerify { builder.change(id, "change it to weekly streaks") }
        coVerify { builder.change(id, "remove the timer from it") }
    }

    @Test fun sentenceNamingTwoAppsAsksWhich() {
        app("Spanish"); app("Spanish Weather"); ageApps()
        assertTrue(handle("Change the Spanish Weather app and the Spanish app"))
        assertTrue(said.single().startsWith(MiniAppRequests.WHICH_APP))
        coVerify(exactly = 0) { builder.change(any(), any()) }
    }

    @Test fun otherSentencesAreNotHandled() {
        app("Spanish"); ageApps()
        assertFalse(handle("Text Alex that I am running late"))
        assertFalse(handle("Add milk to my shopping list")) // no app changed in the last 10 minutes
        assertFalse(handle("Can you share it with Adam?")) // no recent app to mean
        assertTrue(goals.isEmpty() && said.isEmpty())
        coVerify(exactly = 0) { builder.build(any()) }
        coVerify(exactly = 0) { builder.change(any(), any()) }
    }

    @Test fun sentenceThatOnlyMentionsAnAppNameIsNotAChange() {
        app("Weather"); ageApps()
        assertFalse(handle("what's the weather today"))
        assertFalse(handle("Is it going to rain? Check the weather"))
        app("Weather Two") // a recent app does not change that either
        assertFalse(handle("what's the weather today"))
        coVerify(exactly = 0) { builder.change(any(), any()) }
        assertTrue(said.isEmpty() && goals.isEmpty())
    }

    @Test fun changeVerbAtTheStartOrTheWordAppMakesAChange() {
        val id = app("Weather"); ageApps()
        assertTrue(handle("Change Weather to show Celsius"))
        assertTrue(handle("Can the Weather app show the wind too"))
        coVerify(exactly = 2) { builder.change(id, any()) }
    }

    @Test fun buildResultIsReported() {
        val results = mutableListOf<BuildResult>()
        runBlocking { MiniAppRequests.tryHandle("Build me an app to learn Spanish", builder, f.store, { goals += it }, { said += it }) { results += it } }
        assertEquals(listOf<BuildResult>(BuildResult.Ready(1, "x", 1)), results)
    }
}
