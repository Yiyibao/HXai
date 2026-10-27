# B线任务书：专业文件、OCR与数学质量

与[总计划](PLAN.md)及[A任务书](A_TASK.md)配套。B负责领域开发、编译、定向验证和修复，不另建Agent/调度器、不给模型授权、不设审查子代理。

## 开工基线与边界

先读根/client AGENTS与Pro指定原文、最新待办、合同16/17和对应交付。重新记录branch/完整HEAD/status/diff，继承当前完整未提交产品快照，不仅checkout旧HEAD。

B直接改 `feature/material/*`、`feature/math/*`、`server/document-service/*`、领域专用页面/组件、对应测试与专属脚本。共享Agent/Planner/Verifier/Provider、Host/Pigeon、主Chat/providers、路由/DI/Gradle/AAR由A负责；需要这些接口时交before/candidate/current、最小patch与验收夹具，不整文件覆盖。

## B24-01：专业数学合同17收尾（首批）

**目标：** 保留已有算法和34方法原生证据，补齐真正缺失的来源对齐、领域表面证据、增量补丁与完整交接，不再从零实现积分/求根/多页候选。

先读：

- `docs/professional-math-contract17.md`、`P2-CALCULUS-OCR-20261022/CHANGE_CONTRACT.md`、before/TESTED_SOURCE/QA_PACKAGE_SEAL。
- `qa/remaining-development-20261024/SOURCE_COMPARISON.json`、EVIDENCE_INVENTORY/HISTORICAL_LOG_READBACK。
- 真实原生结果位于 `HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/runs/p2-calculus-ocr-20261022-01/`，不是本批目录中的缺失TEST_RESULTS。

实施顺序：

1. 比较B所属 `CertifiedCalculusRuntimeTest.kt` 的被测/当前差异；其余7份差异交A确认。当前源码匹配后续A04/A05完整冻结清单，不因此假定覆盖全部数学测试。
2. 核CLI16例、mpmath20例、原生34方法及源码/APK hash的适用范围；保留Flutter首轮exit1与修复exit0，说明实际原因，不改历史结果。
3. 对数学/候选确认的真实表面、原文件定位/读回、跨owner/profile/epoch、候选修订/撤销、schema1兼容和schema2快照保全补足缺证；变更影响哪些用例才补哪些，不为收尾无条件重复全部。
4. 核临时MathCandidateSurfaceSmoke等驱动位置，向A交普通包隔离检查清单，不修改A的打包脚本。
5. A提供schema总地图后，更新领域文档继承TaskStore10的说明；数学操作合同号与确认schema不能混同数据库版本，不降级现数据。
6. 新收尾批次交完整领域diff、输入/产物/日志hash、来源重建和回滚，补合同17缺失汇总/六类SOL。原始日志/失败只读；新增交接注明历史测试与本轮补验的区别。A共享集成结论由A提供。

先读脚本后可用的纯算法复验模板（根目录；新目录）：

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir HXai_CHANGE_REVIEW/B24-01-C17-CLOSURE-NEW/engine --cases HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/ENGINE_CASES.json
```

Android驱动有安装/force-stop/归档后清nativeqa副作用。只在本线独占模拟器、匹配源码的冻结两包上用新run-id执行，完整模板见最新待办；旧冻结包复验不能宣称新源码通过。没有进行独立人员审查，不因数值参考名称中的independent而误报。

验收：积分/求根只声明合同17明确范围，UNSUPPORTED/DISPROVED不自动完成目标；正确输入/旧schema1不损坏，用户确认不冒充OCR真值或数学证明。回滚逆本批领域增量，保源、版本、schema2确认/撤销与UNKNOWN，不抹旧失败。

## B24-02：真实OCR与扫描文件闭环

入口：MathWorkInputs/MathWorkCandidates、DocumentServiceRepository、MaterialReader/Editor及文档服务MinerU链；Provider物理调用、辅助角色权限和共享费用由A接线。

1. 准备有合法来源的公开/合成/获授权扫描题与多页手写样本，固定原字节SHA、物理页、逐符号人工真值、缺页/空白/歧义、公式/表格与期望出处；记录许可和覆盖范围。
2. 指定真实引擎/版本与授权范围。没有服务/账号时先完成真值、错误分类、夹具与本机合同，真实识别保持NOT_RUN，不用缓存或手工转录替代。
3. 实际识别→候选页/覆盖→用户修订及歧义确认→原统一Chat批改→可编辑Word及真实来源；源hash变化/删除、取消、下载或提交丢ACK不得复用旧确认/假成功。
4. 分开报告OCR逐符号/页覆盖、数学判断、版式与任务完成；原图不自动进入日志、长期记忆或备份，不擅自新增材料外传。

已有 `DocumentServiceLiveTest#actualHttpsBasicParseDownloadPersistenceIsolationAndSource` 的精确命令/特定五页夹具和凭据前置见合同16的SOL_BLOCKERS。它包括真实服务与受控远端清理，须在明确授权和专属设备中运行；该方法通过也不代表任意手写OCR准确率。

