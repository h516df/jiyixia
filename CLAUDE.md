# 交办项目规则

通用规则来源：`D:/Projects/agent-rules/AGENTS.md`。这里只补项目特有信息。

- 产品是“谁交代了什么、有效期内别忘掉”的安卓记录本；沿用现有 Kotlin/Compose 和本地 JSON，不添加账号/同步/后台提醒。
- 当前目标、未合并改动与验收边界见 HANDOFF.md 和关联 GitHub Issue/PR。
- 日期必须由 MainActivity 的可刷新状态传入所有页面，不能各页面缓存 LocalDate.now()。
- 过期区滚动只能使用实际列表索引，不能传 Int.MAX_VALUE。
- 所有分享和输入走 TasksHolder.addFromText，不得静默截断正文或拆开 emoji。
- 安卓验收使用本任务可丢弃 AVD，不在用户真机上运行会改种子记录/时钟的测试。
- 日期原生探针只进入 androidTest APK；它恢复模拟器时间后结束。UiAutomation 节点需在变更后刷新缓存。
- 更新检查不复用已取消的 UpdateHttp；授权、下载和系统安装的真实用户步骤不能靠模拟授权冒充。
