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
        super.onResume();GrowthSession.resume(this)
        // 后台过夜后刷新同一份日期状态，不重建列表、不重新读盘。
        today.value = LocalDate.now()
    }

    override fun onStop() {
        unregisterReceiver(dateChanges)
        super.onStop()
    }

    override fun onPause(){GrowthSession.pause();super.onPause()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared.value = if (savedInstanceState == null) extractShared(intent)
            else savedInstanceState.getString("pendingSharedText")
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                // 系统主题切换后同步状态栏/手势条图标，避免深色页面上仍显示黑字。
                window.decorView.post { WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                } }
            }
            JiaobanTheme {
                val store = remember { TaskStore(File(filesDir, "tasks.json")) }
                App(
                    store = store,
                    today = today.value,
                    sharedText = shared.value,
                    onSharedHandled = {
                        shared.value = null
                        // 已交给可恢复的界面草稿；系统重建不能再派发同一份分享。
                        intent?.removeExtra(Intent.EXTRA_TEXT)
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingSharedText", shared.value)
        super.onSaveInstanceState(outState)
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
