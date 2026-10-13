# 交办第二轮改版交接（Issue #15）

用户原话“还有不足，我觉得太简陋了”，确认界面与使用流程两者都简单，具体跟进选项回答“我不知道”。已确认主要用途仍是别人交代给我，快速记下、每天看事、办完留记录。开始办→等回复→时间化跟进→办完保留过程是代理推荐的可纠正默认，不能伪装为用户逐项明确指定。

范围：紧凑首页、阅读详情与编辑分开、进行中和等回复筛选、带时间的进展/处理记录、按人及真实处理日期历史、保存失败重试、正文和进展草稿保护。保留原到期策略、全部原文、系统分享、搜索、日历与设备授权更新。非目标：步骤/附件/通知/优先级/团队/同步/导出，#2/#3/#5 独立。

唯一主写手/整合者当前 Codex。工作区 D:/Workspaces/Codex/2026-10-13/jia/work/repos/Jiaoban/codex-ui-workflow-redesign，分支 agent/codex/refined-followup-v010，核对基线 main feed71e68ca1f9ff6a04ecc03aa15a7c8866d33d。canonical D:/Projects/Jiaoban 的未知0.7草稿保留，不清理、不覆盖、不reset。OpenDesign 独立项目两次上游连接中断，无可用设计产物；未切模型或充值，现由主写手原生实现与实际截图迭代。

0.10/versionCode10 候选，114项单测、最终正式包原生回归、0.8及0.9同签名覆盖升级和真实更新取消/连续重试已通过。两组各7条旧记录文件哈希完全一致；保存失败重试、进展草稿保护和重启记录保持通过。新增字段为 workStage/followUps，原状态与期限独立，旧文件缺字段可读取、不主动写迁移。证据见 docs/acceptance/v010/README.md；不将早期失败或过时包当完成。下一步PR/CI/合并与私有发布资产回读。

源码指纹1c7feff60732c97d3c2136928863ea69935bb4b77315df25f16d0ca10e5fd4af；APK SHA256 8d3a95861b44cb0273ea416d5314223d457d3eac6e58ae649fb055afc4d14689，1,821,048字节，原签名保持。当前App源码已冻结，后续文档变更须核对指纹一致。

专用 AVD Jiaoban_Redesign_API36/API36/x86_64/AsiaShanghai，127.0.0.1:5591，ADB5043；只使用匿名夹具。证据必须匹配 sourceFingerprint/APK/probe和实际环境，探针仅androidTest。用户手机最后确认0.8，未连接；本人授权后的下载及系统安装真机路径未验收。

已发布0.9与原交付证据见 docs/acceptance/v09/README.md，Release https://github.com/h516df/Jiaoban/releases/tag/v0.9。不得覆盖该发布。0.10未完成发布前不得称手机已更新。回退保持同签名，保留数据，必要时发更高versionCode修复，不卸载或安装低版本。
