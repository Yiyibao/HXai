# 专业数学合同17：认证有限积分、局部唯一根与解析候选确认

适用 `codex/p2-calculus-ocr-20261022` 未提交工作树；HEAD/base `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。本批精确源码/补丁/运行证据在 `HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/`。原Pro产品需求及[合同16](professional-math-contract16.md)保留；本合同不是完整P2质量或通用数学证明器验收。

## 真实入口与权限

`ChatToolFactory → math_verify → ProfessionalMath → CertifiedCalculus → 原持久tool receipt → TaskResultVerifier原输入重算 → TaskPlanner目标语义核对`。仍沿用唯一Scheduler、接受时锁定provider/model/reasoning及local.compute权限；不新增模型、脚本解释器、外部调用或任务执行权威。`RealExpression`是原numeric与新calculus共用的有界编译语法，不执行输入代码；积分/二分只编译一次后求值。

## professional_calculus

外层恰为 `{operation:"professional_calculus",request:JSON对象字符串}`；request恰有以下七个字段：

```json
{"action":"integral","expression":"exp(x)","variable":"x","lower":"0","upper":"1","digits":"12","bindings":{}}
```

- action为`integral`或`root`；variable为一个合法非保留变量名，不能同时出现在bindings。
- lower/upper为精确有理或十进制字符串，不能写pi、无穷、模型自报的浮点来源或隐式不确定端点。反向积分取负；root必须lower<upper。
- digits为2–24整数字符串，要求包围区间宽度≤10^(-digits)，输出estimate及absolute_error_bound=宽度/2；这是绝对而非相对精度。
- bindings为其他变量到`[有理下界字符串,有理上界字符串]`的对象，最多8项。证书包含所有给定参数值，输入不确定性不会因更多位数而消失。
- 表达式沿原numeric的四则、±32整数幂、pi/e、sqrt/exp/ln/log(常用对数)/sin/cos/tan/abs；最多4096字符/256语法节点/32层，保持所有原式子表达式的定义域。exp/三角解析系列、对数及数值量级沿既有安全限制。

### integral：闭区间C8充分证书

每个子区间中心构造正规化导数系数f^(k)/k!的定向舍入包围；积分0–7阶Taylor多项式的偶数项，使用整个闭子区间的8阶区间自动微分系数包围积分余项。完整有限子区间覆盖后才输出已认证误差，不以采样点、相邻网格差或两次近似一致证明。

自适应最多512个闭子区间，按不确定宽度细分；有限十进制端点账本移除旧区间并加入新区间，不能用依赖丢失的区间相减累加旧误差。协程取消在每轮传播。资源/停滞不足返回UNSUPPORTED且不发布精度成功。

这是有限有向积分的充分方法，不是符号原函数、反常积分/主值或收敛性判定。每个原语法子表达式须在整个子区间有可认证平滑域：分母包围0、sqrt参数触0、abs符号不确定等拒绝，哪怕该积分事实上存在；区间依赖过宽也可能拒绝正确输入。因此UNSUPPORTED不表示积分不存在、发散或学生答案错误。

### root：指定区间中的唯一根

```json
{"action":"root","expression":"cos(x)-x","variable":"x","lower":"0","upper":"1","digits":"12","bindings":{}}
```

先在整个请求闭区间认证原式连续可微及导数严格单号，再以端点精确零值或严格异号证明存在性；二分保留有理端点及其真值符号包围，最多2048轮，输出`UNIQUE_ROOT_IN_REQUESTED_INTERVAL`、exact_left/exact_right、导数及最终端点值包围。

严格单调已获证且两个端点均严格同号时才返回`DISPROVED/NO_ROOT_IN_REQUESTED_INTERVAL`。无法认证导数、域、端点或中点符号时是UNSUPPORTED，不宣称无根；小残差不是根存在性证明。只对指定区间认证唯一根，不枚举所有实根、不保证包含多重根，也不将数值包围当代数恒等式。

新操作输出contractVersion17和独立scope；原操作保留各自1–16合同及receipt字段。专业计算结果由宿主从持久原输入完整重算，不能复用别的表达式/端点/参数/精度的证书或伪造status。TaskPlanner另外核对原题是否确实只需这项计算；数值证书不自动完成自然语言原题、一般定理或所有分支。

## 多页数学：从已有解析候选到可信确认

文件详情→数学题目/解答批改→多页手写→填写真实物理页→`载入已有解析/OCR候选（不上传）`。

`MaterialLibraryFrontend.library.mathWork.parseResults/parseCandidate → MathWorkCandidates → DocumentServiceRepository.availableResults/read`只读本owner、同source id/hash、已下载且未取消的解析缓存。不会submit、refresh、download、安装识别器或偷偷新增外传/费用。没有缓存时如实显示不可用，实际识别仍由用户已授权的既有视觉/文档服务链负责。

- 仅装载真正processed且非failed/blank的所选物理页；不猜缺页、页序、印刷页码或空白页步骤。
- 候选完整保存原块text与latex、parser/adapter版本、task/resultRevision、真实覆盖及warnings；编辑区优先以非空latex表示公式，原text仍在可展开证据中，不把二者冒称逐字一致。
- 整页转录超过4000字符、单页快照超过12000 UTF16单位或整份证据超过65536单位时拒绝而非静默截断。现有解析块分页读取仍可核对超限内容；本页并未承诺任意长度原稿。
- 载入候选先设置明确待确认歧义；有旧编辑内容时先询问是否替换，取消保留未保存修订。用户核对原页、修订文字并清除已解决歧义后，仍只有可信前台按钮可确认。
- 新带候选记录使用schema2，在原noBackup owner哈希目录保留固定id/hash/物理页、候选SHA与不可变原候选快照、用户修订。保存前在来源变更锁中重读候选；确认后独立快照不依赖缓存仍存在，清理缓存不丢失已确认转录。
- 原schema1手工记录及ID算法不改，无破坏性迁移。改源/物理页会解除候选绑定；用户也可显式改成手工转录而保留文字。模型没有create/confirm工具。
- 原材料删除/变化使确认不可用，恢复源不复活旧确认；跨owner拒绝。Flutter丢弃旧profile/contextEpoch异步回执，已打开候选对话框在epoch变化时隐藏旧owner内容。
- `SOURCE_READY`只表示用户确认的输入可用；`mathematics_status=NOT_EVALUATED`仍保留。原read_math_work/review_math_work按原页序覆盖所有非空行，再核完整前缀，不按参考解字符串评分。

初批构建时为Room33、TaskStore9、Pigeon164；后续A04/A05已经将当前TaskStore升至10（`AgentTaskStore.kt:38–40`，费用账本增量），本领域没有降级或重复迁移。数学操作合同17、候选确认schema2与TaskStore数据库版本是三个独立版本；Room33、Pigeon164、ToolPolicy及既有A04授权边界保持。确认schema2不是数据库重建或远端信任迁移，且不进入备份。

## 复验命令与限制

先读本批CHANGE_CONTRACT、EMULATOR_OWNERSHIP及脚本。5588为开始时已运行的其他会话设备，本批不改；本批独占新AVD hxai-c17-math-20261022 /5592，仅合成QA资料。

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/engine-NEXT --cases HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/ENGINE_CASES.json
python HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/smoke/run_verification.py flutter-tests NEXT
python HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/smoke/run_verification.py aar NEXT
python HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/smoke/run_verification.py nativeqa NEXT
python scripts/verify_agent_android.py --serial emulator-5592 --run-id p2-c17-NEXT --classes me.rerere.rikkahub.feature.math.CertifiedCalculusRuntimeTest,me.rerere.rikkahub.feature.math.ProfessionalMathRuntimeTest,me.rerere.rikkahub.feature.material.MathWorkCandidateRuntimeTest,me.rerere.rikkahub.feature.material.MathWorkInputsRuntimeTest,me.rerere.rikkahub.feature.agent.MathSharedChatRuntimeTest --isolate-tests
```

