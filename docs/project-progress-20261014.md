# HXai / MathStudyAgent 当前完成情况与开发待办

整理日期：2026-10-14（Asia/Taipei）。范围为当前本地工作树、Pro 最终需求、后续实现交接和已有测试证据的核对；不是重新执行全项目审查。本轮未修改产品代码、运行模型、部署或开启子代理。

当前结论：P0 的原生任务执行、权限与恢复、模型锁定、计划和文档成果核验已接线，并有模拟器局部验证；真实模型质量和完整产品故事仍未验收。P1 已有材料库、版本与安全局部编辑基础，专业 Office 仍不完整。P2 有教学和交互图基础，精确数学核验尚未接入 App 主工具链。

## 1. 当前版本和证据边界

- 分支：`codex/multi-artifact-evidence-20261013`。
- 完整 HEAD：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。
- 本轮开始时 Git 状态：153 个 tracked 变更、44 个 untracked 状态项（目录项不等于文件数）；无 staged diff。功能增量主要仍在未提交工作树，不能仅用 HEAD 重建当前产品。
- 本轮没有查询远端或 PR；本报告不判断当前 main、PR 合并或部署状态。
- 当前 987 个原生源码/schema 文件与最新原生运行的 `SOURCE_INPUTS.json` 全部字节哈希一致；最新四文件增量、41 份原生执行日志、JVM/Debug-lint 两份主日志、三个普通 Debug APK 也与归档哈希一致。
- 本轮只核对已有证据，未重新运行构建、原生测试或 Flutter 测试。原始 Pro 报告与历史失败记录保持不变。

实现状态与测试结果分开：WIRED 表示已接入真实入口；PARTIAL 表示目标仅部分实现；DISABLED 表示当前明确关闭。E4 模拟器结果不代表 E5 Mate50/Release 或 E6 生产通过。独立外部审查、完整全项目审查仍为 `NOT_EVALUATED`。

## 2. 已接线或已完成的局部能力

| 领域 | 已实现内容 | 当前状态及验证范围 | 尚不能据此宣称 |
|---|---|---|---|
| 统一 Chat 底座 | Flutter/Pigeon → ChatService → ConversationSession → GenerationLoop；保留原 Provider、工具与消息持久层 | WIRED，原生模块/模拟器证据 | 所有真实复杂任务已可完成 |
| 全局原生任务调度 | sidecar TaskStore/Scheduler、跨会话/档案执行位、持久队列、递增 fence；辅助模型请求统一调度入口 | WIRED / E4 / PASS_SCOPED | 手机与服务器分布式唯一执行已实现 |
| 模型锁定 | 接受任务时固定 provider/model/reasoning/config；排队、恢复不偷偷更换；专用角色记录请求身份 | WIRED / E4 / PASS_SCOPED | 所有真实供应商/收费行为已验收 |
| 暂停、授权、等待 | manual pause、撤权、自然失效、缺输入、设备确认分开；owner/task/capability/scope/epoch/inputHash/fence 校验 | WIRED / E4 / PASS_SCOPED | 云密钥托管、真正跨 App executor 已完成 |
| 工具调用与恢复 | 副作用 journal、独立 verifier、UNKNOWN 保留、迟到回执、丢 ACK、不重复执行；恢复指定排队任务幂等 | WIRED / E4 / PASS_SCOPED | 所有外部平台副作用都有可靠查询接口 |
| 计划与目标验收 | TaskPlanner 调用已接受主模型提出计划；持久步骤与验收条件、语义核验；所有步骤必须 VERIFIED | WIRED，localhost 合成 Provider/真实宿主链通过 | 已证明真实模型规划和语义判断可靠 |
| 多文档完成门禁 | 全部已核验步骤的 Document 输出独立读回、去重、完整证据哈希；语义回包后重查全部文件和来源 | WIRED / E4 / PASS_SCOPED | 图像语义、一般数学证明或版式已得到核验 |
| 请求用量基础 | 实际请求账本、角色/模型/配置绑定、幂等回执；未知 token 保留 null | WIRED / E4 / PASS_SCOPED | 完整费用账单、日周月聚合和异常通知已交付 |
| 上下文与工具结果 | 压缩原子回写和来源记录；工具完整结果保留，仅截取发给 Provider/摘要的投影 | WIRED / E4 / PASS_SCOPED | 全部真实长对话质量已达到目标 |
| 任务 UI | Pigeon 双端、TaskDto、真实计划/阻塞原因、暂停/恢复/取消/批准；区分当前模型选择与任务模型锁、停止请求与已停状态 | WIRED，Flutter及实际模拟器表面有证据 | 200% 大字和真实 TalkBack 朗读已通过 |
| 网页与图片 | 公共地址/DNS/重定向边界、受控取图和 owner/hash 本地图片展示 | WIRED，已有局部 JVM/Android 证据 | 登录网站操作、远端 MCP 和全网兼容已验收 |
| 文件局部编辑 | 材料/成果版本、来源 hash/expectedText/owner、独立回读；PPT 按逻辑页序；统一 ZIP/XML 安全处理 | WIRED，局部真实文件与模拟器验证 | Word/PPT 任意复杂版式和 Excel 实际重算已完成 |
| 文档服务安全 | 入队/claim/finish 事务撤权栅栏；远程结果运输/续接；Office 元数据并发回读修复 | WIRED，ASGI与受控宿主链证据 | 实际 MinerU/LibreOffice worker 全链已验收 |
| OCR 与记忆基础 | OCR 日志/取消修复，缓存键含档案/源 hash/模型配置；学习记忆与显式保存能力保留 | WIRED/PARTIAL，部分回归证据 | 手写准确率、全缓存删除传播、掌握度判定和恢复防复活已完整验收 |
| 教学与图形 | 既有教学表达、数学交互图/结构化界面能力保留 | WIRED，已有局部行为证据 | 高考/分析/实变证明质量已达标 |

