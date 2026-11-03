# 专业数学合同16：统一Chat、条件推导、数值认证与多页批改

适用：`codex/p2-professional-math-integration-20261022` 工作树，HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。实现位于未提交增量；精确源码、增量补丁与本轮运行证据取 `HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/`。原Pro需求与合同1–15不改写。本合同不是完整P2质量验收或通用定理证明器声明。

## 实际入口与不变量

`ChatToolFactory → math_verify / read_math_work / review_math_work → GenerationLoop → 持久tool receipt → TaskResultVerifier → TaskPlanner`。沿用唯一Scheduler、接受时锁定的provider/model/reasoning和现有ToolPolicy；不另起数学模型、任务执行器、脚本解释器或付费调用。`math_verify`新增操作由同一主模型提出，宿主从持久原输入独立重算，不信模型给出的status。

`ProfessionalMathGuidance`要求先列完整题设、定义域、量词、参数分支、方法与证明义务；高中综合题、圆锥曲线、数列、线代、分析/实变的自然语言建模仍须由已锁定模型完成并单独验收。教学表达不改变学术条件；普通闲聊不强制数学流程。既有MCP逐工具授权、独立读回与Skill停用检查保留。

## math_verify 的新增操作

外层恰为 `{operation, request}`；`request`是最多65536字符的JSON对象字符串，解析前限制嵌套32层。原合同字段不可混用。`contractVersion=16`，每次返回具体scope、输入hash、证据及notice。

| operation | request与已实现范围 | 不能推断的结论 |
|---|---|---|
| `professional_linear_algebra` | `action=solve/rref/inverse/determinant`，`matrix`为1–12阶二维有理/十进制字符串数组，solve另有rhs字符串数组。精确行运算、秩、行列式、无解/唯一/仿射解族；解和逆矩阵有精确残差读回，逆矩阵有精确无穷范数条件数 | 奇异分类已算出不等于逆存在；病态输入不因多位小数而可靠；不核原题建模 |
| `professional_multivariate` | 最多4变量、总次数12、256项；identity用variables/left/right；derivative用variables/expression/variable/claimed。精确系数核含参偏导及几何代数恒等；仅非零常量除数 | 不约去变量分母，不自动证明参数不等式、几何对应或退化情形 |
| `professional_induction` | start为0–1000000整数字符串，initial有理字符串，next为n/u多项式，claimed只含n。实际核初值和归纳步恒等，认证n≥start的递推闭式 | 不由有限项相同猜测证明；题设必须确实给出该递推 |
| `professional_numeric` | expression、digits字符串10–100、bindings为变量到有理上下界字符串对。四则、整数幂±32、pi/e、sqrt/exp/ln/log/sin/cos/tan/abs；定向十进制舍入、级数解析余项与两工作精度包围交叉核对 | VERIFIED只认证表达式值的绝对误差区间；不是恒等式、极限或一般定理证明。输入区间过宽时UNSUPPORTED而非伪造精度 |
| `professional_theorems` | 空对象返回可信定理目录：单调有界序列、M-test、一致极限连续、控制/单调收敛、逐项积分/求导、中值、Tonelli/Fubini，参数与必要条件完整列出 | 目录读回不是条件已满足，也不是用户教材已检索 |
| `professional_proof` | assumptions/goal/steps：显式命题、合取/析取/蕴含/否定/矛盾、数学事实；登记规则逐步推导，追踪和解除假设，定理严格匹配全部有序前提；math规则实际调用原核验器 | `claim_is_conditional=true`须保留`undischarged_assumptions`；模型假设不能冒充原题假设。自由文本、量词自动推理与未登记推导保持未知 |
| `professional_solution` | problem、goals[{id,statement,predicate}]、checks[{goal,input}]；每个已声明目标必须有完全相同predicate输入的获证检查，禁止用无关恒等式换目标 | `EXPLICIT_DECLARED_PREDICATES_ONLY`、`natural_language_solution_certified=false`：不认证自然语言原题覆盖或全部分类讨论 |
| `professional_review` | steps[{original,locator,check或null}]、reference_note。按明确声明核完整前缀，再给first_error_step；未知前缀不把后面的错误称首错；错误原函数证书不等于积分值错 | 原步文字与结构化check的语义对应仍需核对；不是OCR、评分或整份手写机器认证，不按参考解字符串判正确异法 |

