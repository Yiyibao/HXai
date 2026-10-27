# A27 配置提交与发布协议 v1

来源：`HXai_CHANGE_REVIEW/A27-SETTINGS-COMBINED-20261027/`，接续R27-02及R27-01。适用完整HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批SOURCE_FINAL_BUILD；代码状态与裁决取TEST_RESULTS/HANDOFF，不继承旧包资格。

`SettingsStore.update(Settings)`完成DataStore提交后，等待规范化的同一热流发布该提交或更新版本才返回。生产中不再直接乐观写入settingsFlow。`update(fn)`在同一互斥区读取最新已发布快照、计算并提交，后一个函数更新不会重用前一个提交之前的快照。整份Settings快照更新仍保留其显式替换语义，并不自动合并任意陈旧完整快照。

新增偏好键`settings_publication_revision: Long`，只由完整设置提交/恢复在原DataStore事务中递增。旧文件缺省0；负值或耗尽拒绝写入，不重建库。同值更新也推进序号，避免仅按字段值等候而把旧发射误认作新提交。Settings尾部增加internal transient publicationRevision用于本机发布确认，不进入task/config/backup JSON，不是授权epoch、配置修订hash或任务fence。已有Room33、TaskStore13、Pigeon类型与生成码不变。旧应用忽略该附加偏好键，回退也不得删除它。

档案/记忆状态仍由原独立DataStore键管理；普通完整更新不写回调用者的旧profile列表/选择。凭据仍经CredentialVault保护；解密失败保持原恢复门禁，不提前确认保存。更新取消发生在持久提交后时，保已提交数据，之后由持久读者正常发布，不伪造失败零副作用。

真实入口与源码地图：

| 路径 / 符号 | 角色 |
|---|---|
| `client/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`: persistSettings/settingsFlowRaw/update/updateAndAwaitPublication | 事务序号、规范化发布、互斥与确认 |
| `.../flutter/StudyHostFacade.kt`: 模型/提供商设置回调 | 保存配置后返回真实宿主回执 |
| `.../service/ChatService.kt`: mutateMessageQueue | 接受时捕获已发布Settings，原模型快照不改 |
| `.../feature/agent/AgentScheduler.kt`: accept | 无模型仍拒绝、归属与固定模型验证 |
| `client/app/src/androidTest/java/me/rerere/rikkahub/data/datastore/SettingsPublicationRuntimeTest.kt` | 原版双失败、修复后五种发布/并发/兼容反证 |
| 同目录`SettingsPublicationProcessProbe.kt` | 持久ready→观察live PID→真实force-stop→新进程读回与序号继续递增 |
| `client/app/src/androidTest/java/me/rerere/rikkahub/service/AgentChatAcceptanceTest.kt` | 合成夹具改走服务删除/Session失效，按随机会话清理schema13子记录并核外键；生产清理不变 |

扩展平台回归的84 PASS/2 FAIL保留；修复旧测试清理后重跑同进程同选择器，不用隔离通过掩盖混合失败。最终构建以SOURCE_FINAL_FIXTURE_BUILD及新冻结QA两包为准，首次中断的普通构建没有完成资格。

复验使用匹配冻结QA两包、自有API26 AVD和新phase/run-id，先归档再仅清nativeqa。命令与安装SHA见本批pipeline/run_native记录；普通包使用独立输出和关闭QA入口的variant。源码改变后另封印、重建，不直接复用旧SOURCE或日志。原Pro原文保持，新增适用门禁为发布时序/并发/旧JSON/恢复及同源组合包，未降低原门槛。

回滚仅逆本批精确源码增量/禁用对应入口，保新增偏好键、任务/原件/版本/墓碑/授权/receipt/费用/UNKNOWN。独立网络权威、完整云执行、真实Provider/OCR、目标设备与正式签名仍分别接续；本协议不授完整A线或产品PASS。
