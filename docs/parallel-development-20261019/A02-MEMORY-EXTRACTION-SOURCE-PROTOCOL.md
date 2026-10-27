# A02 自动记忆抽取来源保护 — v1.25

增量来源：`HXai_CHANGE_REVIEW/A-MEMORY-EXTRACTION-SOURCE-20261019/CHANGE_CONTRACT.md`，继承v1.19删除保全和v1.24物理请求钩子。原Pro规范及失败证据保持；本文件描述代码合同，不预授测试PASS。

入口仍为`MemoryExtractionRunner.drain/process`→`ModelRequestRunner`→原全局`AgentScheduler`→原Provider与账本。没有第二执行器；原fast模型/推理快照、授权和UNKNOWN规则不变。Room32、sidecar9、Pigeon164、MemoryState payload1或2无迁移。

`LearningMemoryStore.withExtractionSources`在Room事务内核对：

- 当前job完整记录、RUNNING/attempts/epoch与人工修订；来源ID顺序、归属、hash/revision/valid及删除墓碑。
- recoveryBlocked/未映射v0来源，当前真实会话owner/lifecycle/删除标记。
- 当前选中节点中的本人消息ID/角色/原文/附件描述/记录时间；不以来源表valid代替正文读回。抽取请求只含本人文本和附件存在性，不读取或发送附件字节。

抽取前、取得执行位后、每次物理accounting之前、回包后及候选提交均复查。请求钩子保留上游guard再查当前来源；拒绝发生在usage创建前。候选提交与最后来源检查共用Room事务，事务内不执行网络请求。失效来源/保护/撤销job变为或保持CANCELLED；普通抽取解析失败沿用原有有界失败处理，不能释放UNKNOWN或重放未知请求。

来源校验不锁网络：删除发生在已获准发送后，不能撤回已发送数据；后续请求与旧候选发布仍须拒绝。非目标title更新、正常新消息ID和已有手动记忆不因本批自动删除；模型返回成功不等于记忆保存成功。诊断不保存原模型返回或私有输入。

源码地图：Runner.process负责请求边界；Store.withExtractionSources负责可信Room读回及提交事务；MemoryExtractionSourceTest负责真实合成模型/辅助任务/持久读回反证。具体SHA、实际用例/计数、原失败、patch和范围见本批SOURCE_FINAL/TEST_RESULTS/HANDOFF。其他辅助入口、旧派生物库存和全备份语料仍需逐批处理；完整A02/A/P0、独立及全项目审查未由此完成。

回滚停自动抽取入口或逆本批精确差异；保库/key/墓碑/payload2/材料/receipt/UNKNOWN，不允许旧程序恢复未经校验的外发。
