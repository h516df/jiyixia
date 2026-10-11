package cn.jiaoban

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cn.jiaoban.data.TaskStore
import cn.jiaoban.ui.App
import cn.jiaoban.ui.JiaobanTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** 从别处（微信、浏览器）分享进来的一句话，交给界面去解析并存下来。 */
    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared.value = extractShared(intent)
        setContent {
            JiaobanTheme {
                val store = remember { TaskStore(File(filesDir, "tasks.json")) }
                App(
                    store = store,
                    sharedText = shared.value,
                    onSharedHandled = { shared.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shared.value = extractShared(intent)
    }

    private fun extractShared(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
