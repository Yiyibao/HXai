# B线任务书：专业文件、OCR与数学质量

配套[双路线总计划](PLAN.md)与[A线任务书](A_TASK.md)。B负责领域实现/真值/产物独立读回，开发与必要验证，不另建Agent或调度器，不自行调用未经授权模型或接管A设备。

## 起点与归属

继承最新B26修复、合同1–17、B24合同17收尾与OCR语料；TaskStore13/Room33来自A，不回退为旧B交接中的11。最新真实模型/OCR/跨Office/目标设备尚未通过，历史算法/合成链证据保持原范围。

先读根/client AGENTS、所列Pro原文与[最新清单](../remaining-development-20261026.md)。每批重新冻结当前源码/必要untracked/输入及branch/head/diff；禁止覆盖原Pro/旧失败和用户改动。

B直接改feature/material、feature/math、document-service、领域测试/脚本及文件/数学/OCR专用页面。Provider/任务/权限/费用/Host/Pigeon/共享Flutter/路由/构建变更交A最小候选，不共写真源。执行由本助手串行完成，无子代理/新会话/消息派发。

## 2026-10-27 当前接续状态

继承B26-ALL-DOMAINS并完成本机可执行修复/领域冻结，见[合同2](../b27-file-math-quality-contract2.md)和 `HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/`。下文原任务与最终质量门槛保留，不因局部通过删除。

- R01：重复前提/失败证书语义、合同1–17回归和真实Chat合成Provider接线已验证；32题题干/参考分离。真实Provider数学质量与独立人工裁决仍BLOCKED。
- R02：真实WinRT五页识别已执行，但数学转录质量未通过；原页修订/可信确认、取消/空白覆盖拒绝、不可变Word及真实Word打开取证。不是实际学生手写、MinerU或真实主模型完整故事。
- R03：MSOffice真实保存/重开/重算、含隐藏页全部逻辑页渲染通过；API26/API34同源QA领域另列。修复取消状态位不一致的新候选绕过和动态createdAt落盘遗漏；WPS、Mate50、TalkBack朗读及最终Release未验。
- R04：精确领域/共享测试差异、源码/产物封印、支持矩阵和可执行阻碍已交。共享测试的用户新增覆盖保留，不冒认作者身份。API26同进程混合批次有一项无模型入队FAIL，按原隔离驱动另验，原FAIL不隐藏。

完整B线质量验收仍BLOCK；支持范围以本批SOL_ACCEPTANCE_MATRIX/TEST_RESULTS为准。独立/完整项目审查NOT_EVALUATED；未调用真实模型/账号、安装依赖、推送或部署。

R01后续离线增量：已接通32题回答封存→逐义务判定模板→全分母汇总CLI，见[评分合同1](../b27-math-obligation-scoring-contract1.md)和 `HXai_CHANGE_REVIEW/B27-MATH-OBLIGATION-SCORING-20261027/`。仅本机结构/字节绑定与人工判定一致性；无真实调用或参考独立审定。现有模型/手写/WPS等阻碍保持，不扩数学operation，不替代A的API26混合失败修复或当前组合包接收。


## B26-R01：数学质量与错例修复（首批）

**立即动作：** 向A26-R01提供B26六文件候选/原失败、24题题干与参考分离包、永久ProofDerivationTest及领域回归清单，核当前字节，不再重复apply已接收修复。当前App数学JVM通过不代表同源最终Android包通过；A负责匹配两包/安装与系统资格。

随后推进真实数学质量：

1. 复用B26固定题库/义务，核参考论证和未证范围，扩江苏高中导数含参/数列/圆锥曲线、高数/分析/实变、正确异法/未知前缀首错等分层覆盖。公开自推参考的独立人工裁决未做则如实注明，不报权威盲评成绩。
2. 仅把BLIND_PROMPTS交A调用；REFERENCE_RUBRIC、工具input/expected、证书和旧引擎输出不发被测模型。A锁定获授权provider/model/reasoning，沿原Chat串行执行，先封存实际回答/回执/用量。
3. 基于封存回答逐义务判MET/VIOLATED/UNDETERMINED，数学结果CORRECT/INCORRECT/INCOMPLETE/UNDETERMINED与工程PASS/FAIL分开，保全部失败/未知，不用工具命中率代替整题成绩。
4. 真实错例先封存，分清读题/模型、调用、领域核验、覆盖遗漏或参考错误，再修所属领域。禁止看回答改真值提高成绩；参考确错须留版本与理由。

验收：正确异法不误拒；UNSUPPORTED不等于学生错误；首错全部前缀已核；定理条件/量词/参数分支完整，采样/局部证书不冒充证明。未完成输入澄清或证明义务不能强报成功。

