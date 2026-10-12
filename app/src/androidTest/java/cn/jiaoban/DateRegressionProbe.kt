package cn.jiaoban

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** 仅进测试 APK：持续使用 Android 控件树，不靠键盘布局前的像素坐标。 */
class DateRegressionProbe : Instrumentation() {
    private var expectBroken = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectBroken = arguments?.getString("expectBroken") == "true"
        start()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }.trim()

    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
        return null
    }

    private fun root(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        return uiAutomation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == "cn.jiaoban" }
            ?: uiAutomation.rootInActiveWindow
    }

    private fun labels(): Set<String> {
        val labels = mutableSetOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root())
        return labels
    }

    private fun await(condition: (Set<String>) -> Boolean): Set<String> {
        val deadline = SystemClock.elapsedRealtime() + 15000
        do {
            val current = labels()
            if (condition(current)) return current
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < deadline)
        return labels()
    }

    private fun click(label: String): Boolean {
        var node = find(root()) { it.text?.toString() == label } ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun scroll() = find(root()) { it.isScrollable && it.className?.toString() != "android.widget.EditText" }
        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false

    private fun launch() {
        targetContext.startActivity(Intent().setClassName("cn.jiaoban", "cn.jiaoban.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun check(name: String, passed: Boolean) {
        check(passed) { name + " | " + labels().joinToString(" / ") }
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }

    override fun onStart() {
        var originalTime = 0L
        var began = 0L
        var autoTime = "1"
        var result = 0
        var outcome = Bundle()
        try {
            check("fixture AVD identity", shell("getprop ro.boot.qemu.avd_name") == "Jiaoban_Codex_API36")
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            originalTime = shell("date +%s").toLong() * 1000
            began = SystemClock.elapsedRealtime()
            autoTime = shell("settings get global auto_time")
            shell("settings put global auto_time 0")
            val today = LocalDate.now()
            val data = JSONArray()
            listOf("昨天的测试记录" to -1L, "当天的测试记录" to 0L, "次日的测试记录" to 1L).forEachIndexed { index, (text, days) ->
                data.put(JSONObject().put("id", "date-$index").put("text", text).put("from", "王总")
                    .put("due", today.plusDays(days).toString()).put("state", "OPEN")
                    .put("createdAt", Instant.now().toString()).put("closedAt", JSONObject.NULL))
            }
            File(targetContext.filesDir, "tasks.json").writeText(data.toString())
            launch()
            var seen = await { it.any { t -> "今天 1 件，过期 1 件" in t } }
            check("original day groups", seen.any { "今天 1 件，过期 1 件" in it })
            repeat(5) { if ("已过期 1 件" !in labels()) { scroll(); SystemClock.sleep(300) } }
            check("open overdue control", click("已过期 1 件"))
            repeat(5) { if ("收起" !in labels()) { scroll(); SystemClock.sleep(300) } }
            seen = await { "收起" in it && "昨天的测试记录" in it }
            check("overdue expanded", "收起" in seen && "昨天的测试记录" in seen)
            val pid = android.os.Process.myPid()
            shell("input keyevent KEYCODE_HOME")
            launch()
            seen = await { "收起" in it }
            check("same-day resume preserves expansion", "收起" in seen && android.os.Process.myPid() == pid)
            shell("input keyevent KEYCODE_HOME")
            shell("cmd alarm set-time ${originalTime + 86400000}")
            check("device advanced one day", LocalDate.now() == today.plusDays(1))
            launch()
            seen = await { it.any { t -> "今天 1 件，过期 2 件" in t } }
            val correct = seen.any { "今天 1 件，过期 2 件" in it }
            check(if (expectBroken) "broken baseline reproduced" else "next-day groups refreshed", if (expectBroken) !correct else correct)
            check("process unchanged", android.os.Process.myPid() == pid)
            if (!expectBroken) {
                check("expansion preserved across dates", "收起" in seen)
                val accessible = seen.toMutableSet()
                repeat(3) { scroll(); SystemClock.sleep(250); accessible.addAll(labels()) }
                check("both overdue records accessible", "昨天的测试记录" in accessible && "当天的测试记录" in accessible)
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(targetContext.getExternalFilesDir(null), "date-probe-nextday.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                shell("cmd alarm set-time ${originalTime + SystemClock.elapsedRealtime() - began}")
                seen = await { it.any { t -> "今天 1 件，过期 1 件" in t } }
                check("foreground date refresh", seen.any { "今天 1 件，过期 1 件" in it })
            }
            result = -1
            outcome = Bundle().apply { putString("stream", "DATE_REGRESSION_PASS\n") }
        } catch (failure: Throwable) {
            outcome = Bundle().apply { putString("stream", "DATE_REGRESSION_FAIL: ${failure.message}\n") }
        } finally {
            if (originalTime != 0L) {
                shell("cmd alarm set-time ${originalTime + SystemClock.elapsedRealtime() - began}")
                shell("settings put global auto_time $autoTime")
            }
        }
        finish(result, outcome)
    }
}
