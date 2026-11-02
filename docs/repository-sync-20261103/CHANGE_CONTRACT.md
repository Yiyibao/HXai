# REPOSITORY-SYNC-20261103

用户故事：将根工作区当前源码提交并推送到远端 feature 分支，提供本地清理清单；不执行删除。

Base: 070f3d83c294ba0d4a2197d591055e75cff59ac3
来源分支: codex/r30-05-durable-controls-20261030
目标分支: codex/current-code-snapshot-20261103
远端查询: origin/HEAD 与 main 为 5d826d5d7d8755ed42db834d6cd6dc52ae914c69。

本批只封存既有改动和说明，不新增产品逻辑、schema、授权、owner/profile/epoch 或模型快照变更。既有未提交变更包含这些领域的历史实现，源码封存不代替其完整项目验收。

范围：根目录已跟踪改动、client/server/scripts 新源码及 docs 新协议；另纳入新 Office 回归测试直接依赖的 PUBLIC_SYNTHETIC_NATIVE_FIXTURES 最小文件集。
排除：.local、私有配置、APK/AAB、未审阅原始日志/数据库/截图、Pro 原始交付包、其他工作树改动。原件保留。

成功：提交后所选源码与 index blob 一致，push 成功且 ls-remote 完整 SHA 与本地 HEAD 一致。
失败/恢复：push 失败则保留本地 commit 和所有未提交文件；不 reset/clean/stash，不强推。
兼容/回滚：main 不动；通过新分支保留检查点。需回退产品时在后续分支 revert，不删除历史证据。

定向 Python 测试与差异/秘密模式检查仅作为同步前检查；完整 App、Android、Flutter、Release、本机其他工作树集成、独立审查与完整项目审查为 NOT_EVALUATED/NOT_RUN。
