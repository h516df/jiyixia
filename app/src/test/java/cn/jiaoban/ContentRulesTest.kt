package cn.jiaoban

import cn.jiaoban.data.TaskCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ContentRulesTest {
    private val today=LocalDate.of(2026,10,19)
    private val at=Instant.parse("2026-10-19T01:00:00Z")
    private fun task(id:String="a")=Task(id,"原文😀\n第二行",from="王总",due=today,createdAt=at,kind=EntryKind.DELEGATED,
        expiryPolicy=ExpiryPolicy.MUST_FINISH,followUps=listOf(FollowUp("event","保留跟进",at)))
    @Test fun legacyDelegatedJoinsTodoWithoutChangingContent(){
        val old=task();assertEquals(listOf(old),todoRecords(listOf(old,old.copy(id="n",kind=EntryKind.NOTE))))
        assertEquals("待办",old.kind.label);assertEquals("DELEGATED",old.kind.name)
    }
    @Test fun legacyJsonDefaultsPreserveSourceExpiryAndText(){
        val raw="""[{"id":"legacy","text":"完整原文😀","from":"王总","createdAt":"2026-10-01T00:00:00Z"}]"""
        val old=TaskCodec.decode(raw).single()
        assertEquals(EntryKind.DELEGATED,old.kind);assertEquals(ExpiryPolicy.EXPIRES,old.expiryPolicy)
        assertEquals("王总",old.from);assertFalse(old.important);assertTrue(old.steps.isEmpty())
        assertEquals(old,TaskCodec.decode(TaskCodec.encode(listOf(old))).single())
    }
    @Test fun importantAndDuplicateNamedStepsRoundtrip(){
        val old=task().copy(important=true).addStep("核对","1").addStep("核对","2").toggleStep("1")
        val loaded=TaskCodec.decode(TaskCodec.encode(listOf(old))).single()
        assertEquals(old,loaded);assertTrue(loaded.steps[0].done);assertFalse(loaded.steps[1].done)
    }
    @Test fun completionAndRestoreNeverCompleteOrEraseSteps(){
        val old=task().copy(important=true).addStep("核对","1").addStep("发送","2").toggleStep("1")
        val restored=old.withState(TaskState.DONE,at).withState(TaskState.OPEN,at.plusSeconds(1))
        assertEquals(old.steps,restored.steps);assertTrue(restored.important);assertEquals(old.from,restored.from)
        assertEquals(old.followUps.first(),restored.followUps.first());assertEquals(old.kind,restored.kind)
    }
    @Test fun orderKeepsExpiredLegacyOutOfActiveButRetainsIt(){
        val expired=task("old").copy(due=today.minusDays(2),expiryPolicy=ExpiryPolicy.EXPIRES)
        val overdue=task("overdue").copy(due=today.minusDays(1))
        val important=task("important").copy(important=true)
        assertEquals(listOf(important,overdue),orderedTodos(listOf(expired,overdue,important),today))
        assertEquals(listOf(expired),expiredTasks(todoRecords(listOf(expired)),today))
    }
    @Test fun completedReviewExcludesNotesVoidAndRestored(){
        val done=task().withState(TaskState.DONE,at)
        val list=listOf(done,done.copy(id="note",kind=EntryKind.NOTE),done.copy(id="void",state=TaskState.VOID),done.copy(id="open",state=TaskState.OPEN))
        assertEquals(listOf(done),completedRecords(list))
        assertTrue(completedRecords(listOf(done.withState(TaskState.OPEN))).isEmpty())
    }
    @Test fun blankAndDuplicateStepIdsDoNotCreateItems(){
        val t=task().addStep("核对","1")
        assertEquals(t,t.addStep(" ","2"));assertEquals(t,t.addStep("别的","1"));assertEquals(t,t.toggleStep("missing"))
    }
    @Test fun fullBodySearchCanStayInItsOwnKind(){
        val note=task("n").copy(kind=EntryKind.NOTE,text="复盘\n售后运费详情")
        val todo=task("t").copy(text="联系售后运费")
        assertEquals(listOf(todo),searchAll(todoRecords(listOf(todo,note)),"售后运费"))
        assertEquals(listOf(note),searchAll(listOf(todo,note).filter{it.kind==EntryKind.NOTE},"售后运费"))
        assertTrue(noteExcerpt(note.text,"售后运费").contains("售后运费"))
    }
    @Test fun checklistTogglePreservesUnicodeAndSameNamedOtherRow(){
        val text="清单😀\n- [ ] 核对\n- [ ] 核对\n尾巴"
        val changed=toggleNoteLine(text,1)
        assertEquals("清单😀\n- [x] 核对\n- [ ] 核对\n尾巴",changed)
        assertEquals(text,toggleNoteLine(changed,1));assertEquals(text,toggleNoteLine(text,0))
    }
    @Test fun noteTitleAndExcerptKeepFullStoredBody(){
        val body="\n第一行标题\n很长的正文".repeat(30)
        assertEquals("第一行标题",noteTitle(body));assertTrue(noteExcerpt(body).contains("很长的正文"))
        assertEquals(body,TaskCodec.decode(TaskCodec.encode(listOf(task().copy(text=body,kind=EntryKind.NOTE)))).single().text)
    }
    @Test fun checklistSelectionKeepsEverySelectedCharacterAndMovesCursorAfterLines(){
        val text="标题\n核对😀价格\n发送资料\n尾巴"
        val edit=insertChecklist(text,4,13)
        assertEquals("标题\n- [ ] 核对😀价格\n- [ ] 发送资料\n尾巴",edit.text)
        assertEquals(edit.text.indexOf("\n尾巴"),edit.cursor)
    }
    @Test fun checklistWithReversedSelectionAndEmojiBoundaryNeverSplitsText(){
        val text="😀正文\n第二行"
        assertEquals("- [ ] 😀正文\n第二行",insertChecklist(text,2,1).text)
        assertEquals("- [ ] 😀正文\n第二行",insertChecklist(text,1,2).text)
    }
    @Test fun checklistAtEmptyEndInsertsAndExistingChecklistIsIdempotent(){
        val edit=insertChecklist("标题\n",3,3)
        assertEquals("标题\n- [ ] ",edit.text);assertEquals(edit.text.length,edit.cursor)
        val old="- [x] 核对😀"
        assertEquals(old,insertChecklist(old,0,old.length).text)
    }
}
