# B04/B05 资料库与数学来源协议 v1

范围：B_TASK B04 产品闭环、B05 现合同1–15质量；不是完整P1/P2、B06、真实OCR识别率或新增Taylor合同。交付与逐项运行证据位于 `HXai_CHANGE_REVIEW/B04-B05-COMPLETE-20261020/`；源码SHA以最终清单为准。

## 真源与入口

| 用户入口 | 实际调用与持久化 | 消费/边界 |
|---|---|---|
| Flutter FilesPage 系统目录导入 | StudyFlutterActivity.ACTION_OPEN_DOCUMENT_TREE → StudyHostFacade.frontend_features → MaterialLibraryFrontend → MaterialDirectoryImport → MaterialFiles.import/runTask | 仅本次选定DocumentsContract树；不接受模型URI/路径，不持久化树授权，不自动上传；最多500项、12层、单件30MiB、成功复制总量300MiB；逐件报告，重复名使用新ID |
| 文件夹/标签、筛选排序 | MaterialLibraryFrontend.library.organize → MaterialFiles.organize → `<owner>/<id>.meta.json` | 逻辑目录不变成主机路径；目录最多12层/500字符，标签最多20项；原件ID/hash不变 |
| 正文、全库字面检索、章节候选 | 既有MaterialReader / MaterialSourceReceipt / MaterialLibrarySearch / MaterialChapterIndex | 实际固定源字节独立核验；UTF16分页不截断代理对；页码仅为有出处的PDF物理页/PPT逻辑页，不推断印刷页或整书完整性；不另建索引真源 |
| 固定详情、家族与跨会话提问 | LibraryDetailPage / FileDetailPage → 现材料/产物DTO与同一ContextFollowUp、Chat Agent | owner/contextEpoch/id/hash重查；旧档案/旧epoch/已删版本不可继续展示或提交；模型选择仍由原宿主与任务快照控制 |
| 打开、另存/下载、分享 | 原StudyHostFacade.openArtifact/saveArtifact/shareArtifact → 系统用户操作 | 打开/分享前复核epoch；另存关闭并独立读回目标SHA；仅打开分享面板不等于发送成功 |
| 删除、本机回收、恢复 | MaterialFiles.delete/restore/purge、MaterialSourceCaches | 先写永久tombstone，再清派生缓存；迟到读写不得复活。恢复是新ID/parent，丢ACK重试读回同一版本；已恢复分支再删除后可显式生成下一新分支。清除仅移除选中本机原件，不移除删除约束或UNKNOWN任务日志 |
| 加密备份/历史恢复 | BackupManager → MaterialLibraryMetadata → 既有RestoreStagingIntegrity / BackupDeletionMerge / PendingRestore | 删除正文/缓存不进入新备份，墓碑不带私有名字/标签/转录/引用；确认及启动恢复均合并删除身份，先验证暂存seal，不能把被篡改输入重签为可信 |
| 数学来源工作面 | library.mathEvidence / library.confirmSymbols → MaterialMathEvidence / MaterialFiles | 真实id/hash与出处、显式用户转录分开；SOURCE_READY仅为输入就绪，mathematics_status保持NOT_EVALUATED；后续仍使用同一Agent的math_verify合同1–15 |

## 元数据与兼容

材料元数据 `libraryVersion=2` 增加逻辑 `folder`、`tags`、`deletedAt` 和 `mathInputConfirmed`；缺字段的合法旧元数据按默认值读取。`mathInputConfirmed=false` 是旧单字符校对的默认语义，不能冒充完整数学输入转录；完整转录仍须匹配 `symbolSourceSha256`。新字段不是授权、模型锁或数学证明。

同一进程的活跃读写按材料目录记数，第二个MaterialFiles实例不能把它当进程重启。真实进程退出会丢弃内存计数，启动恢复仍清未发布临时件并标记中断，不伪造成功。元数据提交、删除与章节缓存最终发布共用锁；不创建第二个Agent执行器。

历史设置恢复可能移除后来档案。其删除身份可以作为规范无正文tombstone随新备份保存；其未删除材料不因此被纳入，不创建该档案、不赋予任何访问权。未知版本/错owner/损坏清单拒绝，不能清库或降门槛绕过。

Room33为并行A既有增量；本批不新增Room、TaskStore sidecar9、Pigeon164、工具注册/授权或模型协议变更。原数学算法与合同1–15保持不变。

## 质量语料与复验

- `client/app/src/androidTest/assets/math_quality/representative-contracts.json`：现合同1–15与正确异法、原域孔洞、未知首错前缀等代表场景。
- `quality-stories.json`：本批边界增量；全量4267条固定输入在交付 `QUALITY_CASES.json`，既有CLI执行及独立Fraction/Sturm证书核验命令/输出另存，不累加历史计数为产品完成率。
- `REFERENCE.json` 与原始 `calculus-made-easy.pdf`：公开原版教材和实际SHA；独立宿主与Android读回物理位置。来源 [Project Gutenberg, Calculus Made Easy](https://www.gutenberg.org/files/33283/33283-pdf.pdf)。扫描/空页、部分提取范围与用户转录不能冒充机器OCR、整书已读或数学证明。
- `MaterialLibraryLifecycleRuntimeTest`：SAF范围、坏输入/撤回、并发组织/删除、恢复幂等、Unicode、活跃实例及真实加密备份边界。
- `MaterialMathQualityRuntimeTest`：实际本地精确工具、真实教材出处与歧义确认；`MaterialLibrarySurfaceRuntimeTest`：真实安装Flutter与持久化，不是设计PNG或widget截图。
- `scripts/verify_b_library_process.py`：仅独占nativeqa模拟器，实际杀进程验证未发布写入、恢复已提交但ACK丢失；宿主独立读回原件/恢复版本字节。必须先安装匹配的QA与test APK，使用全新run-dir。

精确命令、实际PASS/FAIL与环境限制以交付日志/验收矩阵为准。真实付费Provider/真实OCR识别、API26、目标设备、跨Office、Release/生产与TalkBack语音未由工程夹具自动授予通过。独立审查NOT_EVALUATED。

## 回滚

仅逆本批精确差异/停新入口；不得删除原件、版本、回执、UNKNOWN或tombstone。不得让不理解deletedAt的旧消费者接管新元数据；原有独立材料与聊天生命周期保持分离。先保存当前数据和精确源码SHA，再按交付回滚说明执行，不自动reset/clean/stash/合并/部署。
