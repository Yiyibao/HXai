# B26 数学质量合同1：固定样本与重复前提修复

适用：`codex/b26-01-math-quality-20261026`，base/HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`及继承的未提交产品树。交付、精确源码/输入/产物SHA、旧版FAIL和当前PASS见 `HXai_CHANGE_REVIEW/B26-01-MATH-QUALITY-20261026/`。本合同接续B24-03；不是完整专业数学质量、真实模型或独立盲评通过。

## 领域行为增量

真实链仍是 `ChatToolFactory.math_verify → MathVerification → ProfessionalMath → ProofDerivation`，宿主 `TaskResultVerifier`继续从原持久输入调用`MathVerification.accepts`独立重算。仅移除ProofDerivation对重复premises的一刀切拒绝：

- premises是**有序的前提出现位置**，允许两个位置引用同一已核验step。例如由P推P∧P，或Fubini两个因子为同一测度时重复引用同一个sigma有限性事实。
- 不去重、不自动补位、不增加推理规则。已有规则的前提个数、顺序与公式匹配不变；未知/未来step引用仍未知，重复step ID仍拒绝。
- 原依赖集合照常合并，复用假设不能解除假设；只有原有蕴含引入等显式解除操作才移除依赖。条件证明和无条件证明仍分开，篡改`claim_is_conditional`或依赖回执无法通过重算。

原版三个合法证明实际均为`UNSUPPORTED/duplicate_proof_premise`，旧日志和旧源码只读保留。修复不改变JSON字段、operation、数学合同16/17或数据库格式。Room33/TaskStore11及Pigeon由A线继承，本批不迁移、不改Provider/模型锁/授权/UI。

## 固定分层样本

`scripts/fixtures/b26_math_quality_v1.json`沿用现有`verify_b_rational_math.py`的`input/expected`格式，附`id/category/prompt/reference`。24个原创样本：高中5、高数5、分析3、实变5、顺序批改3、逻辑3。原题、反例/参考论证、必要条件、未证义务在执行前固定；原创部分CC0-1.0，不冒充江苏历年真题或真实学生手写。

每题的`input`只是**明确声明的证书片段**，不自动覆盖整个`prompt`。例如含参导数获证不表示参数正/零/负的单调性已分类；圆锥曲线代数恒等不表示几何建模成立；C8方法对正确的|x|积分返回UNSUPPORTED不表示学生答案错。首错样本的物理页定位是合成转录标签，不冒充资料库真实来源或OCR。

`reference.status=AUTHOR_DERIVED_NOT_INDEPENDENTLY_REVIEWED`；参考由同一执行者推导，定理依据已读取：

- [MIT 18.100A Lecture 24](https://ocw.mit.edu/courses/18-100a-real-analysis-fall-2020/mit18_100af20_lec24.pdf)，物理第1页定理3及第2页极限交换警告：M-test须有统一逐项界和可和非负majorant。
- [MIT 18.102 Lecture 12](https://ocw.mit.edu/courses/18-102-introduction-to-functional-analysis-spring-2021/e407e57ea631a29148ee94afecef7d33_MIT18_102s21_lec12.pdf)，物理第3页定理125：可测、a.e.收敛、共同可积支配。
- [MIT 6.436J Lecture 9](https://ocw.mit.edu/courses/6-436j-fundamentals-of-probability-fall-2018/6fe5ce4d56945ce7241679998922fb3a_MIT6_436JF18_lec09.pdf)，物理第5–8页：sigma有限、非负扩展积分与绝对可积有限积分须分开。

以上URL只提供定理依据，不是用户教材检索/页码证据。外部讲义不纳入CC0，不分发原讲义，不声称独立人员或权威裁决了本题库。完整原题解题质量仍须独立人工核对与真实模型执行。

## 执行与真实边界

已有本机依赖，无安装/网络/模型/设备操作；输出目录必须不存在：

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir .local/b26-quality-NEW --cases scripts/fixtures/b26_math_quality_v1.json
```

此命令编译真实纯Kotlin并执行核验/重算，**不是模型答题**。本批另将原合同16的63例与合同17的16例合入冻结ENGINE_CASES，当前103例PASS；这些层不能累计成产品完成率。50项选定纯Kotlin JUnit通过，含新增6方法和旧领域回归；未运行Gradle/Android instrumentation。实际CLI另外执行`professional_review/solution`嵌套路径两例，保留条件依赖和`natural_language_solution_certified=false`。

原79例的整个JSONL输出在旧版/修复版逐字节相同，说明该冻结回归范围旧结果保持，不推广到任意旧输入。基线三个失败、当前逐题结果、运行命令/依赖、源码/输出SHA均留存。

`BLIND_PROMPTS.json`交A，仅含id/category/prompt，不含参考/expected/工具输入；`REFERENCE_RUBRIC.json`单独保存评估依据。文件公开可见，不是未曝光保密题库，也不是已经完成的独立盲评。A须在明确真实调用授权下固定provider/model/reasoning、接受配置与用量，先封存回答再交未看回答生成过程的评估者依据义务评分。不得把证书命中率当模型准确率；未获独立裁决不报权威质量成绩。

## 回滚与未验

回滚只逆本批领域增量或停样本运行；不降级库、不删原材料/版本/转录确认/撤销/receipt/费用/UNKNOWN。产品修复静态已接原工具链，当前行为证据为E2与真实CLI，非Android或最终包资格。真实Provider、独立人工质量、OCR、MSOffice/WPS、目标设备、完整P2/App/Release/生产均未由本批授予通过；独立/完整项目审查NOT_EVALUATED。
