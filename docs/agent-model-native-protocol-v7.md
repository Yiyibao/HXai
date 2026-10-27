# 原生任务协议增量 v1.4 / sidecar schema7（2026-10-13）

来源：Pro 的 NEW_FEATURE_REVIEW_STANDARD、PROMPT_NEW_FEATURE_REVIEW、PROJECT_FULL_REVIEW_STANDARD、PROMPT_FULL_PROJECT_AUDIT；产品 inputs/01—04、13、14；CURRENT-FEATURES-20261012 的 R01—R05；本轮 REMAINING-FIXES-20261013 的逐批 CHANGE_CONTRACT。继承 protocol-v5 v1.2 与 review-v1_3，原始规范、历史源码定位和失败证据保留。本增量不追溯修改旧版本的实现状态。

适用：base `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`，保全未提交成果后的 `codex/remaining-review-fixes-20261013` 工作树。被测原始字节由各 run 的 SOURCE_INPUTS.json、APK_INPUTS.json 和日志固定；不同 run 不能互相代替。

## 执行和物理请求

ChatService → 单例 AgentScheduler → 原 ConversationSession/GenerationLoop；辅助角色通过 ModelRequestRunner 接入同一工作域执行位。自动记忆、翻译、图片创建/编辑、材料图片、标题、建议、OCR、压缩、连接测试、凭据验证均使用接受配置及 ProviderRequestAccounting。材料工具的图片请求是当前任务的嵌套角色，不另开执行器。

接受时 CredentialVault 保存 JSON 深拷贝配置，排队后的设置变化不修改配置。角色 provider/model/reasoning 明确；未启用或已撤权的角色不退回主模型。物理 SDK 请求先登记稳定 request_id/call/nullable usage，再发 HTTP，已知回执幂等入账。UNKNOWN 保留执行位，禁止自动重放；不设 Token/费用硬预算。

## 工具投影和真实回执

GenerationLoop 使用 ToolTextProjection，只限制送给 Provider 的文本投影：32KiB 文本界限及4KiB预览，保留非文本部分。Room 工具结果和交互 JSON 保持完整；摘要输入也使用受限投影。删除 toolCallId 拼本机路径和未注册 workspace_shell 的虚构读取指引。恢复后从完整持久回执重新投影。

## 授权、计划和验收

TrustedToolRegistry 的 capability/risk/verifier 由本机源码定义，模型或材料不提供授权。每次工具 dispatch 核验 owner/task、scope、grantEpoch、inputHash 和当前 fence。AgentToolDispatch 还把实际文档 RPC 绑定到原工具租约；更新凭据只更新冻结配置的密钥，不修改模型、scope、主动撤权、手动暂停、输入和设备等待。

sidecar schema7 保存独立授权/等待、输入绑定批准回执、计划/目标修订、已验证步骤与目标验收证据。Room32 不变。真实 TaskPlanner 请求与独立验收请求同样入账；验收读取实际 owned 文件、版本、hash/内容、显式记忆的真实记录及出处。记忆是 SELF_REPORTED，不是已掌握证明。验收请求期间成果或来源变化会拒绝发布成功。

补充要求保留已验证副作用，重规划不能凭新计划删掉 UNKNOWN 或重做已知效果。普通读工具不复用历史结果。SUCCEEDED 必须有当前 goal_revision 的持久 acceptance_verified；否则为 COMPLETED_UNVERIFIED。原 R01 三个反例仍为必要门禁。

异步文档下载不得通过新建 USER 消息偷换原任务。原生 RETURNED 回执、工具输入、远端任务身份、原用户消息和会话分支一致时，才允许在原 Task/冻结配置下独立回读、更新同一工具回执并继续。提交丢 ACK 的 SUBMITTING 不算 RETURNED。不能把保守阻断本身当作完成恢复功能。

## 迁移和 UI

原生5/6→7在 SQLite 原库事务中增量迁移，不重建、不清空 task/call/usage/slot/fence/UNKNOWN/旧回执。未来 schema 拒绝打开。schema6 的回归资产来自旧 Android 运行库，不是 Python 生成的协议替身。

Pigeon 的任务 DTO/命令接入真实持久控制、修订和 fence；owner/profile/contextEpoch 不一致的异步响应丢弃。任务模型锁取接受配置，当前选择仍是当前选择。STOP_REQUESTED 与 PAUSED_BY_USER 分开；批准工具和补充要求不能清除手动暂停。

