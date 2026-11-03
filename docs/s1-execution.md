# S1 执行记录

开始：2026-10-06。用户明确继续 Android 开发；按 v0.4 方案的开发门禁推进。目标 Mate 50 验收保留到交付前，未宣告完整 S0 或完整 S1 通过。

## 第一批：独立客户端与教学默认值

- applicationId 改为 org.mathstudy.agent，Debug 为 org.mathstudy.agent.debug；内部 Kotlin namespace 保留 me.rerere.rikkahub，减少源码重构。
- 应用名在已有七种资源配置中统一为 MathStudyAgent；开发版本 0.1.0-dev、versionCode=1。Release 签名仍未配置，不作发布。
- 新增 StudyTutorDefaults，默认助手为 MathStudyAgent：每轮一个卡点、可按用户要求完整讲解、关键符号核对、第一处实质缺口、证据分级、真实保存/导出反馈边界。它是初始教学规则，不等于三个教学 Skills 的迁移和验收。
- 仅调整首次安装默认值；读取已有配置时不主动覆盖人工编辑的助手。不会自动添加接口密钥或预设已验证的模型连接。
- 上游 APK 更新查询改为 Idle，不请求上游更新源；保留旧调用接口并拒绝下载更新，避免安装上游包的入口继续生效。本项目的更新分发方案尚未实现。
- 公开占位 Firebase 配置增加本项目两种包名，保留固定上游包名映射。仅编译探针临时复制，结束后移除；真实遥测精简与凭据保护仍待处理。

## 验证

业务构建日志：.local/s1-build-20261006.log，BUILD SUCCESSFUL in 1m 55s，269 个任务（32 执行、237 up-to-date）。arm64/x86_64/universal 三份 APK 均经签名校验。

AOSP API 34 x86_64 模拟器安装成功，与固定上游 Debug 包共存；首次冷启动 4521 ms，force-stop 后重开 4284 ms，无 crash buffer 记录。默认助手名称和教学提示在真实页面中核对通过。见 [首批证据](../qa/s1/first-client-20261006.json)，截图 .local/s1-defaults.png。未在 Android 界面发送真实模型请求，也未验证本批在 Mate 50 的行为。

占位 Firebase 配置已移除。默认提示通过页面检查不等于教学质量通过；首次助手不等于 LearnerProfile 档案功能。

已取得的 S0 基础证据见 s0-execution.md；它们不能直接标为本批 APK 的验证。

## 尚未完成的 S1 内容

历史/搜索/工具/备份的完整档案访问隔离；模式切换、凭据 Keystore、日志脱敏、取消/中断与恢复、公式和文件实际显示；Android 真实中转 UI 往返。自动抽取和成果导出在 S2–S4，不把上游工具能力当作项目交付。

当前图标及大部分界面仍来自许可保留的上游基线，原创视觉方向尚未实现；未使用 Coucou 角色或素材。应用标识分离不代表两个学习者档案隔离完成。

## 第二批：四主入口与会话档案归属

当前工作树最初仅含 S0 提交，本批先同步主目录未提交的第一批代码和进度资料，主目录保持原样。

- 聊天页接入聊天 / 材料 / 记忆 / 成果四入口。后三页提供所属档案与诚实空态，回到聊天可使用原附件流程；尚未实现材料库、学习记忆管理和成果导出。
- 新增代码绘制的静态折页书签伙伴“页芽”，带无障碍说明；没有动画和操作完成表演。名称与造型仍是开发稿。
- LearnerProfile 独立于 Assistant，默认一个档案，可创建第二人并切换；昵称必填、最多 40 字。档案列表与当前选择写入已有 DataStore，并随 Settings 备份序列化。
- 会话保存 learnerProfileId；Room 25→26 自动迁移为全部旧会话填写 default-learner。完整读取、分页摘要、序列化、分支创建均保留归属。
- 新会话固定创建时的档案；切换只改变下一会话的默认身份。当前会话显示自己的所属档案，进行中回答与当前输入不会因切换而换人。仓储更新拒绝修改已有会话的档案 ID。
- 本批交付归属基础，**不是完整访问隔离**：上游历史、搜索、工具与记忆仍未全链路按档案过滤，不能据此宣称两人数据已完全隔离。下一批优先补这些边界，再接自动记忆。

验证入口：LearnerOwnershipTest（旧 JSON 兼容、切换与分支继承、Settings/会话序列化、空昵称拒绝）；qa/s1/test_learner_migration.py（实际执行 Room 生成迁移 SQL，验证旧正文保全和所属查询）。Android 模拟器、构建和测试结果见本批 qa/s1 记录；Mate 50 验收仍待完成。

本批结果：[验收证据](../qa/s1/second-batch-20261006.json)。`:app:testDebugUnitTest --tests '*LearnerOwnershipTest' :app:assembleDebug` 成功（3m 59s、286 任务），4 项测试零失败；迁移 SQL 检查通过。x86_64 APK 签名验证、模拟器覆盖安装、四入口、第二档案创建、切换后原会话归属、新会话归属、force-stop 后档案与选择恢复均通过；crash buffer 为空。界面证据在当前工作树 `.local/s1-second-*.xml` 与 `.local/s1-second-chat.png`。没有发送真实模型请求，不宣称迟到的真实模型回答或华为后台行为已验证。占位 Firebase 配置已移除。
