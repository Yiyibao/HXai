# B2e — 当前档案跨材料正文检索 v1

来源：Pro inputs/04资料库/教材来源、inputs/13 FILE-02/MATH-04与B2a–B2d来源/位置/重验/单版本检索合同。不改原规范或A总协议，不把本批称持久索引/章节语义检索/完整原文覆盖。精确来源与实跑见HXai_CHANGE_REVIEW/B-LIBRARY-SEARCH-20261015。

## 实际API与范围

新增MaterialFiles.searchLibraryText(profile,query,cursor,limit,sourceLimit)及materialLibrarySearchTool(store,owner)，工具search_material_library。owner由可信宿主闭包固定，不接受模型owner/path。严格四字符串字段query/cursor/limit/source_limit；首cursor空，query沿B2d1–256完整UTF16字面/区分大小写，limit1–20本页总命中，source_limit1–5本页访问版本数。

目录来自现MaterialFiles.list(owner)中SUCCEEDED版本，按id稳定排序，包含已保存源与产出版本；其他状态不作为可检索成功材料。元数据目录快照计算是O(当前目录版本数)，真实内容每页最多5版本、每源现Reader大小/字符/PDF页限额保持，不冒称百万级持久索引或隐瞒完整目录枚举成本。

## 绑定与分页

catalog_snapshot_sha256绑定固定版本标签、owner/query/目录数量和按id顺序每版本id/owner/name/extension/size/hash/parent/state。每字段用4字节有符号大端UTF16单位长度（null为−1）加原UTF16码元的大端字节；避免UTF8替换坏代理字符造成名称碰撞。摘要是数据快照，不是凭据或授权。

cursor是JSON字符串，严格snapshot_sha256/version_index/search_offset三个字符串，最大2048字符。恢复前重算当前目录摘要，跨owner/query或版本集合/名称/hash/谱系/状态变化拒绝；位置有界并由真实源检索核对UTF16边界。cursor不是签名能力，不证明前页已扫描；请求同一自有目录的任意合法位置不产生“已读完”证明。

每页按目录位置逐源调用B2d searchSourceText，沿真实冻结/hash/Reader/v2回执，不用旧缓存。不命中源也消耗source_limit但返回next_cursor以继续后续源。聚合总命中到limit时保同源next_search_offset（首个未交付重叠命中），不能用hit.next_offset；同源穷尽后才进入下版本。页码、缺文本页与截断覆盖继承实际Reader，不跨页拼接，不搜索PDF/PPT生成标题/空页提示。

## 失败、跳过与发布前读回

支持现Reader的txt/md/csv/json/docx/xlsx/pptx/pdf。图片、旧二进制Office等列skipped_sources，source_verified=false、FORMAT_NOT_TEXT_READABLE，不自动OCR/外传也不签发正文回执。可读源缺失/字节hash错误列source_errors，source_verified=false，本页status=FAILED；可保留其他已核验局部hits但不能称整页成功。

所有成功读取源在本页发布前再次verifiedFile真实hash读回，再重算完整目录摘要；此前第一份源在第二份读取期间变化不能用旧hits发布成功。发布后仍可能有新变化，引用时继续用B2c revalidateSourceTextPage独立重验，不承诺跨文件数据库式原子快照或永久有效性。取消原样传播；错误仅固定原因码/安全说明，不向日志或工具返回泄漏异常路径/正文。

## 输出与完成语义

contractVersion1、scope owner_library_extracted_text_page、match_mode literal_case_sensitive。含owner/query/目录摘要与数量、起始位置、本页visited_versions/scanned_sources/skipped_sources/source_errors、hits/has_more/next_cursor（若有）和exhausted_catalog。每hit沿用v2 owner/material/version/hash/parent/UTF16 locator/逐字quote/source_locations，可独立重验。

library_completeness/document_completeness始终NOT_ASSERTED。目录位置到末尾不证明前页都扫描、每份源完整提取或原文无该内容。SUCCEEDED只表示本页指定支持范围执行成功，不是全库索引完成、数学证明、授权或用户任务成功。错误页FAILED不能被另一正确命中掩盖。

## 持久化、旧接口与接线

无持久索引/缓存/DB/schema/备份数据新增，不改材料/metadata；既有单版本search/read/revalidate、不可变版本、模型/任务/授权/owner/epoch/Pigeon/UI不变。没有第二执行器、新OCR角色或外传，不设费用预算。旧负对照一次性原生能力探针只存历史证据，产品测试不保反射/源码文本检查。

A负责Factory/可信Registry最终激活，B仅两文件最小共享候选/当前原SHA，不直接启用。不把直接Tool实跑冒称主Chat已接线。独立外部审查NOT_EVALUATED；准确实际APK/源码/结果及Release/API26/Mate50/真实模型/生产未验边界见本批TEST_RESULTS/SOL_TEST_EVIDENCE/SOL_BLOCKERS。