回滚停识别/新候选入口，保原件、确认/撤销、已验证Word与不明远端任务，不盲重传。

## B24-03：专业数学真实题型质量及按错例补能力

入口：MathVerification/ProfessionalMath/ProofDerivation/ProfessionalSolution/ProfessionalReview、MathWorkInputs、教材来源工具和图形。A负责真实主模型、Planner最终语义及Provider请求；B负责题目、权威依据、证书适用范围、独立数学核验和领域修复。

先建立按题型分层的可复验样本与评分表：江苏高中导数含参/数列/圆锥曲线，高数/分析的极限、级数/一致收敛与交换条件、实变基础证明，正确异法、条件缺失、孔洞/奇点、病态数值和多页首错。每题绑定来源、原题/用户解答、参考论证、已证/未证义务及错误类型；无权威真值不报质量成绩。

与A锁定同一provider/model/reasoning及实际角色后运行盲评，防止看参考答案后生成再称独立解答。模型自评或合成脚本不是真实题库成绩；先分析错例再决定补定理/量词/分支/数值/符号算法，不以算法数代替质量。

正确但不满足当前证书充分条件的输入应UNSUPPORTED或标未证，不应判学生错误。首错先验全部前缀，完整目标要覆盖全部分支；高精度/数值探索不作证明，引用绑定真实材料版本与页内依据。新增共享verifier/planner要求交A最小合同。

回滚禁新增operation或逆增量，保原合同1–17和旧数据，不扩大VERIFIED迎合测试。

## B24-04：跨Office及最终设备领域兼容

继承已完成的Windows LibreOffice、专属Linux/WSL Docker和原API26局部资格，不从零重做Linux环境，不借历史结果宣称后续最终包通过。

1. 在专属会话/VM确认MSOffice/WPS可用性及无用户占用；使用同SHA合成副本，实际保存/重开/重算与渲染。Word原生公式/结构和代表页、PPT全部页/隐藏页/母版、XLSX公式值/日期/图表、PDF原页与可编辑转换各自验收。
2. 缺软件或存在用户实例时不关闭/接管它；准备夹具与比对工具，环境仍NOT_RUN。
3. Linux变更需按 `docs/parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md` 新run-dir与固定源/镜像再资格；取消/撤权/写后kill/ACK丢失/停止未知要符合本次实际影响范围，不覆写原资格日志。
4. A冻结最终API26/Mate50包后，B执行文件/来源/数学/OCR领域故事及性能/小屏/大字检查，回执绑定同一包；A负责系统生命周期、包隔离与完整设备结论，TalkBack实际朗读不能以树节点替代。

第一条领域服务帮助命令（先读脚本和协议，不运行生产）：

```powershell
python scripts/verify_b_linux_office.py --help
```

回滚停新领域adapter或逆对应增量，保原件/生成版本/服务任务/receipt与UNKNOWN，确认自有进程/容器停止后才释放资源。

## B24-05：领域冻结交给A

交一份明确的领域支持矩阵，列每个格式/数学operation/OCR流程的实现状态、证据级别、实际PASS/FAIL/NOT_RUN、输入/输出hash和限制。不要累加历史计数成完成率。

向A交：领域冻结文件/SHA、独立读回器、工具schema与拒绝边界、最小共享patch、代表正常/反向/故障夹具、当前包身份、迁移/兼容与回滚、具体遗留命令。A集成期间保持冻结接口不变；可在另一批准备不改该接口的夹具。A最终构建/Release/签名或生产不由B领域通过自动放行。

