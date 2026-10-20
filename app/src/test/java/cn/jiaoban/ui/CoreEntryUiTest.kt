package cn.jiaoban.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import cn.jiaoban.MainActivity
import cn.jiaoban.data.TaskStore
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],qualifiers="zh-rCN-w360dp-h800dp-xhdpi")
class CoreEntryUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun open() {
        compose.waitUntil(10000){compose.onAllNodesWithText("日记",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("日记",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("写日记").performClick()
        compose.onNodeWithContentDescription("记录正文").assertIsDisplayed()
    }
    @Test fun actualActivityTextSaveAndReopenWithKeyboardInsets() {
        open()
        compose.onNodeWithContentDescription("记录正文").performTextInput("合成测试：键盘打开也能写字\n保留第二行")
        compose.runOnIdle {
            val dialogs=org.robolectric.shadows.ShadowDialog.getShownDialogs()
            val window=dialogs.last().window!!
            window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,600))
                .setVisible(WindowInsets.Type.ime(),true).build())
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("记录正文").assertIsDisplayed().assertTextContains("合成测试：键盘打开也能写字\n保留第二行")
        assertTrue(compose.onNodeWithContentDescription("记录正文").fetchSemanticsNode().boundsInWindow.height>80f)
        val output=File("build/outputs/core-ui/keyboard-entry.png");output.parentFile.mkdirs()
        compose.onNode(isDialog()).captureRoboImage(output.absolutePath)
        compose.onNode(hasText("保存") and hasClickAction()).performClick()
        val store=TaskStore(File(compose.activity.filesDir,"tasks.json"))
        compose.waitUntil(10000){store.load().any{it.text.startsWith("合成测试：")}}
        compose.onNodeWithText("合成测试：键盘打开也能写字",substring=true).performClick()
        compose.onNodeWithText("合成测试：键盘打开也能写字").assertIsDisplayed()
        compose.onNodeWithText("保留第二行").assertIsDisplayed()
    }
    @Test fun actualPickerResultCopiesThenSavesImageOnlyDiary() {
        open()
        val directory=File(compose.activity.filesDir,"imports").apply{mkdirs()}
        val original=File(directory,"synthetic-source.png")
        original.outputStream().use {Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GREEN)}.compress(Bitmap.CompressFormat.PNG,100,it)}
        val before=original.readBytes()
        val uri=FileProvider.getUriForFile(compose.activity,"cn.jiaoban.imports",original)
        compose.onNodeWithText("照片 / 视频").performClick()
        compose.runOnIdle {
            val shadow=shadowOf(compose.activity)
            val request=shadow.nextStartedActivityForResult
            assertNotNull(request)
            shadow.receiveResult(request.intent,Activity.RESULT_OK,Intent().setData(uri))
        }
        compose.waitUntil(10000){compose.onAllNodesWithText("从这一页移除").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("保存") and hasClickAction()).assertIsEnabled().performClick()
        val store=TaskStore(File(compose.activity.filesDir,"tasks.json"))
        compose.waitUntil(10000){store.load().any{it.attachments.isNotEmpty()}}
        val saved=store.load().last{it.attachments.isNotEmpty()}.attachments.single()
        assertArrayEquals(before,File(compose.activity.filesDir,saved.relativePath).readBytes())
        assertArrayEquals(before,original.readBytes())
    }
    @Test fun actualPermissionCallbackBeforeResumeDoesNotBecomeFalseDenial() {
        open()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithText("录音").performClick()
        compose.runOnIdle {
            val request=shadowOf(compose.activity).lastRequestedPermission
            assertNotNull(request)
            assertTrue(request.requestedPermissions.contains(android.Manifest.permission.RECORD_AUDIO))
            val lifecycle=compose.activity.lifecycle as androidx.lifecycle.LifecycleRegistry
            lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
            compose.activity.onRequestPermissionsResult(request.requestCode,request.requestedPermissions,intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        }
        compose.onNodeWithText("未开启麦克风",substring=true).assertDoesNotExist()
        compose.runOnIdle {
            (compose.activity.lifecycle as androidx.lifecycle.LifecycleRegistry).handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("暂停录音").assertIsDisplayed()
        compose.onNodeWithText("结束录音").performClick()
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h800dp-night-xhdpi") fun deniedPermissionWithKeyboardAndLargeFontStillAllowsText() {
        RuntimeEnvironment.setFontScale(2f)
        open()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithText("录音").performClick()
        compose.runOnIdle {
            val request=shadowOf(compose.activity).lastRequestedPermission
            compose.activity.onRequestPermissionsResult(request.requestCode,request.requestedPermissions,intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
            val window=org.robolectric.shadows.ShadowDialog.getShownDialogs().last().window!!
            window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,600)).setVisible(WindowInsets.Type.ime(),true).build())
        }
        compose.onNodeWithContentDescription("记录正文").performTextInput("拒绝麦克风也可以保存文字")
        compose.runOnIdle {
            val window=org.robolectric.shadows.ShadowDialog.getShownDialogs().last().window!!
            window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,600)).setVisible(WindowInsets.Type.ime(),true).build())
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("记录正文").assertIsDisplayed()
        assertTrue(compose.onNodeWithContentDescription("记录正文").fetchSemanticsNode().boundsInWindow.height>80f)
        val output=File("build/outputs/core-ui/denied-dark-320-font200.png");output.parentFile!!.mkdirs()
        compose.onNode(isDialog()).captureRoboImage(output.absolutePath)
        compose.onNode(hasText("保存") and hasClickAction()).assertIsEnabled().performClick()
        val store=TaskStore(File(compose.activity.filesDir,"tasks.json"))
        compose.waitUntil(10000){store.load().any{it.text=="拒绝麦克风也可以保存文字"}}
    }
    @Test fun rawFilesystemErrorNeverBecomesUserVisiblePath() {
        assertFalse(mediaFailureMessage(java.nio.file.FileSystemException("/data/user/0/example.jpg","/data/user/0/.temp","denied")).contains("/data"))
    }
}
