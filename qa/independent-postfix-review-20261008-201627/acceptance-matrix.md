# 逐项验收矩阵

PASS 仅用于直接执行的子场景或明确标为源码检查的范围；FAIL、SKIP、NOT_RUN、MISSING_CONFIGURATION 分开。测试类通过不能关闭整行。本輪已执行本地核心没有 SKIP。

|重点|本轮直接结果|剩余未验证条件|
|---|---|---|
|1 审批双保存失败|PASS：approve/deny/answer 独特原错误保留，第二 pause 错误不掩盖；非空完整队列、内存 pause、Room Pending；同进程重试决定+当前队列同事务；force-stop 后恢复 pause/队列并重试。seed/verify PID 21834/21925。新增 JVM 门控 queue mutation 后 approval 读取两个最新消息且 pause，失败不发布。|本轮 Room 触发器为 ConversationEntity UPDATE；逐个 message node 删除/插入、memory reconcile 和 commit 的故障、锁内并发编辑/暂停所有交错 NOT_RUN。不能凭 withTransaction 源码关闭每个故障点。|
|2 重复/冲突/迟到决定|PASS：本轮旧边界三决定 first accepted、另一实际挂起 Job 不变；另新增完整 ChatService→ChatToolFactory→GenerationLoop→Room→合成 provider 完成，三决定各检查工厂 use_skill 输出、provider 门控期间重复/冲突及完成后迟到、另一挂起 Job。|旧计数1/0/0仍为分段验证；新完整路径观察到 Approved 的真实工厂输出、Denied/Answered 无该输出，每决定合成 provider 调用1次。**未拦截真实工厂 execute 函数计数，不能将 provider=1 写成工具准确执行1次。** 同一目标真正同时线程争抢、更广泛工具 NOT_RUN。|
|3 Skills删除/替换/安装|PASS：Android真实目录 symlink 外部合成哨兵、backup替换清理、卸载、同级卸载别名拒绝；超限128MiB ZIP拒绝且原技能保留；321字节二进制；默认文件外链读取/列表拒绝；JVM累计膨胀、数量/深度、重复规范路径、limit+1。安装/只读/显式启用合同通过。|嵌套链接、断链、同级别名替换的单独设备断言、清理失败、rename/rollback失败后的字节及enabled状态 NOT_RUN。真实GitHub及真实包内容不导出；全局包无自动档案拼接为源码范围。CF02修复前为源码漏洞确认，不伪称本轮执行了修复前负例。|
|4 标题/建议/删除终态|PASS：六组受控 session 门控 title/suggestion×stream/edit/branch；历史/聊天两个删除入口、取消无效迟到结果拒绝及旧样例重开无复活。**FAIL PR01**：删除后的 session 淘汰使旧保存重新插入原消息。|session 注入不等于真实模型/UI回调；PR01复活行重启再读取、全部删除/编辑回调入口 NOT_RUN。此项不得整项关闭。|
|5 附件引用/私有库|PASS：真实 Room 保存失败后消息和SHA保持，另一分支/队列仍引用保留、最后引用清理、私有材料文件保全及核心恢复。源码另检查跨会话/队列查询和存储边界。|本轮跨会话设备专项、一般I/O删除失败及重试未运行。`File.delete()`返回值未用于失败反馈值得补故障测试，但本轮不据此宣称私有材料已损坏。|
|6 服务清理|PASS：本轮28项真实Store/SQLite/生产main启动及周期入口，宽限期、QUEUED/RUNNING行及目录/源字节，enqueue与orphan cleanup门控和失败补偿。|平台flock、Docker、umount和external execute被替换。真实worker、挂载与部署没有验收；execute进行中的所有失败及claim/expiry交错 NOT_RUN。|
|7 材料/Word|PASS：五格式在本次 `WordFormulaAssertionTest.perRunMaterialSamplesExistAndContainSyntheticContent/c0c58e2a-…` 方法/UUID实际新建并生产Reader读取中文；公式结构/明确值/外括号正例及错值/嵌套/纯文本负例；原图片字节、缺失/变更负例；简单表格全部行/单元格顺序及删除/重排/变值/空表负例。**FAIL PR02**：tab变化误接受。|br负例首次方法在tab失败后 NOT_RUN；合法等价结构完整集合、复杂表格和真实模型Word重建 NOT_RUN。新的基本正例不能证明一般Word保真。|
|8 图片生成|PASS：强制无配置的合成工具返回文本FAILED，无Image且未创建成功材料目录；源码检查真实正例要求Image、新SUCCEEDED PNG、解码及非零尺寸。|真实正例 NOT_RUN；历史缺配置沿用 MISSING_CONFIGURATION 条件说明，未检查/配置凭据、未请求真实图片。负例通过不算生成成功。|
|9 证据/交付|PASS：946原输入文件结束哈希零变化；当前三ABI及原测试包与历史指定哈希一致，设备主/测试APK实际SHA核对。新探针APK单独登记；方法/UUID、窗口、日志SHA、mtime、字节绑定；核心10份/交付32份重复采集重新验证设备。两轮交付同方法、10格式组SOURCE/VIEW/SEND/确切Download目标SHA一致。**FAIL PR03**：PASS manifest 与随后JUnit FAILED矛盾未拒绝。|取消、损坏、途中切档、目标重读失败 NOT_RUN；两轮正常不证明长期稳定。原UUID之外没有枚举或导出个人Download。|
|10 诊断/新回归|PASS：六设备方法脱敏/容量保留最新、原诊断记录和导出缓存前后哈希保持；剪贴板/URL合成隐私；本轮app328、核心21、服务28、采集器基线6、Lint0/42。|全部网络包装异常、诊断清除/ZIP UI、权限/lifecycle/UI完整回归、非app模块、迁移/备份、额外设备 NOT_RUN。现有门禁通过不能否认新增PR01–PR03。|

