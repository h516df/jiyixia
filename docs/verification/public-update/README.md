# 公开稳定更新入口（源码草案，未发布）

基于 main `760ae9bb2e91f69b23a5890f0c2dcb9f54247cda`。保持 versionCode 18 / 0.18，不用本 PR 冒充新正式版本；后续发布需原证书、更高版本号及正式包验收。

## 接入与边界

- 复用现有 UpdateCatalog / UpdateHttp / Updater / ReadyUpdateStore / UpdateVerify 及系统安装入口，没有新增发布器或依赖。
- 默认只读公开仓库 ID 1426495445 的 apps/jiyixia/stable.json（ref=main，Accept: application/vnd.github.raw+json），不是全仓 latest。公开请求不读取或发送 GitHub 授权；已有授权保留，只有用户显式点旧版兼容入口时使用。
- 固定 appId=jiyixia、stable、published、cn.jiaoban、来源仓库 ID、原证书 SHA256；验证清单版本、安卓最低版本、设备架构、APK 白名单、大小和 SHA256。下载后再验原证书、包名、版本与已安装签名；系统安装前继续复用本机重校验。
- 403/429 尊重 Retry-After 与 X-RateLimit-Reset（无头时至少等待 60 秒），进程内冷却，不自动重试、不切换身份/IP。404、网络错误、坏清单、错包提示可恢复错误，不转去强制登录。
- 进入应用仅恢复本机状态；用户点检查才联网，点更新才下载，系统安装仍须用户确认。没有扩大 Android 权限、创建凭据或上传用户内容。
- 正式 v0.18 清单固定样例来自公开仓库提交 b8016d254c0787b6820a222b447d7609eaf98ba8；APK 2928598 bytes，SHA256 d44f09befb5c1db37bc6dd3afa729e936ec617ac97d2319d529bf89f0cc58429。

## 定向验证

测试命令、最终结果见本 PR。PublicUpdateTest 直接解析已发布稳定清单及最终打包 release-notes.json；覆盖同版/旧版、版本可用、minSdk/ABI、错误身份/证书/URL/大小/SHA、匿名头、限流、跳转、截断/超量、取消。PublicUpdateUiTest 用真实 Compose 更新页配离线 HTTP 边界，检查无需授权、失败重试、重复检查，并渲染 320dp / 200% 字号浅深色。已有更新模块测试继续覆盖取消、本机缓存恢复、安装前复核和系统安装委托。

真实手机、真实网络端到端 APK 下载、未知来源授权、系统安装器、覆盖升级和用户数据保留未在本轮实测。宿主渲染、合成流和 debug 构建不替代这些设备验收；当前已发布 0.18 APK 也尚不包含本 PR。

2026-10-20 云端定向验证：

```
:app:testDebugUnitTest --tests 'cn.jiaoban.update.*' :app:assembleDebug :app:lintDebug --max-workers=1
```

40 tests，0 failures，0 errors；debug 构建通过；lint 0 errors、35 warnings、1 hint（保留现有告警，未扩大修改范围）。最终中文说明作为运行输入解析通过。真实 Compose 320dp / 200% 字号浅深色已本机目检，正常换行和滚动；截图未提交或上传。只验证受影响更新模块，没有把未运行的其它业务全套测试宣称通过。
