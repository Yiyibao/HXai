# B02 DOCX/OMML 精准编辑领域协议 v2（2026-10-19）

来源：[B1c v1原协议](../parallel-development-20261014/B1c-DOCX-OMML-PROTOCOL.md)、[本批合同](../../HXai_CHANGE_REVIEW/B02-DOCX-PRECISION-20261019/CHANGE_CONTRACT.md)。本文件是有出处的增量，不替换旧证据，不授予完整Chat/G2/Release通过。

## 接口与版本

仍使用已注册的 `inspect_material_edit`、`edit_material_file`、`create_material_file`、`read_material_text_page`，无新工具名、授权种类、schema/Pigeon或第二执行器。可信注册仍为 material.write / VERSION_WRITE / ARTIFACT_READBACK；本批不修改A共享Factory/Registry/Planner/Verifier/Host。共享协议v1.23、Room32/sidecar9/Pigeon164保持；领域协议v2，编辑请求键edit-v4，只有DOCX创建请求键升material-write-v3，其他格式创建键仍v2。旧不可变文件/历史版本保留，不复用旧缓存绕过新约束。

`MaterialEditor.WordProtection` 按主文档顺序追踪嵌套复杂域begin/separate/end，覆盖跨段TOC缓存；未闭合、无对应begin的end/separate、重复separate或未知类型保守禁止编辑。含域标记/指令的段落仍保守不可编辑。简单域、删除/移出修订及非unlocked锁定内容控件也不可编辑，检查与整段/运行/局部范围/公式修改共用边界。读取这些结构不等于目录更新。

## 富文本段落局部替换

检查Word段落返回 `range_index_basis="utf16_in_paragraph_text"`、`editable`（旧简单整段操作）和 `has_editable_text_ranges`（存在可选择普通文本，不表示任意范围均安全）。段落text仍为该段落所有w:t按文档顺序拼接；OMML的m:t不占这个索引。index从1起，包含表格内段落，不是物理页号。

```json
{
  "id": "当前档案的源材料版本ID",
  "expected_sha256": "当前源摘要",
  "operation": "replace_paragraph_text",
  "spec": "{\"index\":12,\"expectedText\":\"TARGET old phrase END TAIL\",\"start\":7,\"end\":17,\"text\":\"new precise phrase\"}",
  "title": "讲义局部修订"
}
```

index必须重新检查取得，示例12不是可重放定位。start/end为完整检查文本的UTF-16半开区间，0<=start<end<=length；可用空text删除，但不支持空选区插入。不拆Unicode代理对；完整预期原文<=2000单位、新文字<=4000单位，不接受XML非法字符/换行控制字符。检查已截断时不得猜原文继续编辑。

只修改直接w:p下普通w:r/w:t，允许跨不同样式运行；新文进入首个选中运行，继承其样式，后续被选文字清除但运行/样式节点保留。未选择内容不动。不跨公式、图像、域、书签、链接、批注、换行/制表或未知结构边界；相邻非选中复杂结构不妨碍普通文字范围。旧replace_paragraph仍仅简单同字符样式，不能用其清空复杂段落。

## 有界可编辑公式树

`replace_equation`仍为 `{index,expectedText,equation}`，按主文档oMath顺序，含表格、不含页眉页脚；expectedText使用检查返回的完整结构显示。目标oMath容器属性、父段落和其他公式保留，目标内部运行格式可以重建。

对象必须type及下表字段，禁止额外键。字符串叶子为文字，不解释XML/LaTeX；除text.text外，子项可为字符串或同结构嵌套对象。

| type | 必需子项 | 可选子项 |
|---|---|---|
| fraction | numerator, denominator | 无 |
| superscript | base, exponent | 无 |
| subscript | base, subscript | 无 |
| subsuperscript | base, subscript, exponent | 无 |
| radical | radicand | degree；省略为平方根并隐藏根指数 |
| row | items，1–32个字符串/对象 | 无 |
| text | text，必须字符串 | 无 |

