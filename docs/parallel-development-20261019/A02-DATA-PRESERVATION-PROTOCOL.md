# A02 数据保全接续协议 v1.27（2026-10-20）

来源：`HXai_CHANGE_REVIEW/A02-A03-COMPLETION-20261020/CHANGE_CONTRACT.md`、`SCHEMA_CONTRACT.md` 与最终 SOURCE/TEST_RESULTS。本文规定接口，不预授未运行环境通过。

## 请求边界

GenerationLoop 把接受时原历史的投影及删除保护摘要绑定到主请求、流式请求、自动摘要、规划与最终语义验收；guard 一直保留到 Provider 的实际编码后 admission，先于 physical accounting。主结果和流式部件在发布前再次检查。title/suggestion/手动压缩保留各自角色模型与原子保存，并在实际请求 admission 复查来源。原翻译、OCR、自动抽取合同继续有效。

删除保护摘要只由本机持久来源约束计算。新增删除阻止旧请求继续发送；恢复不能消除主动撤权、manual pause、设备确认、缺输入或 UNKNOWN。history 工具从真实选中分支重读，不再把旧 FTS snippet/title 当来源；宿主绑定 `history_protection_key`，删除约束变化后无匹配标记的旧查询回执不得继续进入 Provider/语义验收。源消息、工具完整回执及原数据保留供本机管理。

## 派生元数据

Room **32→33** 只新增 `derived_metadata_evidence`，以 owner/conversationId/kind 为主键，绑定 lifecycle、输出摘要、便携 HMAC 来源标记及可信人工命名标志。生成标题/建议与来源清单在同一 Room 事务提交；网页、模型与工具描述不能写此表。删除约束存在时，无清单的旧元数据、失效来源、旧 lifecycle 或不匹配输出均不作为可信标题/建议输出到历史工具和 Flutter 查询。无来源清单的历史摘要不能认证新元数据。用户明确重新命名可登记人工标题。

保留原始聊天/元数据，不物理清除用户历史；旧人工标题因缺少来源记录也可能保守隐藏，可明确重新命名。合法新来源生成的标题/建议仍可用，其他 owner 独立。现有记忆 payload1/2、sidecar9、Pigeon164 均不迁移。

## 恢复

prepare 对迁移/路径重定位后的 payload、settings 与 replace-folders 形成 `prepared-inputs-v1.json`。confirm 和启动安装前核对完整路径/大小/SHA；只在可信删除合并后更新清单。缺库、缺设置、改变/新增文件或软链接拒绝，不能新建空库冒充成功。旧未带此清单的 pending staging 不自动安装，应从完整加密备份重新准备，原库保持。

恢复在确认和启动时都合并当前记忆删除约束、聊天删除记录；owner 冲突拒绝。删除聊天不因旧备份复活。恢复聊天使用递增 lifecycle 并保留排队输入的暂停状态，旧异步结果不得覆盖新 incarnation。任务 sidecar、授权、UNKNOWN 不由备份替换。安装 journal 的期望摘要支持真实进程退出后继续，未提交或异常状态不能当作完成。

合并后的同一 Room 事务还按真实恢复库检查每个记忆来源的聊天是否存在、是否属于原 owner；缺失/删除/跨 owner 来源通过原 MemoryReducer 失效，自动派生记录按既有规则转 SUPERSEDED 并保留原内容和历史。旧 valid=true 位不能绕过聊天墓碑；正常来源和独立手动记忆保留可用性。此校验在 confirm/startup 都执行，进程退出重入保持幂等。

## 验证与回滚

原版发送边界及备份复活、缺失 staging 反例分别保存；迁移、正常输入、新来源、跨 owner、完整备份与真实进程退出各自取证。准确命令/退出码/日志/源码与 APK 见批次交付。

回滚先停受影响入口，保 Room33/原32证据、记忆 key/墓碑、staging、任务/receipt/UNKNOWN；不能让 Room32 消费者直接打开未来库。全产品、真实 Provider/目标设备/生产及独立审查不由本协议授予通过。
