package cn.jiaoban.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class TasksHolderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun record(text: String, from: String? = null, due: LocalDate? = null, overrideDue: Boolean = false,
                       policy: cn.jiaoban.ExpiryPolicy = cn.jiaoban.ExpiryPolicy.MUST_FINISH): cn.jiaoban.Task {
        val store = TaskStore(File(tmp.root, "tasks.json"))
        val holder = TasksHolder(store)
        holder.addFromText(text, LocalDate.of(2026, 10, 12), from, due, overrideDue, policy)
        val task = holder.tasks.single()
        // 等落盘完成后再让 TemporaryFolder 清理，顺便验证最终保存的是同一份全文。
        val deadline = System.nanoTime() + 2_000_000_000L
        while (store.load() != holder.tasks && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(holder.tasks, store.load())
        return task
    }

    @Test fun longSharedMessageKeepsItsTailAndParsesTheDeadlineAtTheEnd() {
        val text = "李姐说" + "活动规则与客户要求，".repeat(45) + "尾巴记号ZZZ，明天要提交汇总"
        val task = record(text)
        assertEquals(text, task.text)
        assertEquals("李姐", task.from)
        assertEquals(LocalDate.of(2026, 10, 13), task.due)
    }

    @Test fun emojiAtTheOldBoundaryIsNotSplit() {
        val text = "a".repeat(199) + "😀保留完整结尾"
        assertEquals(text, record(text).text)
    }

    @Test fun shortInputStillHasTheSamePersonAndDeadline() {
        val task = record("  王总说明天要周报  ")
        assertEquals("王总说明天要周报", task.text)
        assertEquals("王总", task.from)
        assertEquals(LocalDate.of(2026, 10, 13), task.due)
    }

    @Test fun newRecordsMustFinishAndMultilineContentIsPreserved() {
        val text = "王总说明天要周报\n活动要求：\n一、核对价格😀\n二、提交活动表"
        val task = record(text)
        assertEquals(text, task.text)
        assertEquals(cn.jiaoban.ExpiryPolicy.MUST_FINISH, task.expiryPolicy)
    }
    @Test fun manuallyChosenPersonDateAndPolicyOverrideRecognition() {
        val due = LocalDate.of(2026, 11, 20)
        val task = record("王总说明天要周报", "客户甲", due, true, cn.jiaoban.ExpiryPolicy.EXPIRES)
        assertEquals("客户甲", task.from)
        assertEquals(due, task.due)
        assertEquals(cn.jiaoban.ExpiryPolicy.EXPIRES, task.expiryPolicy)
    }
    @Test fun explicitBlankPersonAndNoDeadlineAreNotReplacedByRecognition() {
        val task = record("王总说明天要周报", "", null, true)
        assertEquals("", task.from)
        assertEquals(null, task.due)
    }
    @Test fun progressAndNotesPersistTogetherAndUndoUsesExpectedSnapshot() {
        val store=TaskStore(File(tmp.root,"flow.json"))
        val holder=TasksHolder(store)
        holder.addFromText("王总让周五交活动表",LocalDate.of(2026,10,13))
        val before=holder.tasks.single()
        val working=holder.setWorkStage(before.id,cn.jiaoban.WorkStage.WORKING)!!
        assertTrue(holder.undoChange(before,working))
        assertEquals(before,holder.tasks.single())
        holder.setWorkStage(before.id,cn.jiaoban.WorkStage.WAITING)
        holder.addFollowUp(before.id,"已发给王总\n等确认😀")
        val expected=holder.tasks.single()
        assertEquals(cn.jiaoban.WorkStage.WAITING,expected.workStage)
        assertEquals(2,expected.followUps.size)
        val deadline=System.nanoTime()+2_000_000_000L
        while(store.load()!=holder.tasks && System.nanoTime()<deadline) Thread.sleep(10)
        assertEquals(holder.tasks,store.load())
    }
    @Test fun oldUndoCannotEraseANewerFollowUp() {
        val store=TaskStore(File(tmp.root,"undo.json"))
        val holder=TasksHolder(store)
        holder.addFromText("王总让周五交活动表",LocalDate.of(2026,10,13))
        val before=holder.tasks.single()
        val working=holder.setWorkStage(before.id,cn.jiaoban.WorkStage.WORKING)!!
        holder.addFollowUp(before.id,"价格核对完了")
        val current=holder.tasks.single()
        assertFalse(holder.undoChange(before,working))
        assertEquals(current,holder.tasks.single())
        val deadline=System.nanoTime()+2_000_000_000L
        while(store.load()!=holder.tasks && System.nanoTime()<deadline) Thread.sleep(10)
        assertEquals(holder.tasks,store.load())
    }
}
