# B1e — PDF 原页组装与回读协议 v1

FeatureID：B-PDF-PAGE-ASSEMBLY-20261017。适用 base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批完整未提交增量；精确字节以 `HXai_CHANGE_REVIEW/B-PDF-PAGE-ASSEMBLY-20261017/SOURCE_FINAL.json` 为准。不是裸 HEAD 能力声明。

## 入口与边界

保留已注册的 `edit_material_file` / `operation=pdf_select_pages`，没有新工具、模型或第二执行器。工具以可信 owner 闭包读取当前材料，输入仍为 id、expected_sha256、title、JSON字符串 spec。spec 示例：

```json
{"sources":[{"id":"当前档案PDF材料ID","sha256":"真实源SHA256","pages":[3,2,3]},{"id":"另一个当前档案PDF材料ID","sha256":"真实源SHA256","pages":[1]}]}
```

第一项须对应 id/expected_sha256；1–20组，所有组非空，输出合计1–200物理页，允许重复/逆序；源文档本身可以超过200页。既有总源60MiB/单源30MiB/输出30MiB限制保持。只读当前档案，不接受模型绝对路径或owner。原PDF页 graft，不是截图重建、OCR或可编辑Word。书签、链接、注释、表单、签名及文档级元数据不承诺保全。

## 冻结、验证与持久证据

`MaterialPdfAssembly.edit` 冻结每组输入到私有 `.part` 并检查size/SHA，再调用现 `MaterialEditor.pdfPages`。后者在任何PDF打开或保存前排除与任意源同canonical path/同inode的输出；不再错误限制源总页数为200。

保存后重新打开输出：逐页比较 MuPDF bounds、完整可提取文本UTF8 SHA256，以及独立 Android PdfRenderer 白底ARGB_8888、最长边1024像素的渲染SHA256/宽高。输出页数相同不够。比较精度明确限定为该渲染，不升级为任意缩放、数学语义或所有交互对象保真。

发布前重新检查原材料 bytes/元数据谱系，失败无成功Document；临时输入finally清理，进程退出后沿用 MaterialFiles 对 `.part` 的恢复清理。取消异常沿现工具包装向上传播，不改写为文件成功。原 TaskStore journal/UNKNOWN/授权/模型锁不变。

每个成功新版的现有 `detail` 字符串保存 JSON：contractVersion=1、scope=pdf_original_page_assembly、verification=REOPENED_PAGE_GEOMETRY_TEXT_AND_RASTER_MATCH、raster_long_edge=1024、page_numbering=pdf_physical_page_1_based、notice、pages[]。每页含 output_physical_page、source_id、source_sha256、source_physical_page、page_evidence(bounds/text_sha256/raster_width/raster_height/raster_sha256)。MaterialFile原有SHA绑定输出bytes，sourceId仍保留第一父版本；其他父版本/物理页由pages显式记录。

PDF采用 `pdf-assembly-v1` 幂等键，旧 edit-v3 的无此证据结果不冒充新合同。重放已成功请求先校验当前源、输出bytes、重新逐页验证并比较完整持久证据，损坏输出/伪造映射/变化来源不重复交付，也不盲目新写。其他Office操作键不变。

## 源码地图与兼容

- `feature/material/MaterialEditTools.kt:materialEditTools`：原入口的PDF分支转给专属适配器；原Word/Excel/PPT操作不改。
- `feature/material/MaterialPdfAssembly.kt:edit/verify/fingerprint`：身份/源快照、映射、回读与重放。
- `feature/material/MaterialEditor.kt:pdfPages`：原页graft、原件路径保护、源页数误拒修复。
- `src/androidTest/.../PdfAssemblyRuntimeTest.kt`：实际Tool、文件、回执与错误输入。
- `src/test/.../PdfAssemblyPathTest.kt`：真实NTFS硬链接的写前拒绝与字节保全。
- `scripts/verify_b_pdf_assembly.py`：从实际Android归档独立使用pypdf/PDFium验证物理顺序、文字、几何、图像和空白页。

不改Room/sidecar/Pigeon/MaterialFile schema。既有旧元数据/成果保留；detail消费者仍按字符串处理，结构化证据不新增全局UI控件。根目录三领域源及两专属测试已同步；相关材料依赖SHA与被测私有树一致。未运行根/A整树构建与真实Chat语义任务，不能由领域同源证据授予它们PASS。

## 本批实测与限制

真实Android：9 PASS、1 SKIP（SELinux拒绝app创建硬链接）；相关材料31 PASS；Windows JVM411 PASS，含真实硬链接的写前拒绝；Debug/QA构建成功，lint 0 errors/24 warnings（含本批Bitmap.createBitmap的UseKtx建议，未抑制）。实际四页持久输出由独立pypdf/PDFium重新验证，保存逐页图像，已查看图像页。旧版同路径破坏原件、201页源误拒的FAIL保留；硬链接原生旧反例是环境阻止，不冒称产品失败或修复通过。

准确命令/输入/产物/源码hash及所有初始编译、JUnit和UTF8验证器问题见本批 TEST_RESULTS/RUNS。独立审查NOT_EVALUATED。PDF中途kill与最终发布瞬间并发源变更、API26/Mate50/Release/真实模型/生产未验收。裁决仅PASS_SCOPED于上述领域行为；完整B/P1/P2仍PARTIAL。
