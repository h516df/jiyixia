package cn.jiaoban

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TaskFlowTest {
    private val now = Instant.parse("2026-10-13T03:30:00Z")
    private val task = Task("work", "王总让周五交活动表", "王总", LocalDate.of(2026,10,16),
        createdAt = Instant.parse("2026-10-12T02:00:00Z"), expiryPolicy = ExpiryPolicy.MUST_FINISH)

    @Test fun newAndLegacyTasksStartReceived() {
        assertEquals(WorkStage.RECEIVED, task.workStage)
        assertTrue(task.followUps.isEmpty())
    }
    @Test fun startingAndWaitingKeepDeadlineAndClosureRules() {
        val started = task.withWorkStage(WorkStage.WORKING, now)
        val waiting = started.withWorkStage(WorkStage.WAITING, now.plusSeconds(60))
        assertEquals(TaskState.OPEN,waiting.state)
        assertEquals(task.due,waiting.due)
        assertEquals(task.expiryPolicy,waiting.expiryPolicy)
        assertEquals(WorkStage.WAITING,waiting.workStage)
        assertEquals(2,waiting.followUps.size)
        assertEquals(FollowUpKind.PROGRESS,waiting.followUps.last().kind)
        assertNotEquals(waiting.followUps[0].id,waiting.followUps[1].id)
    }
    @Test fun sameStageDoesNotManufactureProgressEntries() {
        val started = task.withWorkStage(WorkStage.WORKING,now)
        assertEquals(started,started.withWorkStage(WorkStage.WORKING,now.plusSeconds(60)))
    }
    @Test fun closedTasksCannotBeStartedUntilRestored() {
        val done=task.withState(TaskState.DONE,now)
        assertEquals(done,done.withWorkStage(WorkStage.WORKING,now.plusSeconds(60)))
    }
    @Test fun fullFollowUpTextKeepsUnicodeAndLineBreaks() {
        val note="已发给王总确认\n价格核对完毕😀\n尾巴".repeat(30)
        val added=task.withFollowUp("  "+note+"  ",now)
        assertEquals(note,added.followUps.single().text)
        assertEquals(FollowUpKind.NOTE,added.followUps.single().kind)
        assertEquals(now,added.followUps.single().createdAt)
        assertEquals(task,task.withFollowUp(" \n ",now))
    }
    @Test fun completionAndRestorePreserveEarlierProgressAndNotes() {
        val waiting=task.withWorkStage(WorkStage.WAITING,now).withFollowUp("已发给王总，等确认",now.plusSeconds(5))
        val done=waiting.withState(TaskState.DONE,now.plusSeconds(10))
        assertEquals(3,done.followUps.size)
        assertEquals(FollowUpKind.DONE,done.followUps.last().kind)
        assertEquals(now.plusSeconds(10),done.closedAt)
        val restored=done.withState(TaskState.OPEN,now.plusSeconds(15))
        assertEquals(null,restored.closedAt)
        assertEquals(WorkStage.WAITING,restored.workStage)
        assertEquals(FollowUpKind.RESTORED,restored.followUps.last().kind)
        assertEquals(waiting.followUps,restored.followUps.take(2))
    }
    @Test fun repeatedClosingDoesNotMoveCompletionTime() {
        val done=task.withState(TaskState.DONE,now)
        assertEquals(done,done.withState(TaskState.DONE,now.plusSeconds(60)))
    }
    @Test fun completedCardShowsCompletionDateInsteadOfLiveOverdueMessage() {
        val done=task.copy(due=LocalDate.of(2026,10,10)).withState(TaskState.DONE,now)
        val meta=recordMeta(done,LocalDate.of(2026,10,13),ZoneId.of("Asia/Shanghai"))
        assertTrue(meta.contains("10月13日"))
        assertTrue(meta.contains("办完"))
        assertFalse(meta.contains("逾期"))
        assertFalse(meta.contains("过期"))
    }
    @Test fun legacyCompletionWithoutTimestampDoesNotInventADate() {
        val legacy=task.copy(state=TaskState.DONE)
        assertEquals("已办完 · 未记录处理时间",recordMeta(legacy,LocalDate.of(2026,10,13),ZoneId.of("Asia/Shanghai")))
    }
    @Test fun globalSearchFindsFullFollowUpsInClosedRecords() {
        val noted=task.withFollowUp("已发给王总，等待编号AbC123确认",now).withState(TaskState.DONE,now)
        assertEquals(listOf(noted),searchAll(listOf(task,noted),"abc123"))
        assertTrue(matches(noted,"王总"))
        assertFalse(matches(task,"abc123"))
    }
}