## 每批交付

新建 `HXai_CHANGE_REVIEW/B24-<批次>-<日期>/`，按Pro输出合同/完整diff/TEST_RESULTS与原始logs/ADVERSARIAL_REVIEW/实际patch/VERDICT_AND_ROLLBACK/HANDOFF及六类SOL。两类反证、源码和输入产物SHA、未验前置及下一命令必须真实。未做独立/完整项目审查保持NOT_EVALUATED；不启动子代理或真实账号/模型/部署来补空白。

## B24-01 本轮接续状态（2026-10-24）

领域收尾交付见 [B24-01 HANDOFF](../../HXai_CHANGE_REVIEW/B24-01-C17-CLOSURE-20261024/HANDOFF.md)；裁决PASS_SCOPED限合同17领域，不授完整P2/最新App/Release通过。当前CLI16、当前数学JUnit4、候选Flutter3及旧封印QA实际页面smoke分别取证，不累加历史数。

已补原PDF物理页/SHA/渲染、错误候选修订与歧义拒绝/合法确认/重开、深浅色与200%字，15文件领域patch精确重放。临时MathCandidateSurfaceSmoke原字节归档移出androidTest；正式候选回归保留。领域协议已区分初批TaskStore9与当前继承10，A总schema地图仍由A接收。

A24-01接收7份共享差异、最小共享候选和最终包隔离清单；B24-02下一批从合法扫描/手写真值与来源夹具开始。真实服务/Provider、权威质量、MSOffice/WPS和目标设备未在本批调用或验收，具体前置/下一命令/回滚见该批SOL_BLOCKERS。

## B24-02 本轮接续状态（2026-10-24）

新增[OCR评估合同1](../b24-ocr-evaluation-contract1.md)及离线`verify_b_ocr_corpus.py`，交付见`HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/`。已完成原创合成扫描的五物理页/字节封印/作者预置真值、歧义与空白分类、现有解析合同1结果的覆盖/字面符号评分；不是新Agent或真实OCR引擎。

实际27项评分回归、22项旧解析合同、worker归一化→输出发布读回→CLI正反控制、PDFium物理页与pypdf像素读回、同版本字节重建分别PASS。首轮中文字体下标缺字的实际FAIL保留，最终显式数学字体回退并在缺字时拒绝；仅`corpus-final/`有效。假覆盖/错SHA/空白冲突/篡改源拒绝，不把零字符误差或手工控制当真实识别。

B24-02整条真实识别→确认→统一Chat→Word故事仍PARTIAL/NOT_RUN于本批；本机没有可用MinerU/tesseract，未获得本轮外传/真实服务账号授权，未连接远端或读取秘密。真实学生手写逐符号标注/质量仍未验。旧LiveTest要求另一专用五页夹具，不能直接替换成本批语料。具体下一命令、授权与回滚见本批SOL_BLOCKERS；共享Provider/Host/Pigeon/构建由A持有，本批无需共享源码补丁。

## B24-03 首批接续状态（2026-10-26）

领域交付见 `HXai_CHANGE_REVIEW/B26-01-MATH-QUALITY-20261026/` 与[数学质量合同1](../b26-math-quality-contract1.md)。已冻结24个原创分层样本、参考论证/义务/未证范围，并导出不含参考与证书的BLIND_PROMPTS交A；参考为同一执行者推导和已读定理来源，未获独立人工裁决，不宣称江苏真题或权威盲评成绩。

实际错例驱动修复：ProofDerivation不再把有序premises中的重复引用一律拒绝。旧版P∧P、解除假设后的P→(P∧P)、同测度Fubini三个合法证明FAIL保留；当前24样本及原合同16/17共103例PASS，50项领域JUnit及两例实际CLI嵌套工作流通过。原79例旧/新完整JSONL逐字节相同；没有改A共享源码或数据库，TaskStore11由A24-02继承。

本批PASS_SCOPED只授本机核验行为与样本准备。真实Provider回答、独立人工质量、真实OCR、最终Android包、MSOffice/WPS与目标设备未由本批验证；独立/完整审查NOT_EVALUATED。A接收领域最小patch后重建同源QA包，再依授权锁定真实模型、先封存回答后按义务评估，不能以工具命中率代替模型质量；B24-02缺引擎/真实传输授权的故事边界保持。
