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
        text: String = "把周报发给客户",
        from: String = "王总",
    ) = Task(
        id = "id-" + (days?.toString() ?: "none") + "-" + state.name,
        text = text,
        from = from,
        due = days?.let { today.plusDays(it) },
        state = state,
        createdAt = Instant.parse("2026-10-01T00:00:00Z"),
    )

    @Test
    fun todayGoesToTodayBucket() = assertEquals(Bucket.TODAY, bucketOf(task(0), today))

    @Test
    fun tomorrowGoesToTomorrowBucket() = assertEquals(Bucket.TOMORROW, bucketOf(task(1), today))

    @Test
    fun threeDaysLaterIsThisWeek() = assertEquals(Bucket.THIS_WEEK, bucketOf(task(3), today))

    @Test
    fun sevenDaysLaterIsStillThisWeek() = assertEquals(Bucket.THIS_WEEK, bucketOf(task(7), today))

    @Test
    fun eightDaysLaterIsLater() = assertEquals(Bucket.LATER, bucketOf(task(8), today))

    @Test
    fun noDueDateGoesToNoDueBucket() = assertEquals(Bucket.NO_DUE, bucketOf(task(null), today))

    @Test
    fun yesterdayIsAlreadyOverdue() = assertEquals(Bucket.OVERDUE, bucketOf(task(-1), today))

    @Test
    fun longPastIsOverdue() = assertEquals(Bucket.OVERDUE, bucketOf(task(-30), today))

    @Test
    fun doneTaskIsClosed() = assertEquals(Bucket.CLOSED, bucketOf(task(0, TaskState.DONE), today))

    @Test
    fun voidedTaskIsClosed() = assertEquals(Bucket.CLOSED, bucketOf(task(-5, TaskState.VOID), today))

    @Test
    fun overdueSortsBehindEveryLiveTask() {
        val items = listOf(task(-2), task(0), task(null), task(30))
        val main = sortForMain(items, today)
        assertEquals(4, main.size)
        assertEquals(Bucket.TODAY, bucketOf(main.first(), today))
        assertTrue(bucketOf(main.last(), today) == Bucket.OVERDUE)
        assertTrue(main.dropLast(1).none { bucketOf(it, today) == Bucket.OVERDUE })
    }

    @Test
    fun mainOrderIsTodayThenTomorrowThenWeekThenLaterThenNoDue() {
        val items = listOf(task(null), task(20), task(3), task(1), task(0))
        val labels = sortForMain(items, today).map { bucketOf(it, today) }
        assertEquals(
            listOf(Bucket.TODAY, Bucket.TOMORROW, Bucket.THIS_WEEK, Bucket.LATER, Bucket.NO_DUE),
            labels,
        )
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
}