## 已观察证据与限制

- `remaining-native-full-approval-04-20261013`：49项原生通过；同 APK 的 process-approval-04 五个真实进程终止/独立回读点通过。
- `remaining-native-full-memory-05-20261013`：51项通过，含真实显式记忆目标验收与验收期间记录变化反例；38个 Flutter 资产与被测 APK 一致。
- `remaining-remote-surface-baseline-10-20261013`：真实异步回调使用当前选择而非原任务模型，反例失败已保留；后续修复必须用新的 run 证明。
- Flutter full-final-surface-03：287项通过；analyze-final-surface-04：正常 analyze exit0，无降低门槛。

以上不是最终当前源码全面通过；后续异步续接、表面交互、最终普通 Debug/lint/JVM 和进程终止门禁以新 run 为准。真实付费模型规划/验收质量、真实 Office 引擎、Release、Mate50、生产、TalkBack 真正朗读均不能由合成 HTTP、控件测试或模拟器局部通过替代。独立外部审查 NOT_EVALUATED；开发及对抗复验为同一执行者。

源码映射：`feature/agent/{AgentTaskStore,AgentScheduler,ModelRequestRunner,AgentPlan,TaskPlanner,TaskResultVerifier,AgentToolDispatch}.kt`；`data/ai/{GenerationLoop,ToolTextProjection,TranslationHandler}.kt`；`feature/memory/MemoryExtractionRunner.kt`；`feature/material/{MaterialTools,DocumentServiceRepository,OfficeConversionRepository}.kt`；`service/ChatService.kt`；`flutter/StudyHostFacade.kt`；`ui/pages/imggen/ImgGenVM.kt`。各文件的最终 SHA 和可审阅补丁在本轮交付证据中固定。

## Sol 接续校准 v1.5（保留 schema7 / Room32）

2026-10-13 接续未提交工作树，原 OMP 合同和失败日志保留。生成入口及 OCR 必须存在已接受的 AgentExecution；不存在租约时在物理请求前拒绝。相同仍持有的 fence 可结算 UNKNOWN 工具回执，相同 ACK 幂等、冲突或过期 fence 拒绝；结算保留所有独立等待条件，不重复工具效果。SUBMITTING 的远端提交仍保持 UNKNOWN 和执行位。

前台已知 FAILED/CANCELLED/EXPIRED 文档重试使用原任务/模型/目标和当前分支，通过持久的输入绑定审批产生新的调用尝试；旧失败任务和回执保留。Observer 只能独立读回已确认提交，不自行重试未知上传。原任务有暂停、撤权或独立 blocker 时不能开始这条路径。

当前接续证据：`sol-remaining-full-01-20261013` 原生完整选定类列表 57 项 PASS（包含迟到 ACK、无租约、PDF 续接和已知失败重试）；同 APK `sol-remaining-process-01-20261013` 五个真实进程终止点 PASS。Flutter 当前源码 287 项 PASS、analyze exit0；Pigeon 重新生成双端后与保护快照逐字节一致。接手时新增的重试测试曾因不存在的 query 方法构建失败，之后因读取旧失败任务的 artifact 前置条件失败；两次失败原始日志和修复后结果一起保留，未削弱模型锁、上传次数、来源/hash 和旧失败记录保全断言。

最终 Gradle、源码/产物一致性、可审阅补丁与适用范围由 `HXai_CHANGE_REVIEW/REMAINING-FIXES-20261013/` 最终交付固定；不将 57 项局部原生验证授予完整 App、真实模型质量、Release/Mate50 或生产 PASS。独立外部审查继续 NOT_EVALUATED。

## 队列命令接线校准 v1.6（仍为 schema7 / Room32）

Sol 在实际接线复验中发现：selectedTask 显示队列 B，但 taskCommand(resume) 续接 highest-fence 的上一任务 A。`sol-queued-command-before-20261013` 真实本机 HTTP 反例失败，模型期望 qa-accepted-queued，实际 qa-previous-task；不是仅静态推测。

修复保持原命令表和配置：AgentTaskStore.commandRecorded 查询已有命令；StudyHostFacade 仅对尚 QUEUED 的 resume 意图续接，对重复 supplement ACK 不再次触发推理；选中队列任务经 ChatService.resumeQueuedTask 持久恢复 Room 队列，再由原 sendQueuedMessage 使用已接受 task，不改模型或重放 A。该方法不解除其他任务的 manual pause，独立授权/输入/设备 blocker 保持。