新后缀/目录，先冻结匹配源码的QA两包；原生驱动会归档后仅清nativeqa，不碰普通App。独立数值/旧合同重放脚本复用本批engine-01封印并拒绝源码漂移；新代码须先建立新编译封印再运行，不绕过检查。

完整高中/分析/实变真实Provider解题质量、OCR逐符号准确率、权威盲评、Mate50/HarmonyOS4.2、TalkBack实际朗读、MSOffice/WPS、Release/生产与独立/全项目审查仍单独验收。合成错误候选只是流程反证，不是识别率或专业解题质量成绩。

## 2026-10-24 B24-01 收尾增量

本轮领域交接和可重放补丁：[`B24-01-C17-CLOSURE-20261024`](../HXai_CHANGE_REVIEW/B24-01-C17-CLOSURE-20261024/HANDOFF.md)。该目录区分旧批次与本轮执行，不覆盖原始失败、数值参考或34方法原生日志。

- 当前纯Kotlin真实入口16例、当前精确有理数断言4例、候选Flutter定向3例已复验。当前领域生产源码匹配初批封印；1份数学测试差异已在本轮重新编译执行，7份扩展/Agent差异交A24-01，不以邻批冻结代授数学回归。
- 独占新AVD的实际Flutter/Android页面smoke使用初批封印QA两包。人工对照真实PDF物理第1页将错误合成候选`x+x=3*x`修订成`x+x=2*x`；未清歧义时宿主拒绝确认且无记录，合法草稿及确认独立读回，原候选与用户修订分开保存，数学状态仍NOT_EVALUATED。原PDF三页、原字节SHA和物理页渲染另取证；不是OCR质量成绩。
- 实际浅色/常规字与深色/200%字页面、确认重开、原件未变均有新证据。临时`MathCandidateSurfaceSmoke`已按原字节归档至收尾目录`smoke/`，不再位于androidTest自动发现路径；正式候选回归不移除。
- 新封印仅授领域范围PASS_SCOPED；当前共享平台/最终Debug或Release、Mate50/TalkBack、真实Provider/OCR质量与独立/完整项目审查不据此放行。普通包临时类DEX隔离已对A04/A05封印Debug另读回，最终新包仍由A重建并核验。