接续依据：[B26阻碍](../../HXai_CHANGE_REVIEW/B26-01-MATH-QUALITY-20261026/SOL_BLOCKERS.md)、[数学质量合同](../b26-math-quality-contract1.md)、[A评估计划](../../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/MODEL_EVALUATION_PLAN.md)。必要本机复验命令（根目录、新输出）：

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir .local/b26-route-math-NEXT --cases scripts/fixtures/b26_math_quality_v1.json
```

这是证书/算法复验，不能充真实模型质量。回滚逆本批精确领域增量或禁新operation，保原合同、原件/版本/转录/任务/receipt/费用/UNKNOWN。

## B26-R02：真实OCR到Word

入口MathWorkInputs/MathWorkCandidates、DocumentServiceRepository、MaterialReader/Editor及MinerU链。继承`B24-02-OCR-CORPUS-20261024/corpus-final/`的五物理页、truth、CORPUS_SEAL与离线评分器；旧corpus缺字失败仅作反例。

1. 固定实际引擎/版本、输入SHA及传输范围；在授权服务/本地引擎真实识别该五页，保任务receipt、实际parser/adapter、下载document.json/资产SHA及用量。无引擎/授权不使用缓存/人工转录冒充识别。
2. 另补合法真实多页手写、逐符号人工标注和分歧裁决，记录物理页、缺页/空白/歧义、公式/表格与许可。合成排印语料不能宣称真实学生手写质量。
3. 实际识别→候选页/覆盖→用户修订/歧义确认→原Chat批改→真实可编辑Word/来源/预览保存。Provider物理调用/角色/费用和主Chat共享接线由A提供。
4. 源变更/删除、取消、下载/提交丢ACK后不能复用旧确认或盲重传；扫描原图不默认进入日志、记忆或备份。

逐项验收OCR符号/覆盖、数学判断、文档版式和任务完成；人工确认不等于数学证明。现有DocumentServiceLiveTest要求另一专用五页（含固定空白/表格/公式/图像位置），不得偷换成新语料。

完整评分命令、真实服务方法/凭据及受控清理前置见[OCR SOL_BLOCKERS](../../HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/SOL_BLOCKERS.md)和[评估合同](../b24-ocr-evaluation-contract1.md)。真实结果下载后离线score，不把external-unattested自动升级实际引擎资格；绑定引擎运行证据另验。

回滚停新识别adapter/候选入口，保原件/确认撤销/已验证Word/不明远端任务，未知先读回。

## B26-R03：跨Office与领域兼容

复用现Windows/Linux LibreOffice及旧API26限定资格，针对当前版本补实际目标环境。

- 核MSOffice/WPS合法可用及专属会话/VM无用户实例占用，使用同SHA合成副本真实保存/重开/重算/渲染；缺环境准备夹具/读回工具，不关闭或接管用户Office。
- Word：OMML可编辑、局部修改/无关结构、修改页及代表页；PPT：全部逻辑页/隐藏页/母版/图片与版式；XLSX：公式实际值、日期/类型、多表/图表；PDF：原页几何/像素、可编辑候选转换边界分别验证。
- 领域服务/镜像变化后按影响补Linux独立validator、撤权/取消、写后kill、丢ACK/UNKNOWN回归，使用新run-dir/固定源与镜像，不重做已合格环境或复制其PASS。
- A冻结最终API26/Mate50包后，在同包核文件库/来源/数学/OCR领域故事和性能/小屏/大字；A负责系统生命周期、包隔离及TalkBack实际朗读总体资格。

依据[Linux资格协议](../parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md)及旧[B任务书](../parallel-development-20261024/B_TASK.md)。先读脚本，服务帮助入口`python scripts/verify_b_linux_office.py --help`，不是部署命令。本机UNO通过不授MSOffice/WPS或最新设备资格。

回滚停对应adapter或逆精确增量，保原件/版本/任务/receipt/UNKNOWN，核自有进程停止后才释放资源。

## B26-R04：领域冻结交A

为A26-R06交一份领域支持矩阵：每类文件/数学operation/OCR流程的实现状态、E0–E6、实际PASS/FAIL/NOT_RUN、源码/输入/输出SHA、真实读回方法、覆盖/限制及遗留。不能累加历史计数成完成率。

每次交接附before/candidate/current、精确文件清单、工具schema/capability/risk、owner/版本/sourceHash/locator、成功/拒绝/UNSUPPORTED/取消/UNKNOWN/恢复、最小共享patch、正常/反向/故障夹具、迁移/兼容与回滚、具体下一命令。A先三方核对再集成；最终构建/设备期间冻结接口，有新修复先保存反例再约定下一冻结点。

B领域通过不授整个App/Release/签名/生产通过；A构建通过也不授OCR或数学质量。每批交Pro要求的六类SOL/合同/完整patch/TEST_RESULTS原logs/ADVERSARIAL_REVIEW/VERDICT_AND_ROLLBACK/HANDOFF，保两类反证。独立/完整审查未执行保持NOT_EVALUATED，不开审查子代理。
