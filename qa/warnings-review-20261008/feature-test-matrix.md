# 当前完整功能与测试矩阵

本轮 fresh 工作区/模拟器证据；通过、失败、跳过、未验证、条件支持分别记录。支持范围指实现合同，不表示所有设备/提供商/格式均已验收。

|功能|实现/支持边界|本轮结果及负向/恢复边界|证据|
|---|---|---|---|
|四主入口、导航与字体|聊天/材料/记忆/成果及子页面，静态伙伴显示|通过：正常与 1.3 倍字体的真实点击；未测全部动效状态/性能|[StudyNavigationTest.log](StudyNavigationTest.log)|
|模型选择|当前模型列表与选择器入口|通过：页面与持久选择；第三方全部模型未逐一调用|[FrontendModelPickerTest.log](FrontendModelPickerTest.log)|
|四种教学模式|引导/提示/检查/完整讲解，模式持久化与分支继承|通过：真实四模式；本地模式持久化也通过|[ProjectReviewTeachingTest.log](ProjectReviewTeachingTest.log)|
|冷启动配置与真实审批|加密配置冷加载、实际 ask_user 拒绝、重复/迟到审批|失败；首轮 StudyRealModelTest 超时保留，不用独立四模式通过覆盖审批失败|[StudyRealModelTest-diagnostic-final.log](StudyRealModelTest-diagnostic-final.log)|
|聊天错误与取消|显式结束/空响应/断流/HTTP 错误/用户取消/网络恢复/队列编辑|通过：16 项实际网络故障与取消测试；不自动重放不确定工具|[ChatNetworkFaultTest.log](ChatNetworkFaultTest.log)|
|历史分支|编辑、再生成、分支选择，保留旧路径|通过；专用富文本默认样例跳过，不能算视觉通过|[StudyChatCompletionTest.log](StudyChatCompletionTest.log)|
|聊天进程恢复|消息、部分回答、队列和已完成/不确定工具状态|通过：独立 seed/force-stop/verify；不等于后台持续执行|[runtime-verify.log](runtime-verify.log)|
|档案隔离|会话、记忆、材料、成果按学习者归属；切换清空内容|通过：仓储越权拒绝、实际页面切档与成果正文/按钮清空|[ProjectReviewAuditTest.log](ProjectReviewAuditTest.log)|
|凭据保护|Keystore/AES-GCM、篡改/缺密钥/旧迁移与冷加载|通过：10 项；未导出凭据/个人配置，厂商设备仍未验证|[CredentialVaultTest.log](CredentialVaultTest.log)|
|学习记忆仓储|来源、冲突、人工修改、删除抑制、租约、预算与压缩|通过：持久化与失败/取消合同；长期锁屏/厂商杀后台未验证|[LearningMemoryPersistenceTest.log](LearningMemoryPersistenceTest.log)|
|学习记忆页面|搜索、来源回看、修改、取消/确认删除、并发及草稿|通过：真实页面操作、旧入口隔离和跨档案拒绝|[LearningMemoryPageTest.log](LearningMemoryPageTest.log)|
|记忆进程恢复|独立进程重开与持久来源/状态|通过：seed/force-stop/verify；默认占位样例跳过另列|[memory-verify.log](memory-verify.log)|
|真实自动记忆与新会话|30 条合成正反来源、抽取与跨会话使用|通过；结论仅限这批来源，不能推定通用数学掌握度|[LearningMemoryRealModelTest.log](LearningMemoryRealModelTest.log)|
|教学闭环|第一处错误→提示→重答→独立尝试→待复习建议，区分学习表现|通过：真实四轮与来源抽取；持久合同通过|[LearningLoopRealModelTest.log](LearningLoopRealModelTest.log)|
|材料私有库/导入|30 MiB 限制、状态/哈希、图片与现代 Office/PDF/UTF-8|通过：正常/失败/取消/损坏/越权；DOC/XLS/PPT 旧格式拒绝解析|[MaterialFilesTest.log](MaterialFilesTest.log)|
|Word|基础正文/表格/常见 OMML/内嵌图；基本可编辑输出|通过：本机格式保真样例；复杂版式/公式/图表未完整支持|[MaterialFidelityTest.log](MaterialFidelityTest.log)|
|Excel|工作表/坐标/类型/公式及缓存值；基本单表输出|通过：本机样例；不计算公式，不承诺完整样式/图表保真|[MaterialFidelityTest.log](MaterialFidelityTest.log)|
|PPT|逐页文本与基本可编辑幻灯片|通过：本机读写；动画/备注/图表/全部图片保真未验证或未实现|[MaterialFidelityTest.log](MaterialFidelityTest.log)|
|PDF/文本|指定物理页、扫描候选、分页与跨页表格尾部|通过：范围错误/取消/归属拒绝与尾部样例；专业数学排版未支持|[MaterialFilesTest.log](MaterialFilesTest.log)|
|材料页面/分页/筛选|实际文本预览、有界提取/范围提示、状态筛选|通过：短文本、长文本末尾、已解析筛选；损坏反馈真实|[ProjectReviewAuditTest.log](ProjectReviewAuditTest.log)|
|关键符号核对|当前提取片段、手动修正、待核对状态与来源 SHA-256|通过：无虚构公式、取消/暂后处理、持久修正和错误来源拒绝；不是自动正确性认证|[ProjectReviewAuditTest.log](ProjectReviewAuditTest.log)|
|五格式成果与预览|真实 MD/DOCX/XLSX/PPTX/PDF、归属/幂等、格式提取及异常状态|通过：缺失无范文、DOCX 无 ZIP 乱码、切档清空、损坏打开失败|[ProjectReviewAuditTest.log](ProjectReviewAuditTest.log)|
|材料系统交付|五格式实际打开/另存/分享、跨 UID 接收与目标重读|通过：5 份同次原件/目标/VIEW/SEND 回执哈希一致；档案变化/取消合同通过|[MaterialDeliveryTest.log](MaterialDeliveryTest.log)|
|成果系统交付|五格式成果打开/另存/分享及真实接收|通过：另 5 份同次原件/目标/回执哈希一致；未用旧样例冒充|[ProjectReviewDeliveryTest.log](ProjectReviewDeliveryTest.log)|
|真实文件工具/视觉|真实模型工具往返五格式、数字图、扫描 PDF 页、Word 内嵌图/公式重建|通过；清晰合成样例 7/7391/8642，不外推为模糊手写识别|[MaterialRealModelTest.log](MaterialRealModelTest.log)|
|图片生成|配置对应模型后解码/尺寸校验/PNG 落盘|条件支持：本轮独立探针的实际结果见合成记录；没有成功产物就不记生成通过|[fixtures/material-real-model.md](fixtures/material-real-model.md)|
|增强文档服务|HTTPS、专属档案凭据、提交/轮询/取消/下载校验和原页回看|页面通过；服务端 20 项/公开 health 通过；真实解析未验证：无专用合成 token|[DocumentServicePageTest.log](DocumentServicePageTest.log)|
|加密备份/恢复|.msab、密码/预览、启动前恢复、回滚与删除约束合并|通过：正常、错误密码、取消/确认后删除、隔离目标恢复；两台真机迁移未验证|[BackupReliabilityTest.log](BackupReliabilityTest.log)|
|诊断/反馈交付|脱敏/有界事件、崩溃记录、清除取消/确认、ZIP 保存/分享|通过：数据/实际页面与系统交付；未制造真实 ANR/进程崩溃|[DiagnosticsPageTest.log](DiagnosticsPageTest.log)|
|伙伴与一次性反馈|前台/可见性策略、减少动画、持久去重|通过：跨进程反馈不重播；全部动画状态/能耗未验证|[feedback-verify.log](feedback-verify.log)|
|图库保存/滑块迁移|两种图库保存与缺失源清理；受控值/范围/拖动完成/禁用|通过：API 34 实际操作；旧系统图库/通知分支未验证|[slider-final.log](slider-final.log)|
|公共日志隐私|复制与 URL 操作只记录固定事件和异常类型|通过：合成敏感标记不进入 helper/异常日志，实际复制仍交付；断言确认 URL 异常分支已执行；原失败保留|[context-privacy-final-verified.log](context-privacy-final-verified.log)|
|共享 workspace 外部入口|学习者隔离未满足，Web/全局文件/Shell/MCP 未开放|通过：DocumentsProvider 不可外部解析；保留数据/源代码，不声称终端功能支持|[workspace-boundary-final.log](workspace-boundary-final.log)|
|保留的上游扩展|搜索/TTS/ASR/翻译/JS/图表/高级助手/提示注入/导入等代码|条件支持/未验证：协议单元测试通过，全部提供商和实际 UI 入口未逐个端到端验收|[all-module-jvm-results.json](all-module-jvm-results.json)|
|目标交付与后置能力|Mate 50/HarmonyOS、16 KB runtime、正式签名、Skills、视频/自动同步|未验证或未开放：本轮 API 34/4 KB Debug 不能证明上述条件；无发布部署|[report.md](report.md)|

## 计数与历史失败

逐例终态、首次失败和后续复验分别保存在 [test-summary.json](test-summary.json)。同名方法按本轮最新执行判断终态，seed/verify 分进程分别计数；此前失败记录不删除。

两处跳过是专用富文本样例未启用，以及记忆恢复测试默认占位模式；后者有独立分进程通过证据。跳过不计通过。

文件交付以 [exact-delivery-hashes.json](exact-delivery-hashes.json) 为准，它按同一次测试的 SOURCE 回执精确文件名匹配，不使用最新同扩展名文件代替。

格式与视觉结论限定于合成材料。专业排版、完整 Office 保真、自动数学正确性认证和全部中文手写/模糊符号识别不在通过范围。
