# 交办

记录别人交代给我的事情：快速记下，每天看该做什么，办完后留记录。

安装包和中文更新说明在 [最新发布版](https://github.com/h516df/Jiaoban/releases/latest)。现有用户从 **设置 → 检查更新** 获取新版，由系统安装界面覆盖升级；不要卸载或清数据。

## 日常使用

- 首页先看 **逾期、今天、接下来**，可以按筛选切换；打开不会自动弹键盘。
- 点 **记一件**，输入“王总让周五交活动表”。交代人和日期自动识别，保存前可以直接改；没有提期限时暂按三天后，可改日期或选无期限。
- 每件事情选择一种到期规则：**必须办完**，逾期后仍留在首页；**到期失效**，到期后自动收起，记录不会删除。
- 原来的记录保留原到期收起行为，可逐条打开改成必须办完。
- 点开先阅读完整交代、期限和办理过程，需要改原文、人或日期时再点“编辑”；长消息保留换行和 emoji。
- 详情可以“开始办”“等回复”“继续办理”；办理阶段不改变期限规则。首页可按进行中、等回复筛选。
- “记进展”补记已做的事或等谁回复，自动留时间；办完和恢复仍保留过程。搜索也包含跟进文字。
- 点勾号或详情中的“办完了”，收进历史，可撤销。作废有确认，也留在历史；两种记录均可恢复。
- 底部直接进入事情、按人、历史和设置。搜索包含待办、到期失效、办完和作废的所有记录。
- 接受 Android 系统文字分享，先预览并确认再保存。各应用的分享入口不同；没有系统分享入口时可以复制文字后粘贴到记事框。

## 数据与升级

记录保存在手机的本地 JSON 文件。先写临时文件再替换，保留一份备份。新增办理阶段和跟进字段兼容原文件，不删除旧记录或改变旧期限规则。保存失败时页面显示“重试保存”，保留当前待写入的改动。系统云备份和换机行为取决于手机设置，尚无手动导出和同步；参见待确认的 #2/#3/#5。

沿用 Kotlin/Jetpack Compose、Material 3、原有本地存储和 GitHub 设备授权更新，不新增运行时依赖。正式升级沿用原签名，versionCode 递增，下载后由现有更新模块校验包名、版本、大小、SHA256、签名和架构。

## 构建和真实验收

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest

python -X utf8 scripts\android_redesign_acceptance.py --serial <本任务设备> --adb-port <本任务端口>
python -X utf8 scripts\android_redesign_acceptance.py --serial <本任务设备> --adb-port <本任务端口> --apk app\build\outputs\apk\release\app-release.apk
python -X utf8 scripts\android_redesign_upgrade.py --serial <本任务设备> --adb-port <本任务端口> --old-apk <已发布的0.8或0.9正式包>
python -X utf8 scripts\android_update_cancel_acceptance.py --serial <本任务设备> --adb-port <本任务端口>
```

改版验收限本任务可丢弃的 `Jiaoban_Redesign_API36`，会替换测试数据并临时改变日期和显示配置，最后恢复，不可在用户手机运行。仅供测试的 Java 探针不进入成品 APK。检查实际点击后的筛选记录、保存后的字段、历史恢复、原生分享、跨天同一进程刷新、窄屏大字体、深色模式和重启记录哈希。

证据绑定源码/配置指纹、实际已安装 APK 的 SHA256、测试 APK 哈希与运行环境；`--reuse-evidence` 仅用于核对完全相同输入的已通过证据。0.8 的旧验收脚本保留用于历史版本，本轮请使用上述改版入口。

用户手机上的账号授权、私有 Release 下载和系统安装确认必须由本人进行，模拟器通过不能代替真机验收。
