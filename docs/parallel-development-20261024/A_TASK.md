# A线任务书：统一Agent、云设备与最终集成

与[总计划](PLAN.md)及[B任务书](B_TASK.md)配套。A负责平台/主Chat/共享接线/最终App，不从零重写现有任务底座。执行者只负责开发、接线、编译、必要验证与修复；不开子代理、不自行创建其他会话或派发消息。

## 开工基线

先读根/client AGENTS与所列Pro原文、`docs/remaining-development-20261024.md`、当前总计划。重新记录branch/完整HEAD/status/diff，冻结包含必要untracked的完整产品快照；当前HEAD旧树不能替代当前产品。保护所有未提交成果。

A直接改 `feature/agent`、ChatService/ConversationSession/GenerationLoop、Factory/Registry/Dispatch/Planner/Verifier、Provider/费用/凭据/Memory/Backup、Host/Pigeon双端、主Chat/任务/扩展/费用页、云设备代码、Gradle/DI/共享路由/构建。领域material/math/document-service由B负责；通过冻结候选集成。

## A24-01：平台来源核对与Release最小集成（首批）

**目标：** 把已有Release候选接到当前真实工程；与B共同闭合合同17的来源/版本/交叉回归，形成当前可复核基线。

1. 读 `qa/remaining-development-20261024/SOURCE_COMPARISON.json`：核4份Extension生产代码及3份agent测试差异，确认后续A04/A05来源与影响，不回退为合同17旧快照。数学测试差异交B。
2. 当前TaskStore10、Room33，补schema总地图和来源增量；向B提供字段/版本事实，不能通过改回9迎合旧说明。
3. 读 `A07-RELEASE-20261021/CHANGE_CONTRACT.md`、before、SOURCE_BEFORE、构建/包挑战/测试签名及冷启日志。以before/candidate/current三方核Gradle、APK核验器与文档，提取最小增量，不整份覆盖 `.local/a07-release` 的旧代码。
4. 新合同至少覆盖Flutter release依赖、无正式密钥时unsigned产物、Release不得混入QA开关/入口、APK AOT与engine/ABI/资源、正常Debug保持；保留原始签名和缺engine失败反例。
5. B冻结领域后，在当前完整源码重新生成适用AAR、Debug/Release并实际包检查/冷启。普通包与nativeqa分开；旧副本通过不授当前包通过。临时烟测驱动由B给清单，A核普通包隔离。
6. A与B各交本线证据，由B汇总合同17领域交接，A补共享集成结论；不要双写旧批次日志，缺失汇总与新差异证据明确分开。

第一条只读命令（根目录；diff exit1表示差异）：

```powershell
git diff --no-index -- client/app/build.gradle.kts .local/a07-release/client/app/build.gradle.kts
git diff --no-index -- scripts/verify_flutter_apk.py .local/a07-release/scripts/verify_flutter_apk.py
```

构建续用A07的 `aar-build.json`、`final-build.json` 真实命令结构，在新隔离副本/新输出中指向当前源码与匹配缓存。先读脚本，不安装依赖或使用旧副本产物伪装当前构建。验收：新源码/AAR/APK/命令/冷启绑定一致，Debug可用，错误包被拒；正式签名/Mate50/生产另验。

回滚只逆A24-01增量/停入口；保Room33/TaskStore10/原件/receipt/UNKNOWN，不恢复旧schema或覆盖B改动。

## A24-02：A06云端持续Agent与设备协作（主开发缺口）

入口：`feature/agent/AgentTaskStore.kt`、`AgentScheduler.kt`、ChatService/GenerationLoop；`server/agent-runtime`仅本机协议参考，不能直接暴露可信宿主方法当公网API。

分三小批，每批单立合同：

1. **隔离移交合同：** 先fake双host/transport，定义每用户工作域唯一权威、账户/owner/任务身份、锁模型快照、持久grantEpoch、递增fence、独立等待与可信executorStopped证明。不以timeout当旧执行器已停，不让手机与云端各自claim。
2. **账户与云执行：** 鉴权、vault引用/加密存储/撤权轮换、远程executor、任务API与审计、journal及独立结果读回。默认无真实key/公网部署；使用合成账号与专属本机环境开发验证。文档worker仍属B领域服务。
3. **手机执行与隐私：** 锁屏/离线检查点、恢复优先排队但不抢占、前台屏幕接管确认、可见停止、截图本机优先/临时生命周期。跨App默认关闭，真实执行在明确权限内。

必要故障：双host争抢、旧fence晚提交、写后进程退出、停止/移交丢ACK、撤权与发送竞态、UNKNOWN不释放/重放、key更新但仍暂停/缺输入/待设备确认、正常恢复不误拒。新增suite在创建后再登记真实路径和命令；当前没有可直接宣称通过的A06现成suite。

完成判据：合成移交通过只授协议范围；有真实托管与设备证据后，才声明手机离线时已授权云步骤真实继续。回滚停新adapter/调度入口，保权威库、fence、撤权/暂停和UNKNOWN；不把未确认停止的任务交回旧执行器。

