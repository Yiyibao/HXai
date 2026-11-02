# R27-06–09 接续合同1（2026-10-29）

来源：`HXai_CHANGE_REVIEW/R29-QUALITY-06-09-20261029/`。branch=`codex/r29-quality-06-09-20261029`，base/HEAD=`070f3d83c294ba0d4a2197d591055e75cff59ac3`加继承工作树；本批字节以SOURCE_FINAL/精确增量patch为准。本文增量不改原Pro标准。Room33/TaskStore13/Pigeon、模型快照、授权、唯一执行位、UNKNOWN及原件/版本保全均保持。

## 06/07：原Chat任务证据的本机封存

普通任务详情右上角“任务评测快照”菜单，明确同意后保存到本机资料库，再沿既有成果预览/SAF另存。不会新请求模型、同步云状态、读当前Settings代替接受配置或自动对外发送。缺会话/所选分支没有本任务回答时明确记录缺失；无工具闲聊可正常封存。

链路为`AgentTaskDetailPage → AgentEvaluationExport(Flutter) → frontend_features/evaluation.export → StudyHostFacade → AgentScheduler.captureEvaluation → AgentTaskStore/AgentEvaluationSnapshot → ConversationRepository当前选中generationTaskId消息 → AgentEvaluationExport(Kotlin) → MaterialFiles`。同一Store事务封存完整计划、控制条件、调用hash引用与请求用量；前后重读核对任务、用量和所选回答，变化则拒绝拼接。只投影Text，排除Reasoning/附件正文/原始工具结果/凭据引用和配置正文；工具成果仍需独立按真实文件版本/hash核验。endpoint不导出，不能拿当前设置回填。导出是指定时点的本机记录，不是Provider执行、授权、云日志、人工结论或整题正确性的见证。

snapshot协议1：`kind=hxai_agent_evaluation_snapshot`，`scope=NATIVE_PERSISTED_TASK_AND_SELECTED_VISIBLE_ANSWER`；原生任务身份/模型与revision/fence从持久记录取，整数以十进制字符串编码。上限4MiB/1000调用/128步骤/200消息和20万回答字符；超过整项拒绝，无截断。锁模型为原provider/model内部ID/reasoning/configRevision；独立Provider名称和endpoint不伪造。原用量含辅助角色、失败和unknown/null，不能写成零。

导出requestId绑定owner/task/revision/fence，落盘前先保存预期size/hash与可读的无秘密请求身份到原材料requestKey，局部写入与rename由原MaterialFiles mutation lock保护；不会新建任务执行器。`evaluation.last`从原材料意图只读发现原请求，页面重开或刷新后仍核验原revision/fence，不将新任务修订代填原请求。丢ACK只回读同一原件，源任务后来变化不改旧快照；缺文件/损坏/未落盘中断拒绝重写，用户可明确创建新快照。epoch变化的回执不得显示到新档案。用户删除的快照不能靠重试复活。

原App导出的文件可离线绑定到现32题，所有命令均不调用模型；只有明确授权后在原Chat运行的真实结果才可称真实模型答卷。`MAPPING.json`示例（路径相对该JSON所在目录，hash填实际导出文件SHA）：

```json
{"contractVersion":1,"origin":"EXTERNAL_UNATTESTED","runId":"real-run-id","corpusSha256":"填BLIND_PROMPTS中的实际SHA","cases":[{"id":"填实际题目id","snapshot":"task-snapshot.json","snapshotSha256":"填文件实际SHA","engineeringStatus":"PASS"}]}
```

`engineeringStatus=PASS`还要求原任务SUCCEEDED、验收已记录、没有UNKNOWN/IN_FLIGHT及当前分支可见回答。FAIL/BLOCKED保留局部答案和未决请求。不能由这些条件推断数学正确；旧缺Provider执行证明仍NOT_ATTESTED。只允许题干完全相同、无附件的纯文字输入；有追加/不同题干拒绝错配。跨owner/锁模型混批、同任务冒充多题、hash/用量范围错配拒绝。尚未导入的题在原seal/评分器中保留NOT_RUN，不缩分母。

```powershell
python scripts/prepare_b_math_evaluation.py --out .local/quality-corpus-NEXT
python scripts/import_agent_evaluation.py --corpus .local/quality-corpus-NEXT --mapping MAPPING.json --out .local/quality-import-NEXT
python scripts/score_b_math_evaluation.py seal --corpus .local/quality-corpus-NEXT --run .local/quality-import-NEXT/RUN_INPUT.json --out .local/quality-answers-NEXT
python scripts/score_b_math_evaluation.py template --corpus .local/quality-corpus-NEXT --sealed .local/quality-answers-NEXT --out .local/quality-judgments-NEXT.json
```

逐义务人工填写判断/回答定位/复核方法后沿原score命令输出。32题仍为公开原创、作者自推参考，独立参考裁决NOT_EVALUATED。已从项目记录核对用户对固定公开32题和5个Agent场景的真实调用授权：Responses https://xinyue.mom/v1、gpt-6.1-terra、MEDIUM、store=false，凭据仅在原专用App加密配置中复用。真实续测沿既有06–09专用工作树及原APK运行，和本根目录导出增量分别记录，不以另一工作树的模型成绩证明根目录已集成。授权/输入/源码/APK/未决请求检查见本批public-resume/。