每个公式总树深<=8、节点<=64（对象和字符串叶子均计数）、内容<=2000 UTF-16单位；不接受数字/布尔/null叶子、缺键、非法XML字符或未配对代理。Writer和Editor共用 `MaterialEquationXml`，旧fraction/superscript/text平面字符串输入兼容。输出为真实m:f/m:sSup/m:sSub/m:sSubSup/m:rad/m:r，不是把公式显示文字冒充OMML；row仅表示显示序列，不授予运算优先级或数学证明。

```json
{"type":"fraction","numerator":{"type":"subsuperscript","base":"x","subscript":"n","exponent":"2"},"denominator":{"type":"radical","radicand":{"type":"row","items":["a","+","b"]}}}
```

## A03 可用输入与真实证据

`HXai_CHANGE_REVIEW/B02-DOCX-PRECISION-20261019/native/`：
- source-A.docx/source-B.docx：各三页，跨段TOC、页眉页脚/PAGE、蓝色位图、表格、段落/运行样式、书签和OMML。
- paragraph-A/B.docx、edited-A/B.docx：两步不可变新版本，目标段落/公式以及父版本摘要和材料ID见result.json；除document.xml外各部件字节相同，主XML除选中文字/公式内容DOM等价。
- result.json中sourceQuote：实际read_material_text_page摘要绑定读取及逐字复验；DOCX引用是提取字符区间，不捏造原Word物理页。
- created-notes.docx/created-result.json：实际create_material_file生成、包含两源逐字引用、版本ID/摘要及嵌套OMML的说明。由定向测试指定内容，不是模型自主综合或无损拼接两DOCX。

这些ID仅属于已清理的合成QA档案；A03须导入本次授权源后取新ID/hash，不硬编码或跨档案重放。A03沿现有源读回/语义验收和真实预览继续验证整个用户目标，本批不改共享成功裁决。

`runs/real-office-02/RESULT.json`：LibreOffice 26.2.4.2实际导出两源、两新版和说明；独立pypdf读取、PDFium渲染13页。两讲义修改页均为实际导出PDF第2页，代表页为第1/3页，定位绑定PDF摘要；不把段落index、原Word印刷页、PDF物理页混用。imageResourcesPerPage仅列资源引用，可能被多页共享，不代表每页都画了图片；蓝图是否绘制由实际PNG查看取证。目录缓存保留，不宣称重算TOC。

## 复验与适用边界

```powershell
# 直接复验已导出的本批合成原生DOCX，run-dir必须全新；不安装/上传/启动生产。
python scripts/verify_b_docx_precision.py --source-dir HXai_CHANGE_REVIEW/B02-DOCX-PRECISION-20261019/native --run-dir .local/b02-office-replay-01

# 已启动归属B的emulator-5584后，构建隔离QA并运行本批+旧安全/公式回归。
python scripts/verify_agent_android.py --serial emulator-5584 --run-id b02-native-replay-01 --classes me.rerere.rikkahub.feature.material.OfficeSafetyRuntimeTest,me.rerere.rikkahub.feature.material.DocxEquationRuntimeTest,me.rerere.rikkahub.feature.material.DocxPrecisionRuntimeTest --build --isolate-tests

.local/gradle-9.6.0/bin/gradle.bat -p client :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

当轮491个App JVM测试、12个原生方法及真实Office输出通过；原生首轮11/12，另一个旧community测试因缺公开fixture失败，按固定本地fixture补供后该方法通过，原失败未覆盖。详细命令/源码/APK/输入/产物哈希与范围裁决见TEST_RESULTS及HANDOFF，不把重跑或历史累计计数当完成率。

本机领域范围PASS_SCOPED。主Chat自主两讲义/追加要求/分享、MS Office/WPS、Linux/Docker/API26/目标设备/Release/生产NOT_RUN；一般数学证明和完整版式保真未授予通过；独立/全项目审查NOT_EVALUATED。回滚只能逆本批精确领域补丁或禁新操作，保原件/版本/回执；不得恢复旧版“域缓存可编辑”漏洞或让edit-v3缓存重新变成新请求成功依据。
