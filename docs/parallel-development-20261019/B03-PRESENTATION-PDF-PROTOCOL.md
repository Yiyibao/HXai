# B03 PPTX / PDF 领域协议 v1（2026-10-19）

来源：B_TASK B03、原 B1d/B1e、Pro P1/P2。交付与原始失败见 `HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/`；精确源码见同目录本协议的 `B03-PRESENTATION-PDF-SOURCE-MAP.json`。本协议只增量定义领域行为，不替代原产品要求。

## 入口与定位

既有 `inspect_material_edit` / `edit_material_file` 不新增全局工具名。Factory 已传入 DocumentServiceRepository；不改 A 的 Registry/Store/Planner/Verifier/Host/Pigeon、权限或模型锁。

PPT 的 `slide` 始终是 `presentation.xml` 经 r:id 解析的 1-based 逻辑序号，包含隐藏页，不是 ZIP 文件名、可见序号或导出 PDF 物理页。检查返回：

- `part`、`hidden`、`total_slides`。
- `visible_slide`：隐藏页为 null；`total_visible_slides`。
- `visible_slide_basis=presentation_order_excluding_hidden_not_pdf_page`。实际 PDF 页只能绑定真实导出文件后确定；备注页、选择范围等会改变映射。
- 普通/分组文本用 `shapeId` 加运行 `index`、`expectedText`；shapeId 按 unsignedInt 数值解释，合法 +/前导零词法不产生不同身份。数值重复、无效身份不能猜作唯一 shapeId。
- `mc:AlternateContent` 的 Choice/Fallback 文字不提供普通编辑授权，即使其中一个分支有 a:t。全局运行索引也不能绕过。自动域继续不可编辑。普通邻接文字仍可改。

替换仍使用当前材料 id / `expected_sha256` / `operation=replace_text_run` / JSON 字符串 `spec`，创建不可变新版本，不修改原件。只改变目标文本及必要 xml:space；图片、母版、布局、主题、关系和无关 DOM 保留。编辑缓存 `edit-v5`，不重用旧不安全 PPT 编辑结果。

## PDF 原页组装

继续使用原 B1e `operation=pdf_select_pages`：`spec.sources` 每项包含真实材料 `id`、`sha256`、1-based `pages`；允许倒序、重复和多来源。输出仍为 PDF，receipt v1 的 scope 为 `pdf_original_page_assembly`，逐输出页绑定原材料/摘要/原物理页。

新范围没有重新实现 graft。实际新增几何证据覆盖 MediaBox 非零原点、CropBox、90/180/270 度旋转、UserUnit 1.25/2、扫描图像、空白及重复页；独立比较真实输出盒/旋转/单位、文字和整页像素。原页组装不等于 OCR、可编辑 Word、批注/表单/签名等文档级对象保全。

## 已下载 OCR 缓存 → 可编辑候选 Word

新增的是既有 `edit_material_file` 的本地操作，不是 OCR 引擎或新网络工具：

| 参数 | 契约 |
|---|---|
| id / expected_sha256 | 当前 owner 的真实源 PDF 版本及 SHA256 |
| operation | `pdf_to_editable_docx` |
| spec.task_ids | 1–40 个已下载且属于该 owner/源版本的解析任务 ID，不重复；不是文件路径或 URL |
| spec.start_page / end_page | 均必填，1-based 源 PDF 物理页；只允许完整覆盖所选范围，不自动扩张到全书 |
| title | 既有材料标题规则，输出为新的 DOCX |

任务/缓存正文、manifest 的版本、adapter、源 SHA、真实源 PDF 页数、范围、processed/failed/blank、complete、warnings 必须一致。不能用同一份伪造的 1 页正文/manifest 冒充真实 3 页 PDF；不能通过丢弃正文警告取得成功。缺页或重复覆盖拒绝，不猜测空白，不静默截断。显式子范围可成功，但不声称选中了全书。

历史解析任务已下载后，合法本地缓存可离线使用；EXPIRED 不自动触发网络恢复。该操作不上传、不重传、不调用模型、不改变 OCR/主角色配置，也不解除当前任务的取消、授权或暂停边界。

输出明确是**未校对的可编辑识别候选稿**：

- 保留所选块文字、字面 LaTeX、警告、源版本/摘要/物理页/块 ID。
- 表格只保留候选文字，不自称 Word 合并单元格或结构化表格；LaTeX 是未核验文字，不自动变成已证明 OMML。
- 每个所选原物理页附实际渲染的参考图，最长边 1536px；原 PDF 保留。参考图不是可编辑公式或原版保真转换。
- 参考图标题与图片在实际 Word 分页中保持同页；Word 页码从来不代替源 PDF 物理页。
- parser 的无文本结果不等于独立证明“真空白”。`pages_without_editable_text`、符号复核和未核验状态均保留。

