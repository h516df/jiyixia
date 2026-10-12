package cn.jiaoban

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PeopleTest {

    private val today = LocalDate.of(2026, 10, 12)

    private fun task(
        id: String,
        from: String,
        days: Long?,
        state: TaskState = TaskState.OPEN,
    ) = Task(
        id = id,
        text = "事 $id",
        from = from,
        due = days?.let { today.plusDays(it) },
        state = state,
        createdAt = Instant.parse("2026-10-01T00:00:00Z"),
        closedAt = if (state == TaskState.OPEN) null else Instant.parse("2026-10-10T00:00:00Z"),
    )

    @Test
    fun countsPerPerson() {
        val tasks = listOf(
            task("1", "王总", 0),
            task("2", "王总", -3),
            task("3", "王总", 5, TaskState.DONE),
            task("4", "李姐", 1),
            task("5", "", 2),
        )
        val stats = peopleStats(tasks, today)
        assertEquals(listOf("王总", "李姐", ""), stats.map { it.name })
        val wang = stats.first { it.name == "王总" }
        assertEquals(3, wang.total)
        assertEquals(2, wang.open)
        assertEquals(1, wang.overdue)
        assertEquals("没记是谁", personLabel(""))
    }

    @Test
    fun peopleWithMoreOpenItemsComeFirst() {
        val tasks = listOf(task("1", "李姐", 1), task("2", "王总", 1), task("3", "王总", 2))
        assertEquals(listOf("王总", "李姐"), peopleStats(tasks, today).map { it.name })
    }

    @Test
    fun onePersonKeepsOpenFirstThenClosed() {
        val tasks = listOf(
            task("a", "王总", 3, TaskState.DONE),
            task("b", "王总", -1),
            task("c", "王总", 1),
            task("d", "李姐", 0),
        )
        assertEquals(listOf("b", "c", "a"), sortForPerson(tasks, "王总").map { it.id })
    }

    @Test
    fun personSearchTrimsSpaces() {
        val tasks = listOf(task("1", " 王总 ", 1))
        assertEquals(1, sortForPerson(tasks, "王总").size)
        assertEquals(listOf("王总"), peopleStats(tasks, today).map { it.name })
    }
}
