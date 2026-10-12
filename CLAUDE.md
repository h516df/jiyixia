# 交办项目规则

通用规则来源：`D:/Projects/agent-rules/AGENTS.md`。这里只补项目特有信息。

- 产品是“别人交代给我的事，快速记下、每天看该做什么、办完后留记录”的安卓交办本；沿用 Kotlin/Compose、本地 JSON，不添加账号/同步/后台提醒。
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
