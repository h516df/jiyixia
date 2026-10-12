package cn.jiaoban

import cn.jiaoban.data.TaskCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ExpiryPolicyTest {
    private val today = LocalDate.of(2026, 10, 13)
    private fun task(id: String, days: Long, policy: ExpiryPolicy) = Task(
        id, "王总交代的事", "王总", today.plusDays(days), TaskState.OPEN,
        Instant.parse("2026-10-12T00:00:00Z"), expiryPolicy = policy,
    )

    @Test fun mustFinishRemainsVisibleAfterDeadlineAndAcrossDays() {
        val t = task("must", -1, ExpiryPolicy.MUST_FINISH)
        assertEquals(listOf(t), activeTasks(listOf(t), today))
        assertEquals(listOf(t), followUpOverdue(listOf(t), today.plusDays(2)))
        assertFalse(isExpired(t, today))
        assertEquals("王总 · 逾期 1 天", metaLine(t, today))
    }
    @Test fun timeLimitedMovesToExpiredOnlyAfterItsDueDay() {
        val t = task("expires", 0, ExpiryPolicy.EXPIRES)
        assertEquals(listOf(t), activeTasks(listOf(t), today))
        assertTrue(activeTasks(listOf(t), today.plusDays(1)).isEmpty())
        assertEquals(listOf(t), expiredTasks(listOf(t), today.plusDays(1)))
    }
    @Test fun completionAndVoidRemoveEitherPolicyFromAttention() {
        val tasks = ExpiryPolicy.entries.flatMap { policy ->
            listOf(task("done-$policy", -1, policy).copy(state = TaskState.DONE),
                task("void-$policy", -1, policy).copy(state = TaskState.VOID))
        }
        assertTrue(activeTasks(tasks, today).isEmpty())
        assertTrue(expiredTasks(tasks, today).isEmpty())
        assertEquals(4, sortForHistory(tasks).size)
    }
    @Test fun switchingAnExpiredRecordToMustFinishReturnsItToAttention() {
        val t = task("old", -5, ExpiryPolicy.EXPIRES)
        assertTrue(activeTasks(listOf(t), today).isEmpty())
        assertEquals(listOf(t.copy(expiryPolicy = ExpiryPolicy.MUST_FINISH)),
            activeTasks(listOf(t.copy(expiryPolicy = ExpiryPolicy.MUST_FINISH)), today))
    }
    @Test fun legacyJsonKeepsOriginalPolicyAndAllFields() {
        val raw = """[{"id":"legacy","text":"旧记录😀\n第二行","from":"李姐","due":"2026-10-12","state":"OPEN","createdAt":"2026-10-10T00:00:00Z","closedAt":null}]"""
        val t = TaskCodec.decode(raw).single()
        assertEquals(ExpiryPolicy.EXPIRES, t.expiryPolicy)
        assertTrue(isExpired(t, today))
        assertEquals(t, TaskCodec.decode(TaskCodec.encode(listOf(t))).single())
        assertEquals("旧记录😀\n第二行", t.text)
    }
    @Test fun bothPoliciesSurviveSerializationAndNoDeadlineNeverExpires() {
        val tasks = ExpiryPolicy.entries.map { task(it.name, -2, it).copy(due = null) }
        assertEquals(tasks, TaskCodec.decode(TaskCodec.encode(tasks)))
        assertEquals(2, activeTasks(tasks, today.plusYears(1)).size)
    }
    @Test fun unknownPolicyKeepsRecordRecoverableAndLegacyBehavior() {
        val raw = TaskCodec.encode(listOf(task("future", -1, ExpiryPolicy.EXPIRES)))
            .replace("EXPIRES", "UNKNOWN")
        val t = TaskCodec.decode(raw).single()
        assertEquals(ExpiryPolicy.EXPIRES, t.expiryPolicy)
        assertEquals(listOf(t), expiredTasks(listOf(t), today))
    }
    @Test fun attentionOrdersByDeadlineAndKeepsNoDeadlineLast() {
        val later = task("later", 3, ExpiryPolicy.MUST_FINISH)
        val overdue = task("overdue", -2, ExpiryPolicy.MUST_FINISH)
        val noDate = task("no-date", 0, ExpiryPolicy.MUST_FINISH).copy(due = null)
        assertEquals(listOf(overdue, later, noDate), activeTasks(listOf(noDate, later, overdue), today))
    }
}
