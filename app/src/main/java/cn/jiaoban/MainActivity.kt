package cn.jiaoban

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.view.WindowInsetsControllerCompat
import cn.jiaoban.data.TaskStore
import cn.jiaoban.ui.App
import cn.jiaoban.ui.JiaobanTheme
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    /** 从别处（微信、浏览器）分享进来的一句话，交给界面去解析并存下来。 */
    private val shared = mutableStateOf<String?>(null)
    private val today = mutableStateOf(LocalDate.now())
    private val dateChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            today.value = LocalDate.now()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dateChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(dateChanges, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        // 后台过夜后刷新同一份日期状态，不重建列表、不重新读盘。
        today.value = LocalDate.now()
    }

    override fun onStop() {
        unregisterReceiver(dateChanges)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared.value = extractShared(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                // 系统主题切换后同步状态栏/手势条图标，避免深色页面上仍显示黑字。
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            JiaobanTheme {
                val store = remember { TaskStore(File(filesDir, "tasks.json")) }
                App(
                    store = store,
                    today = today.value,
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
