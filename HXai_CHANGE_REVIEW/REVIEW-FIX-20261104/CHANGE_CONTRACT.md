# CHANGE_CONTRACT

FeatureID: REVIEW-FIX-20261104

用户故事：审查发现的可修缺口要在当前树上关闭，不能用本地删除冒充服务端撤权，也不能让过期界面命令或无记账请求继续执行。

base: `54b832613c1b4ab3eefbecedca5367c274b60135`。不提交用户未提交的 `AGENTS.md` 与开发基线说明。

范围：

- F1：服务端 Office 预检与 Android 对齐：大小写签名目录、`vbaData.xml`、宏/签名内容类型与关系、全部外部关系、ZIP CRC/实际大小。
- F2：停止、重试、切分支、教学模式、工具批准携带并校验 `contextEpoch`。
- F3：会话详情不再把实时模型选择写成任务模型。
- F4：持有者可调用文档服务撤权；只有服务端确认后才删除本地凭据。
- F5：没有任务记账上下文的物理模型请求直接拒绝。
- F7：OCR 缓存改到 `noBackupFilesDir`，不迁移旧 `cacheDir` 正文。
- F6：冻结包 Windows 目录句柄失败不是当前产品缺陷，不改历史包。

不在范围：生产部署、Flutter/Gradle 全量构建、Mate50、容器渲染。

回滚：还原本批文件。无 Room/schema 迁移。Pigeon 只增加命令末位 epoch，不改已有字段顺序。