## 原始清单其余范围

|原清单|本轮状态|
|---|---|
|A01–A06|以上1–7行直接范围；A02因PR01 FAIL，A06因PR02断言漏洞不能关闭；其他均局部PASS。|
|B01/B02预览/可信度|NOT_RUN：异步切档、缺失/损坏DOCX预览、人工修正/稍后/哈希/筛选整条UI。|
|B03交付|正常两轮局部PASS；失败分支NOT_RUN。|
|B04隐私与入口|Skills和剪贴板/URL局部PASS；DocumentsProvider全部共享入口隐私负例NOT_RUN。|
|B05/B06/B07|图库/滑块/OAuth/真实HTTP拒绝与数学澄清/Windows与Linux原workspace套件NOT_RUN；本轮完整审批续行为合成provider，不是远端澄清。|
|C01|app JVM/构建/Lint及服务本轮PASS；Web及其他模块NOT_RUN。|
|C02/C03|受控核心恢复局部PASS；真实四模式、所有网络/EOF/记忆30来源及四轮教学NOT_RUN。|
|C04/C05|JVM自身数据安全单元断言计入328；完整迁移/备份/诊断页面/四入口/放大字体/全部材料与UI NOT_RUN。|
|O01|两轮有限分享样本PASS，历史根因UNKNOWN，长期稳定NOT_RUN。|
|O02|本报告分批/版本口径，PR03证据终态FAIL；旧报告不改写为本轮通过。|
|D01|MISSING_CONFIGURATION条件；真实图片正例NOT_RUN。|
|D02–D06|NOT_RUN：真实增强worker、HarmonyOS/API37/16KB/正式Release/双真机/复杂材料/提供商扩展。|
|D07|当前Skills安装/查看/显式启用/卸载局部PASS；制作/编辑/自动草稿关闭；不沿用全Skills禁用旧断言。|

历史44项、18项、首次真实2项以及后续180657、182645、192645各批独立保留；不与本轮合计为全量执行。真实教学/材料历史失败根因仍UNKNOWN。
