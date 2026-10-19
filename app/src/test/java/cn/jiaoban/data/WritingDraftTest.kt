package cn.jiaoban.data

import android.content.Context
import cn.jiaoban.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class WritingDraftTest {
    private val prefs get()=RuntimeEnvironment.getApplication().getSharedPreferences("draft-test",Context.MODE_PRIVATE)
    @Before fun clean(){prefs.edit().clear().commit()}
    @Test fun blankCannotSubmit(){val draft=WritingDraft(prefs);draft.retain(" \n ");assertNull(draft.begin())}
    @Test fun draftSurvivesFreshController(){WritingDraft(prefs).retain("没有标题的想法\n第二行");assertEquals("没有标题的想法\n第二行",WritingDraft(prefs).text)}
    @Test fun doubleTapIsOneRequest(){val draft=WritingDraft(prefs);draft.retain("一次记录");assertNotNull(draft.begin());assertNull(draft.begin())}
    @Test fun failureKeepsTextAndRetryIdentity(){val draft=WritingDraft(prefs);draft.retain("保留正文");val first=draft.begin()!!;assertFalse(draft.finish(first,false));assertEquals(first.text,draft.text);assertEquals(first,draft.begin())}
    @Test fun successfulSaveClearsOnlyItsOwnDraft(){val draft=WritingDraft(prefs);draft.retain("第一条");val first=draft.begin()!!;assertTrue(draft.finish(first,true));draft.retain("第二条");val second=draft.begin()!!;assertNotEquals(first.id,second.id);assertFalse(draft.finish(first,true));assertEquals("第二条",draft.text)}
    @Test fun oldCallbackCannotClearOrOverwriteNewCompositionDraft(){val old=WritingDraft(prefs);old.retain("第一条");val first=old.begin()!!;val newer=WritingDraft(prefs);newer.retain("第二条");val second=newer.begin()!!;assertNotEquals(first.id,second.id);assertFalse(old.finish(first,true));assertEquals("第二条",newer.text);assertTrue(newer.finish(second,true))}
    @Test fun restartReconcilesSavedRecordButKeepsUnsaved(){val draft=WritingDraft(prefs);draft.retain("完整记录");val request=draft.begin()!!;val restarted=WritingDraft(prefs);assertFalse(restarted.reconcile(emptyList()));assertEquals(request.text,restarted.text);assertTrue(restarted.reconcile(listOf(Task(request.id,request.text,createdAt=Instant.now(),kind=EntryKind.NOTE))));assertEquals("",restarted.text)}
}
