# S0 数据合同 v1

2026-10-07 通用文件增量：当前文件仓储采用档案私有目录及逐文件原子 JSON 登记，未新增 Room 迁移；实体保存 profileId/id/name/extension/output/state/processingState/detail/size/sha256/createdAt/requestKey/sourceId/pageNumber。正文类型为通用 body/rows/slides，不限 NOTE/SUMMARY/EXERCISES；输出格式为 md/pdf/docx/xlsx/pptx，图片工具返回真实 PNG。input 与 output 区分，文件内容不作为本人表现。任务固定原 profile，模型只指定内部 ID 或受限输出内容。文件成功需实际文件、SHA-256及格式重读；PDF 页图 sourceId/pageNumber 对应原文件及物理页。完整合同及限制见 [文件执行说明](material-execution.md)，下文旧格式表保留为历史逻辑设计。

2026-10-07 S2 增量：Room 30 使用按 profile_id 聚合的版本化 learning_memory_state 文档，来源/记忆/修订/抑制/Job 原子提交；原始消息保留上游存储。下表是逻辑实体设计，本期未逐张建表。来源摘要也使用按档案 HMAC；删除约束覆盖旧消息全部编辑版本。成功 Job 的来源集合与 rangeHash 充当游标，没有另存画像摘要。实际字段、范围与验证见 [S2 执行说明](s2-execution.md)。

2026-10-02：字段与本地约束冻结为实施合同；尚未创建 Room 表或完成 Android 验证。实际迁移在 S1/S2 实施，不得以本合同宣称已有自动记忆能力。破坏性修改须提升 schema_version 并提供迁移，不修改旧备份含义。

## 统一类型与身份

ID 用小写 UUID 字符串；时间用 UTC Unix 毫秒整数；revision 从 1 起递增；delete_epoch 从 0 起单调递增。正文 UTF-8。数据库字段用 snake_case，Kotlin 使用 camelCase 映射。布尔值在 SQLite 使用 0/1。

所有学习实体携带 profile_id。模型不得指定 profile_id、文件路径、origin、status 或当前 revision。仓储方法显式接收应用生成的 LearnerContext；后台任务固化创建时的 profile，不能读取 UI 当前档案替代它。跨表外键采用包含 profile_id 的复合唯一键，防止串人关联。

上游 Conversation.id、MessageNode.id、UIMessage.id 保留。上游当前分支通过每个节点的 selectIndex 表示，不能把会话 ID 当成分支 ID。本项目新增 BranchSnapshot：按有序 node_id + selected_message_id + message_revision 集合产生不可变 source_range_hash，分支/编辑变更产生新快照。引用使用 application-issued source_id 映射到快照，不让模型拼装 UUID 或旧来源版本。

### Flutter 详情身份边界（2026-10-10）

文件、记忆、文档任务、成果预览/版本详情及记忆编辑、原页核对路由只携带 `ProfileResourceRoute(id, profileId, epoch)`；不得携带正文 DTO 并在查询缺失时兜底。当前档案和当前 `contextEpoch` 的成功查询是唯一显示依据。加载和错误状态不复用旧结果，删除后或来源身份失效时显示不可用，不保留旧标题、来源、编辑草稿或操作按钮。备份恢复引起 epoch 变化时，返回栈内旧页面同样不可用，必须从当前列表重新打开。

确认删除弹层也实时查询身份，不保留正文快照；保存、核对、删除、来源跳转和文件追问在执行前重新确认对象存在，迟到响应不能触发旧页面的成功提示、跳转或新档案刷新。编辑器在身份、条目版本变化或查询撤销时销毁草稿。

Flutter 应用从原生备份/文件工具返回时，根组件先撤销 bootstrap 与档案列表缓存；bootstrap 身份重新成功确认前详情不可显示。文件路径等异步查询完成后，追问仍需重新确认来源存在，不能发送已撤销文件的旧引用和正文。

成果列表 DTO 的 `statusDetail` 仅为处理状态；`previewContent` 只允许真实文件提取正文，`previewCoverage` 描述提取范围。列表不提取正文，详情通过 `getArtifactPreview(profileId, artifactId, expectedEpoch)` 请求原生校验及提取；不能把状态说明、提取异常或系统打开提示渲染成文件正文。

