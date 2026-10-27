# B2g — 真实版本章节候选与覆盖索引 v1

适用 FeatureID `B-CHAPTER-INDEX-20261017`；base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加完整未提交成果。第一批源码固定在该批 `SOL_IMPLEMENTATION/` 与 `SOURCE_FINAL.json`；后继 B2h 已继续扩展 MaterialFiles，当前最终源码以 B2h 批地图为准。原 Pro 规范不改写。

## 真实入口

`materialChapterIndexTool(store, owner)` → `index_material_chapters` → `MaterialFiles.chapterIndex` → 原 `withSourceText` 冻结/hash/真实提取/源重验 → `MaterialChapterIndex.build` → 私有派生缓存独立读回 → 分页 JSON。工具构造器在领域内实际可执行；共享主 Chat 尚未激活。A 最终集成用 B2h 的累计 `SOL_SHARED_INTEGRATION.patch`，不能只加工具名而跳过来源重验。

四个严格字符串：`id`、`expected_sha256`、`offset`、`limit`。owner 来自可信宿主闭包，offset 是候选索引序号（0起），limit 1–20。支持 PDF/PPTX/DOCX/TXT/MD，保原件/元数据，不 OCR、不新增网络传输或模型调用。没有新费用预算或任务执行器。

## 识别与覆盖边界

仅识别独立行 Markdown ATX `#`–`######`、中文 `第N章/篇/部`、英文 `Chapter N` 标题。它们是**标题候选**，不是语义目录权威；目录条目/重复标题可能命中，不合并相同标题，不把未识别当不存在。标题最多256 UTF16单位；保留至多512项，额外读取第513个边界以阻止截断末项吞掉后章，`index_truncated/unindexed_tail_start` 明示。

PDF/PPT 只扫描真实 `textLocations` 正文，排除 Reader 生成页标/空页占位。PDF数字为物理页；PPT按关系逻辑序，含隐藏slide和真实part，不能当可见页或PDF页。其他格式没有物理页就不编造。候选区间定义为标题起点到下一个候选起点或已提取正文末尾，不推断未提取章节结束。

索引含 owner/material_id/version_id/parent_id/source_sha256、extraction_contract、heading_rules、extracted_text_sha256/extracted_characters、真实coverage/total_pages/extracted_pages/pages_without_extractable_text、body_ranges。每候选包含 `chapter_id=heading-<真实全局UTF16起点>`、原始title、start/end/unit、boundary_basis、structure_authority=HEADING_CANDIDATE_ONLY，以及可用现 `revalidateSourceTextPage` 独立核对的逐字 `heading_evidence`/v2 source_receipt。

`index_sha256` 对包含完整候选集合的确定性JSON（不含此hash字段）作UTF8 SHA256。分页保留该完整hash、chapters_retained/index_truncated，返回has_more/next_index_offset；位置不是前页已读证明。所有结果 `document_completeness=NOT_ASSERTED`，没有已识别标题/读到末页都不升级为全文证明或数学结论。

## 持久与生命周期

派生文件为 `materials/<owner>/<id>.chapters-v1.cache`，上限2MiB，不是MaterialFile元数据，也不是新数据库。只在成功独立提取/源重验之后，用现 `PendingRestore.writeDurably` 的临时写入、fd.sync、原子移动，并独立读回。index_storage为CREATED/REVALIDATED/REBUILT_FROM_VERIFIED_SOURCE。

每次调用重新从固定真实源构建再比较派生文件；不是以缓存替代证据，不宣传索引加速或语义检索。缓存损坏/伪造/旧身份只从已核验源重建，原件和元数据保持。`MaterialFiles.invalidateChapterIndex(owner,id)` 是删除/恢复hook，允许源元数据已移除后仅清该owner/id派生cache/tmp。不存在全局材料删除入口自动接线的声明；A生命周期集成仍需调用该hook。恢复的缓存也不获得信任，删除的源不能凭缓存复活。

## 源码与实测

`MaterialFiles.chapterIndex/invalidateChapterIndex`、`MaterialChapterIndex.build/persist/page`、`MaterialChapterTools.materialChapterIndexTool`；专属 `MaterialChapterIndexTest` 与 `ChapterIndexRuntimeTest`；桌面 `scripts/verify_b_chapters.py`。首批真实Android8 PASS、JVM415 PASS，三种实际持久源的6标题及索引hash经pypdf/XML/UTF16独立核对；相关原来源/文件/PDF回归40 PASS/1既有SELinux硬链接SKIP。Debug/QA构建通过，lint0错误24警告。具体源码/APK/hash/log以首批TEST_RESULTS为准，不累加历史计数。

实现领域Tool WIRED/E4；主Chat PARTIAL/NOT_RUN，独立审查NOT_EVALUATED。无Room/sidecar/Pigeon/凭据/任务状态迁移。原件、新版本、任务journal/UNKNOWN保持；回滚见首批VERDICT_AND_ROLLBACK。用户要求的自动接续已实际进入并完成B2h章节正文读取，不承诺回复后后台自动运行。
