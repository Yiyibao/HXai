# 文件与图片能力验证报告

此页保存第一次基础能力测试的历史记录。2026-10-07追加内容处理与真实交付以[追加验收报告](completion-20261007.md)为准；下文“未配分享接收应用”“扫描视觉未专项验证”等是当时的状态，不代表追加测试结论。

2026-10-07。基础通用文件闭环已运行；不宣称全部格式保真、图片生成成功或完整 v1 交付。保留已有 S1/S2/S3 未提交改动，没有发布、提交 Git 或创建远端。

## 实际运行证据

| 检查 | 实际结果 | 证据 |
|---|---|---|
| 当前源码编译及 app JVM 回归 | PASS，287 项、0 失败/错误；Debug 与 AndroidTest APK 已生成，覆盖安装保留现有数据 | [构建](build-final.log)、[计数](jvm-results.json)、[APK 哈希](apk-results.json) |
| 最终模拟器回归 | PASS，16 项；4 文件测试、1 页面测试、教学存储及10项凭据回归 | [Android 日志](android-final.log) |
| 真实模型文件处理 | PASS，五轮分别读取 MD/DOCX/XLSX/PPTX/PDF，调用生成工具，成果实际落盘并校验；另一轮实际识别图片数字7 | [真实测试](real-model.log)、[完整回复及产物记录](real-model.md) |
| 图片生成独立探针 | BLOCKED_CONFIG，未选定图片生成模型；实际工具返回 FAILED，没有登记成功 PNG，不用文字冒充图片 | [探针尾部](real-model.md) |
| 独立文件读取 | 11 个合成/模型输出文件由 python-docx、openpyxl、python-pptx、pypdf 读取，中文正文存在；Excel `=1+1` 为字符串，未执行公式 | [独立结果及 SHA-256](independent-results.json) |
| Office 外部打开 | DOCX/XLSX/PPTX 样例及真实模型成果由本机 LibreOffice Writer/Calc/Impress 读入并转换为 PDF；实际页面检查，基础内容可读 | [渲染目录](rendered/)、[页数](office-open-results.json)、[初始样例页面](office-pages.png) |
| Android 系统另存 | 实际 DocumentsUI Downloads 中点击 SAVE，应用回调写出文件，再从目标 URI 读取并比对 SHA-256；页面反馈“已另存并重新读取校验” | [另存页面](material-save-dialog.png)、[Android 测试](android-final.log) |
| Android 分享 | 实际打开 com.android.intentresolver 系统窗口并携带 FileProvider URI/读取授权；模拟器显示 No apps can perform this action，因此没有接收方交付成功证据 | [窗口包名](material-share-ui.txt)、[真实分享窗口](material-share-dialog.png) |
| 归属、取消、失败、重试 | 越权ID/另一档案私有路径拒绝；写入失败/取消无成功文件；重试成功；源文件篡改拒绝；页面切档案不显示上一人的文件；聊天附件清理不会删除独立材料库 | [Android 测试](android-final.log)、[成果页](material-page.png)、[另一档案](material-profile-b.png) |
| PDF 指定页 | 真实多页中文 PDF 渲染物理第1页 PNG，尺寸/源ID/页号检查；跨人、6页请求、页号越界拒绝 | [多页文件](multipage.pdf)、[Android 测试](android-final.log) |

模拟器 emulator-5554，Android 14 / API 34，x86_64；构建仍用项目已有 SDK/Gradle 基线。真实模型使用本项目模拟器原有 Responses 配置，不输出密钥/认证头；测试临时创建 QA 学习者，结束恢复原设置，关闭该 QA 档案的自动记忆。结果只适用于本次配置和合成样例，换模型/接口不能自动继承为全部能力通过。

## 各格式支持与实际产物

