package cn.jiaoban.data

import cn.jiaoban.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.nio.file.Files

class JournalCompatibilityTest {
    private val at=Instant.parse("2026-10-19T03:00:00Z")
    private val old=Task("old","原文\n完整保留 🌿",from="合成来源",due=LocalDate.of(2026,10,1),createdAt=at,
        state=TaskState.DONE,closedAt=at,expiryPolicy=ExpiryPolicy.EXPIRES,kind=EntryKind.DELEGATED,
        workStage=WorkStage.WAITING,important=true,steps=listOf(TaskStep("s","旧步骤",true)),followUps=listOf(FollowUp("f","旧过程",at,FollowUpKind.NOTE)))
    @Test fun legacyRecordSurvivesDiarySaveAndFullExport() {
        val diary=Task("diary","窗边的下午\n风把书页轻轻翻动。",createdAt=at,kind=EntryKind.NOTE,journalDate=LocalDate.of(2026,10,18))
        val file=Files.createTempDirectory("shiye-test").resolve("tasks.json").toFile()
        try {
            val store=TaskStore(file);store.save(listOf(old));store.save(store.load()+diary)
            assertEquals(listOf(old,diary),store.load())
            assertEquals(listOf(old,diary),TaskCodec.decode(TaskCodec.encode(store.load())))
            assertEquals(old,TaskCodec.decode(file.resolveSibling("tasks.json.bak").readText()).single())
        } finally {file.parentFile?.deleteRecursively()}
    }
    @Test fun oldNoteWithoutJournalDateStaysNote() {
        val raw=JSONArray().put(JSONObject().put("id","n").put("kind","NOTE").put("text","普通笔记").put("createdAt",at.toString())).toString()
        val n=TaskCodec.decode(raw).single();assertEquals(EntryKind.NOTE,n.kind);assertNull(n.journalDate)
    }
    @Test fun diaryRemainsReadableAsNoteByOlderReaders() {
        val n=Task("d","日记文字",createdAt=at,kind=EntryKind.NOTE,journalDate=LocalDate.of(2024,2,29))
        val raw=JSONArray(TaskCodec.encode(listOf(n))).getJSONObject(0)
        assertEquals("NOTE",raw.getString("kind"));assertEquals("2024-02-29",raw.getString("journalDate"));assertTrue(raw.isNull("due"))
        raw.remove("journalDate");assertEquals(n.copy(journalDate=null),TaskCodec.decode(JSONArray().put(raw).toString()).single())
    }
    @Test fun malformedDiaryDateDoesNotLoseText() {
        val raw=JSONArray(TaskCodec.encode(listOf(old.copy(kind=EntryKind.NOTE))));raw.getJSONObject(0).put("journalDate","bad-date")
        val n=TaskCodec.decode(raw.toString()).single();assertEquals(old.text,n.text);assertNull(n.journalDate)
    }
    @Test fun archiveAndRestoreKeepDiaryDateAndText() {
        val n=old.copy(kind=EntryKind.NOTE,journalDate=LocalDate.of(2026,10,18))
        val restored=n.withState(TaskState.VOID).withState(TaskState.OPEN)
        assertEquals(n.text,restored.text);assertEquals(n.journalDate,restored.journalDate)
    }
    @Test fun openingExistingFileDoesNotRewriteIt() {
        val file=Files.createTempFile("legacy-shiye",".json").toFile()
        try {val raw=TaskCodec.encode(listOf(old));file.writeText(raw);TaskStore(file).load();assertEquals(raw,file.readText())}finally{file.delete()}
    }
}
