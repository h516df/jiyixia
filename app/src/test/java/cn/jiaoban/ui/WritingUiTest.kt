package cn.jiaoban.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import cn.jiaoban.*
import cn.jiaoban.data.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real Compose UI, synthetic fixture only. Host renders do not replace device validation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w412dp-h915dp-xhdpi")
class WritingUiTest {
    @get:Rule val compose=createComposeRule()
    private val today=LocalDate.of(2026,10,19)
    private val at=Instant.parse("2026-10-19T08:00:00Z")
    private val old=Task("legacy","以前的工作记录\n这是虚构验收资料。",from="合成来源",createdAt=at,kind=EntryKind.TODO,steps=listOf(TaskStep("step","保留旧步骤",true)))
    private var epoch by mutableStateOf(0)
    private var incoming by mutableStateOf<String?>(null)
    private lateinit var dataFile:File
    private fun setup(font:Float=1f):TaskStore {
        RuntimeEnvironment.getApplication().getSharedPreferences("writing_draft_v1",Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("editor_draft_v1",Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.setFontScale(font)
        val file=File(RuntimeEnvironment.getApplication().cacheDir,"shiye-${UUID.randomUUID()}.json")
        dataFile=file
        val store=TaskStore(file)
        store.save(listOf(old,
            Task("n1","留一点时间给喜欢的事\n散步、读书，以及认真做一顿晚饭。\n生活里的小事也值得专门留一页。 #生活",createdAt=at,kind=EntryKind.NOTE,pinned=true),
            Task("n2","读到的一句话\n把日子过得缓慢一点，细节就会浮现。",createdAt=at.minusSeconds(86400),kind=EntryKind.NOTE),
            Task("n3","周末的小灵感\n一张桌子，一束花，一个没有闹钟的早晨。",createdAt=at.minusSeconds(172800),kind=EntryKind.NOTE),
            Task("d1","雨停之后\n窗外的树叶被洗得很亮。走回家的路上，买了一小束花。",createdAt=at,kind=EntryKind.NOTE,journalDate=today),
            Task("d2","傍晚的一小段路\n没有目的地，只想看看天色慢慢变暗。",createdAt=at.minusSeconds(86400),kind=EntryKind.NOTE,journalDate=today.minusDays(1))))
        compose.setContent{key(epoch){JiaobanTheme{App(store,today,incoming){incoming=null}}}}
        compose.waitUntil(10000){compose.onAllNodesWithText("留一点时间给喜欢的事",substring=true).fetchSemanticsNodes().isNotEmpty()}
        return store
    }
    private fun shot(name:String){compose.waitForIdle();val f=File("build/outputs/shiye-ui/$name.png");f.parentFile?.mkdirs();compose.onRoot().captureRoboImage(f.absolutePath)}
    @Test fun notesLight(){setup();compose.onNodeWithText("待办").assertDoesNotExist();shot("notes-light")}
    @Test @Config(qualifiers="zh-rCN-w412dp-h915dp-night-xhdpi") fun notesDark(){setup();shot("notes-dark")}
    @Test fun diaryLight(){setup();compose.onNodeWithText("日记",useUnmergedTree=true).performClick();compose.onNodeWithText("雨停之后",substring=true).assertIsDisplayed();shot("diary-light")}
    @Test @Config(qualifiers="zh-rCN-w320dp-h900dp-night-xhdpi") fun diaryLargeDark(){setup(2f);compose.onNodeWithText("日记",useUnmergedTree=true).performClick();shot("diary-320dp-font200-dark")}
    @Test @Config(qualifiers="zh-rCN-w320dp-h900dp-xhdpi") fun notesLargeLight(){setup(2f);shot("notes-320dp-font200-light")}
    @Test fun recordsAndDiaryRemainDistinctButSameDayNotesAreLinked(){
        setup()
        compose.onNodeWithText("雨停之后",substring=true).assertDoesNotExist()
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithText("雨停之后",substring=true).assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("同日随记 · 1 条 · 展开"))
        compose.onNodeWithText("同日随记 · 1 条 · 展开").performClick()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("留一点时间给喜欢的事",substring=true))
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true).assertIsDisplayed()
    }
    @Test fun emptyDiaryDayHasNoRedundantFragmentDisclosure(){
        setup()
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("下一天").performClick()
        compose.onNodeWithText("这一天还没写日记").assertIsDisplayed()
        compose.onNodeWithText("同日随记",substring=true).assertDoesNotExist()
    }
    @Test fun searchingFromDiaryAlsoFindsOrdinaryRecords(){
        setup()
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("喜欢的事")
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true).assertIsDisplayed()
        compose.onNodeWithText("雨停之后",substring=true).assertDoesNotExist()
    }
    @Test fun writingAndLegacyPreserved(){
        val store=setup()
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("写日记").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("今天的新日记\n完整的第二行 🌿")
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text.startsWith("今天的新日记")}}
        val saved=store.load().single{it.text.startsWith("今天的新日记")}
        assertEquals(today,saved.journalDate);assertEquals(EntryKind.NOTE,saved.kind);assertEquals(old,store.load().single{it.id=="legacy"})
        compose.onNodeWithText("今天的新日记",substring=true).performClick();shot("diary-reading")
    }
    @Test fun archiveRestoreAndSearch(){
        val store=setup();compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("读到的一句话",substring=true));compose.onNodeWithText("读到的一句话",substring=true).performClick();compose.onNodeWithText("归档").performClick();compose.onNodeWithText("确定").performClick()
        compose.waitUntil(10000){store.load().single{it.id=="n2"}.state==TaskState.VOID}
        compose.onNodeWithText("恢复").performClick();compose.onNodeWithText("确定").performClick()
        compose.waitUntil(10000){store.load().single{it.id=="n2"}.state==TaskState.OPEN}
        compose.onNodeWithContentDescription("返回").performClick();compose.onNodeWithContentDescription("搜索").performClick();compose.onNode(hasSetTextAction()).performTextInput("细节")
        compose.onNodeWithText("读到的一句话",substring=true).assertIsDisplayed();compose.onNodeWithText("周末的小灵感",substring=true).assertDoesNotExist()
    }
    @Test fun longPressDeleteThenRecoverFromRecycleBin() {
        val store=setup()
        // Exercise the accessible long-click action directly: Robolectric's synthetic
        // touch duration can finish before Compose's long-press timeout advances.
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5000) { compose.onAllNodesWithText("已选 1 条").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("删除",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { store.load().single{it.id=="n1"}.deletedAt!=null }
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true).assertDoesNotExist()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true).performClick()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("恢复",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { store.load().single{it.id=="n1"}.deletedAt==null }
        assertEquals("留一点时间给喜欢的事\n散步、读书，以及认真做一顿晚饭。\n生活里的小事也值得专门留一页。 #生活",
            store.load().single{it.id=="n1"}.text)
    }
    @Test fun draftSurvivesTabDetailAndCompositionRecreation(){
        setup()
        compose.onNodeWithContentDescription("随手记录正文").performTextInput("还没写完的想法")
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithText("记录",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("随手记录正文").assertTextContains("还没写完的想法")
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true).performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("随手记录正文").assertTextContains("还没写完的想法")
        compose.runOnIdle{epoch++}
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("随手记录正文").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("随手记录正文").assertTextContains("还没写完的想法")
    }
    @Test fun fullEditorDraftSurvivesFreshComposition(){
        val store=setup()
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("写日记").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("补记的半篇日记")
        compose.runOnIdle{epoch++}
        compose.waitUntil(10000){compose.onAllNodesWithText("补记的半篇日记").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasSetTextAction() and hasText("补记的半篇日记")).assertIsDisplayed()
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text=="补记的半篇日记"}}
    }
    @Test fun failedQuickWriteKeepsDraftAndRetryIsSingleRecord(){
        val store=setup();val blocker=File(dataFile.path+".tmp");assertTrue(blocker.mkdir())
        compose.onNodeWithContentDescription("随手记录正文").performTextInput("失败也不能丢的想法")
        compose.onNodeWithText("记下",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("重试保存").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("随手记录正文").assertTextContains("失败也不能丢的想法")
        compose.onNodeWithText("已记下").assertDoesNotExist()
        assertFalse(store.load().any{it.text=="失败也不能丢的想法"})
        assertTrue(blocker.delete())
        compose.onNodeWithText("重试保存").performClick()
        compose.waitUntil(10000){store.load().any{it.text=="失败也不能丢的想法"}}
        assertEquals(1,store.load().count{it.text=="失败也不能丢的想法"})
    }
    @Test fun inFlightEditorRecreationCannotAcceptOrLoseSecondBody(){
        val store=setup()
        val holder=TasksHolder.forStore(store)
        val ioField=TasksHolder::class.java.getDeclaredField("io").apply{isAccessible=true}
        val io=ioField.get(holder) as java.util.concurrent.ExecutorService
        val gate=java.util.concurrent.CountDownLatch(1)
        io.execute{gate.await(30,java.util.concurrent.TimeUnit.SECONDS)}
        try {
            compose.runOnIdle{incoming="在途第一份正文"}
            compose.waitUntil(10000){compose.onAllNodesWithText("保存",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
            compose.runOnIdle{epoch++}
            compose.waitUntil(10000){compose.onAllNodesWithText("保存中…",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNode(hasText("在途第一份正文")).assertIsNotEnabled()
            compose.runOnIdle{incoming="第二份分享正文"}
            compose.waitUntil(10000){compose.onAllNodesWithText("打开分享").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("打开分享").assertIsNotEnabled()
        } finally {gate.countDown()}
        compose.waitUntil(10000){store.load().any{it.text=="在途第一份正文"}}
        compose.waitUntil(10000){compose.onAllNodesWithText("打开分享").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false}
        compose.onNodeWithText("打开分享").performClick()
        compose.onNode(hasText("第二份分享正文") and hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text=="第二份分享正文"}}
        assertEquals(1,store.load().count{it.text=="在途第一份正文"})
        assertEquals(1,store.load().count{it.text=="第二份分享正文"})
    }
    @Test fun discardedFailedEditNeverReappearsOnNextQuickSave(){
        val store=setup();val original=store.load().single{it.id=="n1"}
        compose.onNodeWithText("留一点时间给喜欢的事",substring=true).performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("应当放弃的修改")
        val blocker=File(dataFile.path+".tmp");assertTrue(blocker.mkdir())
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("保存失败，文字和附件仍在，请重试").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("关闭笔记").performClick()
        compose.onNodeWithText("放弃改动").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertTrue(blocker.delete())
        compose.onNodeWithContentDescription("随手记录正文").performTextInput("另外一条独立记录")
        compose.onNodeWithText("记下",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text=="另外一条独立记录"}}
        assertEquals(original,store.load().single{it.id=="n1"})
        assertFalse(store.load().any{it.text=="应当放弃的修改"})
    }
    @Test fun failedEditorThenNewShareDoesNotReuseOldId(){
        val store=setup();val blocker=File(dataFile.path+".tmp");assertTrue(blocker.mkdir())
        compose.runOnIdle{incoming="第一份分享"}
        compose.waitUntil(10000){compose.onAllNodesWithText("保存",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("保存失败，文字和附件仍在，请重试").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{incoming="第二份分享"}
        compose.waitUntil(10000){compose.onAllNodesWithText("打开分享").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("打开分享").performClick();assertTrue(blocker.delete())
        compose.onNode(hasSetTextAction() and hasText("第二份分享")).assertIsDisplayed()
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text=="第二份分享"}}
        val records=store.load().filter{it.text.endsWith("份分享")}
        assertEquals(1,records.size);assertEquals("第二份分享",records.single().text)
    }
    @Test fun quickSaveAndNextRecord(){
        val store=setup()
        compose.onNodeWithContentDescription("随手记录正文").performTextInput("一闪而过的想法 #随手记")
        compose.onNodeWithText("记下",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text=="一闪而过的想法 #随手记"}}
        compose.waitForIdle()
        compose.onNodeWithContentDescription("随手记录正文").performTextInput("紧接着的第二条")
        compose.onNodeWithText("记下",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){store.load().any{it.text=="紧接着的第二条"}}
        assertEquals(1,store.load().count{it.text=="一闪而过的想法 #随手记"})
        assertEquals(1,store.load().count{it.text=="紧接着的第二条"})
        assertEquals(old,store.load().single{it.id=="legacy"})
        shot("quick-record-saved")
    }
    @Test fun legacyReadOnly(){setup();compose.onNodeWithContentDescription("设置").performClick();compose.onNodeWithText("旧版资料").performClick();compose.onNodeWithText("以前的工作记录",substring=true).performClick();compose.onNodeWithText("编辑").assertDoesNotExist();compose.onNodeWithText("办完了").assertDoesNotExist();shot("legacy-read-only")}

    @Test fun importPreviewRequiresConfirmationAndShowsSyntheticContent(){
        val store=TaskStore(File(RuntimeEnvironment.getApplication().cacheDir,"preview-${UUID.randomUUID()}.json"));store.save(emptyList())
        val holder=cn.jiaoban.data.TasksHolder.forStore(store)
        val plan=cn.jiaoban.importing.ImportParser.parse(cn.jiaoban.importing.ImportBundle.read("2024-05-22.md","合成导入日记\n雨后散步，看见一朵小花。 #生活".byteInputStream()),cn.jiaoban.importing.ImportFormat.OBSIDIAN,now=at)
        compose.setContent{JiaobanTheme{ImportPage(holder,plan,"2024-05-22.md")}}
        compose.onNodeWithText("新增 1 条",substring=true).performScrollTo().assertIsDisplayed()
        assertTrue(store.load().isEmpty());shot("import-preview")
        compose.onNodeWithText("确认导入 1 条").performScrollTo().assertIsDisplayed()
        assertTrue(store.load().isEmpty())
    }
}