## 08：公式候选评分与本机WinRT实验

`verify_b_ocr_corpus.py` evaluatorRevision2使用与App `MathWorkCandidates`相同的“非空latex优先，否则text”。不将LaTeX转成等价表达式，不折叠正负号/上下标；空白页不能通过只在latex藏正文获得零错误。非法latex类型/重复JSON键/NaN/Infinity拒绝，缺页及原93字符分母保留。旧报告不改写。

`verify_b_windows_ocr.py` adapterVersion=`windows-local-2`。默认`--preprocess none`保持完整栅格路径；可显式选择`ink-crop`作本机实验：原非白像素框外加32像素边距，最多3倍LANCZOS且最长2000；保原页/识别栅格SHA及显式坐标变换，不读真值或替换字符。保原显式语言，不自动择优或切语言。原白页保持，不把无识别文字当空白页证据。

实际固定五页zh-Hans-CN：none为41/93字符编辑错误（44.09%），ink-crop为43/93（46.24%），未改善，不获免确认转录资格；此预处理没有接成App默认OCR。专用工作树历史MinerU实测49.46%同样FAIL，本批没有重新外传调用；真实学生手写及OCR到Word整故事仍未验。修复WinRT脚本对缺失Get-FileHash的依赖，改用.NET SHA256，错误只记固定stage/代码行，不输出异常/识别正文或路径。

```powershell
python scripts/verify_b_windows_ocr.py --source HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/corpus-final/source.pdf --out .local/quality-winocr-NEXT --language zh-Hans-CN --preprocess none
```

原`MathWorkInputs`确认/页序/来源变更/取消/只从已下载缓存复用、统一Chat批改和Word可编辑输出链保持，不将本机评分工具包装成新的机器确认入口。

## 09：独占WPS环境的同源准备及独立读回

新`office_roundtrip_contract.py`复用服务端严格XML/ZIP安全检查。比较OMML结构，区分上标/下标/分子分母；比较正文/真实引用的页眉页脚/实际脚注、字段、显著空白、换页、表格行列/合并；兼容Office自动添加的空分隔脚注、简单/复杂字段等价和正文末表后的单个终段。未标xml:space的边界空白按[Microsoft TextType.Space](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.texttype.space?view=openxml-3.0.1)解释；字段语法依据[SimpleField](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.simplefield?view=openxml-3.0.1)。不删显著空白或泛化忽略任意段落。PPT按r:id逻辑顺序比较全部页/隐藏标志/文字，允许页码字段缓存实际更新；XLSX保原公式/类型/日期与图表引用，结果缓存与真实重算分别判定。

`verify_b_microsoft_office.py`真实Office运行后的原读回器已接该校验。新`verify_b_office_compatibility.py`只准备同SHA合成副本与离线读回，不附着/启动/关闭WPS。本轮存在用户wps.exe，未接管。当前已准备`office-bundle-final/`，包括未填写的ENGINE_RECORD_TEMPLATE，不预填引擎成功声明。

```powershell
python scripts/verify_b_office_compatibility.py prepare --manifest HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/microsoft-office-final/INPUTS.json --reference-dir HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/microsoft-office-final/office --out .local/quality-office-NEXT
```

在合法独占WPS会话打开inputs内副本，另存、重开；Excel实际全重算、导出PDF；PPT审阅PDF须包含全部五逻辑页（含隐藏页），保存PPTX的隐藏设置保留。只据实际操作填写模板版本/操作/输出文件SHA，不给已存在用户会话编造自动化命令。另存为新ENGINE_RECORD.json，原模板保留。将新输出放指定目录后执行：

```powershell
python scripts/verify_b_office_compatibility.py verify --bundle .local/quality-office-NEXT --outputs .local/wps-outputs-NEXT --engine-record .local/quality-office-NEXT/ENGINE_RECORD.json --report .local/wps-readback-NEXT.json
```

全部五项输出、源/输出hash、原结构、真实缓存和PDF物理页/可提取文字都须对应；缺页/错页/旧缓存/非目标改动拒绝。PDF文字对照不是视觉版式认证；兼容的是该固定夹具的读回范围，任意复杂Office保真另验。即使填engine=WPS，离线工具仍标`engineExecution=EXTERNAL_UNATTESTED`和`compatibilityQualification=NOT_EVALUATED`，不能代替实际WPS/视觉/目标设备资格。

## 证据与回滚

实际命令/失败/修复/原生APK/AAR与源码hash以本批TEST_RESULTS、logs和六类SOL为准。独立审查及完整项目审查均NOT_EVALUATED；同一执行者的反证不冒称独立签核。回滚仅逆本批精确差异、停新导出/离线工具，保原件、快照、墓碑、授权、TaskStore和UNKNOWN，不降库或重放请求。已授权Provider续测与独立参考、真实手写、MinerU新范围、独占WPS、Mate50/正式Release及生产分别裁决。