新增 QueuedTaskCommandRuntimeTest 三项原生测试：恢复 B 使用 B 冻结模型、补充 B 要求沿 B、相同 ACK 不再发请求、输入阻断时队列仍暂停且零请求。定向三项 PASS，并进入 verify_agent_android 默认类列表；`sol-remaining-full-02-20261013` 当前 60 项原生 PASS，同 APK `sol-remaining-process-02-20261013` 五个真实终止点 PASS。最终当前 JVM/普通 Debug/lint 与源码/补丁哈希仍以本批 TEST_RESULTS 为准。

## 完整计划验收校准 v1.7（仍为 schema7 / Room32）

2026-10-13，`codex/plan-completion-gate-20261013`，base/HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 的未提交工作树。原规范及历史失败保留，当前原始字节和源码映射由 `HXai_CHANGE_REVIEW/PLAN-COMPLETION-GATE-20261013/` 固定。

真实 ChatService / localhost Provider / Word / SQLite 反例证明：计划两个文件只执行首步，验收谓词只引用首步且语义模型返回 accepted=true，旧版会错误发布 SUCCEEDED。原生完整两类15项中11 PASS / 4 FAIL，包含持久层直接接受PLANNED/RETURNED步骤与旧partial marker；正常双文件和全部VERIFIED的持久对照原版已PASS。

现在 TaskPlanner.accept 在独立语义请求前拒绝任何未VERIFIED计划步骤；AgentTaskStore.acceptGoal 事务内再次要求全部步骤VERIFIED；finish 读取当前goal revision的acceptance marker时排除未VERIFIED步骤。旧验收原始证据不删除、历史终态不批量回写，但旧partial marker不能授予新的成功发布。模型锁、授权、等待、fence与UNKNOWN优先级保持，不增加重试、预算或新执行器。

固定后完整默认原生 run `plan-completion-full-fixed-20261013`：67项批量 + 40项逐方法全部PASS，四个旧失败反例在相同测试源码上转PASS；987个源码/schema字节与运行输入一致。JVM、普通Debug/lint、同APK真实进程终止与产物绑定的结果以本批TEST_RESULTS为准，不能沿用历史计数。

新增门禁：未被acceptance引用的PLANNED步骤也阻断成功；RETURNED不是VERIFIED；全部VERIFIED的多步骤仍可通过当前目标的证据绑定验收；旧partial marker不能绕过finish。没有schema/Pigeon变更，原UI继续按真实任务终态显示，不把历史marker单独当新成功。真实模型语义质量、专业Office引擎、Release/Mate50/生产仍NOT_RUN；独立外部审查NOT_EVALUATED。

## 多文档成果证据校准 v1.8（仍为 schema7 / Room32）

2026-10-13，`codex/multi-artifact-evidence-20261013`，base/HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 的未提交工作树。继承v1.7全计划步骤VERIFIED门禁，原规范/历史证据保留；当前字节与来源在 `HXai_CHANGE_REVIEW/MULTI-ARTIFACT-EVIDENCE-20261013/`。

TaskPlanner.accept 现在从全部VERIFIED步骤的持久Document回执定位可信owner-scoped生成材料，独立MaterialReader回读所有文档，不只读acceptance谓词所引用的第一份。结果按artifactId去重，每步result_json只解码一次，owned URL索引仅在出现文档时建立；谓词仍检查format/contains/指定sourceId/sourceHash。每份证据含真实artifactId/format/sha256/sourceId/sourceHash/content/coverage，全部进入原evidenceHash；同接受配置的semantic请求返回后复查全部成果与来源快照。

原两份正确Word因缺第二证据而COMPLETED_UNVERIFIED，和未引用第二文件在验收期间被改动却SUCCEEDED，两项真实Service/Word/SQLite反例FAIL。修复后相同测试转PASS；第二份错误内容仍COMPLETED_UNVERIFIED，验收期字节变化FAILED_BLOCKED且不写成功marker。损坏注入仅作用于方法新建合成文件并finally恢复原bytes。

