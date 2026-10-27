# B1c DOCX OMML 精确编辑增量 v1（2026-10-14）

## 已实现契约

沿用现有 `inspect_material_edit` / `edit_material_file` 的真实 ChatToolFactory 接线和可信审批，不新增工具名、执行器、Pigeon、数据库或模型配置。B领域代码直接可达，A共享文件未修改。

检查：`selection={"equations":true,"offset":0,"count":20}`。仅DOCX主 `word/document.xml`，按全部 `m:oMath` 文档顺序从1编号（包含表格，不含页眉页脚）；不可同时指定 `runs:true`。返回 `part/basis/total/offset/equations[index,text,truncated,editable]`。`text`为OMML结构显示，不是数学等价断言；过长、域结果或删除/移出修订中的公式不能编辑。

修改：原 `id/expected_sha256/title`，`operation=replace_equation`，`spec={"index":2,"expectedText":"[公式 (x)^(2)]","equation":{"type":"fraction","numerator":"x+1","denominator":"y-1"}}`。另支持既有 `superscript(base,exponent)`、`text(text)`；严格键与字符串，有界2000字符，不接受裸XML/外链或任意LaTeX。Writer生成与Editor替换共用同一OMML生成器，旧生成输入及文件编辑操作保留。

## 保全与真实性

只更换所选OMML内容（该公式内部运行格式可以变化），保留该容器属性、段落/OMML父属性和所有无关节点。除主document.xml外，包部件字节不变。XML重新序列化，因此主XML字节不承诺相同；新公式高度也可引起后续内容排版下移。保存后逐部件真实读回，再解析目标公式核对结构显示。原件保留，新版本继续绑定owner/sourceHash/parent/requestKey；缓存幂等沿用edit-v3，不复用错误源版本。

## 本轮证据与复验

`HXai_CHANGE_REVIEW/B-DOCX-OMML-20261014/`：E4真实Android工具/新材料版本，2项新回归通过；旧Office安全3项及Word/Excel/PDF编辑流程1项通过。错owner/hash/expectedText/index/不完整公式拒绝交付；合法UTF16和不同math前缀通过，域结果不可编辑。

真实已装LibreOffice26.2.4.2将原/新复杂合成DOCX各渲染为1页PDF；独立pypdf/PDFium读取真实内容、生成代表页PNG并实际查看。保留图片、其他公式、目录缓存域、页眉页脚、表格和周边粗斜体/样式；新分式导致行高与表格正常下移。目录域保留不代表更新目录。完整复杂版式及跨MS Office为NOT_VERIFIED/NOT_RUN。

Windows本机复验（run-dir必须未存在，输入为真实native测试导出的source.docx/edited.docx/result.json）：

```powershell
python scripts/verify_b_docx_omml.py --source-dir HXai_CHANGE_REVIEW/B-DOCX-OMML-20261014/artifacts/final-native --run-dir HXai_CHANGE_REVIEW/B-DOCX-OMML-20261014/runs/new-unused-office-run
```

脚本仅用已装UNO Python、Pillow、pypdf和LibreOffice自带PDFium，不安装、上传或部署。Android仅使用本批独占QA设备与合成档案。普通Web重建、Linux/Docker、Release/Mate50、真实模型、生产NOT_RUN；独立外部审查NOT_EVALUATED。
