package cn.jiaoban
import cn.jiaoban.data.TaskCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class EntryKindTest {
 @Test fun legacyRecordStaysDelegated(){val task=Task("old","原文",createdAt=Instant.EPOCH);val json=org.json.JSONArray(TaskCodec.encode(listOf(task)));json.getJSONObject(0).remove("kind");json.getJSONObject(0).remove("pinned");assertEquals(task,TaskCodec.decode(json.toString()).single())}
 @Test fun allKindsRoundTripWithPinAndMultiline(){val values=EntryKind.entries.map{Task(it.name,"标题\n正文😀",createdAt=Instant.EPOCH,kind=it,pinned=true)};assertEquals(values,TaskCodec.decode(TaskCodec.encode(values)))}
}