数值算法为有限确定性计算，不用Double采样作证明。exp/三角单点参数绝对值≤100，ln参数1e-100至1e100；分母区间包含0、非实根域、过宽输入和复杂度超限拒绝认证。更高工作精度一致不是证明本身，严谨性来自有向舍入和解析余项。

## 多页原稿与可信确认

文件详情 → 数学题目/批改 → 多页手写页。用户可选择并重排最多12个真实图片或PDF物理页，保留逐行完整转录和待确认符号；不猜PDF印刷页或Word分页。有歧义只存`WAITING_INPUT`草稿，只有可信前台按钮可创建确认记录，模型没有创建或确认工具。

宿主 `MaterialLibraryFrontend.library.mathWork.* → MathWorkInputs`：

- 原页绑定owner、id、SHA256、物理页号和用户顺序。每次读回核真实源文件和页数；确认不是数学已证明。
- 记录schema1位于noBackupFilesDir的owner哈希目录，固定内容生成稳定ID、持久原子提交，重开可读。确认不进入备份，跨设备不继承信任；原材料仍沿用既有备份/不可变版本。
- 来源删除在材料变更锁内撤销相关记录；恢复原件也不复活旧确认。不同来源的新版本需重新选择和核对。
- `read_math_work(work_id)`只读本owner真实确认/草稿。`review_math_work(work_id,review)`仅接受已确认输入，强制按所有页覆盖每个非空原转录行；不能跳过未知前缀、改原文或换页序。
- 工具执行的`SUCCEEDED`与`mathematics_status`分开，宿主重新读源和重算结果。SOURCE_READY、执行成功、已确认转录均不证明学生答案正确。
- Flutter所有异步响应按原profile/contextEpoch丢弃过期回执；提问前再读确认，续接原ContextFollowUp/Chat，不另设模型。

## 图形与文件成果

`render_math_plot`仍支持原2D和默认PNG，新增formats数组`png/svg/pdf/pptx`；新`math_plot_3d`表达`z=f(x,y)`或空间参数曲线，前端后台isolate计算世界坐标、丢弃迟到结果，本地旋转/缩放不重采样。方位角、仰角、缩放均有可访问滑块。输出是正交线框，不宣称遮挡面渲染或解析几何证明。

2D显式/参数曲线新增`exclude`保留已知x/t孔洞，`points[].open=true`标空心排除点；采样跨排除点必断开。未声明极窄孔洞、高频结构仍可能漏掉，模型须先推导原域。

SVG是真实矢量path，PDF由Canvas矢量路径生成；PPTX使用原生可编辑DrawingML采样路径和文字，不嵌PNG冒充矢量。它不是解析函数、关联数据图表或Office可旋转3D对象。导出默认参数/默认视角固定版本，交互不改写已交付文件。所有格式入现有不可变成果库，owner/hash/输入定义/格式/requestKey独立读回；不覆盖源文件。

Room33、TaskStore9、Pigeon164、材料libraryVersion2及授权状态机不迁移。新增本地工具沿用可信local.read/来源读回；绘图仍是material.write与实际文件核验。

## 本轮复验与未验范围

可直接从仓库根运行（新run-dir/后缀，不覆盖证据）：

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/engine-NEXT --cases HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/ENGINE_EXTENDED_CASES.json
python HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/smoke/run_integrated.py flutter NEXT
python HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/smoke/run_integrated.py nativeqa NEXT
```

真实设备验证前核本批EMULATOR_OWNERSHIP、独占专属5588/AVD，不清普通App。`scripts/verify_agent_android.py`需先读：它归档后只清nativeqa合成数据；用新run-id、指定ProfessionalMathRuntimeTest、MathWorkInputsRuntimeTest、ProfessionalPlotRuntimeTest、MathSharedChatRuntimeTest等受影响类。

真实Provider的综合题/分析/实变推理质量、真实多页OCR与人工/权威样本评分、Mate50/HarmonyOS4.2、TalkBack语音、MSOffice/WPS与Release/生产单独验收。合成Provider、人工转录和工程题库不替代这些质量证据；完整P2仍须按Pro的MATH-01至06与真实样本验收。独立与完整全项目审查未评估，不启动子代理、不推送或部署。