receipt v1 scope=`pdf_ocr_editable_draft`；包含源 id/SHA/总页数、所选页、`entire_source_selected`、每个解析结果的 canonical document 摘要/版本/范围/警告、每张源页参考图的页码/PNG 摘要/尺寸/像素摘要。固定：`recognition_correctness=UNVERIFIED`、`editable_content_coverage=OCR_BLOCKS_ONLY`、`math_verification=NOT_PERFORMED`、`layout=NOT_VERIFIED`。这不是完整用户数学目标的完成证据。

源冻结、当前 dispatch 检查、独立 DOCX 段落/图片落点/解码像素回读、源与解析输入再查后才提交。重放重新核验缓存；损坏输出不重发成功。独立键为 `pdf-ocr-draft-v1`。内部位置图片扩展复用唯一 MaterialWriter，普通 create 工具图片上限仍为 5；参考图最多 200 张且总计 16MiB，正文 60000 UTF-16 单位，产物 30MiB，超限明确拒绝并提示选择范围。

## Office 导出选项生效

实际反例已证明：普通 Python tuple 写入 UNO `FilterData` 可被静默忽略，隐藏页请求仍只导出可见页，请求无损却实际输出 JPEG 且像素改变。生产 `office_worker.run.export` 现在显式封装 `[]com.sun.star.beans.PropertyValue`；原固定选项不变。使用同一份保留 DOCX 输入，独立解码实际 PDF 图片验证 RGB 字节一致，非参数转发模拟。

生产 PPT PDF 仍不导出隐藏页/备注；验证脚本另用显式隐藏页过滤器检查全部逻辑页，不修改或保存源幻灯片的隐藏属性。原 B1d 合法前缀 Content_Types 被特定 LibreOffice 装载失败的证据保留，不为迎合该引擎静默归一化用户文件。

## 实测范围与重放

本批源/新版各 3 张可见页、各 5 张含隐藏页：全部 16 页实际导出并独立渲染；未修改幻灯片像素一致。原版编辑器确实写入未显示 Choice，但全部 3 张可见页没有变化，现版无输出拒绝。PDF 新几何案例 4 源页/5 输出页逐页比对；原多源案例 4 输出页也重新核验。

候选 Word 由 Android 真实工具生成，完整稿/选页稿分别实际渲染 4/3 页；参考图标题与实际 Do 图片绘制同页。解析输入明确是 **SYNTHETIC_PROTOCOL_FIXTURE**，不是实际 MinerU OCR。完整当轮 JVM、native、服务、普通 Debug/lint 的准确计数及失败日志以交付 TEST_RESULTS 为准，不累计历史计数。

可重复执行的独立命令（run-dir 必须新建，输出目录不存在）：

```powershell
python scripts/verify_b_presentation_pdf.py --source-dir HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/native/current/presentation --run-dir .local/b03-ppt-replay-01
python scripts/verify_b_pdf_geometry.py --source-dir HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/native/current/pdf-geometry --run-dir .local/b03-pdf-replay-01
python scripts/verify_b_pdf_ocr_draft.py --source-dir HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/native/current/ocr-draft --run-dir .local/b03-word-replay-01
```

需要已安装的 Windows LibreOffice/Python UNO、pypdf/Pillow 与 PDFium。没有自动安装/联网步骤。现有 native 设备夹具与精确命令见 HANDOFF。

## 门禁与回滚

整体 CONDITIONAL：仅隔离、本机、合成资料范围；真实 MinerU/模型准确率、Linux/Docker、MS Office/WPS、API26/Mate50/Release/生产及完整主 Chat 目标均未获得本批通过。Windows UNO 历史关闭阶段 DisposedException 记录保留，未改引擎掩盖异常、未加重试；最后当前源码各实际导出通过，不等于长时间稳定性验收。独立与全项目审查 NOT_EVALUATED，无子代理。

没有数据库/Pigeon/任务/凭据/权限/模型迁移。回滚只能停新操作或逆本批精确差异，保留原件、已验证版本与 receipt；不得恢复兼容分支假编辑、错覆盖缓存读取、旧编辑缓存绕过或把候选稿降格标成已核验。B04 按原 B_TASK 另立资料库合同，不能把本批候选稿宣称扫描全书已读完。
