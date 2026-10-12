package cn.jiaoban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class BucketTest {

    private val today = LocalDate.of(2026, 10, 11)

    private fun task(
        days: Long?,
        state: TaskState = TaskState.OPEN,
        from: String = "王总",
    ) = Task(
        id = "id-" + (days?.toString() ?: "none") + "-" + state.name,
        text = "把周报发给客户",
        from = from,
        due = days?.let { today.plusDays(it) },
        state = state,
        createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    )

    @Test
    fun todayIsTodayBucket() = assertEquals(Bucket.TODAY, bucketOf(task(0), today))

    @Test
    fun tomorrowIsLaterBucket() = assertEquals(Bucket.LATER, bucketOf(task(1), today))

    @Test
    fun farFutureIsAlsoLater() = assertEquals(Bucket.LATER, bucketOf(task(60), today))

    @Test
    fun noDueDateIsNoDueBucket() = assertEquals(Bucket.NO_DUE, bucketOf(task(null), today))

    @Test
    fun yesterdayIsAlreadyOverdue() = assertEquals(Bucket.OVERDUE, bucketOf(task(-1), today))

    @Test
    fun longPastIsOverdue() = assertEquals(Bucket.OVERDUE, bucketOf(task(-30), today))

    @Test
    fun doneTaskIsClosed() = assertEquals(Bucket.CLOSED, bucketOf(task(0, TaskState.DONE), today))

    @Test
    fun voidedTaskIsClosed() = assertEquals(Bucket.CLOSED, bucketOf(task(-5, TaskState.VOID), today))

    @Test
    fun timelineKeepsOnlyLiveTasks() {
        val items = listOf(task(-2), task(0), task(null), task(30), task(0, TaskState.DONE))
        val rest = timelineRest(items, today)
        assertEquals(2, rest.size)
        assertTrue(rest.none { bucketOf(it, today) == Bucket.OVERDUE })
        assertTrue(rest.none { bucketOf(it, today) == Bucket.TODAY })
        assertTrue(rest.none { it.state != TaskState.OPEN })
    }

    @Test
    fun timelineIsNewestFirst() {
        val older = task(5).copy(id = "older", createdAt = Instant.parse("2026-10-01T00:00:00Z"))
        val newer = task(6).copy(id = "newer", createdAt = Instant.parse("2026-10-09T00:00:00Z"))
        assertEquals(listOf("newer", "older"), timelineRest(listOf(older, newer), today).map { it.id })
    }

    @Test
    fun dueTodayPicksOnlyTodaysOpenOnes() {
        val items = listOf(task(0), task(1), task(-1), task(0, TaskState.DONE))
        val pinned = dueToday(items, today)
        assertEquals(1, pinned.size)
        assertEquals(Bucket.TODAY, bucketOf(pinned.first(), today))
    }

    @Test
    fun overdueIsSortedOldestFirst() {
        val items = listOf(task(-1), task(-9), task(-4))
        val days = sortForOverdue(items, today).map { ChronoUnit.DAYS.between(today, it.due!!) }
        assertEquals(listOf(-9L, -4L, -1L), days)
    }

    @Test
    fun metaLineSaysWhoAndWhen() {
        assertEquals("王总 · 今天要", metaLine(task(0), today))
        assertEquals("王总 · 明天要", metaLine(task(1), today))
        assertEquals("王总 · 过期 2 天", metaLine(task(-2), today))
        assertEquals("王总 · 没定期限", metaLine(task(null), today))
        assertEquals("没定期限", metaLine(task(null, from = ""), today))
        assertEquals("王总 · 10月18日 · 还有 7 天", metaLine(task(7), today))
    }
    @Test
    fun theSameRecordsMoveToTheCorrectGroupsOnTheNextDay() {
        val dueNow = task(0)
        val dueNext = task(1)
        val records = listOf(dueNow, dueNext, task(-1))
        assertEquals(listOf(dueNow), dueToday(records, today))
        val tomorrow = today.plusDays(1)
        assertEquals(listOf(dueNext), dueToday(records, tomorrow))
        assertEquals(2, sortForOverdue(records, tomorrow).size)
        assertEquals("王总 · 今天要", metaLine(dueNext, tomorrow))
        assertEquals("王总 · 过期 1 天", metaLine(dueNow, tomorrow))
    }

}
