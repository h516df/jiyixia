package cn.jiaoban;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** 仅打进测试 APK。Java 原生探针可测试 R8 压缩的正式包，不依赖目标包的 Kotlin 运行库。 */
public class DateRegressionProbe extends Instrumentation {
    private boolean expectBroken, dataProbe;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        expectBroken = arguments != null && "true".equals(arguments.getString("expectBroken"));
        dataProbe = arguments != null && "true".equals(arguments.getString("dataProbe"));
        start();
    }
    private String shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            return out.toString("UTF-8").trim();
        }
    }
    private AccessibilityNodeInfo root() {
        if (android.os.Build.VERSION.SDK_INT >= 34) getUiAutomation().clearCache();
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo node = window.getRoot();
            if (node != null && "cn.jiaoban".contentEquals(node.getPackageName())) return node;
        }
        return getUiAutomation().getRootInActiveWindow();
    }
    private void visit(AccessibilityNodeInfo node, Set<String> labels) {
        if (node == null) return;
        if (node.getText() != null && !node.getText().toString().trim().isEmpty()) labels.add(node.getText().toString());
        for (int i = 0; i < node.getChildCount(); i++) visit(node.getChild(i), labels);
    }
    private Set<String> labels() {
        Set<String> result = new HashSet<>(); visit(root(), result); return result;
    }
    private Set<String> await(Predicate<Set<String>> condition) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            Set<String> seen = labels(); if (condition.test(seen)) return seen;
            SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        return labels();
    }
    private boolean contains(Set<String> seen, String part) {
        for (String text : seen) if (text.contains(part)) return true;
        return false;
    }
    private void launch() throws Exception {
        String output = shell("am start -W -n cn.jiaoban/.MainActivity");
        if (output.contains("Error:")) throw new IllegalStateException(output);
    }
    private Bundle message(String text) { Bundle bundle = new Bundle(); bundle.putString("stream", text + "\n"); return bundle; }
    private void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name + " | " + labels());
        sendStatus(0, message("PASS " + name));
    }
    @Override public void onStart() {
        if (dataProbe) {
            try {
                check("fixture AVD identity", "Jiaoban_Codex_API36".equals(shell("getprop ro.boot.qemu.avd_name")));
                byte[] bytes = Files.readAllBytes(new File(getTargetContext().getFilesDir(), "tasks.json").toPath());
                int count = new JSONArray(new String(bytes, StandardCharsets.UTF_8)).length();
                StringBuilder hash = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
                finish(-1, message("DATA_RECORDS_SHA256: " + hash + " count=" + count));
            } catch (Throwable failure) { finish(0, message("DATA_RECORDS_FAIL: " + failure.getMessage())); }
            return;
        }
        long originalTime = 0, began = 0;
        String autoTime = "1";
        int result = 0;
        Bundle outcome = message("DATE_REGRESSION_FAIL: unfinished");
        try {
            check("fixture AVD identity", "Jiaoban_Codex_API36".equals(shell("getprop ro.boot.qemu.avd_name")));
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
            originalTime = Long.parseLong(shell("date +%s")) * 1000;
            began = SystemClock.elapsedRealtime();
            autoTime = shell("settings get global auto_time");
            shell("settings put global auto_time 0");
            LocalDate today = LocalDate.now();
            JSONArray data = new JSONArray();
            String[] texts = {"昨天的测试记录", "当天的测试记录", "次日的测试记录"};
            for (int index = 0; index < texts.length; index++) {
                data.put(new JSONObject().put("id", "date-" + index).put("text", texts[index]).put("from", "王总")
                    .put("due", today.plusDays(index - 1).toString()).put("state", "OPEN")
                    .put("createdAt", Instant.now().toString()).put("closedAt", JSONObject.NULL));
            }
            Files.write(new File(getTargetContext().getFilesDir(), "tasks.json").toPath(), data.toString().getBytes(StandardCharsets.UTF_8));
            launch();
            Set<String> seen = await(s -> contains(s, "今天 1 件，过期 1 件"));
            check("original day groups", contains(seen, "今天 1 件，过期 1 件"));
            int pid = android.os.Process.myPid();
            shell("input keyevent KEYCODE_HOME"); launch();
            seen = await(s -> contains(s, "今天 1 件，过期 1 件"));
            check("same-day resume groups and process", contains(seen, "今天 1 件，过期 1 件") && android.os.Process.myPid() == pid);
            shell("input keyevent KEYCODE_HOME");
            shell("cmd alarm set-time " + (originalTime + 86400000));
            check("device advanced one day", LocalDate.now().equals(today.plusDays(1)));
            launch();
            seen = await(s -> contains(s, "今天 1 件，过期 2 件"));
            boolean correct = contains(seen, "今天 1 件，过期 2 件");
            check(expectBroken ? "broken baseline reproduced" : "next-day groups refreshed", expectBroken ? !correct : correct);
            check("process unchanged", android.os.Process.myPid() == pid);
            if (!expectBroken) {
                Bitmap bitmap = getUiAutomation().takeScreenshot();
                if (bitmap != null) try (FileOutputStream file = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), "date-probe-nextday.png"))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
                }
                shell("cmd alarm set-time " + (originalTime + SystemClock.elapsedRealtime() - began));
                seen = await(s -> contains(s, "今天 1 件，过期 1 件"));
                check("foreground date refresh", contains(seen, "今天 1 件，过期 1 件"));
            }
            result = -1; outcome = message("DATE_REGRESSION_PASS");
        } catch (Throwable failure) {
            outcome = message("DATE_REGRESSION_FAIL: " + failure.getMessage());
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            if (bitmap != null) try (FileOutputStream file = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), "date-probe-failure.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            } catch (Exception ignored) { }
        }
        finally {
            if (originalTime != 0) try {
                shell("cmd alarm set-time " + (originalTime + SystemClock.elapsedRealtime() - began));
                shell("settings put global auto_time " + autoTime);
            } catch (Exception failure) { result = 0; outcome = message("DATE_REGRESSION_FAIL: clock restore " + failure.getMessage()); }
        }
        finish(result, outcome);
    }
}