## A24-03：真实Provider的Agent任务质量

A拥有实际Provider配置/调用入口与账本，B提供Office/OCR/数学真值与领域判据。先准备公开/合成评估任务、完整目标接受标准与独立核验，再在用户已授权provider/model/reasoning和角色范围内运行。

覆盖普通闲聊零工具、复杂多步任务、漏目标/假完成、同任务追加不重复副作用、失败重规划、模型不支持tool、未知收费/断流、源变化和跨owner。分开报告规划/执行/全目标验收、文件/OCR/数学质量；模型自评与localhost脚本不能作为真实质量成绩。缺账号/授权时准备评估集和本机fake，真实部分NOT_RUN。

回滚停实际调用，保UNKNOWN/用量/成果，不换模型或降低推理强度重试。

## A24-04：扩展协议与可信费用覆盖

继承A04通用MCP/Skills/OAuth和A05账本，不重复实现已有管理页或无预算设计。入口：ExtensionPolicy/McpTransport/McpRuntime/OAuth/Frontend、可信Registry/Verifier、TrustedCost、Provider物理适配、费用UI/导出。

- OAuth：先指定获授权HTTPS服务与公有客户端注册，验证resource/issuer/state/PKCE/scope和回调；授权成功不自动启用工具、恢复任务或提升scope。真实账号兼容与合成协议分开记录。
- 新transport/schema/写操作/二进制成果：只按实际用户目标增加，先定义可信dispatch/独立receipt/verifier及丢ACK/撤权/假成功合同；不能以重复读调用证明发送/删除/支付。
- 费用：明确官方来源/版本/有效期/币种/缓存规则，按实际供应商补适配；reported/estimated/unknown分开、旧金额不重算、失败/重试/角色不漏计或双计。当前本地目录注明2026-11-21 UTC到期，新增使用前复核来源，不沿用过期估算。

原精确入口和前置见 `A04-OAUTH-A05-COST-20261022/SOL_BLOCKERS.md`。审构建脚本后，本地适用命令为：

```powershell
.local/gradle-9.6.0/bin/gradle.bat -p client :app:testDebugUnitTest :ai:testDebugUnitTest --offline --console=plain
```

该命令不代替真实OAuth、全部扩展兼容或账单对账。回滚禁新adapter/撤对应连接，保安装墓碑、金额账本与UNKNOWN，不放开宿主Python/stdio规避沙箱。

## A24-05：最终合流与设备/发布资格

B24-05交冻结领域后，A负责同一源码/同一最终包的跨线回归、普通包隔离和系统级API26/Mate50验证；B负责同包领域故事。系统级覆盖后台/锁屏/弱网、SAF、暂停/停止/恢复、档案切换、大字/深浅色、TalkBack实际焦点与朗读；无设备仍NOT_RUN。

完成适用全项目门禁、正式签名/包校验与交付准备；独立审查未执行仍NOT_EVALUATED，不设审查子代理、不虚构完整App或生产通过。生产/发布/真实账号外发不由本任务书自动授权。

## 每批交付

新建 `HXai_CHANGE_REVIEW/A24-<批次>-<日期>/`：CHANGE_CONTRACT、TRACE_AND_DIFF、TEST_RESULTS/原始logs、ADVERSARIAL_REVIEW、实际patch、VERDICT_AND_ROLLBACK、HANDOFF及六类SOL。明确本批源码/输入/产物hash、适用门禁/NOT_RUN、每个遗留的文件/方法/依赖/下一命令/验收/回滚。

## A26-01：接收B26数学增量与真实质量评估门禁（2026-10-26）

用户要求交A线集成，继续遵守禁止子代理/新会话/派发消息；当前助手串行承担A接收。批次 `HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/` 的 `B_ACCEPTANCE.json` 已核B26六文件candidate与当前字节一致，不重复apply或覆盖用户修改；B原失败/测试/补丁封印保持只读。

隔离构建副本包含当前完整未提交client源码，并继承后续A线TaskStore13/账户增量，不回退到B旧交接中的TaskStore11。相对A24-02的差异单列A_SOURCE_DELTA；数学集成不代授账户/云端/迁移资格。App Gradle数学集成实际结果取本批TEST_RESULTS/GRADLE_RUN与原始XML，不用B纯Kotlin或旧APK替代当前结果；Flutter AAR/Web静态复用另绑定来源SHA，不称全量重建。

真实解题质量评估保持WAITING_EXPLICIT_AUTHORIZATION / NOT_RUN；本次“获得授权后开展”不当作调用授权。题干只取本批BLIND_PROMPTS，参考/证书不得一起发给模型。先由用户明确provider/model/reasoning、公开24题传输/原统一Chat调用与角色/用量留存范围，再按MODEL_EVALUATION_PLAN锁接受配置、串行执行、封存回答后逐义务评估。题库公开且参考未独立裁决，不能把工具命中率或模型自评当权威准确率；未知已收费请求不盲重放。当前不读Provider配置/密钥，不调用模型/创建自动日程。
