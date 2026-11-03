# B1d PPT文本形状定位增量 v1（2026-10-14）

复用原工具/可信审批/材料版本库，不改共享Factory、registry、TaskStore、Pigeon或DB。`inspect_material_edit` 的PPT selection增加：

- `{slide:2,shapes:true,offset:0,count:20}`：按真实spTree顺序（包括分组）列出文本形状 `shapeId/name/run_count/text_preview/locatable`。不是图片、表格或母版编辑入口；预览有界1000字符。
- `{slide:2,shapeId:"17",offset:0,count:10}`：检查所选形状局部文本运行。返回实际part、hidden、slide_basis、index_basis=shape_runs、shapeId及run/index/text/truncated/ editable。
- shapes与shapeId互斥；字段仅PPT可用。ID字符串来自源文件，不猜路径或重复文字。与图片/组/其他形状重复的ID不可定位；自动域结果(a:fld)不可作为普通文本编辑。

原 `edit_material_file` / `replace_text_run` 的PPT spec可带shapeId。带时index为所选文本形状内1起始运行；不带时保持原整slide索引。expected_sha256绑定整个源版本，expectedText完全匹配。slide仍为presentation.xml/r:id的逻辑序（含隐藏页），不改为文件名/可见页/PDF页。

只改所选a:t及xml:space，保留运行样式、其他节点、几何、图片/母版/布局/主题；其他包部件逐字节不变。保存真实回读后再按同一逻辑slide/shape定位目标；原件及不可变谱系保留。长文字仍可能溢出，不承诺像素/微软Office保真。

另修复已真实复现的B1c离线消费者断裂：OMML构造逻辑移入唯一纯 `MaterialEquationXml`，Writer/Editor均复用，不保留旧别名；`verify_b_xlsx_readback.py` 编译真实WordContent/codec，重新跑通真实Editor→UNO→XLSX/PDF旧烟测。未引入Android stub/fake结果。

证据在 `HXai_CHANGE_REVIEW/B-PPTX-SHAPES-20261014/`。真实Android形状编辑1项、旧DOCX2/Office安全3/材料流程1项通过；LibreOffice26.2.4.2与独立PDFium读回全部2可见页，逻辑1隐藏→无PDF、逻辑2→PDF1、逻辑3→PDF2。实际页面已查看：图形/图片、相邻文本与未选标题保留；原始夹具日期域与另一个文本已有重叠，新编辑未新增该重叠，不授予全部版式可用性PASS。日期域显示由Office计算，不等同源XML被本地编辑。

初次生成的前缀Content_Types表示：原生结构编辑通过，但当前LibreOffice装载失败；显式滤器也无效，尝试已撤回，engine原字节不变。仅合成夹具生成沿用原模板默认OPC命名空间，没有规范化用户上传文件。此合法前缀表示的引擎渲染仍FAIL，见LOAD_FILTER_DISPOSITION；限定成功不能授予所有OOXML表示支持。

复验：

```powershell
python scripts/verify_b_pptx_shapes.py --make-fixture --run-dir <未存在合成输入目录>
python scripts/verify_b_pptx_shapes.py --source-dir HXai_CHANGE_REVIEW/B-PPTX-SHAPES-20261014/artifacts/native-final --run-dir <未存在渲染输出目录>
```

原生实际入口 `PptShapeRuntimeTest`，输入放本批独占QA设备cache/b1d-shapes-source.pptx。脚本只用已装UNO/PDFium/Pillow/pypdf，不安装、不上传、不调用真实模型。Web沿用哈希绑定未改资源，普通Web重建、Linux/Docker、Release/Mate50、跨MS Office、真实模型/生产NOT_RUN；独立审查NOT_EVALUATED。总体CONDITIONAL，成功仅限已声明模板与编辑范围。