回归入口：`client/flutter_ui/test/detail_isolation_test.dart` 和 `profile_isolation_test.dart`。UI smoke：A 档案打开文件/记忆/任务详情，进入核对/编辑并输入草稿；切换 B 档案或恢复备份后从返回栈回到这些页面，应仅显示不可用且没有 A 的正文和可提交按钮；删除记忆后返回详情不得恢复旧正文。网络延迟下切换档案再释放旧查询/保存响应，不应出现旧正文、成功提示或跳转；重新从当前列表打开仍能正常编辑和保存。

Android 实际表面 smoke：`DetailIsolationSurfaceTest` 的删除和档案切换两项本轮均在模拟器通过。从 `/files` 点击真实私有文件，应用暂停时删除仓储文件或切换档案，恢复后验证详情仍在返回栈但只显示“文件不可用”、无旧文件名和操作。回归命令为 `adb shell am instrument -w -e class me.rerere.rikkahub.service.DetailIsolationSurfaceTest org.hxsi.agent.debug.test/androidx.test.runner.AndroidJUnitRunner`；实际撤销截图已保存到 `qa/review-20261010/review-fixed-detail-deleted.png` / `review-fixed-detail-profile.png`。恢复 epoch、编辑草稿与迟到响应边界另由 Flutter widget 回归覆盖，不把它们冒称真实备份文件恢复验收。

实际导航判定要求真实文件名与可执行“查看原文件”操作共同出现，不能依赖输入框占位文案或把 accessibility click 的返回值当导航成功。删除/切换后断言旧文件名撤销且原文件操作不可执行。场景在打开、撤销及超时处同时保存截图和 `.tree.txt`，包含节点 text/contentDescription/hintText、class、enabled、clickable、editable、visible 和 bounds；超时断言附完整当时树，便于区别导航失败、错误空态与输入提示的 Android 语义字段差异。


## 核心实体

| 表 | 冻结字段 | 约束 |
|---|---|---|
| learner_profile | profile_id, nickname, locale, timezone, auto_memory_enabled, delete_epoch, created_at, updated_at | nickname 非空；首次启用自动记忆需说明；timezone 为 IANA 名称 |
| conversation_owner | profile_id, conversation_id, created_at | conversation_id 全局唯一；所有权不可被模型修改 |
| branch_snapshot | profile_id, branch_id, conversation_id, source_range_hash, created_at, is_valid | 会话所有权复合外键；快照不可原地修改 |
| message_source | profile_id, source_id, branch_id, node_id, message_id, message_revision, role, content_hash, span_start, span_end, is_valid | 位置以 Unicode code point 计数，左闭右开；原始正文存上游；编辑递增 revision；仅应用签发 source_id |
| memory_item | profile_id, memory_id, type, topic_key, fact_key, content, origin, status, created_at, updated_at, last_evidence_at, revision | type/origin/status 为固定枚举；当前条目 revision 用 CAS 更新；ACTIVE 必须有有效来源 |
| memory_evidence | profile_id, evidence_id, memory_id, source_id, evidence_type, validation_basis, material_anchor, created_at | 同一 memory_id + source_id + evidence_type 唯一；来源和记忆均属同一人；validation_basis 可空，不凭空填已验证 |
| memory_revision | profile_id, memory_id, revision, content, status, origin, changed_at, source_ids_json | 同一 memory_id + revision 唯一；彻底删除同时清除修订正文 |
| memory_suppression | profile_id, suppression_id, scope, source_fingerprint, topic_key, fact_fingerprint, delete_epoch, created_at | scope=SOURCE/FACT/TOPIC；至少有与 scope 对应的键；无已删除正文/摘录；事实指纹用本地密钥 HMAC，不能用可枚举明文哈希 |
| extraction_cursor | profile_id, conversation_id, branch_id, extractor_version, last_committed_range_hash, updated_at | 游标只在保存整批成功的事务内推进；空候选是成功 |
| study_job | profile_id, job_id, kind, branch_id, source_range_hash, extractor_version, state, attempts, max_attempts, lease_until, next_attempt_at, expected_delete_epoch, expected_revisions_json, budget_json, created_at, updated_at | profile + branch + source_range_hash + extractor_version + kind 唯一；预算/错误日志无凭据 |
| memory_summary | profile_id, summary_id, content, item_revisions_json, revision, created_at, is_valid | 仅来自有效条目；任一依赖失效即撤销并重建 |

