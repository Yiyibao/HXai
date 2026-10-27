# A线任务书：统一Agent、云端协作与最终App

配套[双路线总计划](PLAN.md)与[B线任务书](B_TASK.md)，承接最新R26待办。A负责平台真源、实际调用和最终集成，执行开发/接线/编译/必要验证与修复。按用户规则串行执行，不启动子代理、新会话或派发消息。

## 起点和开工要求

当前TaskStore13/Room33，完整HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加未提交产品。读根/client AGENTS、所列Pro原文及本轮总计划；每批重新记录branch/head/status/完整diff，冻结必要untracked及输入。代码工作使用隔离feature分支/目录，不能裸checkout旧HEAD丢失当前成果。

A持有feature/agent、主Chat/Loop/Planner/Registry/Verifier、Provider/授权/费用/Memory/Backup、Host/Pigeon/共享Flutter、云/设备、Gradle/DI/路由/共享驱动。B所属material/math/document-service仅以冻结候选集成。

## A26-R01：当前组合包基线（首批）

**输入：** A26-ACCOUNT-TRANSPORT、A26-01-B-MATH-INTEGRATION的SOURCE_FINAL/TEST_RESULTS与B26六文件候选；[最新清单](../remaining-development-20261026.md)及来源比较。已有Release依赖保留，不重复套A07旧补丁。

1. 核账户与B26当前字节、调用者和消费者；接受B26-R01的领域回归清单，冻结包含TaskStore13的完整产品快照，审生成/QA驱动是否进入普通源码。
2. 按现有BUILD_AND_TEST与[Release协议](../a24-release-integration-v2.md)建立独占构建输出，核JDK/SDK/Flutter/AAR输入；生成匹配QA两包及普通Debug/Release，普通包关闭QA入口。缺正式密钥保unsigned/明确测试签名，不冒充发行签名。
3. 按改动影响运行账户/移交/数学/来源及迁移交叉回归，普通包冷启和安装SHA独立读回；不把旧账户包84方法或数学JVM41方法代作当前组合包通过。

验收：源码/AAR/APK/安装SHA/日志相互对应；原slot/fence/模型锁/UNKNOWN保持，合法重复前提证明不误拒，旧数据不损坏。真实Provider/OCR、目标设备和独立审查另验。

精确前置/旧命令：[账户SOL_BLOCKERS](../../HXai_CHANGE_REVIEW/A26-ACCOUNT-TRANSPORT-20261026/SOL_BLOCKERS.md)、[数学接收SOL_BLOCKERS](../../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/SOL_BLOCKERS.md)。先读驱动，新run-id/独占设备/匹配源码包；冻结旧包复跑不能证明新源码。

## A26-R02：独立宿主与网络权威（主要开发批）

入口：AgentAccountAuthority/Transport/Executor、AgentScheduler.prepareHandoff、AgentTaskStore.activateHandoff/beginRequest。以[账户协议](../agent-account-transport-protocol-v1.md)和[移交协议](../agent-executor-handoff-protocol-v1.md)为起点；现Python runtime是本机参考，不直接暴露trusted方法当公网API。

先选定每用户工作域唯一权威，设计账户/设备注册及scope/epoch、认证transport、持久实例身份/进程代次、可信停止/恢复/读回。使用两个实际独立进程及合成账号/secret开发网络边界，不能仍共用同一coroutine后宣称分布式通过。

必要反例：双端争抢、旧fence/实例晚提交、移交丢ACK、写后进程退出、撤权/断网、UNKNOWN与未确认停止、正常恢复不误拒。验收只允许一个权威admission；时间到期/失联不证明旧executor已停，已有结果先读回。

第一条只读定位命令（仓库根）：

```powershell
rg -n -e prepareHandoff -e activateHandoff -e AgentAccountExecutor -e AgentAccountAuthority client/app/src/main/java/me/rerere/rikkahub/feature/agent
```

新suite建立后再登记真实运行命令；当前没有现成跨宿主产品suite可直接报PASS。回滚禁新transport/adapter，保权威库/绑定/fence/journal/UNKNOWN。

## A26-R03：服务器vault与完整云执行

依赖R02权威/transport，先合成环境。实现最小持续或任务级托管grant、服务器加密密钥管理/引用、撤权轮换、实际key验证、加密配置快照传输；云接收端复用原GenerationLoop/Planner/工具/独立Verifier和费用台账。

每次物理请求绑定owner/task/capability/scope/grantEpoch/inputHash、稳定requestId与接受时provider/model/reasoning，不让辅助角色替换主推理。账户撤权与物理发送竞态、断流/可能收费、轮换后仍暂停/缺输入/待设备确认分别验证。Provider文本响应最多COMPLETED_UNVERIFIED；用户目标必须另验收。

