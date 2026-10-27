# B线文件与数学质量合同2（2026-10-27）

来源：`HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/`，接续未完成交接的 `B26-ALL-DOMAINS-20261026/`，并保留B24合同17收尾、OCR合同1、数学合同1–17及原Pro规范。完整HEAD仍为 `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`；实现位于未提交工作树，精确适用字节以SOURCE_FINAL与FINAL_BUILD_BINDING为准，不将HEAD冒充当前产品源码快照。

## 真实链与行为增量

### 文件与候选

`MaterialLibraryFrontend.library.mathWork.parseResults/parseCandidate/create → MathWorkInputs → MathWorkCandidates → DocumentServiceRepository`，以及 `list_material_parse_results → availableResults` 共用原owner、sourceId/SHA、物理页、缓存校验边界。取消请求位与持久CANCELLING/CANCELLED状态分别有效；任一成立，都不能再列出/读取新候选或创建新确认。已独立保存的用户确认快照不因解析取消或缓存清理被抹掉，原源删除/改变仍撤销。

`MaterialPdfOcrDraft`继承B26修复：入口及发布/复用前均复查取消，保原件和取消前已验证Word。客户端 `DocumentContract.validateDocument` 与服务 `api.output.validate_output` 均拒绝空白页却有正文/公式/图像、缺页/覆盖不一致；客户端还要求正文警告与manifest完全一致。不能把空OCR输出推断为空白原页。

`MaterialFiles`私有Json现在完整编码默认字段。createdAt是动态构造默认，省略后重读会重新取时间；完整持久化避免材料排序和DTO回读漂移。不改变MaterialFile字段、Room33、TaskStore13、Pigeon或libraryVersion。既有显式时间保持；已经缺失原始createdAt的历史元数据不能由本修复恢复精确历史时间，不伪造、批量覆写或降库。

### 数学

仍为 `ChatToolFactory.math_verify → MathVerification → ProfessionalMath/ProofDerivation → 持久tool receipt → TaskResultVerifier/TaskPlanner`，模型与原唯一Scheduler不变。

- 有序前提允许重复引用，规则前提个数、公式、步骤身份及假设依赖不放宽；显式解除假设才得到无条件结论。
- 无效自然推导输出 `certificate_status=DISPROVED`、`claim_status=NOT_EVALUATED`。这是否定证书，不是反驳目标。`professional_review`将这种原声明判断保持未知，不把后面的局部错误伪称为全稿首错。
- 合同1–17的证明范围不扩张。目录读回不是条件证明，条件证明不是题设条件已满足；数值包围/局部唯一根不自动证明整题。
- 32个原创公开样本的题干与参考/工具证书分离。参考为作者推导，独立人工裁决未完成；包装、核验器回归及合成Chat运行均不是实际模型解题成绩。

## 真实引擎与Office支持边界

Windows.Media.Ocr是本机评估驱动，不是新Android默认OCR或MinerU替代。明确选择语言运行，不自动切换；原五页扫描SHA固定，识别不读取真值/文字层。实际字符错误仍保留，强制原页修订/前台确认，不能用于免确认数学转录。真实学生手写、MinerU和真实主模型完整故事另验。

Microsoft Word/Excel/PowerPoint 16.0在专属新进程操作公开合成副本，保存、重开、实际重算及独立ZIP/PDF/像素读回。Word保OMML和非目标页；Excel核真实公式缓存、日期、类型、多表和图表；PPT保原逻辑页/隐藏标志及全部页PNG。

PowerPoint原PrintHiddenSlides请求实际仍漏隐藏PDF页。现只在**已保存PPTX的只读内存渲染视图**取消隐藏，不再保存此视图，导出全部逻辑页PDF；前后保存PPTX摘要不变。校验器必须见全部五个逻辑页及对应标题，旧三页PDF直接拒绝，不能以PNG齐全降级放行。此PDF是明确的全逻辑页审阅输出，不改变原PPT放映隐藏设置。

WPS用户实例不接管/关闭；本批不授WPS资格。Windows/Linux LibreOffice旧结果保持原源/镜像范围，不将MSOffice通过推广为所有Office兼容。

## 复验、源码映射与回滚

具体已执行命令/退出码/日志和同源包只取该批 `TEST_RESULTS.json`、`FINAL_BUILD_BINDING.json`；逐项三轴范围取 `SOL_ACCEPTANCE_MATRIX.md`，未验前置及下一命令取 `SOL_BLOCKERS.md`。API26/API34为专属nativeqa模拟器，不授普通Debug/Release/Mate50、TalkBack朗读或生产通过。共享测试的用户并行增量保留并单独记录，不冒认本助手编写。

本批不新增工具schema/capability/risk、不改授权/模型/队列/UNKNOWN；不迁移候选schema1/2、parseContract1或数学operation合同号。回滚只逆精确增量或停新评估驱动，保完整元数据、原件/不可变版本、墓碑、确认/撤销、receipt/费用/UNKNOWN。不得reset/clean/stash或重放未知远端动作。独立/完整项目审查均NOT_EVALUATED。