| 格式 | 当前支持程度 | 可检查的真实模型输出 |
|---|---|---|
| Word DOCX | 读取正文/基础表格文本；生成可编辑段落/基础表格，非 OMML 或复杂图片布局 | [DOCX](real-验证docx.docx) |
| Excel XLSX | 读取单元格坐标、字符串、公式原文/缓存值；生成单工作表文字单元格，不重算或写可执行公式 | [XLSX](real-验证xlsx.xlsx) |
| PPT PPTX | 按关系顺序读取逐页文字；生成可编辑文字页，图表/图片/动画/备注未支持 | [PPTX](real-验证pptx.pptx) |
| PDF | 有界物理页文本和指定页原图；生成中文分页文字 PDF，专业数学排版未完成 | [PDF](real-验证pdf.pdf) |
| 文本/Markdown | UTF-8 文本，明确字符范围；Markdown 真实文件，外链图片未打包 | [Markdown](real-验证md.md) |
| 图片输入 | 常用图像私有导入及视觉模型；真实数字样例通过，手写/模糊符号样例尚未验证 | [真实视觉回复](real-model.md) |
| 图片生成 | 服务调用、最终图片校验和 PNG 落盘路径已实现；现有配置没有图片模型，实际成功未验证 | [失败证据](real-model.md) |
| DOC/XLS/PPT | 原文件可保存，解析明确拒绝并要求转换；未假称二进制内容为文本 | Android 异常/旧格式测试 |

所有 Office 输出目前为基础结构；未保证复杂布局、自动列宽/打印分页、图像或可编辑数学公式。Excel 长文字的默认打印会跨页，不能把这种基础输出当专业排版成果。PDF 保存 LaTeX 原文，不能把原文字串说成已排版公式。原始材料内容不作为本人表现，含附件的 PERFORMANCE 自动抽取候选被本地拒绝。

## 已发现并修复的问题

1. 原解析兜底把未知二进制当文本，错误当正文：改为显式格式路由/失败和范围反馈。
2. PDF 中文视觉正确，但字体子集把“文、二”等映射为康熙部首，导致重读样例失败：仅规范化该范围；避免全局 NFKC 破坏数学上标。修复后实际重读通过。
3. 材料页误把旧聊天路由当当前档案库权限，导致切档案后按钮禁用：库按当前档案浏览，工具任务按原会话档案固定。
4. 既有默认 profile 是 default-learner：明确兼容，仍拒绝任意路径型身份。
5. 聊天删除/编辑清理可能删除独立材料库文件：清理函数保护 materials 和外部原文件，实际回归验证库文件保留。
6. 仪器测试首次 PDF 页测试返回了 File，JUnit 要求 void：修正测试签名后全部16项通过。

## 仍未通过的范围

- 图片生成服务未配置；需要实际可执行图片模型/接口与凭据，完成最终 PNG 内容验证后才能标为支持。当前没有成功生成图片证据。
- 模拟器没有分享接收应用；已经验证系统分享窗口与授权入口，没有邮件/WPS/其他接收方实际收到文件的证据。外部手机 Office/PDF 打开、系统接收仍留验收；不是“已分享给别人”。
- PDF 扫描页本地渲染已验证，视觉工具回传后的复杂公式/整份扫描阅读还没有专项真实模型证据；手写模糊符号亦未验证。
- 复杂 Office 公式/图片/图表/日期格式、嵌套或合并表格、工作表显示名、专业 PDF 数学排版、任意长文档完整处理和稳定块引用链接尚未完成。明确范围详见 [实现说明](../../docs/material-execution.md)。
- materials 纳入现有包括文件备份，缓存可重建；完整加密备份/跨设备恢复/删除合并留 S5，未以凭据测试冒充材料迁移完整验收。
- Mate 50 / HarmonyOS 4.2.0 的安装、文件查看器、分享、后台、字体和性能仍属于交付验收。当前为开发 Debug APK，不是签名发布交付。

## 复现

`scripts/test_material_completion.ps1 -RealModel`：构建、安装、16项本地 Android 和真实模型探针；真实模型使用模拟器内本项目现有配置。`scripts/collect_material_evidence.py --real` 导出合成产物/截图，不读取应用设置。`scripts/validate_material_outputs.py` 用独立文件库读取及渲染 PDF。Office 可用本机 LibreOffice --headless --convert-to pdf 检查，使用独立 QA user profile，避免影响用户已打开文档。
