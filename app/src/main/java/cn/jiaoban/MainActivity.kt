package cn.jiaoban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import cn.jiaoban.data.TaskStore
import cn.jiaoban.ui.App
import cn.jiaoban.ui.JiaobanTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JiaobanTheme {
                val store = remember { TaskStore(File(filesDir, "tasks.json")) }
                App(store)
            }
        }
    }
}
