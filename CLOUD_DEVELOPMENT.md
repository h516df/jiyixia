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
