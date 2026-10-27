# B2f — PDF/PPT 指定来源位置正文读取 v1

来源：Pro inputs/04 教材真实位置、inputs/13 FILE-03/MATH-04；继承 B2a–e 的冻结源/版本哈希/v2 回执和实际 Reader location。不覆盖原 Pro 规范或 A 总协议。源码/输入/APK/真实日志见 `HXai_CHANGE_REVIEW/B-SOURCE-LOCATION-READ-20261016/`。

## 接口与语义

`MaterialFiles.readSourceLocation(profile,id,expectedSha256,kind,number,offset,count)`；`materialSourceLocationTool(store,trustedOwner)`，工具 `read_material_source_location`。六个严格字符串字段 id/expected_sha256/kind/number/offset/count。owner 只来自可信 host，仍需现 Agent local.read 授权，模型不得传 owner/path。

kind 仅 `pdf_physical_page` 或 `ppt_logical_slide`，与实际 pdf/pptx 类型匹配；number 为正整数，PPT 包括隐藏页且依 presentation.xml/r:id，不是文件名序/可见序/导出 PDF 页。DOCX/TXT 等不伪造物理页。offset 为该位置正文内 UTF16 相对偏移，count=1..24000，范围上限沿 Reader.LIMIT；不能拆分代理对。

冻结实际 source 并核验 SHA 后，按实际 MaterialTextLocation 选择唯一正文区间，限定到该区间末尾。不包含生成标题/空页提示，不跨位置。返回旧 v2 source_receipt（全局 UTF16 locator，可继续既有 revalidateSourceTextPage 独立重验）；顶层 contractVersion=1/scope=source_location_extracted_body/status=SUCCEEDED，location_offset/location_extracted_characters/has_more_in_location/next_location_offset 用于同页续读。移除旧全局 next_offset，避免误入邻页。

无提取正文的位置返回 NOT_AVAILABLE/NO_EXTRACTED_BODY_FOR_LOCATION、source_verified=true（仅源版本已核验）及真实 coverage/缺文本页信息，不提供 source_receipt 或 untrusted_file_data。可能是扫描无字、超提取上限或不存在，不能据此判断原文件没有该页或该页为空。不自动 OCR/外传。

## 安全、兼容和验收

文件漂移、owner/hash/谱系、毒缓存与取消沿现 withSourceText；发布前原文件独立回读。格式/输入/代理对非法返回 FAILED 固定安全说明，不泄漏异常路径。无新缓存/索引/DB/schema/Pigeon/模型/权限/UI，不改旧工具合同或原材料 bytes/metadata。

SUCCEEDED 只表示指定提取范围读取成功；source_receipt VERIFIED 只表示版本/逐字引用事实。document_completeness 永远 NOT_ASSERTED，到提取末尾不是原页完整读取、OCR、版式或数学证明，不授予任务完成。

已实际 API34 Android Tool 六行为方法、相邻材料24方法、数学46方法及独立8引用/2不可用位置真实产物核对；JVM410、lint0错误23警告和 Debug/QA构建有本批日志。负对照原 API 缺失 FAIL 保留。数学命令曾将预猜60作为外层条件，实际 Android46全通过，保留 harness FAIL 与准确解释，不重跑冒充第一次成功。

A 共享 Factory/Registry 激活仅最小候选 patch，未 apply。实现 PARTIAL（领域 WIRED，主 Chat NOT_RUN），证据 E4 direct Tool，裁决 PASS_SCOPED 仅本领域。独立审查 NOT_EVALUATED；完整 Chat、Release、Mate50、真实模型、生产未据本批放行。