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
public class RedesignRegressionProbe extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    private Bundle args;
    private File out;
    private long originalTime, began;
    private String autoTime = "1";
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    private String shell(String cmd) throws Exception {
        try(InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(cmd));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            in.transferTo(bytes); return bytes.toString("UTF-8").trim();
        }
    }
    private AccessibilityNodeInfo root() {
        getUiAutomation().clearCache();
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
                        if(p.isSelected() && java.util.Arrays.asList("待办","笔记","设置","全部","今天","以后","完成","全部笔记","已归档").contains(text)) return;
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
    private void search(String text) throws Exception {
        home();
        if(labels().contains("返回记录")) tap("返回记录");
        if(labels().contains("取消")) tap("取消");
        tap("搜索全部记录"); waitFor(s->hasPart(s,"搜索待办"));
        setText(0,text); hideKeyboard(); waitFor(s->s.contains(text));
    }
    private void reader(String text) throws Exception { search(text); tap(text); waitFor(s->s.contains("事项详情")); }
    private void open(String text) throws Exception { reader(text); tap("编辑"); waitFor(s->s.contains("编辑待办")); }
    private void home() throws Exception {
        if(labels().contains("返回记录")) tap("返回记录");
        if(labels().contains("取消")) tap("取消");
        if(labels().contains("返回设置")) tap("返回设置");
        tap("待办");
        waitFor(s->s.contains("记一件"));
        tap("全部");
    }
    private void seed(LocalDate today) throws Exception {
        JSONArray all=new JSONArray();
        String[] ids={"must","today","later","expires","legacy","done","void"};
        String[] texts={"逾期活动表","今日周报","未来预算","到期活动名额","旧版到期记录","已完成记录","作废记录"};
        int[] days={-2,0,1,-1,-3,-1,-1};
        for(int i=0;i<ids.length;i++) {
            JSONObject t=new JSONObject().put("id",ids[i]).put("text",texts[i]).put("from",i<3?"王总":i<5?"李姐":"张工")
                .put("due",today.plusDays(days[i]).toString()).put("state",i==5?"DONE":i==6?"VOID":"OPEN")
                .put("createdAt","2026-10-01T00:00:00Z").put("closedAt",JSONObject.NULL);
            if(i!=4) t.put("expiryPolicy",i==3?"EXPIRES":"MUST_FINISH"); all.put(t);
        }
        Files.write(new File(getTargetContext().getFilesDir(),"tasks.json").toPath(),all.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Override public void onStart() {
        if(args!=null && "true".equals(args.getString("dataOnly"))) {
            try {
                if(!"Jiaoban_Redesign_API36".equals(shell("getprop ro.boot.qemu.avd_name"))) throw new IllegalStateException("Wrong AVD");
                if("true".equals(args.getString("seedLegacy")) || "true".equals(args.getString("seedFixture"))) {
                    seed(LocalDate.now());
                    JSONArray legacy=data();
                    if("true".equals(args.getString("seedLegacy"))) for(int i=0;i<legacy.length();i++) legacy.getJSONObject(i).remove("expiryPolicy");
                    Files.write(new File(getTargetContext().getFilesDir(),"tasks.json").toPath(),legacy.toString().getBytes(StandardCharsets.UTF_8));
                }
                byte[] bytes=Files.readAllBytes(new File(getTargetContext().getFilesDir(),"tasks.json").toPath());
                StringBuilder hash=new StringBuilder();
                for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x",b & 255));
                Bundle end=new Bundle(); end.putString("stream","DATA_SHA256: "+hash+" count="+new JSONArray(new String(bytes,StandardCharsets.UTF_8)).length()+"\\n");
                finish(-1,end);
            } catch(Throwable failure) { Bundle end=new Bundle(); end.putString("stream",failure.toString()); finish(0,end); }
            return;
        }
        int result=0; JSONObject report=new JSONObject(); String error="";
        try {
            check("Dedicated AVD","Jiaoban_Redesign_API36".equals(shell("getprop ro.boot.qemu.avd_name")));
            AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; getUiAutomation().setServiceInfo(info);
            out=new File(getTargetContext().getExternalFilesDir(null),"redesign-qa"); out.mkdirs();
            originalTime=Long.parseLong(shell("date +%s"))*1000; began=SystemClock.elapsedRealtime();
            autoTime=shell("settings get global auto_time");
            LocalDate today=LocalDate.now();
            seed(today);
            shell("am start -W -n cn.jiaoban/.MainActivity");
            waitFor(s->s.contains("记一件"));tap("待办");waitFor(s->s.contains("逾期活动表"));
            check("Home keeps must-finish overdue visible and folds expiring records",
                labels().contains("逾期活动表") && !labels().contains("到期活动名额") && !labels().contains("旧版到期记录"));
            check("Home does not open keyboard",getUiAutomation().getWindows().stream().noneMatch(w->w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD));
            shot("01-home");
            check("Two primary tools without delegated entry",labels().contains("笔记") && !labels().contains("交办"));
            tap("完成");waitFor(s->s.contains("已完成记录"));
            check("Completed tab excludes open tasks",!labels().contains("今日周报") && !labels().contains("逾期活动表"));
            check("Legacy completion without timestamp is not invented",byId("done").isNull("closedAt"));
            search("已完成"); waitFor(s->s.contains("已完成记录") && s.contains("共 1 件"));
            check("Global search includes closed records and excludes unrelated records",!labels().contains("今日周报") && !labels().contains("逾期活动表"));
            search("不存在的记录XYZ"); waitFor(s->s.contains("没有找到“不存在的记录XYZ”"));
            check("Search no-result state shows no unrelated records",!labels().contains("已完成记录") && !labels().contains("今日周报"));
            home();
            tap("今天");
            waitFor(s->s.contains("今日周报"));
            check("Today keeps unfinished overdue and excludes future",labels().contains("逾期活动表") && !labels().contains("未来预算"));
            tap("今天"); waitFor(s->s.contains("逾期活动表"));
            check("Overdue filter only keeps must-finish records",labels().contains("今日周报") && !labels().contains("到期活动名额"));
            tap("以后"); waitFor(s->s.contains("未来预算"));
            check("Later filter results",!labels().contains("逾期活动表") && !labels().contains("今日周报"));
            home();
            tap("到期失效 · 2 件"); reveal("旧版到期记录");
            check("Expired section retains legacy record",labels().contains("旧版到期记录"));
            reveal("到期活动名额");
            check("Expired section opens real records",labels().contains("到期活动名额")); shot("02-expired");
            open("到期活动名额"); tap("必须办完"); tap("保存修改"); persisted("expires","expiryPolicy","MUST_FINISH");
            home(); tap("今天"); waitFor(s->s.contains("到期活动名额"));
            check("Policy change returns expired item to overdue list",labels().contains("逾期活动表")); shot("03-overdue");

            home(); tap("记一件"); waitFor(s->s.contains("新建待办"));
            String manual="李姐说明天要提交活动表\n第一项：核对价格😀\n第二项：保留尾巴标记";
            setText(0,manual); hideKeyboard();
            check("Quick capture parses visible deadline without owner form",!hasPart(labels(),"谁交代的") && hasPart(labels(),"已识别期限"));
            tap("无期限"); tap("更多选项"); tap("到期失效"); shot("04-new");
            tap("记下"); waitFor(s->s.contains("记一件"));
            JSONObject added=byText(manual);
            check("Multiline Unicode input and manual metadata persisted",added.getString("from").isEmpty() && added.getString("kind").equals("TODO") &&
                added.isNull("due") && added.getString("expiryPolicy").equals("EXPIRES"));
            open("今日周报"); tap("选择日期"); waitFor(s->s.contains("确定日期"));
            calendarDay(today.withDayOfMonth(20)); tap("确定日期"); tap("保存修改");
            persisted("today","due",today.withDayOfMonth(20).toString());
            open("今日周报"); tap("选择日期"); calendarDay(today.withDayOfMonth(21)); tap("取消"); tap("关闭详情");
            persisted("today","due",today.withDayOfMonth(20).toString());
            check("Calendar cancel preserves chosen date",true);

            reader("逾期活动表");
            check("Detail opens reader rather than editable form",nodes().stream().noneMatch(n->n.isEditable()));
            tap("补充记录");tap("开始办"); persisted("must","workStage","WORKING");
            tap("等回复"); persisted("must","workStage","WAITING");
            tap("记进展"); waitFor(s->s.contains("记一条进展"));
            String note="活动表已发给王总，等确认\n价格已核对😀，编号ABC123";
            setText(0,note); hideKeyboard();
            shareText("新的分享不能覆盖进展"); waitFor(s->s.contains("收到新的分享")); tap("继续当前编辑");
            waitFor(s->s.contains(note)); check("Incoming share preserves unsaved follow-up",labels().contains("记一条进展"));
            tap("保存进展"); waitFor(s->s.contains(note));
            JSONArray events=byId("must").getJSONArray("followUps");
            check("Full note and progress events persist",events.length()==3 && events.getJSONObject(2).getString("text").equals(note));
            shot("11-detail-followup");
            search("ABC123"); waitFor(s->s.contains("逾期活动表") && s.contains("共 1 件"));
            check("Search finds preserved follow-up content",!labels().contains("今日周报"));
            reader("逾期活动表");tap("补充记录");tap("继续办理");persisted("must","workStage","WORKING");
            tap("标为重要");persisted("must","important","true");
            tap("添加步骤");setText(0,"核对价格");hideKeyboard();
            shareText("分享不能覆盖步骤");waitFor(x->x.contains("收到新的分享"));tap("继续当前编辑");
            check("Share interruption retains step draft",labels().contains("核对价格"));tap("保存步骤");
            waitFor(x->x.contains("核对价格"));
            check("Step text saved",byId("must").getJSONArray("steps").getJSONObject(0).getString("text").equals("核对价格"));
            tap("步骤：核对价格");
            SystemClock.sleep(350);
            check("Step completion saved",byId("must").getJSONArray("steps").getJSONObject(0).getBoolean("done"));
            reader("逾期活动表");
            check("Reopen preserves important and steps",labels().contains("已标重要") && labels().contains("核对价格"));
            check("Legacy source and kind remain intact",byId("must").getString("from").equals("王总") && byId("must").getString("kind").equals("DELEGATED"));
            shot("12-todo-steps");
            open("逾期活动表");setText(0,"逾期活动表（已核对）");hideKeyboard();tap("办完了");tap("撤销");persisted("must","state","OPEN");
            check("Undo completion preserves explicitly submitted edits",byId("must").getString("text").equals("逾期活动表（已核对）"));
            check("Undo retains steps important and source",byId("must").getBoolean("important") && byId("must").getJSONArray("steps").getJSONObject(0).getBoolean("done") && byId("must").getString("from").equals("王总"));
            open("逾期活动表（已核对）");setText(0,"逾期活动表");hideKeyboard();tap("保存修改");
            open("逾期活动表"); tap("办完了"); persisted("must","state","DONE");
            if(labels().contains("返回记录")) tap("返回记录");
            if(labels().contains("取消")) tap("取消");
            tap("设置");tap("历史与归档"); waitFor(s->s.contains("逾期活动表"));
            tap("已办完"); check("Done history filter excludes void records",!labels().contains("作废记录")); shot("05-history");
            tap("逾期活动表"); waitFor(s->s.contains("恢复为待办"));
            tap("恢复为待办"); persisted("must","state","OPEN");
            check("Restore retains follow-up and working stage",byId("must").getString("workStage").equals("WORKING") &&
                byId("must").getJSONArray("followUps").toString().contains("ABC123"));
            open("未来预算"); tap("作废这件事"); waitFor(s->s.contains("确认作废"));
            check("Void requires explicit confirmation",byId("later").getString("state").equals("OPEN"));
            tap("确认作废"); persisted("later","state","VOID");
            open("未来预算"); tap("恢复为待办"); persisted("later","state","OPEN");
            if(labels().contains("返回记录")) tap("返回记录");
            if(labels().contains("取消")) tap("取消");
            home();search("李姐");waitFor(x->x.contains("到期活动名额"));
            check("Old owner remains searchable without a people entry",!labels().contains("逾期活动表"));
            home();tap("设置");check("Removed people and delegated entries",!labels().contains("按交代人查看") && !labels().contains("交办"));

            home(); tap("记一件"); setText(0,"未保存草稿"); hideKeyboard();
            shareText("新的分享不能覆盖草稿");
            waitFor(s->s.contains("收到新的分享")); tap("继续当前编辑");
            waitFor(s->s.contains("未保存草稿"));
            check("Incoming share cannot silently replace an unsaved draft",labels().contains("未保存草稿"));
            tap("关闭详情");
            waitFor(s->s.contains("继续编辑")); tap("继续编辑");
            waitFor(s->s.contains("未保存草稿")); check("Continue editing preserves draft and keeps sheet visible",labels().contains("新建待办"));
            tap("关闭详情"); tap("放弃改动"); waitFor(s->s.contains("记一件"));
            check("Discard does not create record",data().length()==8);

            String shared="王总说"+ "活动规则与客户要求，".repeat(45)+"\n尾巴记号ZZZ😀，明天要提交汇总";
            shareText(shared);
            waitFor(s->s.contains("新建待办") && hasPart(s,"尾巴记号ZZZ"));
            hideKeyboard(); tap("更多选项"); tap("到期失效"); tap("记下"); waitFor(s->s.contains("记一件"));
            JSONObject share=byText(shared);
            check("Long real share confirms and saves full tail/person/deadline",share.getString("from").isEmpty() && share.getString("kind").equals("TODO") &&
                share.getString("due").equals(today.plusDays(1).toString()) && share.getString("expiryPolicy").equals("EXPIRES"));
            shot("07-shared-home");

            shell("settings put global auto_time 0");
            int pid=android.os.Process.myPid();
            shell("input keyevent KEYCODE_HOME");
            shell("cmd alarm set-time "+(originalTime+86400000));
            shell("am start -W -n cn.jiaoban/.MainActivity");
            waitFor(s->hasPart(s,(today.plusDays(1).getMonthValue())+"月"+today.plusDays(1).getDayOfMonth()+"日"));
            home(); tap("今天"); waitFor(s->s.contains("未来预算"));
            check("Same-process overnight resume refreshes actual today results",android.os.Process.myPid()==pid &&
                labels().contains("未来预算") && labels().contains("逾期活动表"));
            shell("cmd alarm set-time "+(originalTime+172800000));
            waitFor(s->hasPart(s,today.plusDays(2).getMonthValue()+"月"+today.plusDays(2).getDayOfMonth()+"日"));
            home(); tap("今天"); waitFor(s->s.contains("未来预算"));
            check("Foreground date change keeps must-finish overdue visible",labels().contains("未来预算"));
            shot("08-next-day");

            shell("cmd alarm set-time "+(originalTime+SystemClock.elapsedRealtime()-began));
            shell("settings put global auto_time "+autoTime);
            home(); shell("settings put system font_scale 1.3"); shell("wm size 900x2000");
            SystemClock.sleep(900); waitFor(s->s.contains("记一件"));
            check("Large-font narrow-screen navigation available",labels().containsAll(Arrays.asList("待办","笔记","设置")));
            shot("09-large-font");
            shell("settings put system font_scale 1.0"); shell("wm size reset");
            shell("cmd uimode night yes"); SystemClock.sleep(600); waitFor(s->s.contains("记一件"));
            long barEnd=SystemClock.elapsedRealtime()+7000;
            while(!lightStatusIcons() && SystemClock.elapsedRealtime()<barEnd) SystemClock.sleep(200);
            check("Dark status bar renders readable light time icons",lightStatusIcons()); shot("10-dark");
            check("Dark mode main path remains usable",labels().contains("记一件"));
            shell("cmd uimode night no");
            home();
            File blocked=new File(getTargetContext().getFilesDir(),"tasks.json.tmp");
            check("Inject recoverable save failure on owned fixture",!blocked.exists() && blocked.mkdir());
            try {
                tap("记一件"); setText(0,"保存失败后重试保留这件事"); hideKeyboard(); tap("无期限"); tap("记下");
                waitFor(s->s.contains("重试保存"));
                check("Save failure is visible and keeps prior disk data",!data().toString().contains("保存失败后重试保留这件事"));
                shot("12-save-error");
            } finally { if(blocked.isDirectory()) blocked.delete(); }
            tap("重试保存");
            check("Retry writes the same pending record",byText("保存失败后重试保留这件事").isNull("due"));
            waitFor(s->!s.contains("重试保存"));
            home();tap("待办");tap("记一件");waitFor(s->s.contains("新建待办"));setText(0,"自己的采购清单");hideKeyboard();tap("无期限");tap("记下");
            home();tap("以后");waitFor(s->s.contains("自己的采购清单"));check("Todo saves own kind without invented owner",byText("自己的采购清单").getString("kind").equals("TODO") && byText("自己的采购清单").getString("from").isEmpty());
            tap("笔记");tap("写笔记");waitFor(s->s.contains("新建笔记"));setText(0,"复盘标题\n今天完成选品，明天继续。😀");hideKeyboard();tap("保存笔记");
            waitFor(s->s.contains("复盘标题"));tap("复盘标题");waitFor(s->hasPart(s,"今天完成选品"));tap("置顶");
            check("Note preserves multiline and pin",byText("复盘标题\n今天完成选品，明天继续。😀").getBoolean("pinned"));shot("13-note-detail");
            tap("编辑");setText(0,"复盘标题\n补充完整文字与表情😀");hideKeyboard();tap("保存笔记");waitFor(s->hasPart(s,"补充完整文字"));
            tap("归档");tap("确定");tap("返回记录");tap("笔记");check("Archived note leaves active notes",!labels().contains("复盘标题"));
            tap("已归档");tap("复盘标题");tap("恢复笔记");tap("确定");
            check("Restore retains note kind and full text",byText("复盘标题\n补充完整文字与表情😀").getString("kind").equals("NOTE"));
            tap("返回记录");tap("笔记");tap("搜索全部记录");waitFor(x->x.contains("搜索笔记正文"));setText(0,"补充完整文字");hideKeyboard();
            waitFor(x->x.contains("复盘标题"));check("Notes search matches body and excludes tasks",!labels().contains("逾期活动表"));tap("取消");
            tap("写笔记");setText(0,"独立笔记草稿😀");hideKeyboard();
            shareText("另一份分享");waitFor(x->x.contains("收到新的分享"));tap("继续当前编辑");
            check("Incoming share retains full screen note draft",labels().contains("独立笔记草稿😀") && labels().contains("保存笔记"));
            shell("cmd uimode night yes");SystemClock.sleep(700);
            check("Configuration recreation preserves draft without replaying handled share",labels().contains("独立笔记草稿😀") && labels().contains("保存笔记") && !labels().contains("收到新的分享"));
            shell("cmd uimode night no");SystemClock.sleep(700);
            check("Note editor has no task deadline or completion",!labels().contains("期限") && !labels().contains("办完了"));shot("18-note-editor");
            tap("关闭笔记");tap("继续编辑");check("Continue preserves full screen draft",labels().contains("独立笔记草稿😀"));
            tap("关闭笔记");tap("放弃改动");
            tap("写笔记");setText(0,"素材清单\n- [ ] 核对\n- [ ] 核对\n尾巴😀");hideKeyboard();tap("保存笔记");tap("素材清单");
            tap("清单第2行：核对");
            SystemClock.sleep(350);
            check("Note checklist updates only one duplicate row",byText("素材清单\n- [x] 核对\n- [ ] 核对\n尾巴😀").getString("kind").equals("NOTE"));
            shot("20-note-checklist");
            home();search("素材清单");waitFor(x->hasPart(x,"没有找到"));check("Todo search excludes notes",hasPart(labels(),"没有找到“素材清单”") && !hasPart(labels(),"尾巴😀"));
            home();reader("自己的采购清单");tap("办完了");
            home();tap("设置");tap("最近做成的事");waitFor(x->x.contains("自己的采购清单"));
            check("Review shows actual completed task",labels().contains("自己的采购清单") && !labels().contains("素材清单"));
            check("Review has no level goal streak pressure",!hasPart(labels(),"等级") && !hasPart(labels(),"本周小目标") && !hasPart(labels(),"连续"));shot("14-review");
            tap("日常纪念");waitFor(x->x.contains("第一步完成"));
            check("Completed record earns a secondary keepsake",labels().contains("第一步完成"));
            tap("第一步完成");waitFor(x->hasPart(x,"完成事项 1 件"));tap("知道了");shot("15-keepsakes");
            android.content.SharedPreferences progress=getTargetContext().getSharedPreferences("growth_v1",0);
            long earnedAt=progress.getLong("badge_done_1",0);check("Keepsake is persisted",earnedAt>0);
            tap("返回");tap("返回");tap("最近做成的事");
            check("Reopening never duplicates keepsake",progress.getLong("badge_done_1",0)==earnedAt);
            tap("自己的采购清单");tap("恢复为待办");home();tap("设置");tap("最近做成的事");
            check("Restored task leaves completed review",!labels().contains("自己的采购清单"));
            shell("cmd uimode night yes");SystemClock.sleep(650);
            check("Theme recreation does not reopen consumed share",!labels().contains("收到新的分享"));shot("16-review-dark");
            shell("wm size 720x1600");shell("wm density 320");shell("settings put system font_scale 1.3");SystemClock.sleep(700);
            tap("日常纪念");waitFor(x->x.contains("第一步完成"));
            check("Small dark large text keeps actual keepsakes reachable",labels().contains("第一步完成"));shot("17-review-large-font");
            tap("返回");tap("返回");home();tap("笔记");tap("写笔记");setText(0,"小屏笔记\n正文末尾必须可编辑😀");hideKeyboard();
            check("Small dark note editor retains body and save",labels().contains("保存笔记") && hasPart(labels(),"正文末尾必须可编辑"));shot("19-note-small-dark");tap("保存笔记");
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");shell("cmd uimode night no");SystemClock.sleep(500);
            home();tap("设置");tap("从最近任务中隐藏");
            check("Hide recents persists and changes task flag",progress.getBoolean("hide_recents",false) && getTargetContext().getSystemService(android.app.ActivityManager.class).getAppTasks().stream().anyMatch(t->(t.getTaskInfo().baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)!=0));
            tap("从最近任务中隐藏");check("Recent task can be restored",!progress.getBoolean("hide_recents",true));
            report.put("version",getTargetContext().getPackageManager().getPackageInfo("cn.jiaoban",0).versionName);
            report.put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("timezone",ZoneId.systemDefault().toString());
            report.put("recordCount",data().length()).put("sourceFingerprint",args==null?"":args.getString("sourceFingerprint",""));
            result=-1;
        } catch(Throwable failure) {
            error=failure.toString();
            try { if(out!=null) shot("failure"); } catch(Throwable ignored) {}
        } finally {
            try {
                if(originalTime>0) { shell("cmd alarm set-time "+(originalTime+SystemClock.elapsedRealtime()-began)); shell("settings put global auto_time "+autoTime); }
                shell("settings put system font_scale 1.0"); shell("wm size reset"); shell("wm density reset"); shell("cmd uimode night no");
                report.put("passed",result==-1).put("error",error).put("checks",checks);
                if(out!=null) Files.write(new File(out,"results.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch(Throwable failure) { error+=" report: "+failure; result=0; }
            Bundle end=new Bundle(); end.putString("stream",(result==-1?"REDESIGN_PASS":"REDESIGN_FAIL")+" "+error+"\n"); finish(result,end);
        }
    }
}
