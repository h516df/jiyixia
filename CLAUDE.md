# 交办项目规则

通用规则来源：`D:/Projects/agent-rules/AGENTS.md`。这里只补项目特有信息。

- 产品按2026-10-18用户纠正扩展为待办、交办和笔记的安卓文字工具；沿用 Kotlin/Compose、本地 JSON，不添加账号/同步/后台提醒。
- Issue #15 第二轮：用户确认界面与流程都简陋，具体方案回答“不知道”；开始办/等回复/时间化跟进是代理给出的可纠正默认，不能写成用户逐项明确指定。办理阶段独立于 OPEN/DONE/VOID，关闭及恢复保留原进展。
- 旧 JSON 缺 kind/pinned 时保持 DELEGATED/false，笔记不参与任务期限；缺 workStage/followUps 默认未开始/空列表，旧原文及 expiryPolicy 不变；历史用 closedAt，缺处理时间不能拿 createdAt 冒充。撤销须核对预期快照，不能抹掉后续进展。
- 阅读详情与编辑分开；新分享不能静默覆盖正文或进展草稿。保存失败可见并重试当前快照。原生列表有横向筛选，验收探针区分横向与纵向滚动。
- Issue #12 用户决定：每条选择必须办完（逾期继续醒目显示）或到期失效（到期收起）。新建默认必须办完；旧 JSON 缺字段保留原到期失效行为，不能替用户改选择。
- 当前目标、未合并改动与验收边界见 HANDOFF.md 和关联 GitHub Issue/PR。
- 日期必须由 MainActivity 的可刷新状态传入所有页面，不能各页面缓存 LocalDate.now()。
- 过期区滚动只能使用实际列表索引，不能传 Int.MAX_VALUE。
- 所有分享和输入走 TasksHolder.addFromText，不得静默截断正文或拆开 emoji。
- 安卓验收使用本任务可丢弃 AVD，不在用户真机上运行会改种子记录/时钟的测试。
- 0.9 改版使用 scripts/android_redesign_acceptance.py 与独立 Jiaoban_Redesign_API36；原脚本针对 0.8 历史 UI。证据核对源码指纹、安装包和探针哈希；探针按完整日期语义点击日历，分享走原生 Intent，避免 shell 引号/换行破坏输入。
- Scaffold 新建按钮需要明确 zIndex，列表内容不应从无障碍控件树遮掉按钮。编辑未保存时拦截 SheetValue.Hidden，选择继续编辑须保持表单可见。
- 跟随系统深色模式时同步 WindowInsetsControllerCompat 的状态栏/手势栏图标颜色；真实截图须确认时间和电量可读，不能只检查 Compose 卡片。
- 日期原生探针只进入 androidTest APK；它恢复模拟器时间后结束。UiAutomation 节点需在变更后刷新缓存。
- 更新检查不复用已取消的 UpdateHttp；授权、下载和系统安装的真实用户步骤不能靠模拟授权冒充。
- 发布时标签查询可能不显示未发布草稿；先查有权限的发布列表，再按 release ID 读取原草稿。不要把标签 404 当成可以重复创建或覆盖的依据。

0.14/code14 的品牌显示名为有序，包名 cn.jiaoban、更新前缀与签名保持不变。成长页只从真实记录计算；NOTE不算办结任务，缺失closedAt不冒作本周完成。原生测试不要将残留IME窗口当成当前键盘显示，避免Back关闭编辑弹层。
