package cn.jiaoban

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

// One family of original, scalable emblems. Shapes carry the category, not emoji.
@Composable private fun MedalMark(unlocked:Boolean,modifier:Modifier=Modifier,key:String="done") {
    val dark=isSystemInDarkTheme()
    val gold=if(unlocked) Color(0xFFB18C4C) else if(dark) Color(0xFF737C86) else Color(0xFFB2B5BA)
    val enamel=if(unlocked) Color(0xFF344C61) else if(dark) Color(0xFF30353C) else Color(0xFFE6E8EB)
    val line=if(unlocked) Color(0xFFF5E7C9) else if(dark) Color(0xFF9CA4AC) else Color(0xFF7D8792)
    Canvas(modifier) {
        val c=Offset(size.width/2,size.height/2);val r=size.minDimension*.42f
        drawCircle(gold.copy(alpha=.12f),r*1.16f,c)
        val seal=Path().apply { for(i in 0..7) {
            val a=Math.toRadians(i*45.0-22.5);val x=c.x+r*kotlin.math.cos(a).toFloat();val y=c.y+r*kotlin.math.sin(a).toFloat()
            if(i==0)moveTo(x,y) else lineTo(x,y)
        };close() }
        drawPath(seal,enamel);drawPath(seal,gold,style=Stroke(r*.045f))
        drawCircle(gold.copy(alpha=.65f),r*.81f,c,style=Stroke(r*.018f))
        val stroke=r*.065f
        fun segment(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(line,Offset(c.x+x1*r,c.y+y1*r),Offset(c.x+x2*r,c.y+y2*r),stroke,StrokeCap.Round)
        when(key.substringBefore('_')) {
            "notes" -> {
                val book=Path().apply {moveTo(c.x,c.y-r*.38f);quadraticTo(c.x-r*.24f,c.y-r*.53f,c.x-r*.43f,c.y-r*.37f);lineTo(c.x-r*.43f,c.y+r*.3f);quadraticTo(c.x-r*.2f,c.y+r*.18f,c.x,c.y+r*.36f);quadraticTo(c.x+r*.2f,c.y+r*.18f,c.x+r*.43f,c.y+r*.3f);lineTo(c.x+r*.43f,c.y-r*.37f);quadraticTo(c.x+r*.24f,c.y-r*.53f,c.x,c.y-r*.38f)}
                drawPath(book,line,style=Stroke(stroke));segment(0f,-.36f,0f,.34f)
            }
            "visit","streak" -> {
                segment(0f,.42f,0f,-.3f)
                for(side in listOf(-1f,1f)) for(i in 0..2) {
                    val y=.2f-i*.23f
                    val leaf=Path().apply {moveTo(c.x,c.y+y*r);quadraticTo(c.x+side*r*.38f,c.y+(y+.04f)*r,c.x+side*r*.32f,c.y+(y-.24f)*r);quadraticTo(c.x+side*r*.04f,c.y+(y-.24f)*r,c.x,c.y+y*r)}
                    drawPath(leaf,line)
                }
            }
            "followup" -> {
                rotate(45f,c){drawRoundRect(line,Offset(c.x-r*.27f,c.y-r*.48f),androidx.compose.ui.geometry.Size(r*.54f,r*.65f),androidx.compose.ui.geometry.CornerRadius(r*.25f),style=Stroke(stroke));drawRoundRect(line,Offset(c.x-r*.27f,c.y-r*.05f),androidx.compose.ui.geometry.Size(r*.54f,r*.65f),androidx.compose.ui.geometry.CornerRadius(r*.25f),style=Stroke(stroke))}
            }
            else -> {
                val star=Path().apply {moveTo(c.x,c.y-r*.5f);lineTo(c.x+r*.14f,c.y-r*.13f);lineTo(c.x+r*.45f,c.y);lineTo(c.x+r*.14f,c.y+r*.13f);lineTo(c.x,c.y+r*.5f);lineTo(c.x-r*.14f,c.y+r*.13f);lineTo(c.x-r*.45f,c.y);lineTo(c.x-r*.14f,c.y-r*.13f);close()}
                drawPath(star,line)
            }
        }
    }
}

@Composable fun GrowthEntry(stats:GrowthStats,modifier:Modifier=Modifier) {
    val context=LocalContext.current;val store=remember{GrowthStore(context)}
    var opened by rememberSaveable{mutableStateOf(false)}
    val days=store.days;val streak=GrowthRules.streak(days,LocalDate.now())
    val metrics=stats.metrics+listOf(
        GrowthMetric("visit","使用天数",days.size.toLong(),listOf(3,7,30),listOf("初来相伴","一周相伴","月度同行"),"天"),
        GrowthMetric("streak","连续使用",streak.toLong(),listOf(3,7),listOf("渐入佳境","七日同行"),"天"))
    val badges=GrowthRules.badges(metrics)
    var earned by remember{mutableStateOf(store.earned())}
    LaunchedEffect(badges){earned=store.settle(badges)}
    Surface(onClick={opened=true},modifier=modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            MedalMark(true,Modifier.size(40.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stats.title,fontWeight=FontWeight.Medium)
                Text("${badges.count{it.id in earned}} 枚日常纪念",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("查看",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        }
    }
    if(opened) Dialog(onDismissRequest={opened=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val dark=isSystemInDarkTheme()
        SideEffect {
            val window=(view.parent as? DialogWindowProvider)?.window
            if(window!=null) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                WindowCompat.setDecorFitsSystemWindows(window,false)
                // Apply after Dialog updates its own window properties.
                view.post {
                    WindowCompat.getInsetsController(window,view).apply {
                        isAppearanceLightStatusBars=!dark
                        isAppearanceLightNavigationBars=!dark
                    }
                }
            }
        }
        GrowthDashboard(stats,badges,earned,store){opened=false}
    }
}

@Composable private fun GrowthDashboard(stats:GrowthStats,badges:List<GrowthBadge>,earned:Map<String,Long>,store:GrowthStore,onClose:()->Unit) {
    val today=LocalDate.now();val days=store.days
    val firstDate=Instant.ofEpochMilli(store.firstInstall).atZone(ZoneId.systemDefault()).toLocalDate()
    var goal by remember{mutableIntStateOf(store.goal(stats.weekGoals[1]))}
    var editGoal by remember{mutableStateOf(false)}
    var allBadges by rememberSaveable{mutableStateOf(false)}
    var timeline by rememberSaveable{mutableStateOf(false)}
    var information by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<GrowthBadge?>(null)}
    val won=badges.filter{it.id in earned}
    val dark=isSystemInDarkTheme()
    val paper=if(dark)Color(0xFF1B2027) else Color(0xFFFAF9F6)
    val ink=if(dark)Color(0xFFEDECE8) else Color(0xFF273545)
    val secondary=if(dark)Color(0xFFAEB6C0) else Color(0xFF64707B)
    val line=if(dark)Color(0xFF343D47) else Color(0xFFE7E5DF)
    val gold=if(dark)Color(0xFFD3B578) else Color(0xFF896B35)
    Column(Modifier.fillMaxSize().background(paper).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回",tint=ink)}
            Text(stats.title,Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center,color=ink)
            IconButton(onClick={information=true}){Icon(Icons.Rounded.Info,"说明",tint=secondary,modifier=Modifier.size(20.dp))}
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=28.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
            item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("日常积累",fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=ink)
                Text(if(won.isEmpty())"从一件小事开始" else "每件做过的小事，都在这里",fontSize=14.sp,color=secondary)
            }}
            item {Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("最近完成",fontSize=16.sp,fontWeight=FontWeight.Medium,color=ink)
                if(stats.recent.isEmpty())Text("办完一件事，就会留下一笔。",fontSize=14.sp,color=secondary)
                stats.recent.forEach {title->Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                    Icon(Icons.Rounded.Check,null,Modifier.size(20.dp),tint=gold)
                    Text(title,fontSize=15.sp,lineHeight=22.sp,color=ink,maxLines=2,overflow=TextOverflow.Ellipsis)
                }}
            }}
            item {Column {
                Surface(onClick={allBadges=!allBadges},color=Color.Transparent) {
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(allBadges)"收起徽章" else "徽章收藏",Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.Medium,color=ink)
                        Text("${won.size} / ${badges.size}",fontSize=13.sp,color=secondary)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=secondary,modifier=Modifier.size(22.dp))
                    }
                }
                AnimatedVisibility(!allBadges) {
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        (won.takeLast(3) + badges.filter{it.id !in earned}).distinctBy{it.id}.take(3).forEach{badge->
                            Surface(onClick={selected=badge},color=Color.Transparent,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)) {
                                Column(Modifier.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                    MedalMark(badge.id in earned,Modifier.size(52.dp),badge.id)
                                    Text(badge.title,fontSize=12.sp,color=secondary,textAlign=TextAlign.Center)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(allBadges) {
                    Column(Modifier.padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        badges.chunked(3).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            row.forEach{badge->Surface(onClick={selected=badge},color=Color.Transparent,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)) {
                                Column(Modifier.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                    MedalMark(badge.id in earned,Modifier.size(56.dp),badge.id)
                                    Text(badge.title,fontSize=12.sp,color=ink,textAlign=TextAlign.Center)
                                }
                            }}
                            repeat(3-row.size){Spacer(Modifier.weight(1f))}
                        }}
                    }
                }
            }}
            item {Column {
                HorizontalDivider(color=line)
                Surface(onClick={editGoal=true},color=Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(vertical=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("本周小目标",fontSize=16.sp,fontWeight=FontWeight.Medium,color=ink)
                            Text(if(stats.weekValue>=goal)"已达成，按自己的节奏来" else "${stats.weekValue} / $goal ${stats.weekUnit}",fontSize=13.sp,color=secondary)
                        }
                        Text("调整",fontSize=13.sp,color=gold)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=secondary,modifier=Modifier.size(22.dp))
                    }
                }
                HorizontalDivider(color=line)
                Surface(onClick={timeline=!timeline},color=Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(vertical=20.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(if(timeline)"收起成长记录" else "查看成长记录",fontSize=16.sp,fontWeight=FontWeight.Medium,color=ink)
                            Text("相伴 ${days.size} 天",fontSize=13.sp,color=secondary)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight,null,tint=secondary,modifier=Modifier.size(22.dp))
                    }
                }
                AnimatedVisibility(timeline) {
                    Column(Modifier.padding(top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text("最近 28 天",fontSize=15.sp,fontWeight=FontWeight.Medium,color=ink)
                        (27 downTo 0).toList().chunked(7).forEach{week->Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){week.forEach{ago->val date=today.minusDays(ago.toLong());val active=date in days
                            Box(Modifier.weight(1f).height(24.dp).background(if(active)gold else line,RoundedCornerShape(5.dp)).semantics{contentDescription="$date ${if(active)"使用过" else "未记录使用"}"})
                        }}}
                        Text("连续 ${GrowthRules.streak(days,today)} 天 · 累计 ${store.seconds/60} 分钟",fontSize=13.sp,color=secondary)
                        Text("本机初次安装 $firstDate",fontSize=12.sp,color=secondary)
                    }
                }
            }}
        }
    }
    if(editGoal) AlertDialog(onDismissRequest={editGoal=false},containerColor=paper,title={Text("本周小目标",color=ink)},text={Column {
        stats.weekGoals.forEach{value->Surface(onClick={goal=value;store.goal(value,true);editGoal=false},color=Color.Transparent) {
            Row(Modifier.fillMaxWidth().heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=goal==value,onClick=null)
                Text("$value ${stats.weekUnit}",Modifier.padding(start=12.dp),color=ink)
            }
        }}
    }},confirmButton={TextButton(onClick={editGoal=false}){Text("取消")}})
    selected?.let{badge->AlertDialog(onDismissRequest={selected=null},containerColor=paper,icon={MedalMark(badge.id in earned,Modifier.size(80.dp),badge.id)},title={Text(badge.title,color=ink)},text={Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(badge.detail,color=secondary)
        Text(if(badge.id in earned)"已收入收藏" else "${badge.current} / ${badge.target}",color=gold)
    }},confirmButton={TextButton(onClick={selected=null}){Text("知道了")}})}
    if(information) AlertDialog(onDismissRequest={information=false},title={Text("成长说明")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("平时使用就能收获徽章，拿到后一直保留。")
        Text("小目标每周一重新开始。不打卡，也不扣分。")
        Text("使用记录从新版开始累计，仅存在这台手机。换机或清除数据会丢失。")
    }},confirmButton={TextButton(onClick={information=false}){Text("知道了")}})
}

@Composable fun RecentTasksPreference() {
    val context=LocalContext.current;val store=remember{GrowthStore(context)}
    var hidden by remember{mutableStateOf(store.hidden)}
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).semantics{contentDescription="从最近任务中隐藏"}.toggleable(value=hidden,role=Role.Switch,onValueChange={hidden=it;store.hideRecents(it)}).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)){Text("从最近任务中隐藏");Text("桌面仍能打开；不影响系统省电限制",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(hidden,onCheckedChange=null)
    }
}
