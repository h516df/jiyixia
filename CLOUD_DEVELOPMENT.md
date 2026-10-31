# 云端开发 · jiyixia

自 2026-10-21 起，后续源码开发、测试与构建默认在云端执行。

[在浏览器创建开发工作区](https://codespaces.new/h516df/jiyixia)。选择本仓库的开发容器；首次创建会安装固定主版本工具与项目依赖。用完停止工作区，避免持续计费。也可以在 Codex 云端拉取本仓库，运行 `bash .devcontainer/setup.sh` 后使用同一检查入口。

```bash
source .cloud/env.sh
bash cloud/check.sh
```

GitHub Actions 的 **Cloud development** 在 PR、main 推送及手动运行时检查。通过结果绑定当前提交；构建和模拟器通过不能替代实体手机验收。依赖与缓存可重建，代码必须提交到分支并推送，临时工作区不是长期资料备份。

## 当前边界

本机原证书尚未接入云端。调试包不是正式升级包，不可覆盖或卸载存有资料的正式 App。

原 README、AGENTS 与交接文件中的 Windows 路径作为历史恢复线索保留；云端开发不依赖这些路径。生产运行与发布步骤按本节边界处理，不自动运行旧的本机派发、安装、隧道或签名脚本。凭据通过平台 Secrets 注入，不能进入源码、日志或构建附件；私有项目继续保持私有。

历史规则引用的 `h516df/agent-rules` 当前不可访问（404），本次以用户的云端迁移要求和仓库内可读规则执行；未据此放宽数据、签名或生产服务约束。

## 配置依据

- [Dev Container 规范](https://containers.dev/implementors/json_reference/)
- [GitHub Codespaces](https://docs.github.com/en/codespaces/overview)
- [Android 命令行工具](https://developer.android.com/tools/sdkmanager)：固定工具包版本 20.0 并核对 SHA256，SDK 36 / Build Tools 36.0.0；JDK 21，兼容模块另装 JDK 17，Python 3.12。

## 构建附件配额

2026-10-21 核对：Cloud development [运行 39549162478 第一次尝试](https://github.com/h516df/jiyixia/actions/runs/39549162478/attempts/1)的单测、APK 构建及 lint 通过；上传明确报 `Artifact storage quota has been hit`，附件列表为空。不能把整体绿色等同于 APK 已交付。随后重新运行在 runner 分配前失败：认证读取的 GitHub 检查注释明确提示近期账户付款失败或支出上限阻止任务启动。这不是测试、构建或上传步骤失败。账户最新账单用量因现有凭据权限不足未读到，没有扩大权限或修改预算；存储当前是否已释放仍需恢复运行后用实际上传复测。测试/构建失败继续严格阻断；不删除历史产物，不自动付费或公开 APK。

### 默认交付路径：云端构建后私有下载

Actions 因账务或附件配额受阻时，继续使用当前 Codex 云工作区运行已有 `bash cloud/check.sh`。代理核对源码提交、构建退出状态、APK 大小、SHA256 和开发/正式属性，再通过**当前任务实际可用的私有文件下载能力**交付。GitHub 保留源码与交接；当前会话无需 Windows 电脑登录或中转。不同云端任务的工具和凭据各自核对，不把这次能力当作账户级自动配置。

2026-10-21 的现有开发包已保存在用户自己的私有文件空间，可[直接下载已保存的 APK](https://chatgpt.com/api/library/files/libfile_bf5350e7bd308191a2f6b2bd2df8bcf1/download)。它与本页后述草稿 APK 相同：源码 `28d9c7cd4f63b5b4b0102f33e69581e0fb428ce6`，15,850,660 字节，SHA256 `79a2b9b6743ae32e2f55d6793cb73536b9ef9276f81baec612196ee179a2f2f8`。需由该 ChatGPT 账号访问；没有公开分享。只是开发测试包，不是手机正式升级包。

上次为核验 GitHub 草稿而借用本机已有 CLI 中转，作为历史实际经过保留，**不作为后续默认全云端路线**。已有私有草稿保持不动；正式 v0.19、原签名和用户资料保持不动。

### 现有云端 GitHub 连接的核对

已读取另一云端 Codex 合并的 [通用工作流 0.2 / PR #7](https://github.com/h516df/vibe-ui-quality-kit/pull/7) 与 `workflow/CLOUD_CAPABILITIES.md`。复用其中已建立的 Make 按需场景 `6715378` / GitHub 连接 `11842349`，仅执行 GET，未修改共享场景：

- `/repos/h516df/jiyixia`：成功，账号连接可访问本私有仓库，返回 push/admin 权限及 OAuth `repo` scope；execution `090b7b2b626f4a7e93843f122d887eb0`。
- `/repos/h516df/jiyixia/releases/419373728`：成功读取既有 draft/prerelease 和三项资产摘要；execution `cefe366a45194ccab4d351441c00a322`。
- 当前 Make Free 许可证的单文件上限为 5 MiB（`fslimit=5242880`），而本包为 15,850,660 字节。本轮**没有验证该连接的 APK 二进制上传**，不把仓库管理权限等同于可传此包，不拆包、不升级套餐、不改另一任务的连接或场景。
- 当前云端私有文件下载已具备，因此无需为了开发包交付再引入传输系统。GitHub 草稿自动上传仍以已有 Actions 恢复后的真实运行结果为准，不反复启动已知被账务拦截的任务。

### 按需取得开发 APK（不使用 Actions artifact）

复用现有云端工具链，新增 **Private development APK** 工作流：

- 代理向 `delivery/**` 任务分支推送，或在 Actions 手动运行此工作流。普通 PR 不触发交付，也不向 PR 授予写权限。
- 分别运行 Test、Build、Lint。任一步失败均不会创建交付草稿；摘要分别显示失败环节。
- 只有本私有仓库才能运行。通过后将 APK、SHA256 和构建来源保存到 **未发布的 Release 草稿**，不进入正式/最新版本，不调用现有正式发布脚本，不读取原签名密钥。
- 三项附件均通过 GitHub API 下载回读并核对 SHA256；全部通过后运行摘要才给出下载入口。使用有仓库写权限的账号登录，打开草稿的三项附件即可。仅有读权限的协作者不能访问草稿。
- 每次运行尝试使用独立 `dev-apk-<run>-<attempt>` 标识，不覆盖或删除已有资产；失败时保留现场。草稿不会自动过期，不自动清理；仅按需运行以避免无谓积累。
- 开发包沿用应用 ID `cn.jiaoban` 和版本号，使用当前云环境的 debug 证书，**不是原证书签名的正式升级包**。不要在存有资料的手机卸载、清数据或安装开发包。需要试装时使用可丢弃模拟器。
- 该方案避开 Actions 附件存储，但构建仍使用 Actions 分钟。若 runner 无法启动或分钟不足，也不能声称已生成 APK；不擅自提高预算。

### 本轮已验证的交付（2026-10-21）

- 源码提交：`28d9c7cd4f63b5b4b0102f33e69581e0fb428ce6`，PR [#33](https://github.com/h516df/jiyixia/pull/33)。
- 当前云端工作区执行 `bash cloud/check.sh`：279 项 JVM 测试（0 失败/错误/跳过）、debug APK / AndroidTest APK 构建、lint 全部通过；不是 Actions runner 结果，也不代表实体手机已验收。
- APK 大小 15,850,660 字节，SHA256 `79a2b9b6743ae32e2f55d6793cb73536b9ef9276f81baec612196ee179a2f2f8`，包名 cn.jiaoban / 0.19 / debug；签名结构已验证，没有使用或迁移原证书。
- 使用已有授权 GitHub CLI 仅中转上传；复用本分支交付脚本的 API、草稿保护和回读函数。APK、SHA256、构建来源三项资产均已实际下载回读，哈希一致。
- [私有未发布草稿](https://github.com/h516df/jiyixia/releases/tag/untagged-0b0b34b71ada30405859)，release ID `419373728`；仓库仍私有，draft=true / prerelease=true，正式 v0.19 未变。当前应用代码与正式 v0.19 相同，日常安装继续使用原正式包。
- 新工作流配置通过 actionlint 1.7.7 和四项保护测试。Actions 实际触发被账户账务拦截，工作流的端到端自动执行仍未验收；不能把上述云工作区结果标为 Actions 通过。

### 云开发工作区备用路径

在已有 Codespaces / Codex 环境执行现有 `bash cloud/check.sh`，成功后从文件面板下载 `app/build/outputs/apk/debug/app-debug.apk`。代理负责构建与核对提交，不要求用户重建环境或搬日志。这条路径不依赖 Actions 附件存储；Codespaces 仍受账户额度约束，不自动新建收费资源。当前会话没有成功访问现成工作区时，必须明确标记未验证。

正式升级继续使用已有私有 Releases 中经原证书签名和数据保护验收的包，不以开发草稿替代。

依据：[GitHub Release 资产容量](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases#storage-and-bandwidth-quotas)、[草稿与发布 API](https://docs.github.com/en/rest/releases/releases#create-a-release)。

## 会话接续

### 新会话从哪里开始

本仓库内的约定和已推送的文件是接续依据。新会话不会自动继承旧聊天、未推送改动、上一台云机器的临时工具或登录态。

1. 读取根目录 [AGENTS.md](AGENTS.md) 与 [HANDOFF.md](HANDOFF.md)，再读已有项目规则和任务相关文档。
2. 核对远端 main、当前分支、工作区状态与相关 PR。HANDOFF 里的历史分支/测试结果需和 GitHub 当前状态逐项核对，不能直接当成最新状态。
3. 如果上次工作尚未合并，打开交接指定分支或 PR；不要只取 main 后重新实现一遍。一个分支对应一个进行中的任务，共享文件避免并发覆盖。
4. 工具链缺失时按本文现有初始化方式恢复；业务验证继续使用现有检查入口。不要自动运行历史恢复、生产部署、发消息、签名或发布命令。

### 结束前必须保存什么

- **长期约定**：更新 AGENTS 或已有项目规则，区分用户明确要求与代理建议。
- **当前进度**：维护 HANDOFF 顶部“当前接续记录”，替换过时状态，保留下面原有历史和证据链接。不同任务的未合并进度留在各自分支/PR，合并时整合当前记录。
- **交接最少字段**：当前目标；已完成和待完成；仓库与分支/PR；基线或验证提交；运行/测试命令及 passed / failed / not run；所需配置的名称与来源；阻塞；下一步。
- **远端副本**：提交本任务源码与交接，推送独立分支，再核对 GitHub 上的提交 SHA。PR 尚未合并也可继续，但必须告知准确分支/PR；不要仅保存到旧会话的磁盘。
- **交付回执**：给用户仓库、分支/PR、远端提交 SHA 与下一次可直接粘贴的续接提示。远端未确认前不能说“可以删除旧会话”。

只写配置的键名与申请位置，不写值、个人资料或私人日志。未推送的文件、任务 VM 和 Actions 临时附件不作为唯一长期备份。

### 环境与凭据

本仓库的开发容器和初始化脚本用于恢复工具链；它们不会自动把当前任务变成所有会话共用的机器。若需新 Codex 任务自带已安装工具，必须在 Codex Cloud 环境的编辑流程中准备、验证并 Publish / Republish；仓库提交不等于环境已发布。

密钥通过所选环境的变量/Secret 配置提供；GitHub Actions Secrets、Codex Cloud 凭据和旧电脑登录态不是同一处配置。本轮只建立仓库接续方式，没有读取或迁移凭据，也不声称已修改 Codex Cloud 的已发布环境。

### 给新会话的提示

> 请继续这个仓库的工作。先读 AGENTS.md、CLOUD_DEVELOPMENT.md 和 HANDOFF.md，核对远端 main 与交接指定分支/PR，简短说明已知进度和下一步，再继续未完成任务。不要把历史测试或未合并候选当作当前主线；把本轮进度与代码一起保存并推送。

如果要继续尚未合并的任务，在这段话前补上旧会话给出的分支或 PR 链接。

参考：[Codex Cloud 的环境复用和任务保存机制](https://learn.chatgpt.com/docs/environments/cloud-environments#reuse-and-update-saved-state)。

## 通用工作流入口

通用约定与模板统一维护在 [vibe-ui-quality-kit-open/workflow](https://github.com/h516df/vibe-ui-quality-kit-open/blob/main/workflow/README.md)。开始任务由代理主动读取；本仓库 AGENTS 内保留可独立执行的简版，跨仓库读取暂不可用时先沿用，不要求用户重复说明工作流或添加仓库。具体工具链、数据、签名、生产与设备保护仍以本项目约束为准。

新任务从默认分支开始时，代理应按用户描述核对相关未合并 PR、依赖和对应分支交接，不能直接把 main 当作所有最新成果；不根据旧快照自动合并业务 PR。这里的仓库配置不代表 Codex 账户级环境已发布。