type：BACKGROUND / PREFERENCE / PROGRESS / DIFFICULTY / PERFORMANCE / REVIEW_SUGGESTION。

origin：USER_EXPLICIT / USER_EDITED / AUTO_EXTRACTED。status：ACTIVE / CANDIDATE / SUPERSEDED / DISPUTED。学习表现：SELF_REPORTED / READ_ONLY / AFTER_EXPLANATION / WITH_HINT / INDEPENDENT_ATTEMPT / UNVERIFIED；独立尝试不等同正确或正式掌握度。

Job kind：MEMORY_EXTRACT / MEMORY_SUMMARIZE / MATERIAL_PARSE / ARTIFACT_EXPORT。state：PENDING / RUNNING / RETRY_WAIT / SUCCEEDED / FAILED / CANCELLED。租约过期仅恢复未提交范围；副作用使用幂等键。关闭自动记忆在提交事务中再次检查开关。

## 材料、成果、Skills 和能力

2026-10-08 补充：技能卸载和替换后的目录清理不跟随符号链接，技能根目录不接受指向同级技能的别名。审批失败导致的内存安全暂停，必须在随后成功审批时与决定及当前完整队列同事务落库，提交成功后再发布内存。见 [本地修复报告](../qa/coverage-completion-20261008-192645/report.md)。

当前 Skills 合同：制作、编辑和自动生成草稿暂时关闭，已有技能安装、查看、显式启用和卸载保留；仅显式启用项进入工具列表。安装输入和总展开内容上限 8 MiB，单文件 4 MiB，SKILL.md 256 KiB，GitHub API JSON 1 MiB，条目 128 个、路径/递归深度 8 层；超限在安装提交前拒绝并保留原技能。安装及默认/指定文件读取均校验 canonical 路径边界。当前全局指令包不能自动拼入学习档案；下表规划的档案关联字段不表示已安装全局包会携带档案内容。修复证据见 [本轮报告](../qa/independent-review-fixes-20261008-182645/report.md)。

| 表 | 冻结字段 | 约束 |
|---|---|---|
| material | profile_id, material_id, original_name, mime_type, byte_size, sha256, private_file_key, parser_version, status, revision, created_at | private_file_key 由应用生成，仅在档案私有目录解析；30 MiB 初始上限，待真机调整 |
| material_block | profile_id, material_id, material_revision, block_id, anchor_type, page_number, paragraph_index, table_index, region_json, text, uncertainty_json | anchor_type=PAGE/PARAGRAPH/TABLE/LINE；PDF 物理页号从 1 起；DOCX 不生成假页号 |
| material_chunk | profile_id, chunk_id, material_id, material_revision, block_ids_json, topic_key, search_text, parser_version | block 引用须有效且同人；一次扫描识别初始最多 5 页 |
| artifact | profile_id, artifact_id, kind, title, body_markdown, revision, source_refs_json, created_at, updated_at | kind=NOTE/SUMMARY/EXERCISES；练习题与答案分开；修改产生新 revision |
| artifact_export | profile_id, export_id, artifact_id, artifact_revision, format, private_file_key, sha256, byte_size, state, idempotency_key, created_at | format=MARKDOWN/PDF/DOCX；format 可选不表示已支持；成功要求实际文件校验；不覆盖旧导出 |
| skill_install | profile_id, skill_id, name, description, source_url, source_commit, sha256, version, private_file_key, capability_requirements_json, state | state=DRAFT/ENABLED/DISABLED/UNSUPPORTED；人工版本不可静默覆盖；不执行任意脚本 |
| provider_capability | provider_config_id, config_fingerprint, model, protocol, capability, state, checked_at, evidence_key | 不含密钥；能力状态 UNKNOWN/PASS/FAIL/NOT_SUPPORTED；配置/模型变更后失效 |

provider_config_id 只引用凭据仓储；凭据使用 Keystore 保护，不放进学习表或普通备份。配置指纹对协议、地址、模型和非敏感选项计算，不对 API 密钥计算。备份中不记录可恢复的凭据。

