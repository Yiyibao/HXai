# 文件与图片能力执行说明

2026-10-07。本轮沿用 Android APK 路线，保留原 S1/S2/S3 未提交实现。范围为通用文件读取、分析、处理和生成；不要求固定笔记模板，也不以 Skills 制作作为前置。文件保真及模拟器跨应用交付的追加验收见 [追加报告](../qa/material/completion-20261007.md)。

## 底座核对与实现

底座有系统附件选择、裁剪、UIMessage 图片、模型工具循环、FileProvider、系统另存/分享和独立图片生成 API。原文档变换器有 PDF/DOCX/PPTX 解析，但未知格式直接 `readText()`，错误字符串被注入正文，共享 upload/workspace 没有档案工具权限。当前 ai Provider 序列化没有通用 Document 原生文件输入实现，因此本轮不向所有模型直接发送 Office/PDF 二进制，也不假称中转文件上传端点已验证。

新增 `feature/material/`：

- `MaterialFiles`：`files/materials/<profile>/<id>.<ext>` 私有仓储，逐文件 JSON 状态与 SHA-256；兼容既有 default-learner 档案。模型仅接受应用签发 ID，身份来自固定会话 profile，不能指定路径或档案。历史中显式附加的旧 upload 文件迁入该会话档案；另一档案私有路径拒绝。文件内容和缓存均归档案。
- `MaterialReader`：严格 UTF-8；有界 Office ZIP/XML；PDF 物理页提取；错误不作为材料正文。内容缓存版本 `read-v3`，先校验原文件哈希再使用缓存。拒绝 DTD/实体及超限解压。不执行宏、公式、脚本或文件中的权限指令。
- `MaterialPdfPages`：实际渲染指定 PDF 物理页，最多 5 页，最长边 1536 像素；返回真实图片及范围，源 ID/物理页保留。需要配置视觉模型，服务实际能力仍以探针为准。
- `MaterialWriter`：直接写真实 Markdown、分页 PDF 和基本 DOCX/XLSX/PPTX，不依赖桌面电脑在线。Office 输出重读校验；PDF 检查可解析性；成功前临时文件，不覆盖原材料。相同档案、格式、标题、正文的成功文件按 requestKey 复用；修订内容产生新不可变输出。
- 工具：`list_material_files`、`read_material_file`、`read_material_text_page`、`read_material_word_images`、`read_material_pdf_pages`、`create_material_file`、`generate_material_image`。不提供 Shell、任意路径、URL 或系统权限。退出原共享 workspace 指令注入，继续禁用未授权共享 workspace/MCP。
- 页面：材料上传、提取范围预览；成果真实文件列表、系统打开/另存/分享，图片模型选择；取消/失败/重试反馈。系统另存后重新读取并比对 SHA-256；打开仅报告请求外部应用打开，分享选择器不声称接收方已收到。

## 实际支持合同

| 格式 | 输入路径与范围 | 输出范围 |
|---|---|---|
| Word DOCX | 正文/基础表格；常见 OMML 分式、上下标、根式转为带括号表达；图片关系按文档引用顺序另用视觉工具读取；不生成原文件页码 | 可编辑段落、带列网格/边框/重复表头的表格；显式 equations 支持 OMML 分式、上标、文字，images 支持本档案图片ID，最多5张；正文LaTeX仍为文字 |
| Excel XLSX | 按 workbook 关系与显示顺序读取实际工作表名、坐标、字符串/数值/布尔/错误/ISO日期类型、原公式与缓存值；样式日期不自动换算 | 单个可命名工作表，最多1000行×100列；rows 的 JSON 数字/布尔保留类型，字符串保持文字，null为空；数字最多15位有效精度，长编号应传字符串；冻结表头、换行、横向打印适配；不执行公式 |
| PPT PPTX | 按 presentation 关系顺序提取逐页文字；图表、图像、演示备注未保证 | 1–50 页可编辑文字；每页最多 1000 字符、16 行；未生成图像/动画 |
| PDF | 前最多200页、100万字符；显示总页数/处理页数/可能截断；无文本页标记扫描候选；另可指定1–5页原图 | 原生分页文字 PDF，最多100页；rows生成1–10列边框表格、跨页重复表头，超高单元格按整行文字分片；LaTeX为原文，非专业数学排版 |
| TXT/MD/CSV/JSON | 最大1 MiB UTF-8、100万字符；CSV不解释类型/公式 | UTF-8 Markdown；外链图片不自动打包 |
| PNG/JPEG/WebP | 原图给配置的视觉模型；模糊符号需核对；简单数字实际视觉样例单独验证 | 图片服务返回最终图片后实际解码、尺寸校验、写 PNG；缺配置/失败/无最终图片不成功 |
| DOC/XLS/PPT | 可接收保存，解析明确拒绝，要求转换 | 未生成旧版二进制格式 |

