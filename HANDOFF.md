# 当前接续：0.21 整体改版与短/长录音（2026-10-31）

- **手机基线与边界**：用户手机实际是 **0.19**，不是 0.20。下一正式包必须使用 `cn.jiaoban`、原证书及递增版本码同签名覆盖，不能卸载、清资料或拿云端 debug APK 直接安装。原签名私钥只在 Windows `MY-AI-PC`，未转移到云端；DSH 主机离线，未触碰用户手机。
- **源码与正式发布**：整体改版及短/长录音 [PR #37](https://github.com/h516df/jiyixia/pull/37) 已 squash 合并至 `main`，合并提交 `0d7a60ab78e45ffd24151491693dc1a0004229fe`。v0.21 / versionCode 21 已作为 [正式 GitHub Release](https://github.com/h516df/jiyixia/releases/tag/v0.21) 发布（非 draft、非 prerelease，GitHub latest），v0.21 tag 精确指向此提交。未来 main 可能继续更新，但正式包与该 tag 固定对应。
- **界面/操作**：暖纸/松绿 Compose 主题，按日时间流，首页文字、图片视频、麦克风快捷入口；日记与旧资料保持，320dp/200% 字号重新排布。编辑器可直接打开系统媒体选择器，原草稿只在成功保存后清理。新全局录音与旧编辑器前台录音互斥，录音分段在阅读/编辑页合并播放，原文件可查看。
- **录音**：`VoiceRecordingService` 麦克风前台服务 + `VoiceSessionController` 持久草稿；用户可锁屏或离开应用，通知栏暂停/继续/结束。Android 13+ 请求可选通知权限，拒绝后仍可返回应用控制；仅录制时持有 partial wake lock。沿用 30 秒 M4A 分段、RecordingJournal、原媒体目录；`VoiceSessionStore` 同步落盘会话，停止后以稳定 ID 保存一条 NOTE，数据回调成功才清理草稿索引；异常保留已完成片段和原文件。没有改变旧 Task JSON 或导出格式。
- **最终云端验证**：JDK21 / SDK36 / Gradle9.1 一次性 `:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease -PunsignedRelease` **BUILD SUCCESSFUL**（129 tasks）；40 类、290 项 JVM 测试、0 fail/error/skip；lint 0 error、48 warnings、1 hint。debug APK SHA256 `a9ae9bb176aace5a5acb4b5fc443ba34080f8d73c30d4d80cacfbd8b4b659783`（仅开发签名）；unsigned release SHA256 `980dc3fd97646a41f7b3425a9909a72502c52b453fa6a430c0c17f48df5a103e`（未签名不可安装）。Robolectric/Roborazzi 已生成并目检亮/暗、小屏 320dp + 200% 字号、日记和编辑器截图；AndroidTest APK 只构建未在设备运行。
- **正式签名已核验（2026-10-31）**：从官方 [v0.19 Release](https://github.com/h516df/jiyixia/releases/tag/v0.19) 下载原 APK（SHA256 `54144a6d59ed5bef3ed96b0fa8169420f77df11b977badd374cfc7cf05eac7c6`），其 Android 证书 SHA256 为 `21b74e3e2a10e12da8284d4e6bd7d99e55b00a385215902e94d7896f7a46c967`。获用户明确许可后，对 Windows `MY-AI-PC` 既有 keystore 本地运行 `keytool`，证书 SHA256 **完全一致**；私钥未出 Windows。云端 0.21 unsigned APK SHA256 `980dc3fd97646a41f7b3425a9909a72502c52b453fa6a430c0c17f48df5a103e` 经分块传输到 Windows 后独立哈希复核一致，随后原证书签名生成 `Jiaoban-v0.21-universal.apk`（Windows 本地，SHA256 **`05520f7a256c198cd91ee6320de093e6334231c2fff1296a6a531916cb435619`**）。官方 Android `apksigner verify --verbose --print-certs`：Verifies，v2/v3 true，签名者证书与正式 v0.19 完全一致。`aapt dump badging` 在 unsigned 产物中核实包名 `cn.jiaoban`、versionCode 21、versionName 0.21。
- **0.21 公共发行校验**：发布附件 `Jiaoban-v0.21-universal.apk`（3,022,773 字节，SHA256 `05520f7a256c198cd91ee6320de093e6334231c2fff1296a6a531916cb435619`）、`Jiaoban-v0.21-release-notes.json`（SHA256 `97782f287a68d30428ea0217764a867a6e78271cbf0c647ffabdb6a9a66c7f36`）、`Jiaoban-v0.21-SHA256.txt`（SHA256 `035f73562ab62efd271aaba128549be4ef0ca2d9fedc4c10970127d4441299fc`）。三个文件从 GitHub Release 独立下载回 Windows 后逐个计算 SHA256 与本地全部一致；GitHub 端 digest 和资源回读一致。GitHub latest API 返回 v0.21，release tag 的 git ref 指向 `0d7a60a`。用户手机未触碰，0.19→0.21 实机覆盖及录音实机验收仍未执行。
- **仍待验收**：真实设备从 0.19→0.21 覆盖安装及旧数据/媒体/备份、锁屏长录音、音质、来电、通知授权、强杀和低空间；AndroidTest APK 已构建但未设备运行。不能宣称用户手机已更新，不能要求卸载或清资料；已完成分段优先保存，强杀仍可能损失正在写入的 ~30 秒段。GitHub Actions 已知账务/产物额度阻塞。按用户许可临时开放的 Windows SentinelX `keytool`、`java`、`gh` 已在发布后移除并重载，核实原有 57 条允许命令已恢复。

---

# 历史接续：0.20 免登录更新交付（2026-10-24）

- 基线：公开 main `83cdd3fc147662b68ea0911289e5b708ab14dcaf`，已包含正式 0.19 的修复；复用 PR #28 / `33a189083a7b26ece8f740d5169328d98cca7e5d` 的公开稳定更新实现。
- 当前写手：本任务独立分支 `agent/codex/public-update-release-20261024`；旧主工作区未知修改保持原状。
- 目标：原证书签名的 0.20 正式安装包、可匿名下载及固定 stable 清单，保留记录/日记/附件与原系统安装流程。
- 当前是交付候选，尚未发布；源码/构建/签名/Release 下载/手机各自验收，不用旧证据代替。准确 head、PR 和最终发行状态以本次远端记录及交付回执为准。
- 接续：云端跑现有检查；手动运行 Cloud development 得到 unsigned release 及小屏/大字 UI 图。原签名只在本机使用，不向云端搬私钥；核对原证书、版本递增、最终包与公开下载摘要后正式交付。实体手机未验不称通过。
- 通用入口：https://github.com/h516df/vibe-ui-quality-kit-open/blob/main/workflow/README.md；历史快照仅作线索，保留项目定制规则。

---

# 当前接续记录（2026-10-21 · 全云端开发包交付与并行核对）

- **目标与结果**：绕开 Actions 附件容量，恢复开发 APK 获取。本次已用当前云工作区构建、既有 GitHub CLI 中转，交付到[私有未发布草稿](https://github.com/h516df/jiyixia/releases/tag/untagged-0b0b34b71ada30405859)，release ID `419373728`。三项附件下载回读 SHA256 全部一致，draft/prerelease 均为 true；无正式发布、历史删除、公开资源、密钥迁移或设备操作。
- **分支 / PR**：`delivery/apk-20261021` / [#33](https://github.com/h516df/jiyixia/pull/33)；main 基线 `ddc8c17f0158dec3e3ae11665ba39fa00bf55975`。最终远端提交/合并以 PR 实时状态为准；未修改或合并 #28 的公开稳定更新源任务。
- **实际验证来源**：源码 `28d9c7cd4f63b5b4b0102f33e69581e0fb428ce6`；`bash cloud/check.sh` passed：279 JVM（0 failure/error/skipped）、debug APK、AndroidTest APK、lint。APK 签名结构 passed；没有运行 AndroidTest APK 或实体手机验收。当前业务源码与现有正式 v0.19 一致。
- **产物绑定**：`jiyixia-DEVELOPMENT-28d9c7cd4f63.apk`，15,850,660 字节，SHA256 `79a2b9b6743ae32e2f55d6793cb73536b9ef9276f81baec612196ee179a2f2f8`。三项 readback 结果在草稿构建说明/哈希与本轮回执中；包名 cn.jiaoban / 0.19 / debug，未使用原证书，不是手机正式升级包。
- **修复配置**：新增 Private development APK，复用现有工具链；Test / Build / Lint / 交付分开，仅本私有仓库手动运行或 delivery/** 推送触发。普通 PR 不交付；API 只创建草稿，不发布、替换或删除资产。actionlint 1.7.7（官方 SHA256 校验）、4 项保护测试、Python 语法及 diff 检查 passed；交付辅助函数本轮实际上传/回读 passed。
- **准确故障分类**：运行 39549162478 第一次尝试测试/构建/lint passed，artifact 上传因 storage quota 失败，实际附件为空。后来第二次尝试与本 PR 的新旧工作流在 runner 分配前失败（无 steps，runner_id=0）；认证读取注释明确为近期付款失败或支出上限需检查。不是测试/构建失败。账户实时用量读取因权限不足未取得，不增权、不付费；当前存储是否释放仍未知。
- **并行核对**：另一云端 Codex 的最新可见成果为工具库 PR #7（通用工作流 0.2，已合并），声明仅升级工具库、TimeWorth 和模板。本轮仅改 jiyixia 的项目交付约定，不改工具库、TimeWorth、共享 Make 场景或其他业务 PR。无法看到另一会话未推送内容，因此每次写入仍须重新核对 main/PR；不声称已直接联系或锁定其会话。
- **新增云端证据**：复用已有 Make 场景/连接，只读 GET 私有仓库与现有草稿均成功，OAuth repo 权限和草稿三资产摘要已核对；具体 execution 与已知 5 MiB 文件限制见 CLOUD_DEVELOPMENT。APK 15,850,660 字节，本轮未验证 Make 二进制上传，不创建新的上传场景。
- **默认交付**：云端构建后使用当前任务实际可用的私有文件下载；[本次已保存的开发包](https://chatgpt.com/api/library/files/libfile_bf5350e7bd308191a2f6b2bd2df8bcf1/download) 的大小/哈希/私有身份已核对。上次本机 CLI 中转仅作为历史，不再作为默认路线。不要求用户开电脑、搬包或提供 token；其他 Codex 环境须核对其实际文件交付能力。
- **本轮验证**：并行成果/分支/PR 核对 passed；两次云端只读连接实测 passed；已有 APK 大小、SHA256 和私有文件身份复核 passed；文档差异、原历史/规则保留及空白检查 passed。没有业务/脚本/CI 修改，不重复构建或重跑已知账务失败的 Actions。
- **剩余限制**：自动 Actions 因账务阻塞未验收；正式签名仍未接入云端。开发包下载无需等待 Actions 恢复；正式签名迁移、正式发布和付款/预算改变仍不在本次授权内。保留正式 v0.19、原签名及用户资料。

## 之前的交接（保留）

# 当前接续记录（2026-10-21 · 统一通用入口）

- **目标**：适应用户经常新开云端 Codex 的方式；每个项目默认分支都指向同一通用工作流，并保留本地可执行简版，用户无需记启动语或分支名。
- **通用入口**：[vibe-ui-quality-kit/workflow](https://github.com/h516df/vibe-ui-quality-kit/blob/main/workflow/README.md)。简版修订 2026-10-21-r1；项目 AGENTS/CLAUDE 的特有约束继续适用。
- **本轮基线 / 分支**：`9fedab1ab26b8ac74982f2e74778da7c352df2ac` / `codex/shared-workflow-entry-20261021`。本轮只改规则与交接文档，不改业务源码、安装器、签名、数据、服务或发布权限。准确提交与 PR 以本次远端记录为准。
- **已完成**：统一入口、清理有效入口里的失效 agent-rules 引用、独立简版与自动发现相关任务的约定；保留下方项目历史和原检查入口。
- **验证**：本轮文档范围/简版一致性/项目约束保留/失效入口检查 passed；业务构建、设备、生产发布 not run。通用模板另在工具库验证。新 CI 结果以对应 PR 的实际运行记录为准。
- **当前任务发现**：下面是本轮开始前的未合并 PR 快照，不是建议合并清单。代理根据用户任务自行核对实时 head、依赖和相关分支交接；不要求用户逐个记住，也不能把最新 PR 自动当主线。
- **下一步**：新任务先读本仓库 AGENTS、云端说明与本记录，再按用户目标接续相关成果。完整私有规则暂不可读时先沿用本地简版；不能把存档视为账户级配置。

## 未合并任务快照（2026-10-21）

| PR | 任务分支 | 目标分支 | 读取时 head |
| --- | --- | --- | --- |
| [#28: 记一下：接入免登录公开稳定更新源](https://github.com/h516df/jiyixia/pull/28) | `codex/public-stable-updates` | `main` | `33a189083a7b26ece8f740d5169328d98cca7e5d` |

## 之前的交接（历史保留）

# 当前接续记录（2026-10-21）

本节是会话接续入口；长期规则见 [AGENTS.md](AGENTS.md)，工具链和检查命令见 [CLOUD_DEVELOPMENT.md](CLOUD_DEVELOPMENT.md)。后续任务更新本节，下面已有历史保留。

- **本轮目标**：将项目约定、进度和续接方式保存到 GitHub，使新 Codex 会话可以继续工作。
- **核对的主线基线**：`44c8e37632a1c049e991b558f59692888c78b5fe`（main 的日期快照，继续前重新读取；不是永远最新提交）。
- **本轮分支**：`codex/session-continuity-20261021`。此分支仅修改协作与交接文档，PR 和实际合并状态以 GitHub 为准。
- **已准备**：新会话阅读顺序、结束保存流程、远端提交核对及续接提示；保留原项目规则、既有历史和云端开发配置。
- **验证范围**：本轮检查文档差异、仓库内链接、原文保留及远端读回；业务测试/构建/设备/发布为 **not run**，历史证据不能重标成本轮结果。实际检查结果与远端提交见本轮 PR。
- **配置与边界**：本机原证书尚未接入当前环境；不覆盖或卸载存有资料的正式 App，设备验收单列。 其他配置键名、初始化入口及限制仍以云端说明和任务相关文档为准。
- **下一步**：核对下面 PR 快照及当前 main；按用户的具体目标选择主线或指定分支，读对应 PR 的最新交接后继续。尚未完成的业务工作不因本轮文档完善而视作已完成。

## 打开的 PR 快照

以下仅为 2026-10-21 读取到的列表，不判断是否仍需实施、能否合并，或是否已由其他提交包含；不批量合并、关闭或重复搬运。

| PR | 分支 | 读取时 head | 状态 |
| --- | --- | --- | --- |
| [#28: 记一下：接入免登录公开稳定更新源](https://github.com/h516df/jiyixia/pull/28) | `codex/public-stable-updates` | `33a189083a7b26ece8f740d5169328d98cca7e5d` | 草稿 |

## 下一次交接时更新

请在本节写清本轮目标、完成/待办、工作分支和 PR、所验证的提交 SHA、实际命令与 passed / failed / not run、阻塞、下一步；代码和交接一起推送，再给用户已核实的远端提交链接。未合并时须交代指定分支，不能让新会话只读 main。

---

## 原有交接历史

以下原文保留；其中旧路径、候选版本与测试结论需按日期和源码重新核对。

# 交办第二轮改版交接（Issue #15）

用户原话“还有不足，我觉得太简陋了”，确认界面与使用流程两者都简单，具体跟进选项回答“我不知道”。已确认主要用途仍是别人交代给我，快速记下、每天看事、办完留记录。开始办→等回复→时间化跟进→办完保留过程是代理推荐的可纠正默认，不能伪装为用户逐项明确指定。

范围：紧凑首页、阅读详情与编辑分开、进行中和等回复筛选、带时间的进展/处理记录、按人及真实处理日期历史、保存失败重试、正文和进展草稿保护。保留原到期策略、全部原文、系统分享、搜索、日历与设备授权更新。非目标：步骤/附件/通知/优先级/团队/同步/导出，#2/#3/#5 独立。

唯一主写手/整合者当前 Codex。工作区 D:/Workspaces/Codex/2026-10-13/jia/work/repos/Jiaoban/codex-ui-workflow-redesign，分支 agent/codex/refined-followup-v010，核对基线 main feed71e68ca1f9ff6a04ecc03aa15a7c8866d33d。canonical D:/Projects/Jiaoban 的未知0.7草稿保留，不清理、不覆盖、不reset。OpenDesign 独立项目两次上游连接中断，无可用设计产物；未切模型或充值，现由主写手原生实现与实际截图迭代。

0.10/versionCode10 已正式发布，114项单测、最终正式包原生回归、0.8及0.9同签名覆盖升级和真实更新取消/连续重试已通过。两组各7条旧记录文件哈希完全一致；保存失败重试、进展草稿保护和重启记录保持通过。新增字段为 workStage/followUps，原状态与期限独立，旧文件缺字段可读取、不主动写迁移。证据见 docs/acceptance/v010/README.md；不将早期失败或过时包当完成。PR #16最终提交CI通过并squash合并；私有Release三项资产逐项回读一致。

源码指纹2a9780d0e59fb9fc1d2ee01759131ab0bcad1d46e0144355f61bae4c6ffdcde9；APK SHA256 483464515604f22bf73418cc4006c308d4b69eaf4596819fb9d12158c4377038，1,821,048字节，原签名保持。当前App源码已冻结，后续文档变更须核对指纹一致。

专用 AVD Jiaoban_Redesign_API36/API36/x86_64/AsiaShanghai，127.0.0.1:5591，ADB5043；只使用匿名夹具。证据必须匹配 sourceFingerprint/APK/probe和实际环境，探针仅androidTest。用户手机最后确认0.8，未连接；本人授权后的下载及系统安装真机路径未验收。

已发布0.9与原交付证据见 docs/acceptance/v09/README.md，Release https://github.com/h516df/Jiaoban/releases/tag/v0.9。不得覆盖该发布。0.10未完成发布前不得称手机已更新。回退保持同签名，保留数据，必要时发更高versionCode修复，不卸载或安装低版本。


0.10已正式发布：https://github.com/h516df/Jiaoban/releases/tag/v0.10。PR #16最终head 728c59e088390f00f4aec671f7a18bf01a0e6874 的[CI](https://github.com/h516df/Jiaoban/actions/runs/38339552268)通过；合并/发布目标 `d93f773e82bf86f871390520997d168355b82437`。APK、中文说明、SHA256三项资产逐项下载回读一致，见published-results.json。用户手机本人授权下载及系统安装仍未验收。


## 2026-10-18 v0.12 首页直接办理

当前 Codex 单写手，工作区 D:/Worktrees/Jiaoban/codex-ui-quality-20261018，分支 agent/codex/usability-20261018；基线 b4cd15a。最新验收见 docs/acceptance/v012/README.md。新增首页直接办理，旧数据与更新权限边界保留；目标手机未连接。发布以最终 PR/Release 回读为准。


## 2026-10-18 用户纠正后的继续任务（实施中）

扩展为待办、交办、笔记的本地文字工具；旧记录保持交办与到期规则；笔记完整多行编辑、置顶、分享、归档恢复与全局搜索。

同一写手分支 agent/codex/modern-experience-20261018；上一版本不覆盖。通过针对性逻辑和原生交互回归后再发新版本，实体手机未连接，不冒称真机已验。

当前待发布版本 0.13，证据见 docs/acceptance/v0.13/README.md。用户2026-10-18已授权既有公开/私有仓库连续推送、PR、审查合并及验后发版。

## 2026-10-18 成长与探索扩展
用户要求三个App增加游戏化、记录感和成就感；足迹收拢重复诊断入口，探索页显示可核验的地区探索度，并提供后台稳定设置与最近任务隐藏选项。当前Codex单写手，沿用独立工作区；本轮交付成长页、等级/徽章/周目标/活跃日历、本机初次安装与前台使用统计。奖励绑定真实记录，升级保留旧数据；未得到村级边界不能伪称村级百分比，不承诺系统强制停止后永不退出。真实UI、持久化与原有功能回归后发布递增版本。

2026-10-18 用户新增审美约束：苹果式克制、留白、少文字，不铺满。成长页改为稀疏数字布局，徽章墙与日历按需展开，统计规则收进说明。

2026-10-18 用户要求换名称和图标，当前采用「有序」；仅品牌显示与图标变化，包名、仓库、更新前缀及持久化标识不变。足迹涂色保留原名和图标。

本轮正式包原生80检查、122 JVM以及重启记录摘要通过。证据 docs/verification/v0.14 与 ignored verification/redesign-release/bound-results.json，发布门禁绑定当前源码/APK/探针。此后仅文档变化，不改业务源。

v0.15减少成长页文字和重复统计，详细记录按需展开。122 JVM及完整原生回归/重启资料保持通过，见docs/acceptance/v0.15。


## 2026-10-18 审美纠正：v0.15候选，未发布
用户否定“苹果/原神审美已实现”的判断。当前先重做完整成长收藏页，见docs/acceptance/v0.15：有分类型徽章、详情、收藏展开、目标选择与日历；移除等级主视觉/重复进度条。原0.15 APK67362bed证据失效，使用最新绑定。当前PR22继续更新，暂不合并发布，不能声称全App或用户审美已通过。

首页仍有成排数字、厚卡片、重复操作，待办/交办/笔记的整体视觉需要后续复查；不机械复制成长页皮肤。工作区D:/Worktrees/Jiaoban/codex-ui-quality-20261018；AVD home D:/Temp/app-research-20261018/work/avdhome，Jiaoban_Redesign_API36，5590/5037。所有数据为匿名夹具，手机未接入。


## 当前继续：2026-10-18 用户纠正不再中途停工
用户明确“只学到皮毛”“我是甩手掌柜”“中间停了消耗我的时间”，并授权必要时用Astra高子代理提速，须省额度。前文因等待用户审美确认而暂停发布是Agent自己的判断，现已撤销；以完成实际可用改版和验证为准，继续既有授权交付，不再追问设计方向。

有序本轮将首页改为内容列表，移除概况大数字/厚卡片/重复详情按钮，保留直接开始/等待/完成；成长移到设置，展示真实最近完成的内容；笔记、详情、设置统一层级，资料按需展开。全App视觉Issue23与PR22同一切片，真实UI回归后交付0.15。原候选截图/哈希均不再代表当前源码。

本轮最终有序正式候选已通过122JVM、83原生及12记录重启一致；APK df851f0a9051c56527b414924930e610f3862ef97543de1e9e16993685ba5aa9，完整当前证据docs/acceptance/v0.15。整合者已查看首页/详情/成长/大字体/深色真实截图。继续已授权PR检查合并与发布；不得再沿前文旧候选等待审美批准。

## 当前任务 2026-10-19 · Issue #24 · v0.16

用户最新纠正覆盖前文方向：移除交办入口；待办和笔记必须实质不同；成长仍需改善，不能只换标题或加徽章。root已将干净093f481、分支agent/codex/todo-notes-20261019及当前隔离worktree移交给唯一业务写手Codex子代理shicang_design；root只复核整合发布。

实际采用参考：Apple提醒事项的短行动/日期/完成，Apple备忘录与Quillpad的正文/搜索/置顶。独立实现，不复制GPL代码或换存储栈。待办采用今天/以后/全部/完成；旧DELEGATED仍在待办，来源/跟进/期限策略不迁移。笔记独立全屏正文编辑、范围内正文搜索、归档与轻量清单。重要与单层步骤独立持久化；完成/恢复不篡改步骤。成长改真实已完成记录和次级日常纪念，不奖励打开App，不铺等级/目标压力。

非目标：同步/账号/提醒、富文本、任务树、批量数据重写。新增JSON字段使用兼容默认值；草稿关闭与新分享保护、保存失败重试仍需原生验证。旧探针中的按人/交办入口/阶段筛选/成长目标已不适用，以旧资料可找回、四分区、步骤/重要、独立笔记和真实成果行为代替，保留其余有效回归。当前为实现中，尚无v0.16验收通过结论。

参考来源：[Apple提醒事项](https://support.apple.com/en-us/102484)、[Apple备忘录](https://support.apple.com/en-us/118442)、[Quillpad](https://github.com/quillpad/quillpad)。采纳“先录入再补日期”“笔记正文/置顶/搜索”的行为，不引入第三方代码、同步或编辑框架。Tasks.org多模块/Room、Quillpad混合View/Compose/Room/Markwon均重于当前JSON结构，故只借交互，不换栈。

v0.16最终验收已通过：135 JVM、正式包110原生断言、14匿名记录重启摘要一致。源/探针提交4549b0d，正式APK70393929024abd15d641e24fdd1c728a5ee8a5981b137173e749e5664819d633；docs/acceptance/v0.16含完整绑定与8张实际截图。最终修复配置重建重播旧分享；本轮没有复制第三方代码、新依赖或数据迁移。5590已关闭，资源释放；交root审查PR、合并发布，手机路径仍未验。发布显示名必须为“有序”，不可沿用旧脚本硬编码“交办”。