初版完整默认run `multi-artifact-full-fixed-20261013` 为70批量+40逐方法PASS；追加正常来源/合法源版本变化边界后，最终 `multi-artifact-source-fixed-final-20261013` 为72批量+40逐方法共112项PASS、987源码/schema绑定。来源关系由可信夹具准备，不计实际编辑器/专业保真；新增夹具suspend编译FAIL及runBlocking修复保留。最终同APK `multi-artifact-process-final-20261013` 五实际停止/重开点PASS，JVM410产品SHA不变、最终测试源码普通Debug/lint PASS（50warnings）；patch/版本/产物见TEST_RESULTS，不累加历史。

证据JSON字段为原持久evidence_json的增量，不改sidecar7/Room32/Pigeon或输出response schema，不新增执行器/权限/模型路由/预算/retry，不回写旧终态。此能力仅覆盖文档成果独立文本/来源证据，图像语义、专业Office渲染/重算和真实模型语义质量不能据此升级PASS；Release/Mate50/生产仍NOT_RUN，独立外部/完整全项目审查NOT_EVALUATED。

## 计划调用覆盖校准 v1.9（2026-10-14；仍为 schema7 / Room32）

A 线接续 `codex/agent-usage-ledger-20261014` 的完整未提交基线，HEAD仍为 `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。精确源码、旧版失败、当前验证与适用范围见 `HXai_CHANGE_REVIEW/A-REPLAN-COVERAGE-20261014/`，原 Pro 规范与 v1.8 保留。

`TaskPlanner.ensure` 按工具名统计本轮实际待 dispatch 的调用数量，与 PLANNED 步骤数量逐类比较。一条计划步骤不能覆盖多次同名调用；保留的 VERIFIED 步骤不算新增执行名额。只有 GenerationLoop 实际从独立已核验回执复用的效果豁免新步骤；普通读取仍重新 dispatch。数量不足先按原锁定模型重规划，新计划仍不足则在该批副作用前拒绝。重复工具调用 ID 也在批次开始前拒绝。

`AgentTaskStore.beginCall` 在同一 journal 事务中核对已发布计划的 goal/planned revision，并绑定一条未消耗的同名 PLANNED 步骤，再写 IN_FLIGHT。无可用步骤或追加目标尚未重新规划时事务拒绝，不新增 calls、不更改旧回执。原无 action plan 的受信任兼容入口仍保留，不能由此跳过真实 GenerationLoop 的规划入口。`publishPlan` 保留 VERIFIED id/tool/call/state/result，更新其 purpose/ordinal 以符合新计划，不继续展示旧序号。

实际旧版7项中4项FAIL：重复消耗步骤、旧目标计划dispatch、重规划投影顺序不一致、真实服务一条计划执行两次Word写入且错误SUCCEEDED；原始日志不覆盖。修复版定向20项PASS，包括真实Chat/Room/Pigeon追加命令、保task ID/锁模型、原Word字节/hash/receipt、新Word独立回读、旧效果回执复用、重复ACK、丢弃旧步骤/数量不足的安全拒绝。完整默认回归、普通构建和真实进程终止的最终结果以本批TEST_RESULTS为准，不将不同轮次重复用例相加。

数据形状、授权/grantEpoch、fence、独立blocker、UNKNOWN、安全限额和用户模型锁均不变；无预算硬限或新执行器。真实endpoint规划/语义质量、全局任务中心、专业Office引擎、云设备、Release/Mate50/生产仍未由此验收；独立外部/完整全项目审查NOT_EVALUATED。



## v1.19 A线孤立v0来源删除约束保全（2026-10-19）

来源合同：HXai_CHANGE_REVIEW/A-LEGACY-SOURCE-PRESERVATION-20261019/CHANGE_CONTRACT.md及CONTRACT_AMENDMENT_SCHEMA2.md。LearningMemoryStore迁移逐一登记可映射v0墓碑；未映射HMAC原值保存在legacySuppressedSources，不逆解正文、不以便携key重算旧marker。此类payload显式schemaVersion2并保持recoveryBlocked，旧schema1恢复器不得消费；健康数据及旧缺字段JSON仍兼容新读取器。Room32/sidecar9/Pigeon164不变。

未映射来源存在时，主/摘要/辅助请求的历史投影及历史来源查询明确拒绝，context/relevant为空且自动抽取停用；管理记录与其他owner保留。迁移读取消息前核对会话profile，不能用其他owner正文认证来源。MemoryRestore合并两侧未映射集合和保护状态/最大payload版本；BackupManager在staging核验版本和保护一致性。没有新增执行器/权限/模型路由或费用预算。

实际测试、源码/APK/patch哈希及裁决以本批TEST_RESULTS/SOL_TEST_EVIDENCE/HANDOFF为准；本节不预授PASS。既往已经丢失的v1墓碑不能凭空恢复；全备份语料、其他辅助入口/旧派生物、B共享集成、真实模型、云设备/Release/Mate50及独立/完整项目审查继续保留原未完成状态。回滚须理解payload2，保库/key/墓碑/UNKNOWN，不降级保护。


## v1.20 A线接入B精确数学工具（2026-10-19）

来源：HXai_CHANGE_REVIEW/A-B-MATH-INTEGRATION-20261019/CHANGE_CONTRACT.md；B最终来源为B-RATIONAL-STURM-BOUND-20261019/SOURCE_FINAL及四共享候选，按before/candidate/current逐字核对后仅归并数学部分。Factory注册mathVerifyTool，Registry赋local.compute/COMPUTE/EXACT_MATH；TaskResultVerifier从持久输入独立重算，拒绝伪状态/错输入/改证书/跨owner和非单Text结果。TaskPlanner仅允许明确Q[x]/Q(x)合同1–15范围的tool_verified，并保留全部步骤VERIFIED、原文档/记忆/语义hash及用户完整目标验收门禁。四共享修改不包含候选章节注册，v1.19记忆guard保持。

没有新增数学算法、执行器、外部模型、权限/费用硬预算或schema/桥接字段；Room32/sidecar9/记忆payload1或2/Pigeon164保持。B领域十文件SHA与最终交付一致；领域算法证据不能当全部Chat质量证据。本批真实Chat代表场景为恒等与有理闭区间误差界，错误/未知及语义hash不符不发布任务成功。其他合同虽统一可达，其逐题型完整目标质量仍需实际故事/真实模型验证。

准确原版FAIL、原生/JVM/Debug/lint、六任务持久输出与Fraction独立核验、产物/源码SHA和patch见本批TEST_RESULTS与HANDOFF；本节不预授未运行范围PASS。数学UI专用呈现、章节/Office主链、真实Provider、Release/目标Mate50、全A/P0、独立及完整项目审查仍不因本批自动完成。回滚只停数学入口/逆本批共享差异，保任务/receipt/UNKNOWN/材料/记忆payload2，不放松原门禁。


## v1.21 — 章节共享接线（2026-10-19）
A-B-CHAPTER-INTEGRATION-20261019在v1.20上接B3o三章节工具/local.read/真实源重推导步骤Verifier。TaskPlanner对所有已VERIFIED章节步骤在目标语义验收前后绑定owner/material_id/version_id/source_sha256/index_sha256并复验真实字节，包括非谓词单列步骤；源变化/删除不得沿用旧回执成功。只支持固定版本提取标题候选与正文读取/字面搜索，不推断整章完整、印刷页或数学结论。Room32/sidecar9/Pigeon164/记忆payload1或2及数学EXACT_MATH/旧记忆guard不变。实际证据/限制以本批TEST_RESULTS/HANDOFF为准；独立及全项目审查NOT_EVALUATED。
## v1.22 — B 来源定位与跨材料搜索主链接线（2026-10-19）

来源合同：`HXai_CHANGE_REVIEW/A-B-SOURCE-INTEGRATION-20261019/CHANGE_CONTRACT.md`。B2d 原搜索器已由 B2i 抽出有界 `find`，以 B2i 最终字节核对当前版本；B2e/B2f 两领域最终 SHA 保持。原 Pro 规范及三个领域合同不改写。

`ChatToolFactory.createTools` 注册 `read_material_source_location`、`search_material_text`、`search_material_library`，`TrustedToolRegistry` 指定原 `local.read`/READ 与新增可信 verifier `SOURCE_READBACK`，没有新增 capability 或放开外发。`TaskResultVerifier.verify` 从 Room 持久原输入独立调用 MaterialFiles 实际冻结/hash/Reader，严格比较完整单 Text 页，包括正文、来源、缺字/覆盖、全部命中、无命中扫描源、目录摘要和游标；NOT_AVAILABLE/失败或非单 Text 不证明成功。

`GenerationLoop.generateText` 将真实工具回执传给 `TaskPlanner.accept`。每个 SOURCE_READBACK 步骤按 callId/tool 取原输入并与当前 task journal 的 inputHash/resultJson 以及 Room 回执核对；语义验收前后分别重新提取整个请求页。验收谓词未单列的来源步骤同样检查，目录新增/删除、无命中源变化不能借旧 hits 成功。保留全部计划步骤 VERIFIED、原 goalRevision/evidenceHash、模型快照/授权/fence/UNKNOWN 及 v1.19 记忆保护。

Room32/sidecar9/Pigeon164/记忆payload1或2保持，不迁移旧库，不改原材料；三工具只声明有界提取文本/PDF物理页/PPT含隐藏逻辑页与当前目录分页，不声明整章/整书/版式/OCR/印刷页或数学证明。文件系统并非跨文件原子快照，验收是实际重验时的证据事实，不能承诺未来源永久不变。

实际失败、当前源码/安装APK/独立XML与UTF16/停机SQLite、测试及范围裁决以本批 TEST_RESULTS 与 HANDOFF 为准，不由本文预授 PASS。真实 Provider、Office 共享重算、Release/Mate50、完整 A/P0、独立与全项目审查仍分别评估。回滚只停三入口/逆本批差异，保任务/receipt/UNKNOWN/材料/删除墓碑和原数学/章节。

## v1.23 — XLSX 真实重算主 Chat 闭环（2026-10-19）

来源：`HXai_CHANGE_REVIEW/A-OFFICE-CHAT-20261019/CHANGE_CONTRACT.md`。接续 v1.22 完整脏树，逐段整合 B1b 七共享提案而非重放旧文件。Factory 注册 `recalculate_material_xlsx`，沿用可信 `document.remote` / EXTERNAL / REMOTE_READBACK；原上传策略、逐调用审批、模型快照与唯一 Scheduler 保持。Store 将该工具 SUBMITTING 记为 UNKNOWN，保执行位；有确认回执时按原 task/call/inputHash/grantEpoch 续接，不创建用户消息、不后台重传。

`OfficeConversionRepository.calculationEvidence` 在实际工作簿、manifest、witness 及来源独立回读后给出绑定摘要与明确 proof。TaskResultVerifier 将持久原输入/输出与同 owner/operation/source/outputId 的真实 Office task 核对。TaskPlanner 对每个重算步骤（包括未被谓词单列者）检查 journal/Room 原回执，将真实重算证据纳入 evidenceHash，语义返回后再次回读所有该类步骤；见证损坏、改源、取消、撤权不得沿用“文件仍存在”成功。原数学/章节/来源及记忆保护不回退。

Host 异步成果使用实际扩展名 MIME，避免续接后重复文件卡；卡片明确服务排队/下载/读回失效/取消意图与确认。Office 预览、打开、保存、分享会重验绑定材料；保存目标返回后再次重验，不以历史成功标签绕过缺见证或取消。Room32 / sidecar9 / Pigeon164 / memory payload1或2 / manifest-witness v1 保持，无新执行器、模型路由、权限种类或费用硬预算。

本轮实跑：9 个合成主 Chat 场景调用真实本机 FastAPI/LibreOffice 26.2.4.2；实际 Chat 产物原生重开核对日期显示、多表/隐藏表、零值、001、公式、图表对象与更新缓存。56 项原生定向旧回归、129 服务回归、403 Flutter 测试、analyze/AAR、480 App JVM、普通 Debug/lint 通过。原入口 FAIL、仅接线后见证竞态假成功 FAIL、合成身份触及真实存储上限导致 UNKNOWN 的夹具 FAIL 和修复记录均保留。准确来源/APK/日志以本批 TEST_RESULTS 为准；不累加历史通过数。

证明范围仅保存公式缓存/实际指定数据和本机兼容渲染，不承诺 Microsoft Office/WPS、版式、Linux/Docker、Release/Mate50、真实模型质量或生产。独立及完整全项目审查 NOT_EVALUATED；同一执行者完成定向反证。可重复入口：`python scripts/verify_xlsx_chat_android.py --build --run-dir <未存在目录> --serial emulator-5554 --port <空闲本机端口>`；只安装/归档后重置合成 nativeqa 包。回滚保留原件、版本、manifest/witness、receipt/UNKNOWN 和授权 epoch，仅停新入口或逆精确增量。