真实托管/模型只在具体授权范围内，缺授权继续合成实现。声明手机离线持续运行前必须有独立云进程、任务状态与真实成果读回。回滚停真实adapter，保托管撤权/原快照/费用/UNKNOWN，不让旧执行器擅自接管。

## A26-R04：手机执行器与前台接管

接R02/R03的停止/移交，不新增第二任务权威。实现手机锁屏/离线检查点、恢复优先排队不抢占、可信前台确认、持续可见停止、原生受控动作；可选跨App默认关闭。截图本机优先、临时保留，远传须明确接收服务/目的/范围，不入自动记忆/备份。

验收：联网/解锁/换key不解除manual pause、撤权、input或device confirmation；旧epoch异步UI回执丢弃；STOP_REQUESTED不能显示已停。未知发送结果不能自动再发。真实设备权限/接管另按具体授权；回滚禁设备adapter，保等待/原件/receipt/UNKNOWN。

## A26-R05：真实Agent评估、扩展与费用

可在原本机Chat开展，不必等云功能全部完成。与B冻结公开题目/文件输入及完整目标判据，由A持有真实调用入口、任务快照、回执/用量与回答封存。

- Agent评估：闲聊零工具、多步两讲义/文件目标、漏目标假完成、同任务追加不重复写/发、失败重规划、不支持工具、断流/源变化/跨owner。回答/成果先封存，再评分。
- 数学/OCR协作：只发送已批准题干/材料，不带参考/证书；主模型和辅助角色分别明示。A交去秘密的实际配置/任务/请求/回答/工具/用量SHA，B逐领域义务评估；未知收费不盲重试。
- 扩展：按实际HTTPS/OAuth服务和任务目标补transport/schema/写操作verifier/沙箱二进制成果。OAuth不赋工具权限，重复只读不能证明发送/删除/支付成功。
- 费用：官方来源/版本/有效期/币种/缓存规则绑定请求，不重算旧金额；reported/estimated/unknown分开，不设预算硬上限。现费率目录注明2026-11-21 UTC到期，新使用须核有效性。

真实调用前须明确provider/endpoint/model/reasoning及传输/角色/留存范围；凭据在可信App配置，不进入聊天/仓库。现24题范围见[评估计划](../../HXai_CHANGE_REVIEW/A26-01-B-MATH-INTEGRATION-20261026/MODEL_EVALUATION_PLAN.md)，扩展/费用见[A04/A05阻碍](../../HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/SOL_BLOCKERS.md)。缺授权时准备任务集/输入隔离/失败分类，真实结果NOT_RUN。

验收按完整目标而非工具命中率；同一助手评分注明同一执行者。回滚停实际调用/新adapter，保回答/成果/账本/UNKNOWN，不切模型或改参考迎合输出。

## A26-R06：最终合流与系统交付

接B26-R04领域冻结，三方核最小候选；源码/接口变化后重新封印并构建最终QA及普通两包。在同一包核API26、Mate50/HarmonyOS4.2、后台/锁屏/弱网/SAF/暂停停止恢复、档案切换、小屏/200%字/深浅色/TalkBack真实焦点和朗读；B在同包验证领域故事。

按Pro做适用需求追踪、安全/迁移/恢复/依赖许可证/包隔离与正式签名交付准备。缺设备/签名保持NOT_RUN；独立及完整审查未执行保持NOT_EVALUATED，开发验证不代签。发布/生产单列授权。回滚保TaskStore13及全部数据/撤权/UNKNOWN，禁止降库或清数据。

每批交付六类SOL、CHANGE_CONTRACT、完整diff/patch、TEST_RESULTS/原logs、ADVERSARIAL_REVIEW、VERDICT_AND_ROLLBACK及HANDOFF；按适用门禁保旧FAIL并覆盖“假成功/错恢复”与“正确输入误拒/旧数据损坏”。


## A26-R01 当轮冻结交付（2026-10-27完成）

当前冻结组合：App 522 / AI 214 / 搜索 33 项JVM、Flutter416项和analyze、Android 147 方法全部通过；两种lint无错误（Debug 30、Release 28 警告）。三次真实QA进程退出后读回通过。普通Debug/测试签名Release冷启、任务中心真实Host查询、安装SHA及全部普通包Flutter/QA隔离和故障副本反证均有当轮证据。

仅授开工冻结SOURCE_FINAL的账户+B26组合本机资格；共享根目录后来B26-ALL-DOMAINS增量未纳入，不借本批包证明当前根目录或完整App。Release原件为unsigned，测试签名副本仅本机。真实模型/OCR、独立云、目标设备/正式发布与独立/完整项目审查分别未验。

三个主Chat数学回归已交精确patch；当前B领域后续增量待其正式冻结，再按本任务书做同源合流。详见../../HXai_CHANGE_REVIEW/A26-R01-COMBINED-BUILD-20261026/HANDOFF.md，不改写原R02–R06任务。
