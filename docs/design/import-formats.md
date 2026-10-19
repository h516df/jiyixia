# 本地导入与完整备份

所有解析在设备本地完成。选择文件后先展示新增、重复、冲突和格式警告；确认前不写记录库。导入只补缺，不覆盖已有记录。日期无时区时保留原值，不伪造 UTC。源文件和识别到的附件保留；外链不下载，HTML 不执行。

## 本轮格式范围

- Obsidian：UTF-8 Markdown 和 vault ZIP。严格 YYYY-MM-DD 文件名作为日记，其余作为记录。Markdown 原文、frontmatter 原文保留；本轮不把任意插件 YAML 字段转为专有模型。解析相对附件链接和 wiki 链接；歧义或缺附件有警告；仅保留接受的原 Markdown 和已引用附件，不复制完整原 ZIP。
- Thino：仅 DAILY 日期文件中的 `- HH:mm`、`* HH:mm`、可选单字符任务框或 time 标签。精确标题可配置；一天拆多条，未识别文字单独保留，围栏代码不拆。JOURNAL、FILE、MULTI、Canvas 与自定义模板未验证，不承诺完整兼容。任务框保留为文字，不恢复待办功能。
- Diarium：JSON 单条对象或数组，date 与 heading/html；正文转换为可读文本，完整原 JSON 元数据保留。支持已观察的 media/yyyy-MM-dd_HHmmssSSS/ 独立附件目录；同时间多篇无法唯一关联时明确警告，不自动导入歧义附件，请保留原始导出。.diary 专有备份不支持。
- 墨记：仅 PDF/图片原件收存与手动日期。不会自动恢复可搜索正文、标签或原始时间，不称无损日记迁移。

有稳定源 ID 时据此去重；无 ID 时使用源路径、原日期、规范正文及语义附件摘要。相同来源中真实重复片段仍各自保留。源文件改名、正文或附件改变可能识别为新增，用户应核对预览。原始整包变化不会令所有旧片段重复。

## 安全与恢复边界

- 单文件/ZIP 条目上限 32 MiB；ZIP 原始与解压总量各 64 MiB；最多 10000 条目、5000 条记录、16 MiB 新导入正文。包含目录在内的所有 ZIP 条目都受预算限制；拒绝目录载荷、重复/越界/绝对路径。
- 附件仅以内容哈希存入应用私有 imports 目录；拒绝哈希不符、路径异常或覆盖现存损坏文件。
- 写库前先安装新附件；写库失败清理本次新附件，旧库保持原样。数据库以同步临时文件和同目录 rename 提交，不退化成直接覆盖。
- 完整备份包含 records.json、版本标识和所有关联附件。导出前严格验证结构、附件哈希及恢复预算；超限报错，不称导出成功。当前没有分卷功能。
- 完整 ZIP 恢复只新增缺少的记录，可补回同 ID 的缺失附件；已有记录冲突不覆盖，已有损坏附件明确报错。未知版本、损坏字段或缺附件整包失败，不把部分恢复标成成功。
- 单纯归档不删除内容。请勿卸载或清除应用资料；备份含个人正文，应保存到可信位置。

## 依据与许可

只借鉴公开格式和交互，未复制第三方实现代码或品牌素材。

- [Obsidian 附件](https://help.obsidian.md/attachments) 与 [链接](https://help.obsidian.md/links)
- [Thino 主库](https://github.com/Quorafind/Obsidian-Thino)；[v1 DAILY 解析证据](https://github.com/Quorafind/Obsidian-Thino/blob/v1/src/obComponents/obGetMemos.ts)
- [Diarium 官方帮助](https://diariumapp.com/help)；[独立导入实现的观察格式](https://github.com/Erallie/diarian/blob/eb08ddda08fdbe91632e4a4a4e966986e2bff052/src/import-journal.ts)
- [墨记官方导出说明](https://www.moji01.com/qa/how-to-export-diary)

## 系统备份隐私策略

应用默认关闭 Android 系统云备份和自动设备间传输；旧版 full-backup 与 Android 12+ data-extraction 规则均排除正文、附件、草稿、偏好、更新授权与其他私有域。缓存不参与主动 ZIP。此配置不操作用户系统账号，也不删除此前系统可能保存的备份。换机、卸载或清资料前需要用户主动导出并妥善保存完整 ZIP，在新设备确认导入；不能依赖自动恢复。主动 ZIP 仅含内容记录和附件，不读取应用设置中的更新凭据或草稿。

导入不复制完整原 ZIP。隐藏配置目录（如 .obsidian）、明确凭据类文件及未引用文件默认跳过，预览列出未导入数量。只保留已接受的原 MD/JSON/PDF/图片和已识别关联附件；源文件本身仍可能含用户写入的敏感内容，分享或保存完整备份前需确认内容。无法关联的附件保留在用户原 ZIP 中，不会被静默称为已迁移。
