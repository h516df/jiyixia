package cn.jiaoban.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.jiaoban.*
import cn.jiaoban.media.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w412dp-h915dp-xhdpi")
class MediaEditorUiTest {
    @get:Rule val compose=createComposeRule()
    private var epoch by mutableStateOf(0)
    private var saved:List<ImportedAttachment>?=null
    private fun setup(font:Float=1f) {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("editor_draft_v1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit();RuntimeEnvironment.setFontScale(font)
        val bitmap=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(124,160,127))}
        val bytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val image=MediaStorage.copy(context.filesDir,bytes.inputStream(),"合成照片.png","image/png")
        prefs.edit().putString("session","media-ui").putString("text","").putBoolean("active",true).commit()
        AttachmentDraft.retain(prefs,listOf(image))
        compose.setContent { key(epoch) { JiaobanTheme { NoteEditor(null,"",session="media-ui",onDismiss={},onSave={text,_,attachments->assertEquals("",text);saved=attachments}) } } }
        compose.waitForIdle()
    }
    private fun shot(name:String) { compose.waitForIdle();val file=File("build/outputs/shiye-media-ui/$name.png");file.parentFile.mkdirs();compose.onRoot().captureRoboImage(file.absolutePath) }
    @Test fun mediaOnlyDraftSurvivesRecreationAndSaves() {
        setup();compose.onNodeWithContentDescription("录音").assertIsDisplayed();compose.onNodeWithContentDescription("添加照片或视频").assertIsDisplayed()
        compose.runOnIdle { epoch++ };compose.waitForIdle()
        compose.onNode(hasText("完成") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals("image/png",saved!!.single().mimeType)
        shot("editor-light")
    }
    @Test fun imageOpensFullScreenViewerAndReturnsToEditor() {
        setup()
        compose.onNodeWithContentDescription("照片预览：合成照片.png").performClick()
        compose.onNodeWithText("1 / 1").assertIsDisplayed()
        shot("photo-fullscreen")
        compose.onNodeWithContentDescription("关闭图片").performClick()
        compose.onNodeWithContentDescription("记录正文").assertIsDisplayed()
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h900dp-night-xhdpi") fun mediaEditorDarkLarge() { setup(2f);shot("editor-dark-320-font200") }
    @Test @Config(qualifiers="zh-rCN-w320dp-h900dp-xhdpi") fun mediaEditorLightLarge() { setup(2f);shot("editor-light-320-font200") }
    @Test fun removingLastAttachmentDisablesEmptySaveAndPersists() {
        setup();compose.onNodeWithText("从这一页移除").performScrollTo().performClick()
        compose.onNode(hasText("完成") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { epoch++ };compose.waitForIdle();compose.onNode(hasText("完成") and hasClickAction()).assertIsNotEnabled()
        val raw=RuntimeEnvironment.getApplication().getSharedPreferences("editor_draft_v1",Context.MODE_PRIVATE).getString("attachments",null)
        assertTrue(AttachmentDraft.decode(raw).isEmpty())
    }
    @Test fun newEditorSessionNeverReusesPreviousAttachmentDraft() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("editor_draft_v1",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("session","previous").putString("attachments",AttachmentDraft.encode(listOf(ImportedAttachment("a".repeat(64),"old.mp4","imports/${"a".repeat(64)}.mp4","video/mp4")))).commit()
        val wanted=ImportedAttachment("b".repeat(64),"new.m4a","imports/${"b".repeat(64)}.m4a","audio/mp4")
        val note=Task("new","different",createdAt=Instant.EPOCH,kind=EntryKind.NOTE,attachments=listOf(wanted))
        compose.setContent { JiaobanTheme { NoteEditor(note,"",session="new-session",onDismiss={},onSave={_,_,_->}) } }
        compose.waitForIdle()
        assertEquals(listOf(wanted),AttachmentDraft.decode(prefs.getString("attachments",null)))
    }
}