最新两批已经解决的具体反例：

1. 两步计划只完成第一步、验收只引用第一步时，不能再发布整项任务成功。
2. 正确的第二份文档必须进入语义证据，不能因未被某个谓词单独引用而遗漏。
3. 第二份文档或其来源在验收期间变化时，不能沿用旧证据发布成功。
4. 正确的双文件成果和合法来源版本保持可成功，避免通过“一律拒绝”掩盖问题。

## 3. 当前能引用的测试结果

| 证据批次 | 已运行结果 | 范围 |
|---|---|---|
| MULTI-ARTIFACT-EVIDENCE 最终原生运行 | 112 项 PASS（72 批量 + 40 逐方法），0 FAIL | 选定模拟器模块、真实服务/Room/Pigeon、合成材料、localhost Provider |
| 同批最终 APK 真实进程终止/重开 | 5 个检查点 PASS | 宿主终止与独立重开，不等于目标真机后台策略 |
| 同产品代码 App JVM | 410 项 PASS，0 failure/error/skip | 单元测试；后续仅增加 androidTest 来源场景 |
| 最终普通 Debug / lint | 构建 exit0；lint 0 errors / 50 warnings | 普通 Debug，非 Release；警告未隐藏 |
| REMAINING-FIXES Flutter | 287 项 PASS，analyze exit0；Pigeon 双端重新生成通过 | 前批 Dart/桥接版本；本报告未重跑或声称本轮新通过 |

这些结果不能相加为完成率，也不能授予完整 App、真实模型、专业引擎、Release、Mate50 或生产 PASS。本轮证据哈希核对见 `../qa/project-progress-20261014/SNAPSHOT.json`。

## 4. 按优先级仍需开发的任务

