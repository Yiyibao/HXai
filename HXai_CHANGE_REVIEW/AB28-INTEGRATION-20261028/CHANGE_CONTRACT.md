# CHANGE_CONTRACT — AB28-INTEGRATION-20261028

- 用户故事：继承并集成当前 A/B 线，交付可定位的本地集成分支、实际差异和验证；不重做已经接入的补丁。
- base/初始 HEAD：5d826d5d7d8755ed42db834d6cd6dc52ae914c69；初始分支 codex/b27-math-obligation-scoring-20261027。来源为当前未提交产品工作树、A27-SETTINGS-COMBINED、B27-B-LINE-CLOSURE、B27-MATH-OBLIGATION-SCORING 的精确源码封印，不把分支同名指针当作成果。
- 入口：Flutter/Pigeon → 原 ChatService/ConversationSession/GenerationLoop → 原唯一 Scheduler/TaskStore → 材料/数学/Provider → 持久 receipt → 真实 DTO/UI。离线数学评分保留独立 CLI，不冒充 App 模型评估。
- 合并策略：先冻结原始 status/diff 与源码 hash，核 before/candidate/current；已一致的改动不重复 apply。使用独立 codex/ab28-integration-20261028 feature 分支；保留原工作树全部未提交内容和历史证据。仅集成本地，不推送 main、不部署。
- 不变量：owner/profile/contextEpoch、授权 epoch/inputHash、模型接受快照、UNKNOWN、全局 slot/fence 和所有材料/墓碑不变。Room33/TaskStore13/Pigeon 及数学合同1–17/离线评分合同1无迁移。
- 副作用：本机 Git 分支/集成提交、离线构建及合成验证产物；不读真实密钥、不调用模型/生产、不上传私人材料。设备烟测仅使用有归属证明的合成模拟器，不碰用户实例。
- 可证伪完成条件：A27 最终产品源码、B27 领域与评分源码的字节均进入集成版本；共享文档只保留可解释的追加增量；不存在未解决源码冲突；从当前集成源码运行相关检查与真实入口烟测；提交/patch 与源码封印一致。
- 失败/恢复：hash 不符先定位真实差异，禁止强盖；构建/行为失败保留原日志，最小修复后另存复验。真实 Provider/OCR/手写/MinerU/WPS/Mate50/云端/正式签名及完整独立审查不因集成升级。
- 回滚：切换回原 feature 分支或逆集成提交；保留全部证据、材料/任务库/授权/UNKNOWN，不 reset/clean/stash，不降库，不重放副作用。
- 开发与定向反证为同一执行者；不启动子代理；独立/完整全项目审查 NOT_EVALUATED。
