package cn.jiaoban;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Real Android emulator acceptance for the v0.24 native writing/navigation/swipe flow.
 *  Runs exclusively on an ephemeral, specifically named QA AVD; never on a user device. */
public class V024RegressionProbe extends Instrumentation {
    private final JSONArray checks=new JSONArray();
    private File output;
    private final StringBuilder events=new StringBuilder();
    private String failure="";

    @Override public void onCreate(Bundle args){super.onCreate(args);start();}

    private String shell(String command)throws Exception{
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))){
            return new String(in.readAllBytes(),StandardCharsets.UTF_8).trim();
        }
    }
    private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> nodes){
        if(node==null)return;
        nodes.add(node);
        for(int i=0;i<node.getChildCount();i++)collect(node.getChild(i),nodes);
    }
    private List<AccessibilityNodeInfo> nodes(){
        List<AccessibilityNodeInfo> found=new ArrayList<>();
        getUiAutomation().clearCache();
        for(AccessibilityWindowInfo window:getUiAutomation().getWindows()){
            AccessibilityNodeInfo root=window.getRoot();
            if(root!=null && "cn.jiaoban".contentEquals(root.getPackageName())){collect(root,found);return found;}
        }
        collect(getUiAutomation().getRootInActiveWindow(),found);
        return found;
    }
    private Set<String> labels(){
        Set<String> names=new HashSet<>();
        for(AccessibilityNodeInfo n:nodes()){
            if(n.getText()!=null)names.add(n.getText().toString());
            if(n.getContentDescription()!=null)names.add(n.getContentDescription().toString());
        }
        return names;
    }
    private boolean contains(String name){
        for(String v:labels())if(v.contains(name))return true;
        return false;
    }
    private void until(String name)throws Exception{
        long end=SystemClock.elapsedRealtime()+18000;
        while(SystemClock.elapsedRealtime()<end){
            if(contains(name))return;
            SystemClock.sleep(180);
        }
        throw new AssertionError("Expected "+name+", got "+labels());
    }
    private AccessibilityNodeInfo find(String label){
        for(AccessibilityNodeInfo node:nodes()){
            if(label.contentEquals(node.getText()==null?"":node.getText()) ||
                label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        }
        return null;
    }
    private void tap(String label)throws Exception{
        for(int attempts=0;attempts<8;attempts++){
            AccessibilityNodeInfo found=find(label);
            for(AccessibilityNodeInfo node=found;node!=null;node=node.getParent()){
                if(node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                    SystemClock.sleep(280);return;
                }
            }
            SystemClock.sleep(250);
        }
        throw new AssertionError("Cannot click "+label+", visible "+labels());
    }
    private void type(String value)throws Exception{
        for(AccessibilityNodeInfo n:nodes())if(n.isEditable()){
            Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
            if(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){
                SystemClock.sleep(250);return;
            }
        }
        throw new AssertionError("No editable input: "+labels());
    }
    private void check(String label,boolean passed)throws Exception{
        checks.put(new JSONObject().put("name",label).put("passed",passed));
        Bundle status=new Bundle();status.putString("stream",(passed?"PASS ":"FAIL ")+label+"\n");sendStatus(0,status);
        events.append((passed?"PASS ":"FAIL ")).append(label).append('\n');
        if(!passed)throw new AssertionError(label+" | "+labels());
    }
    private JSONArray stored()throws Exception{
        return new JSONArray(new String(Files.readAllBytes(new File(getTargetContext().getFilesDir(),"tasks.json").toPath()),StandardCharsets.UTF_8));
    }
    private JSONObject record(String value)throws Exception{
        JSONArray rows=stored();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.getJSONObject(i);
            if(value.equals(r.optString("text")))return r;
        }
        return null;
    }
    private void saved(String value)throws Exception{
        for(int i=0;i<50;i++){
            if(record(value)!=null)return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Not saved: "+value);
    }
    private void shot(String name)throws Exception{
        SystemClock.sleep(400);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        if(bitmap==null)throw new AssertionError("Screenshot failed");
        try(FileOutputStream target=new FileOutputStream(new File(output,name+".png"))){
            bitmap.compress(Bitmap.CompressFormat.PNG,100,target);
        }finally{bitmap.recycle();}
    }
    private void swipe(String label,float span)throws Exception{
        AccessibilityNodeInfo node=find(label);
        if(node==null)throw new AssertionError("Swipe target missing: "+label);
        Rect box=new Rect();node.getBoundsInScreen(box);
        float screenWidth=getTargetContext().getResources().getDisplayMetrics().widthPixels;
        float x0=screenWidth*.82f;
        float y=box.centerY();
        float x1=x0-screenWidth*span;
        long start=SystemClock.uptimeMillis();
        inject(start,start,MotionEvent.ACTION_DOWN,x0,y);
        for(int i=1;i<=20;i++){
            long time=start+i*25L;
            inject(start,time,MotionEvent.ACTION_MOVE,x0+(x1-x0)*i/20f,y);
            SystemClock.sleep(25);
        }
        inject(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x1,y);
        SystemClock.sleep(800);
    }
    private void inject(long start,long event,int action,float x,float y){
        MotionEvent m=MotionEvent.obtain(start,event,action,x,y,0);
        try{getUiAutomation().injectInputEvent(m,true);}finally{m.recycle();}
    }

    @Override public void onStart(){
        int result=0;
        try{
            String avd=shell("getprop ro.boot.qemu.avd_name");
            if(!avd.startsWith("Shiye_024_") || !"1".equals(shell("getprop ro.kernel.qemu")))
                throw new IllegalStateException("Only dedicated QA emulator allowed: "+avd);
            AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
            output=new File(getTargetContext().getExternalFilesDir(null),"v024-qa");output.mkdirs();

            JSONArray seed=new JSONArray();
            seed.put(new JSONObject().put("id","legacy").put("kind","TODO")
                .put("text","旧版事项原文\n完整保留 🌿").put("from","合成来源")
                .put("state","DONE").put("createdAt","2026-10-01T00:00:00Z"));
            seed.put(new JSONObject().put("id","n1").put("kind","NOTE")
                .put("text","这是一条可滑动的旧记录").put("state","OPEN")
                .put("createdAt","2026-10-18T00:00:00Z"));
            Files.write(new File(getTargetContext().getFilesDir(),"tasks.json").toPath(),seed.toString().getBytes(StandardCharsets.UTF_8));
            Intent launch=getTargetContext().getPackageManager().getLaunchIntentForPackage("cn.jiaoban");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getTargetContext().startActivity(launch);
            until("这是一条可滑动的旧记录");
            check("v0.24 notes and diary navigation",contains("记录")&&contains("日记")&&!contains("待办"));
            shot("01-notes");
            swipe("这是一条可滑动的旧记录",.16f);
            check("Short left swipe rebounds",record("这是一条可滑动的旧记录").isNull("deletedAt") || !record("这是一条可滑动的旧记录").has("deletedAt"));
            swipe("这是一条可滑动的旧记录",.70f);
            JSONObject removed=record("这是一条可滑动的旧记录");
            check("Single left swipe immediately moves to trash",removed!=null && !removed.isNull("deletedAt"));
            tap("撤销");
            check("Undo preserves old text",record("这是一条可滑动的旧记录").isNull("deletedAt"));

            tap("写记录");until("记录正文");
            boolean keyboard=false;
            for(int i=0;i<20 && !keyboard;i++){
                for(AccessibilityWindowInfo window:getUiAutomation().getWindows()){
                    if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)keyboard=true;
                }
                if(!keyboard && shell("dumpsys input_method").contains("mInputShown=true"))keyboard=true;
                if(!keyboard)SystemClock.sleep(250);
            }
            check("One tap opens native Android keyboard",keyboard);
            String body="新的一页\n完整的第二段 🌿";
            type(body);shot("02-editor-ime");tap("完成");saved(body);
            check("One-tap writing persists multiline Unicode",record(body).getString("kind").equals("NOTE"));
            check("Existing legacy row remains unchanged",record("旧版事项原文\n完整保留 🌿").getString("from").equals("合成来源"));

            tap("搜索");type("新的一页");until("新的一页");
            check("Offline search finds new body",contains("新的一页"));shot("03-search");
            tap("取消");tap("日记");until("写日记");shot("04-diary");
            tap("写日记");until("记录正文");
            String diary="今天的日记\n第二自然段";type(diary);tap("完成");saved(diary);
            check("Journal date persists",LocalDate.now().toString().equals(record(diary).getString("journalDate")));
            shot("05-diary-saved");

            shell("settings put system font_scale 2.0");shell("wm size 640x1600");shell("wm density 320");
            SystemClock.sleep(900);shot("06-large-font");
            check("320dp 200-percent scale keeps navigation",contains("记录")&&contains("日记"));
            result=-1;
        }catch(Throwable exception){
            failure=exception.toString();
            try{if(output!=null)shot("failure");}catch(Throwable ignored){}
        }finally{
            try{
                shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");
                if(output!=null){
                    JSONObject report=new JSONObject().put("passed",result==-1).put("failure",failure).put("checks",checks);
                    Files.write(new File(output,"results.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
                    Files.write(new File(output,"instrument.log").toPath(),events.toString().getBytes(StandardCharsets.UTF_8));
                }
            }catch(Throwable e){result=0;failure+="; finalization "+e;}
            Bundle finish=new Bundle();finish.putString("stream",(result==-1?"V024_PASS ":"V024_FAIL ")+failure+"\n");
            finish(result,finish);
        }
    }
}