| 顺序 / 任务 | 已有基础与剩余工作 | 主要入口 / 依赖 | 完成判据 |
|---|---|---|---|
| P0-01 真实复杂任务闭环 | 规划和验收代码已接线。需使用明确授权的真实 endpoint 与合成材料，验证完整目标覆盖、失败重规划、同任务新增要求、模型不支持工具的处理，并据反例继续修实现 | `feature/agent/TaskPlanner.kt`、`ChatService.kt`、`TaskPlanningRuntimeTest.kt`；真实调用授权 | 需求故事 A/B/D 的目标、真实成果、模型锁和恢复均成立，错误证据不成功 |
| P0-02 全局任务中心与可观察性 | Chat 内任务面板已接。需确认/补齐跨会话任务浏览、可展开调用/成果/日志以及完整用量可见链；不把面板存在当全局中心完成 | `flutter/StudyHostFacade.kt`、Pigeon、`agent_task_panel.dart` | 所有授权任务可查、状态与回执一致，旧档案回包丢弃，操作有真实 ACK |
| P0-03 用量、费用和异常通知 | 请求账本已接，当前统计页仍主要汇总消息库 token。需从真实账本贯通逐调用/任务/模型/供应商/日周月，补费用来源、估算与未知、导出及异常提醒 | `AgentTaskStore.kt`、`ProviderRequestAccounting.kt`、`StatsVM.kt`及 Flutter 展示 | 后台/重试/辅助角色不遗漏、不双计；unknown 不记 0；无费用硬预算 |
| P0-04 Skills/MCP/计算沙箱 | Skill 发现/读取已有；主 Chat MCP 和共享 workspace 当前明确停用。需补 owner 与细粒度授权、安装/更新/撤权、脚本隔离、依赖审批、网络/资源限额和结果 verifier | `ChatToolFactory.kt`、`SkillManager`、`McpManager`、workspace/服务端 sandbox | 真实扩展可调用且不可从描述自授权；跨档案/撤权/恶意脚本反例成立 |
| P0-05 云端续作和设备执行器 | 原生状态与确认控制已有；远程权威 TaskStore、账户鉴权、云凭据 vault、远程 executor、fence 移交与跨 App 接管链尚未完整实现 | `AgentScheduler.kt`、`AgentTaskStore.kt`；Pro AUTH-03，受控服务器/设备 | 手机离线仅真实托管步骤继续；旧 executor 确认停止后移交；设备恢复前台确认；未知发送不重复 |
| 支撑-01 数据保全与删除恢复 | Memory/备份/材料版本已有。需补整个删除→缓存/索引/备份→恢复链，以及旧 Compose/旁路执行的授权和模型边界验证 | `LearningMemoryStore.kt`、`BackupManager`、`EncryptedBackup`、OCR cache、旧入口 | 删除和撤权不复活；聊天删除不误删独立成果；旧库迁移/恢复可验证 |
| P1-01 专业 Office | 安全局部编辑已经接线。需补复杂 DOCX 样式/图片/表格/OMML，PPT 母版/形状与逐页视觉验证，XLSX 多表/日期/图表/真实重算，PDF 原页操作和可编辑转文档 | `MaterialEditor.kt`、`MaterialReader.kt`、`OfficeConversionRepository.kt`、document-service | 保原件和版本；目标准确、无关区域保全；实际渲染/重算和跨应用打开核对 |
| P1-02 MinerU/LibreOffice 真实链 | API/运输/恢复有基础。需实际受限 worker 运行、覆盖率/物理页/符号证据、缺字体、worker 死亡、撤权/取消窗口、产物回读与清理 | `server/document-service/README.md`及实际 worker/container | 真实引擎产物正确且可打开；不能用假文件头/文本提取冒充转换或版式 |
| P1-03 资料库需求闭环 | 导入/浏览/版本/检索基础保留。需逐项核对/补齐授权目录、跨会话版本引用、文件夹/标签/索引、删除与独立成果生命周期、下载保存恢复 | `MaterialFiles.kt`、`FilesManager.kt`、Flutter files/artifacts 页 | 需求 FILE-01/02/08/10 真正贯通，不只当前会话临时附件 |
| P2-01 精确核验器原生接线 | Python Q[x] 恒等/形式导数/表达式等式链存在；原生注册表将 `math_verify` 标为 UNSUPPORTED，ChatToolFactory 未加入可调用实现 | Python `math_verify.py`、`TrustedToolRegistry.kt`、`ChatToolFactory.kt` | 同一 Agent lease/grant/journal/receipt；验证范围明确；不支持/反证不作成功 |
| P2-02 专业数学与手写批改 | 教学/图形基础已有。需 CAS/高精度/误差界、定理条件、手写符号确认、首错前缀验证、正确非标准解法和真实题库基准 | 数学工具/受限计算、OCR、教材检索 | 高中/高数/分析/实变分题型真实验证；采样不作证明；无基准不报准确率 |
| P2-03 教材引用与图形质量 | 材料检索/图形有基础。需真实版本+页内来源、物理页/印刷页区分、缺页覆盖，以及奇点/定义域/3D/可编辑导出逐项核对 | 材料 Reader/parse/数学图形/UI | 引文可打开核对，图像和导出不冒充可编辑数据/数学证明 |
| 交付-01 Release 与 AAR | Debug 已构建；当前 Gradle 仍只声明 Flutter debug AAR。需生成/接入 release AAR、核对签名与依赖、构建和冷启正式包 | `client/app/build.gradle.kts`、Flutter AAR、Release 构建 | 真实 Release Flutter 启动与包内容通过；不能以 Debug 替代 |
| 交付-02 真机与可访问性 | 模拟器已有表面/故障证据。需 Mate50 HarmonyOS4.2、后台/锁屏/SAF/大字/200%/TalkBack 真验证 | 已授权目标设备、UI与后台入口 | 真机操作与停止/恢复成立；没有设备保持 NOT_RUN |

