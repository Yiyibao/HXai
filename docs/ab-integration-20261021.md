# A/B 本地合流结果（2026-10-21）

当前集成分支为 `codex/ab-merge-20261021`，完整 HEAD 为 `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。A/B 成果原本位于未提交工作区；本次保留完整工作区，核实并验证联合源码，没有用普通 `git merge` 的空操作冒充搬运未提交文件。未提交、推送、远端合并或部署。

**本机合流范围 PASS_SCOPED。** A02/A03 与 B04/B05 当前已在同一源码和同一匹配的 QA APK 上通过定向验证。

| 合流核对 | 结果 |
|---|---|
| A 冻结源码 + B 最终补丁 | 隔离副本反向撤回 B 后29/29与A原字节一致，再正向应用后59/59联合文件与当前根目录一致 |
| A 全部原生源码来源 | 原1151项索引中1145项原字节不变，6处变化均由B最终补丁解释；新B源码另由当前索引覆盖 |
| 原工作区保全 | 2813文件完整产品快照及原始status/diff保留；既有tracked diff不变；仅修复验证脚本的干净设备包查询 |

| 本轮实际验证 | 结果与边界 |
|---|---|
| Flutter | analyze无问题；404项测试通过；当前debug AAR构建通过 |
| JVM强制重跑 | App501、AI211、Search33，分别零失败/错误/跳过 |
| A定向原生 | 7类45项通过：物理输入/派生元数据/历史查询、Room迁移、备份、Office协作与完整目标验收 |
| B定向原生 | 5类28项通过：资料库生命周期/实际Flutter界面、教材与符号来源、限定数学与同一Chat、异步PDF恢复 |
| 真实进程退出 | A恢复安装后exit77、B未发布写入、B恢复已提交但丢ACK，共三个边界通过；原件与恢复结果独立SHA读回一致 |
| 普通Debug / lint | 构建通过；lint 0 Fatal、0 Error、24 Warning；不隐藏现有警告 |
| 正常包/QA隔离 | 直接检查实际Manifest和DEX：QA provider只存在于QA包，普通Debug不含测试provider/authority/class |

旧验证入口在新模拟器没有QA包时把正常的`pm path`退出1误判为失败，已保留原FAIL并最小修复为列出、精确匹配包名。新设备安装和已有QA状态的归档后清理两种路径均已实际通过。产品模型、授权、任务、schema、材料及数学算法没有新增变更。

完整合同、补丁、59文件往返、源码/APK/产物哈希、原始FAIL/PASS和六类SOL交接见 [AB-MERGE-20261021](../HXai_CHANGE_REVIEW/AB-MERGE-20261021/HANDOFF.md)。原生测试仅使用本任务创建的`mathstudy-ab-merge-20261021`/`emulator-5590`及合成nativeqa包；未操作普通App或其他模拟器。

真实Provider/OCR、Linux/Docker、MS Office/WPS、Release、Mate50/HarmonyOS4.2、TalkBack语音和生产仍未由本批验收。独立与完整全项目审查为NOT_EVALUATED；同一开发执行者的定向反证不冒充独立审查。

后续开发可沿此完整集成工作区接续A04等任务，不重复应用旧A/B累计补丁，不从旧HEAD裸建目录丢失现有未提交成果。原件、墓碑、receipt、UNKNOWN及Room33/sidecar9/Pigeon164/记忆payload1或2/材料metadata2保持。
