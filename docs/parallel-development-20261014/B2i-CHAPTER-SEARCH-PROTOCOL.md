# B2i — 索引绑定的章节字面检索 v1

FeatureID `B-CHAPTER-SEARCH-20261018`；base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批完整未提交域源码。接续 B2g/B2h；source/APK/输入输出精确SHA与差异在本批 SOURCE_FINAL、SOURCE_BUILD_INPUT、TEST_RESULTS。

## 真实域入口
`materialChapterSearchTool(store, owner)` → `search_material_chapter` → `MaterialFiles.searchChapter` → 既有 `withSourceText` 冻结原件/hash/实际提取/发布前源谱系重验 → `MaterialChapterIndex.build` → `MaterialChapterSearch.search` → 同一 `MaterialSourceSearch.find` 有界KMP扫描器 → 逐字v2来源回执。

七个严格字符串 `id/expected_sha256/index_sha256/chapter_id/query/offset/limit`。索引哈希、章节ID来自真实索引，不接受模型指定owner/path/章节起止。query 1–256完整UTF16单位、区分大小写、字面非正则；offset为候选章节提取跨度内相对坐标，limit1–20。错源、索引、章节、Unicode或参数拒绝，无命中回执；取消传播。

## 边界、重叠与分页
只扫描 `[chapter.start + offset, chapter.end)` 与实际PDF物理页/PPT含隐藏逻辑slide正文的交集，不匹配生成页标、空页占位、正文间隙或下一标题。普通文本/DOCX不编物理页。支持重叠命中；`next_chapter_search_offset` 为首个未返回命中的相对起点，和原全文检索分页约定一致。续查保持源/索引/章节/query；每hit的全局 `offset` 和 `chapter_offset` 分开，不保留会误导续接的 hit.next_offset。

输出合同1/scope=`detected_chapter_extracted_body_suffix`，含 query、候选边界、来源/索引、覆盖与缺字页、`hits`、`has_more_in_chapter`。命中为精确query及v2 `source_receipt`，仍可用原来源重验API逐字核验。空命中可以是成功完成限定搜索，不能证明原章节/原书无内容；chapter/document_completeness 始终 NOT_ASSERTED。索引候选不是语义结构权威、OCR、印刷页或数学证明。未增加持久状态或DB/Pigeon/权限/模型协议。

## 共享集成边界
Factory/可信Registry/TaskResultVerifier仅交付累计三工具最小候选 `SOL_SHARED_INTEGRATION.patch`：沿用 local.read/READ/LOCAL_RECEIPT，并从真实持久输入重新索引/读取/检索比较结果，不能仅信签回JSON。仅index_storage允许重建枚举差别；正文/命中事实必须精确一致。TaskPlanner完整目标门禁保留。当前A源码原SHA固定在 SHARED_SOURCE_PREREQUISITES；候选可应用检查通过，未apply/编译/运行共享主Chat。

## 实测范围
私有Debug/QA构建成功；JVM425 PASS；lint0错误24警告。B专属API34真实Tool五场景PASS，原章节/全文/库检索/来源重验等36场景PASS。实际持久Markdown/PDF/隐藏乱序PPT共13结果页、9命中，由桌面原文件UTF16/pypdf/XML独立逐一检查，并在Android逐字重验全部hit。原件hash/元数据保全、错owner/索引/恶意参数/伪造cache/取消与临时源清理已验。

领域 WIRED/E4/PASS_SCOPED；主Chat PARTIAL/NOT_RUN，独立审查 NOT_EVALUATED。中途提取kill/发布竞态/API26/Mate50/Release/真实模型/生产未据此放行。后续B进入有理原函数证书的有限定积分核验；不改A调度器或绕过授权。