建议接续顺序：先完成 P0-01 的真实任务切片及 P0-03 用量可见链，同时推进 P0-04 安全扩展；云端/设备从受控 fake executor 合同开始。P1 以一份复杂 Office 文档的真实编辑→版本→引擎→回读→预览闭环推进；P2 先把限定范围精确核验接入统一任务链，再扩专业能力。Release 接线可与功能推进并行，正式发布仍等待必要验收。

百度网盘、邮箱、日历专用连接器继续后置；不计入当前必须补做的首批任务。自动换主模型/推理强度和 Token/费用硬预算不在允许实现范围。

## 5. 下轮可直接落地的入口

1. P0 规划基线：先读 `TaskPlanningRuntimeTest.kt` 和运行脚本，再使用新的 run-id 执行 `python scripts/verify_agent_android.py --build --run-id planning-next-unused --classes me.rerere.rikkahub.feature.agent.TaskPlanningRuntimeTest --isolate-tests`。该命令仍只验证合成 Provider；真实质量验收需要另立 endpoint/材料/收费范围合同。
2. Office：先读 service README/worker 脚本并执行 `docker info` 探测环境；已有安全基线为 `python scripts/verify_agent_android.py --build --run-id office-next-unused --classes me.rerere.rikkahub.feature.material.OfficeSafetyRuntimeTest`。安全基线不替代引擎保真。
3. 扩展/备份：先 `rg -n 'Backup|Restore|Mcp|Skill|tombstone' client/app/src/test client/flutter_ui/test` 定位真实行为测试，然后按独立实现合同接线；保持现有停用边界直至安全契约补齐。
4. Release：在 Flutter 工作目录审查并生成 `flutter build aar --debug --release --no-profile`；补齐 Gradle release 依赖与安全签名后执行根目录 `.local/gradle-9.6.0/bin/gradle.bat -p client :app:assembleRelease --offline --console=plain`，再验证正式包实际入口。

每批遵守根 AGENTS 和 Pro 新功能门禁，保留旧失败、源码/产物 hash、验收矩阵、回滚及六类 SOL 交接；不开启子代理。真实付费 endpoint、材料新增外传、云部署、跨 App/生产动作依既有明确授权范围执行。

## 6. 主要出处

- 最终范围：`HXai_Pro_Delivery/inputs/01_SCOPE_DECISIONS.md`、`02_FINAL_PRODUCT_REQUIREMENTS.md`、`03_P0_SMART_CHAT_SPEC.md`、`04_P1_FILES_P2_MATH_SPEC.md`、`13_REQUIREMENT_ACCEPTANCE_SCENARIOS.md`。
- 累积增量：`docs/pro-development-baseline-20261012.md` 第 7–11 节、`docs/agent-model-native-protocol-v7.md`（v1.8）。
- 最新局部验收：`HXai_CHANGE_REVIEW/MULTI-ARTIFACT-EVIDENCE-20261013/SOL_ACCEPTANCE_MATRIX.md`、`SOL_BLOCKERS.md`、`TEST_RESULTS.json`、`SOURCE_AFTER.json`。
- 前批桥接/角色/Flutter：`HXai_CHANGE_REVIEW/REMAINING-FIXES-20261013/SOL_IMPLEMENTATION.md`、`SOL_ACCEPTANCE_MATRIX.md`。
- Office 安全：`HXai_CHANGE_REVIEW/FILE-05-OOXML-PPT-20261012/SOL_IMPLEMENTATION.md`、`SOL_ACCEPTANCE_MATRIX.md`；Office 原子元数据增量见 `CHAT-LEASE-REGRESSION-20261013`。
- 本轮当前源码抽查：`TaskPlanner.kt`、`AgentTaskStore.kt`、`ChatService.kt`、`ChatToolFactory.kt`、`TrustedToolRegistry.kt`、`OcrTransformer.kt`、`StatsVM.kt`、`agent_task_panel.dart`、`app/build.gradle.kts`。

本报告的完成结论限上述有证据的范围；剩余项中的“先核对/补齐”是需求差距待查，不能自动当作已证实缺陷。没有给出完成百分比，也没有授予完整产品放行。