单文件30 MiB；Office条目最多2000、单XML 4 MiB、累计读取XML 16 MiB。解析结果最多100万字符，工具每次最多24000字符，返回total_characters和next_offset，可继续调用read_material_text_page；每次聊天附件注入合计48000字符。预览以6000字符分页，表格行横向滚动；这些页不是原文件页码。限额仍可能截断，须看coverage。Word内嵌图仅接受包内PNG/JPEG/WebP，拒绝外链，单图8 MiB/3200万像素。输出幂等键升级为material-write-v2，不复用旧版生成结构。Excel输出共享默认样式含单元格边框，独立库无默认样式警告。没有通过样例的复杂格式不能声称完整支持。图片输入与图片生成是两条独立能力。

## 状态、隔离与学习记忆

文件状态 RUNNING/SUCCEEDED/FAILED/CANCELLED，文本处理另有 NOT_STARTED/RUNNING/SUCCEEDED/FAILED/CANCELLED；进程重启后中断任务标为失败待重试，原文件保留，不承诺锁屏持续执行。工具轮仍使用 S1 检查点，取消向上传播，已成功产物保留供检查。图片网络错误不存原始 URL/响应/凭据；自动图片重试未启用，避免重复计费。

聊天删除、队列取消和分支编辑的附件清理不会删除独立 materials 库，也不会删除应用目录外的用户原文件。文件 ID 和扩展名在实际路径构造时再次检查，损坏或导入元数据不能携带路径越界。

上传和生成不写学习表现，也不提高掌握度。自动记忆只读取原用户文本，模型输出/工具结果不成为本人来源；本轮进一步拒绝以含图片/文件的消息作为 PERFORMANCE 自动提交证据。文件中的 nested 指令只是数据，运行时工具身份和路径边界不会变化。

现有“包括文件”备份纳入 materials 的合法 profile/id 文件及 JSON，不纳入临时文件/可重建解析缓存。完整加密备份、跨设备恢复/删除约束仍属于 S5；本轮不宣称其交付验收完成。系统自动备份规则未放宽材料目录。

### Flutter 成果正文预览（2026-10-11）

`getArtifacts` 只返回成果登记与 `statusDetail`，不再把处理状态当作文档正文，也不在列表加载时批量解析所有文件。详情通过 `getArtifactPreview(profileId, artifactId, expectedEpoch)` 按需校验真实文件的大小与 SHA-256，再复用 `MaterialReader` 提取正文；返回前再次核对档案、epoch 和成果登记。`previewContent` 是文件正文，`previewCoverage` 是实际提取范围与限制，二者与处理状态分开显示。

Markdown 使用数学 Markdown 渲染；DOCX/XLSX/PPTX/PDF 及其他可读文本显示可选择的实际提取文字，并明确不代表原始排版。图片、旧 Office 或提取失败时不伪造正文，显示限制和系统打开入口。预览及版本路由只携带 `ProfileResourceRoute(id, profileId, epoch)`，刷新、删除、恢复或来源档案变化时撤销旧内容与追问操作。

回归：`artifact_preview_content_test.dart` 覆盖正文/状态分离、不可提取格式和迟到预览；`ArtifactPreviewContentTest` 在模拟器生成真实 DOCX，通过原生桥接提取后实际点击 Flutter 成果列表，确认正文、处理状态和提取范围均可见，并保存 `review-fixed-artifact-body.png`。本轮未验证 Microsoft Office 版式一致性或真机兼容性。


## 验证与剩余项

本轮测试、实际模型记录、最终 APK、外部 Office 打开和渲染证据见 [验证报告](../qa/material/report.md)。手机 Office 查看器兼容、Mate 50、复杂中文公式排版、大型/异常格式、手写模糊图、完整文档来源块链接、跨设备材料备份仍需后续验收。当前图片生成未配置可执行模型；工具已实现但未获真实生成成功图片证据。
