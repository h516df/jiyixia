package cn.jiaoban

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SearchAndSortTest {

    private val today = LocalDate.of(2026, 10, 11)

    private fun task(
        id: String,
        text: String,
        from: String,
        days: Long?,
        state: TaskState = TaskState.OPEN,
        createdAt: String = "2026-10-01T00:00:00Z",
        closedAt: String? = null,
    ) = Task(
        id = id,
        text = text,
        from = from,
        due = days?.let { today.plusDays(it) },
        state = state,
        createdAt = Instant.parse(createdAt),
        closedAt = closedAt?.let { Instant.parse(it) },
    )

    @Test
    fun searchHitsTheText() {
        val items = listOf(
            task("1", "把周报发给客户", "王总", 0),
            task("2", "订会议室", "李姐", 1),
        )
        assertEquals(listOf("把周报发给客户"), searchAll(items, "周报").map { it.text })
    }

    @Test
    fun searchHitsThePerson() {
        val items = listOf(
            task("1", "把周报发给客户", "王总", 0),
            task("2", "订会议室", "李姐", 1),
        )
        assertEquals(listOf("订会议室"), searchAll(items, "李姐").map { it.text })
    }

    @Test
    fun searchAlsoFindsExpiredAndClosed() {
        val items = listOf(
            task("1", "早就过期的事", "王总", -12),
            task("2", "已经办完的事", "王总", -3, TaskState.DONE, closedAt = "2026-10-10T00:00:00Z"),
            task("3", "作废掉的事", "王总", -1, TaskState.VOID, closedAt = "2026-10-09T00:00:00Z"),
        )
        assertEquals(3, searchAll(items, "的事").size)
        assertEquals(3, searchAll(items, "王总").size)
    }

    @Test
    fun blankQueryReturnsEverything() {
        val items = listOf(task("1", "甲", "王总", 0), task("2", "乙", "李姐", 1))
        assertEquals(2, searchAll(items, "   ").size)
    }

    @Test
    fun historyShowsLatestClosedFirst() {
        val items = listOf(
            task("1", "先办完的", "王总", 0, TaskState.DONE, closedAt = "2026-10-05T00:00:00Z"),
            task("2", "后办完的", "王总", 0, TaskState.DONE, closedAt = "2026-10-09T00:00:00Z"),
            task("3", "还在办", "王总", 0),
        )
        assertEquals(listOf("后办完的", "先办完的"), sortForHistory(items).map { it.text })
    }

    @Test
    fun summaryCountsOpenTodayAndOverdue() {
        val items = listOf(
            task("1", "今天要的", "王总", 0),
            task("2", "过期的", "王总", -2),
            task("3", "下周的", "王总", 5),
            task("4", "办完的", "王总", 0, TaskState.DONE, closedAt = "2026-10-10T00:00:00Z"),
        )
        assertEquals("手上 3 件，今天 1 件，过期 1 件", summaryLine(items, today))
    }

    @Test
    fun summarySaysNothingLeftWhenAllClosed() {
        val items = listOf(task("1", "办完的", "王总", 0, TaskState.DONE, closedAt = "2026-10-10T00:00:00Z"))
        assertEquals("手上没有活的事", summaryLine(items, today))
        assertEquals("还没记过事", summaryLine(emptyList(), today))
    }
}