## 抽取与提交合同

模型响应只允许 schema_version 和 candidates；候选字段见 [候选合同](../qa/s0/candidate-contract.json)。整个响应严格解析，未知字段、畸形 JSON、重复键、无来源、未知来源、越限或重复事实均拒绝整批，不先保存部分结果。参考校验器见 [validate_candidates.py](../scripts/validate_candidates.py)，它只证明结构/allowlist 检查，不能证明事实是否正确。

提交在一个事务内重新校验：job 所属身份、自动记忆开关、分支与消息 revision、来源有效性、delete_epoch、人工条目 revision、抑制规则及预算。任何过期条件都不提交旧候选。来源 allowlist 必须由同一任务下有效的、未抑制来源产生；材料作者和模型陈述不得作为学习者事实来源。低证据推断进入 CANDIDATE，只有 ACTIVE 可进入事实检索。

删除事务清理记忆与修订正文和证据摘录，保存最小抑制键，递增 delete_epoch，撤销摘要/缓存/在途候选。过滤自动抽取和模型历史读取所用来源；用户手动回看聊天不受假装删除影响。重新记住必须有新的明确用户来源，不能由自动任务解除抑制。

## 尚不能冻结的产品/运行参数

- 2026-10-06 用户明确首版 PDF + Markdown，Word 导出及可编辑 Word 公式后置；DOCX 输入仍保留。格式范围已确认，实际保真检查未通过；同机/各自设备及同机认证需求仍待确认。
- provider 协议、模型、窗口/usage、图片和工具能力：等真实中转，不能用配置名推定。
- 2026-10-07 备份格式 v1 已实施：`.msab`，PBKDF2-HMAC-SHA256（600,000 次，256 位），AES-GCM 分块认证，完整性清单与路径/版本校验，启动前 journal/回滚。可移植 HMAC 密钥本机由 Keystore 保护、归档由备份密码保护；恢复两次合并当前删除约束。旧事实/主题指纹无法精确迁移时暂停自动抽取并保留保护。细节与限制见 [S5 合同与执行说明](s5-execution.md)，模拟器结果不替代目标设备耗时和跨手机实测。
- 自动抽取阈值、上下文预算、材料大小与重试次数按方案初值实测调整，均作为配置版本记录。


## S1 会话运行态增量（2026-10-06）

2026-10-07：Room 29 增加会话列 `study_mode TEXT NOT NULL DEFAULT 'GUIDED'`，28→29 使用非破坏性自动迁移。取值为 GUIDED / HINT / CHECK / EXPLAIN，归属与会话一致，分叉继承。模式在开始生成时固定为该会话的模式，不根据后来选择的档案变化；用户当轮明确指令优先。编辑题面、选择另一消息版本或重生成较早回复时，将旧完整路径保存为同档案的历史分叉，当前路径解除后续旧回复及建议；非空队列暂停，不自动套用新上下文。尚无 S2 记忆派生记录，本批不声称完成其失效联动。

Room 28 的 `ConversationEntity.queue_state` 是带 `messages`/`paused` 的 JSON，旧行默认 `{}`。队列条目包含稳定 UUID、消息 parts 与 answer；从队列移除和用户消息写入同一事务，用户消息复用输入 UUID。恢复后的非空队列暂停，内存编辑标志与 Deferred 不跨进程保存。普通会话更新不能覆盖最新持久化队列；排队附件属于有效引用。

UIMessage 的 generationState 取 RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED/WAITING_TOOL，generationErrorCode 保存可用的 HTTP 状态码。工具 executionState 取 NOT_STARTED/RUNNING/INTERRUPTED/COMPLETED/SKIPPED_UNKNOWN；兼容旧输出非空即已处理，空成功结果借 COMPLETED 标记完成。开始标记提交后才执行，结果提交后才执行下一工具；恢复或开始后的异常没有结果时为未知，禁止自动重放。用户核查后选择跳过未知工具，保存 SKIPPED_UNKNOWN 和明确 unknown 结果再接续原轮；它表示已处理接续决定，不证明执行成功或效果不存在，重开后仍保留未知标识。这些状态不启用自动学习记忆。
