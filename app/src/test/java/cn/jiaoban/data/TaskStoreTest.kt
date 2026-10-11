package cn.jiaoban.data

import cn.jiaoban.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

class TaskStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val one = Task(
        id = "1",
        text = "订会议室",
        from = "李姐",
        due = LocalDate.of(2026, 10, 13),
        createdAt = Instant.parse("2026-10-11T01:00:00Z"),
    )

    private fun store() = TaskStore(File(tmp.root, "tasks.json"))

    @Test
    fun saveThenLoadGivesBackTheSameList() {
        val s = store()
        s.save(listOf(one))
        assertEquals(listOf(one), s.load())
    }

    @Test
    fun missingFileLoadsEmptyInsteadOfCrashing() {
        assertEquals(emptyList<Task>(), store().load())
    }

    @Test
    fun corruptMainFileFallsBackToBackup() {
        val s = store()
        s.save(listOf(one))
        s.save(listOf(one.copy(text = "改过的事")))
        File(tmp.root, "tasks.json").writeText("{ 坏掉的内容", Charsets.UTF_8)
        assertEquals("订会议室", s.load()[0].text)
    }

    @Test
    fun saveLeavesNoTempFileBehind() {
        val s = store()
        s.save(listOf(one))
        assertTrue(File(tmp.root, "tasks.json").exists())
        assertFalse(File(tmp.root, "tasks.json.tmp").exists())
    }

    @Test
    fun savingAnEmptyListIsStillReadable() {
        val s = store()
        s.save(listOf(one))
        s.save(emptyList())
        assertEquals(emptyList<Task>(), s.load())
    }
}
