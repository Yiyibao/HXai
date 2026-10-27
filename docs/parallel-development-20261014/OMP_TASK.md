# B：专业文件与数学能力 — 交给 OMP 的执行任务书

你负责 HXai / MathStudyAgent 两条并行开发线中的 **B：专业文件与数学能力**。Sol 负责 A：统一 Agent/授权/模型/账本/共享桥接/Release 集成。请直接做开发、接线、编译、必要验证和故障修复；不启动子代理，不以独立审查报告代替开发。

## 先读取与保全

仓库 `D:/Office/Study/code/MathStudyAgent`。先读根及 client AGENTS、其中要求的 Pro 权威原文，再读本目录 `PLAN.md`、`docs/project-progress-20261014.md`、最新多文档 HANDOFF/SOL_BLOCKERS 和原生协议 v1.8。

分配时分支 `codex/multi-artifact-evidence-20261013`、完整 HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`；当前功能包含大量未提交和 untracked 源码，不能从裸 HEAD 重建后当最新产品。重新记录真实 snapshot，保护原件/脏树/Pro 包/旧失败证据。禁止 reset/clean/stash、直接 push main、自动合并/部署。独立 feature 工作树完整基线约定见 PLAN；不要在共享目录切换分支或同 Sol 同时安装/清测试包。

## 用户故事

在统一 Chat 里使用授权教材/Office/PDF：精确修改并保留原件，输出可编辑新版本，真实渲染/重算/独立回读，按逻辑页和来源定位；数学解题有可核对教材依据，限定范围核验真正执行，不支持的证明如实报告。

## B1：真实 Office 编辑与核验闭环（首批）

从已接入的材料编辑与远程文档代码继续，**不要重做已通过的 XML/ZIP/PPT 逻辑页序修复**。优先选择一条真实引擎可验收切片：含多 sheet、公式、日期、图表的 XLSX 局部编辑与实际重算；复杂 DOCX/PPT 保真按独立批次继续。

1. 读 MaterialEditor/Reader/Writer/Files/Versions、OfficeConversionRepository、DocumentServiceRepository、实际 server/document-service worker/README 与现有测试。确认支持矩阵和真实依赖，不从文件后缀宣称能力。
2. 编码前写 B1 CHANGE_CONTRACT：真实入口→版本库→引擎→回读→预览；输入 owner/sourceHash/expectedText/locator/parent；成功/失败/未知/迁移/兼容/回滚。
3. 文件修改必须输出不可变新版本，保原件 bytes 和不相关结构。当前材料 API 保持兼容；必要新增字段先给 Sol 明确接口增量，不直接改共享 Pigeon/TaskStore。
4. XLSX：真正多表、数字/日期类型、公式/引用、图表，受控实际重算；缓存清空/calcPr 不算重算。记录引擎版本/公式值/独立读取/跨软件验证状态。
5. DOCX：复杂样式/表格/图片/页眉页脚/目录和可编辑 OMML，精准局部定位；必要代表页渲染；不能以抽取文本冒充版式。
6. PPTX：presentation.xml/r:id 逻辑序、隐藏/可见/导出 PDF 页清楚区分；形状/图片/母版保全；新增编辑范围后实际全页视觉核对。
7. PDF：原页组装与扫描/OCR 转可编辑文档分路径；页码/覆盖率/数学符号歧义可追踪，不假称完全复原。
8. MinerU/LibreOffice：实际受限 worker、最小挂载/资源/no-network 合同、缺字体、失败/取消/撤权、进程死后重开、结果 staging/下载/回读/cleanup。不能用合成文件头和 HTTP200 当转换通过。

没有可用引擎时仍完成本地适配器、能力拒绝、真实版本/结构核验与可执行容器配置，明确 NOT_RUN 和下一命令，不能把它们当引擎 PASS。新依赖/安装/下载先读相关脚本并依授权边界处理，不接真实生产。

## B2：资料库、教材检索与来源

- 授权目录导入、跨会话材料/版本引用、文件夹/标签/全文/章节索引、长文覆盖与缺页，真实预览/保存/下载/版本恢复。
- 引用绑定 owner/material/version/hash/locator 与页内依据；物理页/印刷页/slide 分开。
- 提供材料/索引/缓存删除 hook、恢复完整性和跨 owner 反例给 Sol；全局备份与撤权恢复由 Sol 归并。
- Flutter files/artifacts/doc task/数学领域页面由你实现。需要共享 host/Pigeon/路由时，交付独立 facade/组件和精确接线片段，不覆盖共享文件。

## B3：限定数学核验接入，再扩专业能力

1. 现 Python Q[x] 精确恒等、形式导数、表达式等式链可复用；原生 `math_verify` 目前是 UNSUPPORTED，主工具列表没有可调用实现。
2. 新 `feature/math/*` 实现原生或受控计算 adapter，标 AST/复杂度/域/范围/证据与 VERIFIED/DISPROVED/UNSUPPORTED；不用 eval/随机采样作证明。
3. 为 Sol 提供 Tool 构造函数、输入/输出 schema、能力/风险/verifier、正常与反例以及 **最小共享注册补丁**。Sol 负责 ChatToolFactory/TrustedToolRegistry/AgentToolDispatch/TaskResultVerifier/TaskPlanner 最终接线，不另造任务或模型执行器。
4. 手写批改首错需先验证前缀，模糊符号先确认，正确非标准方法不能误拒；教材引用真实版本与页内依据。
5. CAS/高精度/误差界、高中/高数/数学分析/实变证明逐题型扩展；真实题库和权威参考建立后才报质量，不把 Q[x] 当一般证明器。
6. 交互图的定义域/奇点/间断/参数/导出和按需 3D；静态图像不冒充 Office 可编辑数据。

## 独占与共享边界

直接修改 `feature/material/*`、`server/document-service/*`、新增 `feature/math/*` 及领域 UI/专属测试/脚本。现 `client/ai/*`、Agent 状态/权限/模型/账本、ChatService/GenerationLoop、共享 registry/Factory/Pigeon/StudyHostFacade/全局 providers、build/pubspec/DI/导航、共享验证脚本归 Sol。

如必须修改共享处，提供文件/方法/原字节 hash/原因/API或schema影响/最小 patch/测试要求，写入你的交接；由 Sol 做三方核对后集成。等待集成时继续领域实现和专属测试，不强写、不降低安全门禁。

## 必要验收与交付

- 正常输出真实存在、可打开、hash/版本/owner/来源一致；原件和无关内容不损坏。
- 保留 UTF16 DTD、实体编码 XInclude、正常 UTF16、ZIP 路径/重复/限额/宏/签名/外链反例；PPT乱序/隐藏页、wrong expectedText/hash、跨 owner、写后崩溃/丢 ACK。
- 反证一：工具返回成功但第二份内容错、引擎未重算、source变化或数学 UNSUPPORTED，不能授予任务成功。
- 反证二：正确文档/公式/合法源版本/不同正确数学解法保持可用，不以一律拒绝消除 FAIL。
- 独立保存原日志/准确环境/cwd/退出码/源码和产物 hash、旧版 FAIL 与修复结果；相关旧回归不省略。测试/构建共享资源按 PLAN 串行。
- 每批 `HXai_CHANGE_REVIEW/B-<feature_id>/`：合同、调用链/完整 diff、原始日志、TEST_RESULTS、ADVERSARIAL_REVIEW、实际 patch、VERDICT_AND_ROLLBACK、HANDOFF及六类 SOL 材料。实现、证据、测试三轴分开。
- 输出独立文档/协议增量，不覆盖 Sol 的全局进度/原生总协议或原 Pro 文件。独立外部审查 NOT_EVALUATED；真实模型/专业引擎/Release/真机未运行则 NOT_RUN，不累加历史计数。

所有领域能力仍使用用户锁定模型/推理、统一租约/权限/journal/账本；不自动换主模型、不新增硬费用预算、不默认上传私人材料、不部署生产、不启用后置专用连接器。先完成 B1 首批的具体可运行代码与验证，再继续 B2/B3。
