# B2d — 版本绑定正文检索 v1

领域构造materialSourceSearchTool(store, owner)，工具search_material_text；全部输入字符串id/expected_sha256/query/offset/limit。query为1–256 UTF16完整Unicode字面串，区分大小写、无正则；offset为现提取文本全局UTF16位置，不能拆代理对；limit=1–20命中。固定owner由宿主构造，不允许模型切档。

MaterialFiles.searchSourceText与旧readSourceTextPage共用withSourceText：流式有界冻结、真实输入SHA、真实Reader、构造结果后重新核对源bytes/元数据/谱系，finally清理临时输入。KMP单次扫描每正文区间、query前缀表至多256整数，支持重叠命中；PDF/PPT只搜索结构正文，不搜索页标题/空页诊断，跨页/间隙不拼接。其他格式搜索现有提取文本，不冒称完整结构/版式/印刷页。

返回contractVersion=1/status=SUCCEEDED/scope=extracted_body_suffix/match_mode=literal_case_sensitive，owner/material_id/version_id/parent_id/source_sha256/extraction_contract=material_reader_text_v2、coverage/实际提取页/缺文本页和hits。每hit是既有文本页JSON，含逐字untrusted_file_data和v2 source_receipt；可交B2c重新核验。继续搜索只用next_search_offset（首个未交付命中start），不用hit.next_offset（quote读取结束），以免丢重叠命中。has_more只涉及当前已提取suffix；document_completeness=NOT_ASSERTED，无匹配不是原文不存在结论。Reader原30MiB/1M字符/PDF200页与扫描/复杂Office覆盖限制保留。

不建持久索引、不改原件/DB/Pigeon/模型/权限/任务执行器，不上传材料，不把receipt当数学或授权证明。原API签名、cache6/5及不可变版本保留。

共享接线由Sol：HXai_CHANGE_REVIEW/B-SOURCE-SEARCH-20261014/SOL_SHARED_INTEGRATION.patch仅两文件（ChatToolFactory添加构造；TrustedToolRegistry既有local.read/READ/LOCAL_RECEIPT追加名字）。根共享源未改，不广告未知未授权工具；B私有副本精确应用并实际Factory→tool→源→Reader→hit成功。自动能力集合未新增种类。Root主Chat激活仍PARTIAL/未归并，不能拿私有通过宣称最终App完成。

证据：真实旧App能力缺失FAIL_BASELINE/ABSENT；最终Native20例PASS（含B2a/b/c/Office/版本回归，不累加历史），独立实际输入SHA/quoteSHA/UTF16及PDF physical1/3核对；烟雾/负对照源码仅存证据，产品中已移除。专属协议/源码映射随本批，不覆盖Sol总协议。
