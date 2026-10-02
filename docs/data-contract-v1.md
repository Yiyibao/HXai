# S0 数据合同 v1

2026-10-02：字段与本地约束冻结为实施合同；尚未创建 Room 表或完成 Android 验证。实际迁移在 S1/S2 实施，不得以本合同宣称已有自动记忆能力。破坏性修改须提升 schema_version 并提供迁移，不修改旧备份含义。

## 统一类型与身份

ID 用小写 UUID 字符串；时间用 UTC Unix 毫秒整数；revision 从 1 起递增；delete_epoch 从 0 起单调递增。正文 UTF-8。数据库字段用 snake_case，Kotlin 使用 camelCase 映射。布尔值在 SQLite 使用 0/1。

所有学习实体携带 profile_id。模型不得指定 profile_id、文件路径、origin、status 或当前 revision。仓储方法显式接收应用生成的 LearnerContext；后台任务固化创建时的 profile，不能读取 UI 当前档案替代它。跨表外键采用包含 profile_id 的复合唯一键，防止串人关联。

上游 Conversation.id、MessageNode.id、UIMessage.id 保留。上游当前分支通过每个节点的 selectIndex 表示，不能把会话 ID 当成分支 ID。本项目新增 BranchSnapshot：按有序 node_id + selected_message_id + message_revision 集合产生不可变 source_range_hash，分支/编辑变更产生新快照。引用使用 application-issued source_id 映射到快照，不让模型拼装 UUID 或旧来源版本。

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

- PDF/Word 首版优先级、是否必须可编辑公式，以及同机认证需求：等用户回复，G08 未通过。
- provider 协议、模型、窗口/usage、图片和工具能力：等真实中转，不能用配置名推定。
- 加密备份采用经过验证的认证加密和密码派生库；算法、工作因子、设备耗时及封装在可构建基线之后核验，暂不写死为已验证参数。S5 前必须补齐此合同。
- 自动抽取阈值、上下文预算、材料大小与重试次数按方案初值实测调整，均作为配置版本记录。
