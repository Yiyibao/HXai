# B2a 源文本区间回执 v1（2026-10-14）

## 实际入口与兼容

沿用现有 `read_material_text_page` / `materialTools` / ChatToolFactory 接线及只读材料权限，不新增工具名、共享registry/TaskStore/Pigeon或DB。

输入旧 `id/offset/count` 字符串继续可用，新增可选 `expected_sha256`。提供摘要时走真实冻结输入重新提取，不用 `read-v5.cache`；不提供时保持旧缓存读取，**不产生核验回执**。`list_material_files`增加 `extension/size/sha256/source_id`；列出元数据不是实际文件仍完整的验证。

## 回执与定位

提供摘要的实际分页响应继续包含 `coverage/total_characters/offset/next_offset/untrusted_file_data`，并返回：

- `source_receipt.contractVersion=1`、`status=VERIFIED`、`scope=extracted_text_range`；
- `owner/material_id/version_id/parent_id/source_sha256`；本库一个不可变文件ID代表该具体版本，material_id/version_id均该ID，source_id是parent，不按文件名猜族谱；
- `extraction_contract=material_reader_text_v1`、`quote_sha256`和真实coverage；
- `locator={kind:extracted_text,unit:utf16,start,end}`，半开区间。

只证明当前提取器从该真实文件版本得到该文本区间，不证明数学结论、完整覆盖、版式或物理/印刷页/slide。正文中的 `[物理页999]` 等标记不会被解析为可信locator。真实PDF的total_pages/extracted_pages仍是原提取元数据，不等于本段quote位于某页。Unicode代理对必须完整，否则拒绝区间；读到末尾仍检查coverage。

## 真正的来源核对

MaterialFiles在固定owner查成功版本，核对期望摘要/大小，边有界复制边SHA到私有cache临时输入（最多30MiB，检查取消）。只有复制的实际字节摘要匹配，才将冻结副本交MaterialReader。收尾独立确认真实源版本及parent/format/size未变，再生成JSON；finally清除临时输入，成功路径清理失败不交付回执。路径临时替换又恢复不能把不同复制字节冒充原SHA。没有原件覆写、材料新建、私料外发、模型调用、截图入库或全局调度变化。

## 本轮证据

`HXai_CHANGE_REVIEW/B-SOURCE-RECEIPTS-20261014/`：旧工具在污染缓存下返回FORGED_CACHE的真实FAIL_BASELINE保留；新原生回归2项通过（长文本尾部/emoji边界/假页标记/缓存隔离/跨owner/hash/source变更/新旧parent版本/真实DOCX与2页PDF/坏UTF8/临时输入清理）。旧材料存储4项及Word/Excel/PDF编辑流程1项本轮通过。NativeQA Debug/AndroidTest编译通过；原生返回真实receipt保存到artifacts/native。

只授予本机/隔离App限定PASS_SCOPED，整体CONDITIONAL。没有新UI行为或完整Agent引用验收声明。专业章节/物理页/印刷页定位、全库全文索引、授权目录导入、全局备份删除仍是后续能力，不把B2a说成完整B2。进程硬退出后的临时cache孤件回收未执行（NOT_RUN）；普通Web重建、Docker、Release/Mate50、真实模型、生产NOT_RUN，独立外部审查NOT_EVALUATED。

复验需本批独占QA设备安装自己的包，运行 `me.rerere.rikkahub.feature.material.SourceReceiptRuntimeTest`。具体cwd/源码/APK摘要/命令在RUNS及BUILD_AND_PATCH；不得占A设备或盲覆盖当前A树。回滚按精确after hash还原本批base，保留所有旧缓存/原件/版本与用户后续变更；无schema迁移。
