package cn.jiaoban;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.*;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.function.Predicate;

/** Only in androidTest; verifies real Compose controls and persisted results on the task AVD. */
public class WritingRegressionProbe extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    private Bundle args;
    private File out;
    private long originalTime, began;
    private String autoTime = "1";
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    private String shell(String cmd) throws Exception {
        try(InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(cmd));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8").trim();
        }
    }
    private AccessibilityNodeInfo root() {
        if(Build.VERSION.SDK_INT>=33)getUiAutomation().clearCache();
        for(AccessibilityWindowInfo w : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo n = w.getRoot();
            if(n != null && "cn.jiaoban".contentEquals(n.getPackageName())) return n;
        }
        return getUiAutomation().getRootInActiveWindow();
    }
    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> all = new ArrayList<>(); collect(root(),all); return all;
    }
    private void collect(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> all) {
        if(n == null) return; all.add(n);
        for(int i=0;i<n.getChildCount();i++) collect(n.getChild(i),all);
    }
    private Set<String> labels() {
        Set<String> all = new HashSet<>();
        for(AccessibilityNodeInfo n:nodes()) {
            if(n.getText()!=null) all.add(n.getText().toString());
            if(n.getContentDescription()!=null) all.add(n.getContentDescription().toString());
        }
        return all;
    }
    private void waitFor(Predicate<Set<String>> test) throws Exception {
        long end = SystemClock.elapsedRealtime()+18000;
        do { if(test.test(labels())) return; SystemClock.sleep(150); } while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("UI timeout: "+labels());
    }
    private boolean hasPart(Set<String> all,String part) { return all.stream().anyMatch(s->s.contains(part)); }
    private void check(String name,boolean pass) throws Exception {
        checks.put(new JSONObject().put("name",name).put("passed",pass));
        Bundle b = new Bundle(); b.putString("stream",(pass?"PASS ":"FAIL ")+name+"\n"); sendStatus(0,b);
        if(!pass) throw new AssertionError(name+" | "+labels());
    }
    private void tap(String text) throws Exception {
        for(int attempt=0;attempt<18;attempt++) {
            for(AccessibilityNodeInfo n:nodes()) {
                if(text.contentEquals(n.getText()==null?"":n.getText()) ||
                    text.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())) {
                    for(AccessibilityNodeInfo p=n;p!=null;p=p.getParent()) {
                        if(p.isSelected() && java.util.Arrays.asList("记录","日记").contains(text)) return;
                        if(p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { SystemClock.sleep(250); return; }
                    }
                }
            }
            boolean moved=false;
            if(text.equals("全部")) {
                for(AccessibilityNodeInfo n:nodes()) if(n.isScrollable()) {
                    Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                    if(bounds.height()<bounds.width()/2) moved=n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) || moved;
                }
            } else moved=scrollForward(Arrays.asList("今天","以后","完成").contains(text));
            if(!moved) SystemClock.sleep(200); else SystemClock.sleep(200);
        }
        throw new AssertionError("Missing clickable "+text+" | "+labels());
    }
    private boolean scrollForward(boolean horizontal) {
        for(AccessibilityNodeInfo n:nodes()) if(n.isScrollable()) {
            Rect bounds=new Rect();n.getBoundsInScreen(bounds);
            if((bounds.height()<bounds.width()/2)==horizontal && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }
    private void reveal(String text) throws Exception {
        for(int i=0;i<15 && !labels().contains(text);i++) { scrollForward(false);SystemClock.sleep(200); }
        waitFor(s->s.contains(text));
    }
    private void setText(int index,String value) throws Exception {
        List<AccessibilityNodeInfo> inputs = new ArrayList<>();
        for(AccessibilityNodeInfo n:nodes()) if(n.isEditable()) inputs.add(n);
        if(index>=inputs.size()) throw new AssertionError("Missing editable "+index);
        Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        check("Set input "+index,inputs.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b));
        SystemClock.sleep(450);
    }
    private void calendarDay(LocalDate date) throws Exception {
        tap(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault())));
    }
    private void shareText(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,text).setClassName("cn.jiaoban","cn.jiaoban.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(intent);
    }
    private void hideKeyboard() throws Exception {
        // An accessibility IME window can linger after the keyboard has gone away.
        // Sending Back then dismisses the editor sheet and loses the test's draft.
        if(!shell("dumpsys input_method").contains("mInputShown=true")) return;
        for(AccessibilityWindowInfo w:getUiAutomation().getWindows()) if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            shell("input keyevent KEYCODE_BACK"); SystemClock.sleep(250); return;
        }
    }
    private void shot(String name) throws Exception {
        SystemClock.sleep(650); // Capture settled native transitions, not a mid-animation frame.
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if(bitmap==null) throw new AssertionError("No screenshot");
        try(FileOutputStream stream = new FileOutputStream(new File(out,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); }
    }
    private boolean lightStatusIcons() {
        Bitmap image=getUiAutomation().takeScreenshot();
        if(image==null) return false;
        int bright=0;
        for(int y=(int)(image.getHeight()*.01);y<(int)(image.getHeight()*.045);y++)
            for(int x=(int)(image.getWidth()*.04);x<(int)(image.getWidth()*.24);x++) {
                int pixel=image.getPixel(x,y);
                if(Color.red(pixel)>200 && Color.green(pixel)>200 && Color.blue(pixel)>200) bright++;
            }
        image.recycle(); return bright>50;
    }
    private JSONArray data() throws Exception {
        File file = new File(getTargetContext().getFilesDir(),"tasks.json");
        return file.isFile()?new JSONArray(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)):new JSONArray();
    }
    private JSONObject byText(String text) throws Exception {
        long end = SystemClock.elapsedRealtime()+4000;
        do {
            JSONArray all=data();
            for(int i=0;i<all.length();i++) if(text.equals(all.getJSONObject(i).getString("text"))) return all.getJSONObject(i);
            SystemClock.sleep(100);
        } while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Not persisted: "+text);
    }
    private JSONObject byId(String id) throws Exception {
        JSONArray all=data();
        for(int i=0;i<all.length();i++) if(id.equals(all.getJSONObject(i).getString("id"))) return all.getJSONObject(i);
        throw new AssertionError("Missing id "+id);
    }
    private void persisted(String id,String key,String expected) throws Exception {
        long end=SystemClock.elapsedRealtime()+5000;
        do { if(expected.equals(byId(id).optString(key))) { check("Persist "+id+" "+key+"="+expected,true); return; } SystemClock.sleep(100); }
        while(SystemClock.elapsedRealtime()<end);
        check("Persist "+id+" "+key+"="+expected,false);
    }

    private void seed() throws Exception {
        JSONArray all=new JSONArray();
        all.put(new JSONObject().put("id","legacy").put("kind","TODO").put("text","旧版事项原文\n完整保留 🌿").put("from","合成来源").put("state","DONE").put("createdAt","2026-10-01T00:00:00Z").put("closedAt","2026-10-02T00:00:00Z").put("expiryPolicy","EXPIRES").put("due","2026-10-02").put("important",true).put("steps",new JSONArray().put(new JSONObject().put("id","s").put("text","原步骤").put("done",true))));
        all.put(new JSONObject().put("id","n1").put("kind","NOTE").put("text","留一点时间给喜欢的事\n散步、读书，以及认真做一顿晚饭。").put("pinned",true).put("state","OPEN").put("createdAt","2026-10-18T00:00:00Z"));
        all.put(new JSONObject().put("id","n2").put("kind","NOTE").put("text","读到的一句话\n把日子过得缓慢一点，细节就会浮现。").put("state","OPEN").put("createdAt","2026-10-17T00:00:00Z"));
        all.put(new JSONObject().put("id","d1").put("kind","NOTE").put("text","雨停之后\n窗外的树叶被洗得很亮。回家时买了一小束花。").put("journalDate",LocalDate.now().toString()).put("state","OPEN").put("createdAt","2026-10-19T00:00:00Z"));
        Files.write(new File(getTargetContext().getFilesDir(),"tasks.json").toPath(),all.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Override public void onStart() {
        int result=0;String error="";JSONObject report=new JSONObject();
        try {
            String avd=shell("getprop ro.boot.qemu.avd_name");
            if (avd.isEmpty()) avd=shell("getprop ro.kernel.qemu.avd_name");
            if (avd.isEmpty()) avd=shell("getprop qemu.avd_name");
            if(!avd.startsWith("Shiye_") || !"1".equals(shell("getprop ro.kernel.qemu"))) throw new IllegalStateException("Only disposable Shiye_ emulator allowed, got "+avd);
            AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
            out=new File(getTargetContext().getExternalFilesDir(null),"writing-native");out.mkdirs();
            seed();String old=byId("legacy").toString();
            Intent launch=getTargetContext().getPackageManager().getLaunchIntentForPackage("cn.jiaoban").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(launch);
            waitFor(x->hasPart(x,"留一点时间给喜欢的事"));
            check("Notes home and no task navigation",labels().containsAll(Arrays.asList("拾页","记录","日记","记下")) && !labels().contains("待办"));shot("notes-light");
            tap("随手记录正文");setText(0,"键盘打开时的第一条 #随手记");
            long keyboardEnd=SystemClock.elapsedRealtime()+5000;
            while(!shell("dumpsys input_method").contains("mInputShown=true") && SystemClock.elapsedRealtime()<keyboardEnd)SystemClock.sleep(100);
            check("Real soft keyboard is visible",shell("dumpsys input_method").contains("mInputShown=true"));
            Rect keyboard=new Rect();
            for(AccessibilityWindowInfo w:getUiAutomation().getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)w.getBoundsInScreen(keyboard);
            boolean saveAbove=false,inputAbove=false;
            for(AccessibilityNodeInfo n:nodes()){
                Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                if("记下".contentEquals(n.getText()==null?"":n.getText()))saveAbove=n.isVisibleToUser() && bounds.bottom<=keyboard.top;
                if("随手记录正文".contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))inputAbove=n.isVisibleToUser() && bounds.bottom<=keyboard.top;
            }
            check("Composer and save action remain above IME",keyboard.top>0 && saveAbove && inputAbove);shot("quick-keyboard");
            tap("记下");byText("键盘打开时的第一条 #随手记");setText(0,"紧接着的第二条");tap("记下");byText("紧接着的第二条");
            check("Consecutive keyboard records persist independently",byText("键盘打开时的第一条 #随手记").getString("id").equals(byText("紧接着的第二条").getString("id"))==false);hideKeyboard();
            tap("日记");waitFor(x->hasPart(x,"雨停之后"));check("Journal date timeline",hasPart(labels(),"今日小结"));shot("diary-light");
            tap("写日记");waitFor(x->x.contains("保存"));setText(0,"新的日记\n完整第二行和表情 🌿");hideKeyboard();tap("保存");
            JSONObject diary=byText("新的日记\n完整第二行和表情 🌿");check("Diary persists as NOTE with date",diary.getString("kind").equals("NOTE") && diary.getString("journalDate").equals(LocalDate.now().toString()) && diary.isNull("due"));
            tap("新的日记\n完整第二行和表情 🌿");waitFor(x->hasPart(x,"完整第二行"));shot("diary-reading");tap("编辑");setText(0,"新的日记\n改写后完整保留 🌿");hideKeyboard();tap("保存");check("Diary edits preserve date",byText("新的日记\n改写后完整保留 🌿").getString("journalDate").equals(LocalDate.now().toString()));
            tap("归档");tap("确定");persisted(diary.getString("id"),"state","VOID");tap("恢复");tap("确定");persisted(diary.getString("id"),"state","OPEN");tap("返回");
            tap("记录");tap("搜索");setText(0,"细节");hideKeyboard();waitFor(x->hasPart(x,"读到的一句话"));check("Body search filters notes",!hasPart(labels(),"留一点时间给喜欢的事"));tap("取消");
            setText(0,"未保存的想法 🌿");hideKeyboard();tap("日记");tap("记录");check("Tab switch preserves quick draft",labels().contains("未保存的想法 🌿"));
            shareText("新的分享内容");waitFor(x->x.contains("保存"));check("Incoming share opens separately",labels().contains("新的分享内容"));tap("关闭笔记");tap("放弃改动");check("Incoming share preserves quick draft",labels().contains("未保存的想法 🌿"));
            shell("cmd uimode night yes");SystemClock.sleep(700);waitFor(x->x.contains("未保存的想法 🌿"));check("Theme recreation preserves quick draft",labels().contains("记下"));shot("quick-draft-dark");tap("记下");byText("未保存的想法 🌿");
            shell("wm size 640x1600");shell("wm density 320");shell("settings put system font_scale 2.0");SystemClock.sleep(700);waitFor(x->x.contains("记下"));check("Narrow large text navigation remains reachable",labels().containsAll(Arrays.asList("记录","日记")));shot("notes-320dp-font200-dark");tap("日记");waitFor(x->x.contains("写日记"));shot("diary-320dp-font200-dark");
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");shell("cmd uimode night no");SystemClock.sleep(700);
            tap("设置");tap("旧版资料");tap("旧版事项原文\n完整保留 🌿");check("Legacy details read only",!labels().contains("编辑") && !labels().contains("办完了") && hasPart(labels(),"合成来源"));shot("legacy-read-only");
            check("Legacy fields preserved after all writes",byId("legacy").getString("text").equals("旧版事项原文\n完整保留 🌿") && byId("legacy").getJSONArray("steps").getJSONObject(0).getBoolean("done") && byId("legacy").getString("from").equals("合成来源"));
            report.put("recordCount",data().length()).put("sdk",Build.VERSION.SDK_INT).put("avd",avd);result=-1;
        } catch(Throwable failure){error=failure.toString();try{if(out!=null)shot("failure");}catch(Throwable ignored){}}
        finally {try {
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");shell("cmd uimode night no");
            report.put("passed",result==-1).put("error",error).put("checks",checks);
            if(out!=null)Files.write(new File(out,"results.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        }catch(Throwable failure){error+=" "+failure;result=0;}
        Bundle end=new Bundle();end.putString("stream",(result==-1?"WRITING_PASS":"WRITING_FAIL")+" "+error+"\n");finish(result,end);}
    }
}
