package cn.jiaoban.update

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.jiaoban.ui.UpdateScreen
import cn.jiaoban.ui.JiaobanTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h900dp-xhdpi")
class PublicUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private fun runEntry(dark: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        context.resources.configuration.fontScale=2f
        ReadyUpdateStore(context).clear()
        UpdateTokenStore(context).clear()
        UpdateHttp.publicRetryAt=0
        val urls=java.util.Collections.synchronizedList(mutableListOf<String>())
        val updater=Updater(context,httpFactory={UpdateHttp({ url ->
            urls.add(url)
            object:HttpURLConnection(URL(url)) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy()=false
                override fun getResponseCode()=if(urls.size==1) 404 else 200
                override fun getInputStream()=ByteArrayInputStream(javaClass.getResource("/jiyixia-stable.json")!!.readBytes())
            }
        })})
        val end=System.currentTimeMillis()+5000
        while(updater.state is UpdateState.Restoring && System.currentTimeMillis()<end) {
            shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)
        }
        compose.setContent { JiaobanTheme { androidx.compose.material3.Surface { UpdateScreen(updater, PaddingValues(0.dp)) } } }
        compose.onNodeWithText("检查更新").performClick()
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); updater.state is UpdateState.Failed }
        compose.onNodeWithText("需要你授权一次").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); updater.state is UpdateState.Latest }
        compose.onNodeWithText("已是最新版本").assertIsDisplayed()
        compose.onNodeWithText("需要你授权一次").assertDoesNotExist()
        assertEquals(listOf(UpdateConfig.PUBLIC_MANIFEST,UpdateConfig.PUBLIC_MANIFEST),urls.toList())
        compose.onNodeWithText("再检查一次").performClick()
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); urls.size==3 && updater.state is UpdateState.Latest }
        val file=File("build/outputs/public-update-ui/${if(dark) "dark" else "light"}-320dp-font200.png")
        file.parentFile!!.mkdirs();compose.onRoot().captureRoboImage(file.absolutePath)
    }
    @Test fun noLoginEntryLight()=runEntry(false)
    @Test @Config(qualifiers="zh-rCN-w320dp-h900dp-night-xhdpi") fun noLoginEntryDark()=runEntry(true)
}
