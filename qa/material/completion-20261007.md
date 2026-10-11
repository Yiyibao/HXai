# 文件内容与真实交付追加验收

2026-10-07，emulator-5554 / Android 14 API34 x86_64。保留已有未提交开发内容，未发布 APK；Mate50真机仍待验收。

## 变更

- Word：结构化读取常见OMML分式/上下标/根式，保留括号边界；标记内嵌图片关系，专用工具按引用顺序输出真实图片。生成可编辑OMML分式/上标/文字和本档案图片，表格补齐标准列网格、边框、列宽和重复表头。
- Excel：跟随workbook关系和显示顺序读取工作表名，标记数值/布尔/字符串等类型；输出支持工作表名称、JSON数值/布尔/null，`0012`与`=1+1`字符串原样保留。超过15位有效精度的数值拒绝并提示改为字符串。增加换行、冻结表头、横向打印适配。
- 长内容：解析上限100万字符、PDF最多200物理页；工具返回总长度/下一偏移，可分段继续读取。预览6000字符分页，表格行可横向滚动。仍有明确限额，不承诺任意长文完整读取。
- PDF输出：边框表格、跨页重复表头、超长单元格按文字整行续页；正文LaTeX仍是原文。26页合成样例包括跨5页的第80行及第160行末尾。
- 交付：独立QA APK在另一UID下实际接收FileProvider URI，复制完整字节、计算SHA256并渲染；应用成果页逐格式点开、经DocumentsUI另存、通过系统选择器交给真实接收Activity。

## 验证状态

最终构建及安装成功，287项app JVM测试、21项本地Android测试和1项真实模型专项全部通过。真实模型专项包含8轮对话（5种基础格式、原图数字、扫描PDF、Word图片/公式），另有图片生成未配置的失败探针；失败探针没有登记成功图片。

| 检查 | 结果与实际证据 |
|---|---|
| 最终APK/JVM | PASS，287项、0失败/错误；[最终构建日志](build-delivery-final.log)、[测试计数/最终APK哈希](completion-results.json) |
| 本地原有回归 | PASS，16项；归属、失败/取消/重试、篡改、另存、切档案、教学与凭据回归；[日志](android-final.log) |
| 新增保真与分页 | PASS，4项；Word图片/OMML、Excel名称/类型/非标准部件名及顺序、52902字符长文尾部、26页PDF表格、工具next_offset和UI上下页/横向表格单元；[日志](fidelity-android.log) |
| 扫描PDF真实模型 | PASS，无文本层→实际物理页PNG→模型识别7391及`(x+1)/2`→真实Markdown；[输入](scan-input.pdf)、[输出](scan-output.md)、[模型专项日志](real-model.log) |
| Word真实模型 | PASS，实际提取公式、调用图片工具识别8642、生成包含原图/可编辑分式和上标/表格的新DOCX，图片字节完全一致；[产物](word-vision-output.docx)、[完整记录](real-model.md) |
| 独立解析 | PASS，python-docx确认图片/表格，直接OOXML核对可编辑OMML；openpyxl确认工作表显示顺序、`0012`字符串、12.5数值、true布尔、`=1+1`文字和null空单元格；pypdf检查第80/160行与23个表格页的重复表头；[结果](fidelity-independent.json) |
| 实际视觉检查 | PASS，LibreOffice导出的Word图片/分式/上标/表格与Excel边框表格均可见；Poppler渲染的26页PDF联系表、超高行续页及末页无裁切；[Word渲染](word-fidelity-1.png)、[模型Word渲染](word-model-final-1.png)、[Excel渲染](excel-final-1.png)、[PDF联系表](table-contact.png) |
| 五格式实际交付 | PASS，1项逐格式UI测试；每格式实际打开、系统另存后重读哈希、分享给另一UID并复制完整文件；Word采用图片/OMML样例、Excel采用命名/类型样例；[日志](delivery-android.log)、[接收方完整回执](delivery-receipts.txt)、[逐格式字节数与哈希](completion-results.json) |

| 格式 | 外部查看器打开 | DocumentsUI另存并校验 | 另一UID实际接收并校验 | 接收界面 |
|---|---|---|---|---|
| DOCX | PASS | PASS | PASS | [图片/公式/表格](delivery-send-docx.png) |
| XLSX | PASS | PASS | PASS | [名称/单元格与类型](delivery-send-xlsx.png) |
| PPTX | PASS | PASS | PASS | [逐页内容](delivery-send-pptx.png) |
| PDF | PASS | PASS | PASS | [PDF原生渲染](delivery-send-pdf.png) |
| Markdown | PASS | PASS | PASS | [正文](delivery-send-md.png) |

最终本地回归安装的是`completion-results.json`所记APK。真实模型测试先使用同一Word/视觉实现完成，随后仅增加Excel默认样式/边框及将交付测试切换为复杂样例，重新构建安装并重跑所有本地回归；这些末轮改动不重复调用已通过的模型测试。模拟器没有清空原应用数据；各测试临时档案和当前设置通过fixture恢复，凭据未进入日志。

修复了独立检查发现的Word表格缺少`tblGrid`问题与Excel默认样式缺失；也修复QA检查中首次WebView启动时窗口暂为空、同内容旧回执误读的问题。每轮交付先清理接收回执，等待接收应用实际显示，再接受匹配哈希。pypdf与MuPDF的文字换行不同，独立末行检查只规范化康熙部首与空白，不改动原PDF。

## 验收边界

QA文件查看器是独立测试应用，Office部分用自己的XML解析器显示基础正文/表格/公式/图片，PDF由Android PdfRenderer渲染；它证明跨应用授权、实际字节到达和基础显示，不代表WPS、Microsoft Office或HarmonyOS文件查看器兼容。桌面LibreOffice渲染及python-docx/openpyxl/pypdf独立解析作为额外保真证据。

扫描PDF测试限定单页、清晰印刷数字和分式；不代表任意扫描件、模糊手写或整份论文公式都正确。Word输出公式是显式结构对象，不自动把任意LaTeX变成OMML，复杂图片锚点、嵌套表格及图表未保证。Excel日期样式不自动转为日期，公式仅读取原文/缓存，不重算；当前生成一个命名工作表。

图片生成服务仍未配置；已有失败探针不改标成功。Mate50/HarmonyOS、商业查看器、跨设备备份及签名发布不属于此次模拟器通过范围。

## 复现

`scripts/test_material_completion.ps1 -Delivery -RealModel`构建并安装Debug和测试APK，运行回归、内容保真/分页、真实交付及模型测试；`scripts/build_material_receiver.ps1`只安装到emulator-5554。`scripts/collect_material_evidence.py --real`只导出合成测试文件/截图，不读取设置或凭据。`scripts/validate_material_fidelity.py`用独立文件库核对新增样例。
