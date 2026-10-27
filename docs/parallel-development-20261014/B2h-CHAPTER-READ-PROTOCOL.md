# B2h — 索引绑定的章节正文分块读取 v1

FeatureID `B-CHAPTER-READ-20261017`，自动接续已完成的 B2g；base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加两批完整未提交源码。当前准确版本见本批SOURCE_FINAL/patch，前批第一版源码和证据独立保留，不用最终计数重写首批结果。

## 入口与输入

`materialChapterReadTool(store, owner)` → `read_material_chapter` → `MaterialFiles.readChapter` → 原withSourceText冻结/hash/独立提取/源重验 → 同一MaterialChapterIndex.build重建 → 精确比较index_sha256/chapter_id → MaterialChapterRead.page → 真实逐字v2 source_receipt。

六个严格字符串 `id/expected_sha256/index_sha256/chapter_id/offset/count`。索引hash和章节ID取自B2g，不接受模型自报起止/owner/path/页码。offset从0起，是候选区间坐标跨度内UTF16相对位置；count 1–24000，不拆代理字符。索引hash不是权限，当前owner/源版本与宿主每dispatch授权仍须独立核验。没有另起模型、执行器或新任务协议。

## 区间、空白与分页

候选章节定义仍为实际标题至下一候选或已提取正文末尾，语义结构未经断言。单次只返回一个真实PDF物理页/PPT含隐藏逻辑slide正文区间与该章节的交集，长度受count限制，绝不跨进下一候选章节。纯文本/DOCX不编物理页。

Reader生成页标、无字页占位和跨页间隙不作正文。输出同时记录 `requested_chapter_offset` 与实际 `chapter_offset`；跳过间隙时实际值可能更大。续接只用 `next_chapter_offset` 并保持原index_sha256/chapter_id/sourceHash，结果不保留会诱导跨章的全局next_offset。`chapter_span_characters=end-start` 是提取坐标跨度，可能包含被跳过间隙，不是原书字数。

返回合同1/scope=detected_chapter_extracted_body_chunk，含index_sha256、chapter_id/title/start/end/span、boundary_basis、source_verified及v2来源回执。has_more_in_chapter仅表示该候选尚有已提取正文，最后一个chunk不产生虚假续页。到提取跨度末尾NOT_AVAILABLE且不发untrusted_file_data/source_receipt；未知章节/错hash/非法Unicode或范围失败，不切换到别章。chapter_completeness/document_completeness始终NOT_ASSERTED，不能推出原页/原章节完整、印刷页、OCR、证明正确或任务完成。

## 缓存与身份

读取从冻结原件重建边界，不以持久索引或read-v6缓存授信；伪造缓存不能替换正文。源/父谱系变化或错owner/hash被原withSourceText拒绝。取消向上传播；成功调用的临时输入清理已实测。无新持久状态/schema/Pigeon/授权范围；B2g派生索引可独立失效，原件/元数据/版本/journal/UNKNOWN不改。

## A共享集成候选

本批 `SOL_SHARED_INTEGRATION.patch` 为针对当前A根源码的**累计候选**：Factory注册两个领域Tool，Registry沿现local.read/READ/LOCAL_RECEIPT，TaskResultVerifier在实际持久回执一致之外，重新调用真实chapterIndex/readChapter并比较结果；索引仅排除易变index_storage且校验其合法枚举，读取必须精确匹配。NOT_AVAILABLE不得授予已读取正文成功。不能只注册工具而遗漏该核验分支，仍需TaskPlanner完整目标验收。

候选原字节SHA/锚点见SHARED_SOURCE_PREREQUISITES，原文与候选副本、精确diff单独交付；未修改A共享文件、未默认激活、未编译或运行该候选。A应三方核对后在其空闲构建窗口集成并跑真实Chat→journal→源读取→目标验收，不能由直接领域Tool通过推定整链通过。

## 实测与证据

第二批真实Android7 PASS；最终同APK第一批及原来源/文件/PDF回归48 PASS/1既有硬链接SKIP；JVM419 PASS；私有Debug/QA构建及lint0错误24警告。实际持久输出9个正文chunk由桌面pypdf/XML/UTF16独立核对：重复标题不同区间、PDF物理页1/3跨空白页且不带页标、隐藏乱序PPT、小chunk含emoji连续回合及无邻章泄漏。所有源绑定回执在Android还逐字重验。

桌面首次因MuPDF把定位文字分成双换行、pypdf只产单换行而FAIL，原日志/旧验证器与PDF_EXTRACTION_DIFFERENCE保留。删除“不存在的跨引擎统一段落分隔符”假设；独立PDF检查非空文字行内容/顺序，字内空格不变，原提取quote的UTF16范围、SHA和Android逐字重验不变。未篡改产品正文或正确测试期望来掩盖丢字。

领域Tool WIRED/E4，整体实现PARTIAL；主Chat/根A整树/共享候选编译与运行/API26/Mate50/Release/真实模型/生产/中途kill未验收，独立审查NOT_EVALUATED。完整证据与回滚见本批TEST_RESULTS/HANDOFF/SOL_BLOCKERS。下一B能力为在相同索引/章节边界内的字面检索与命中定位；不能以全库搜索结果冒充已读整章。
