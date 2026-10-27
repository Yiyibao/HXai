# B27 数学离线逐义务评分合同1

适用批次：`HXai_CHANGE_REVIEW/B27-MATH-OBLIGATION-SCORING-20261027/`；base/HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`，分支 `codex/b27-math-obligation-scoring-20261027`，当前未提交文件以该批SOURCE_FINAL为准。接续B26-R01/R27-07，保原32题、corpusVersion2与数学operation合同1–17；本机评估文件新增contractVersion1，不迁移Room33/TaskStore13/Pigeon。

## 入口与证据边界

`prepare_b_math_evaluation.py → score_b_math_evaluation.py seal → template → score` 是本机离线评估链，不是App新工具/新Agent或模型调用器。回答先封存，参考后载入；seal只读取题干与CORPUS_SEAL，不打开REFERENCE_RUBRIC。template和score必须验证封存的题干、原始回答和参考摘要。

**哈希绑定字节，不认证来源、时间先后、模型接受快照、授权或人工结论。** 同一文件系统的写入者能改文件并重算摘要，因此不得把本链称密码学见证、真实Chat执行证明或独立盲评。外部数据只能声明EXTERNAL_UNATTESTED；演示数据声明SYNTHETIC。输出始终保留executionAttestation/modelQuality=NOT_ATTESTED、independentReview=NOT_EVALUATED、taskCompletion=NOT_EVALUATED。顶层PASS只表示离线结构/绑定/判定一致性通过，绝非数学题库或任务全通过。

现有参考为作者推导，未独立审定，obligations不保证穷尽题干全部正确性要求。数学CORRECT等结论仅汇总所列义务的人工作业，不自动认证自然语言全文、额外错误、首错前缀或整题证明。发现参考缺项/错误应保留旧版本并新建题库/授权范围/回答封存，不改既有封印迎合答案。

## 回答输入：RUN_INPUT.json

根对象严格五字段：`contractVersion:1`、`origin:SYNTHETIC|EXTERNAL_UNATTESTED`、`runId`、`corpusSha256`、`cases`。每条case严格包含以下字段：

| 字段 | 合同 |
|---|---|
| id | 题干包已有唯一id；额外/重复题拒绝 |
| promptSha256 | 该题prompt原始Unicode文本的UTF-8字节SHA256，不归一化 |
| engineeringStatus | PASS / FAIL / NOT_RUN / BLOCKED；不决定数学成绩 |
| response | 原始可见回答，允许空文本；不含隐藏推理、Key或用户历史 |
| taskId | 原Chat任务标识；NOT_RUN时为null |
| modelSnapshot | providerId、endpoint、modelId、reasoning、configRevision五个非空字符串；从接受快照记录，不从当前Settings推断；NOT_RUN时为null |
| receiptSha256 / usageSha256 | 单独导出的去敏感回执/用量证据摘要；非NOT_RUN行必填合法SHA256，NOT_RUN时为null。摘要不代替原证据或实际用量，未知用量仍保留unknown，不能写零 |

非NOT_RUN行包括有任务但尚未发出请求的BLOCKED；其摘要可绑定实际阻塞/未知用量导出，不伪造请求或费用。reasoning明确不适用时用明确非空说明，不私自选推理档位。endpoint只记去敏感服务地址，禁止含API Key、Cookie或带密钥查询。CLI不扫描/清洗敏感正文，输入责任在获授权的导出端。

缺失case自动保留NOT_RUN/无回答；报告按完整题干包列出，不能省略失败/未知缩分母。NOT_RUN不得携带回答/执行元数据。全部输入必须是JSON对象，拒绝重复JSON键、NaN、未来版本、错题干/题库、缺快照。文件读取上限16MiB、单回答20万Unicode码点、题目最多200、每题义务最多100；这是本机资源界限，不是Token或费用预算。

## 判定输入与数学汇总

`template`生成全部题/义务的未评估表，evaluator.id初始null，必须记录实际评估者后才能score。模板不是已完成评估。只允许evaluator.relationship=SAME_EXECUTOR或INDEPENDENT_DECLARED；后者仅原样记录声明，不能自动升级独立审查资格。

每题绑定id/responseSha256，每义务绑定零基index及原文text。每题每义务必须完整唯一且按参考次序；少题/重复题、少义务/重排、换运行/参考/回答摘要均拒绝。每义务还须提供：

- `status=MET|VIOLATED`：reason=null，非空rationale/verification，evidence={start,end,quote}。范围为回答Unicode码点的零基半开区间[start,end)，quote必须与该片段精确相等；不同于UTF-16/Dart字符串索引。空回答不能认定MET或VIOLATED。位置校验不证明评估者的数学解释正确。
- `status=UNDETERMINED`：reason=UNKNOWN或OMITTED，evidence=null，非空rationale/verification。UNKNOWN表示尚不能判断；OMITTED表示已有回答未完成该义务，不代表数学错误。无回答只能UNKNOWN。

数学汇总按优先序：存在VIOLATED→INCORRECT；否则存在UNKNOWN→UNDETERMINED；否则存在OMITTED→INCOMPLETE；否则全部MET→CORRECT。工程FAIL可有数学正确答案，工程PASS也可有数学错误或未完成答案。无需工具的正确异法不按参考解法字串拒绝；工具UNSUPPORTED不自动映射VIOLATED。报告列逐题、逐类别及全分母计数/义务/未决原因，不生成统一分数或准确率。

## 可执行命令

从仓库根目录运行；所有输出路径必须未使用。模型调用仍须原App明确授权，以下命令均不调用模型：

```powershell
python scripts/prepare_b_math_evaluation.py --out .local/b27-scoring-corpus-NEXT
python scripts/score_b_math_evaluation.py seal --corpus .local/b27-scoring-corpus-NEXT --run RUN_INPUT.json --out .local/b27-scoring-answers-NEXT
python scripts/score_b_math_evaluation.py template --corpus .local/b27-scoring-corpus-NEXT --sealed .local/b27-scoring-answers-NEXT --out .local/b27-scoring-judgments-NEXT.json
```

在模板中记录实际评估者、逐义务判断、回答定位和复核理由后：

```powershell
python scripts/score_b_math_evaluation.py score --corpus .local/b27-scoring-corpus-NEXT --sealed .local/b27-scoring-answers-NEXT --judgments .local/b27-scoring-judgments-NEXT.json --out .local/b27-scoring-report-NEXT.json
python -m pytest -q scripts/tests/test_score_b_math_evaluation.py scripts/tests/test_verify_b_ocr_corpus.py scripts/tests/test_verify_b_windows_ocr.py
```

seal创建新目录及末尾ANSWER_SEAL.json，部分写入/中断不能当完成；不覆盖或自动修复。再次评估只读原封存，改判断用新文件和新报告，保留旧输出。源码、原始输入/产物、命令/cwd/版本/退出码/日志见本批SOURCE_FINAL、INPUT_OUTPUT_SHA256和COMMANDS。真实Provider/独立参考裁决仍缺原授权与人工前置；OCR、WPS、目标设备和最终组合包限制继承B27，不因本机评分器通过而升级。

## 回滚

停用或移除本批新增离线评分入口，逆精确文档增量；保留原题库/回答/判定/报告和历史失败。无App数据迁移或任务状态写入，不降库、不重放请求、不放宽模型锁/授权/UNKNOWN。不做独立或完整全项目审查声明。
