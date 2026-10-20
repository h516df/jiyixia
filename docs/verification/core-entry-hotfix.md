# 正文、媒体与录音入口热修复

基于正式 0.18 的生产源码；不修改包名、数据库格式、原签名或现有记录。此候选不含独立的公开更新源 PR。

## 原因与改动

- 普通 Android App 的 SELinux 策略禁止 `file link`。原媒体导入和导入恢复依赖 `Files.createLink`，宿主 JVM 上通过不代表手机可用。改为完整写入、同步、同目录原子改名；两个附件写手共享进程内锁，已有哈希文件先校验不覆盖，不跨目录移动、不退回非原子复制。失败仅清本次临时文件，已发布附件可能已被另一草稿复用，不能回滚删除。
- 编辑器 `Scaffold.safeDrawing` 已提供 IME 边距，普通 `padding` 未消费又叠 `imePadding`。现在消费父边距，显式 resize 对话窗口；长错误/恢复提示在滚动区域，键盘打开时隐藏非必要底部说明，避免正文被挤掉。
- 权限回调可能在 Activity 恢复前到达。授权成功不再因尚未 RESUMED 被误报拒权；仅本次明确录音请求可等待恢复启动一次，离开页面取消待启动。真正拒权提供重试和系统设置入口，不妨碍文字与照片保存。
- 不向用户显示内部文件路径；错误保持可重试、原文件不变。

## 针对性验收

`CoreEntryUiTest` 使用真实 MainActivity/NoteEditor，覆盖文字输入、保存、逐行重开，系统 picker 返回合成 PNG 后实际复制与入库，权限回调先于 resume，拒权后仍可写，窄屏/200%字号和模拟 IME inset。不是用户手机或真实输入法截图。

`CoreMediaFixTest`、MediaStorage、RecordingJournal、VoiceCapture、ImportTransaction 定向测试覆盖原件、去重、并发首次目录建立、共享附件和失败保持旧库、恢复片段。测试素材均为自制，用户截图不进入仓库。

本轮仅宿主 Android/Compose 验证和开发包构建。真实麦克风编码、OEM 软键盘、系统选择器、实体设备回放与原签名覆盖升级需本地设备验收；不可把宿主 MediaRecorder shadow 当作真实录音成功。

## 参考与取舍

- [AOSP App neverallow](https://android.googlesource.com/platform/system/sepolicy/+/android16-qpr2-release/private/app_neverallows.te)：普通 App 不依赖硬链接，不改系统策略、不索取额外权限。
- [Android Insets](https://developer.android.com/develop/ui/compose/system/insets-ui) 与 [官方 Jetchat 输入布局](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt)：IME 由一处负责，父级 padding 必须正确消费。
- [运行时权限](https://developer.android.com/training/permissions/requesting)：在用户明确交互后请求，按真实授权结果而非回调瞬间页面状态处理。
- [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile) 与 [Files](https://developer.android.com/reference/java/nio/file/Files)：写完整、同步、同目录原子提交；不以删除原文件换取保存成功。
- [Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder)：参考简明离线录音交互；GPL-3.0，仅产品思路，不复制代码、不引入依赖。
