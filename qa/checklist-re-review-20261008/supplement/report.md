# 继续复审：本地受控时序补证据

本批与主报告的 Android 97通过/2失败/2跳过分开记账。只新增测试源码，生产 APK SHA-256 仍为 A206A5965B84C631F7EEE97DEB50604B46FE3F6C146882838E22687EE813A095。每次测试 APK 变化分别见 apk-hashes.json、final-apk-hashes.json、job-await-apk-hashes.json。

|场景|实际结果|证据与边界|
|---|---|---|
|A01 标题/建议 × 正文/编辑/分支|6组阶段断言通过，独立进程重开通过|seed-job-await.log 记录读前快照、门控释放与结果；verify-partial-manifest.log 核对持久化标题、完整消息与分支选择。当前受控正文和分支修改由 session 注入，不代表真实网络流或编辑 UI 全入口。|
|A02 历史/聊天删除|2组阶段断言通过，独立进程确认不复活|实际 HistoryVM/ChatVM 入口；回调置于 NonCancellable，删除后释放并拒绝旧结果；检查行、搜索、来源及重新保存拒绝。其他会话完整隔离仍待补。|
|A03 审批故障|补测失败，未关闭|首个 approve 的内存/DB Pending 断言通过，但错误通知列表未含目标会话，seed-job-await.log 第151行断言失败。deny/answer、审批故障后重启与重试均 NOT_RUN；不能把 Pending 单独当作故障注入成功。根因未定位，暂不归为确定生产缺陷。|
|A04 新增附件子场景|NOT_RUN|在 A03 前置失败后未运行，分支/队列/私有库及故障后文件哈希仍有证据缺口。|
|D07 Skills 工具列表|FAIL，确定边界缺口|skills-boundary.log：合成已启用技能进入实际 ChatToolFactory 工具列表，包含 use_skill；未执行工具或外部请求。仅验证工具工厂，未宣称完整导入 UI 或跨档案泄漏。|

seed-job-await.log 进程 PID 与 verify-partial-manifest.log 的 PID9901不同，宿主明确 force-stop。部分 manifest 含6个元数据记录和2个已删除记录，verify 通过只支持这8条记录，不把失败的整个 seed 转记为通过。

早期 seed.log 的只读 SQLite 连接错误、seed-final.log 的审批错误断言失败及后续失败均保留。测试等待改为直接 join 对应任务后，错误列表断言仍失败；之前“等待竞态”仅是调查假设，未证明是原因。清理调用的通过不计入产品验收。

自动审批曾拒绝按档案枚举删除的测试清理，原因是范围可能误伤用户数据。清理已改为 manifest 中确切合成 UUID，并校验所属档案和 QA 标题；私有库仅允许已记录的匹配 UUID 文件路径。收窄后的执行获批，当前无待批动作。首次失败中未写入 manifest 的合成审批行未推断删除，保留为测试残留。
