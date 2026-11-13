package ai.opencyclon.ui

import ai.opencyclon.db.AppDatabase
import ai.opencyclon.proactive.RuleEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleDaoRemoveTest {
    @Test fun removeDeletesOnlyTheRuleWithThatId() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.ruleDao()
            val first = dao.insert(RuleEntity(kind = "package", packageName = "a.app", text = "hide a", createdAt = 1))
            dao.insert(RuleEntity(kind = "text", text = "hide sales", createdAt = 2))
            dao.remove(first)
            assertEquals(listOf("hide sales"), dao.all().map { it.text })
            dao.remove(9999)
            assertEquals(1, dao.all().size)
        } finally {
            db.close()
        }
    }
}
