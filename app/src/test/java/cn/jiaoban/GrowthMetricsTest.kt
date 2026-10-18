package cn.jiaoban
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class GrowthMetricsTest {
 @Test fun archivedNotesAndVoidTasksNeverCountAsCompletions(){val today=LocalDate.of(2026,10,18);val now=today.atStartOfDay(ZoneId.systemDefault()).toInstant()
  val done=Task("done","完成",createdAt=now,state=TaskState.DONE,closedAt=now,kind=EntryKind.TODO)
  val list=listOf(done,done.copy(id="void",state=TaskState.VOID),done.copy(id="note",kind=EntryKind.NOTE),done.copy(id="unknown",closedAt=null))
  val stats=actionGrowth(list,today)
  assertEquals(2L,stats.metrics.first().value);assertEquals(1L,stats.weekValue)
  assertEquals(1L,stats.metrics.first{it.key=="notes"}.value)
  assertEquals(listOf("完成"),stats.recent)
 }
}
