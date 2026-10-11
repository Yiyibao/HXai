# 历史、搜索与工具档案隔离验证

日期：2026-10-06。

## 可重现检查

`python qa/s1/test_profile_queries.py`：4 项通过。使用生产 DAO SQL 和全文索引归属过滤，在宿主 SQLite 执行，涵盖：

- 旧会话新增列后归属 `default-learner`。
- 同一助手下两份档案各 60 个会话；12 种列表/检索/数量查询只返回指定档案。
- 三种按 ID 更新拒绝另一档案，同时允许所属档案更新。
- 全文搜索的全部助手范围仍按档案过滤；50 条上限、过期索引、未知档案的空结果。

此检查使用 SQLite FTS5 替换 Android jieba/simple 的分词与摘要函数，仅验证查询边界，不验证中文召回质量。数据库列迁移检查不代替 Android Room 升级实测。

Android 最终验证：`:app:assembleDebug :app:testDebugUnitTest --tests me.rerere.rikkahub.service.ChatServiceTest`，BUILD SUCCESSFUL in 1m 38s，286 个任务（30 executed、256 up-to-date）。ChatServiceTest 共 8 项，0 failure、0 error、0 skipped；其中会话分叉使用非默认档案，验证继承原归属。日志 `.local/s1-profile-final-validation.log`，JUnit 报告 `client/app/build/test-results/testDebugUnitTest/TEST-me.rerere.rikkahub.service.ChatServiceTest.xml`。Room 已生成 26.json schema 和 25→26 的新增列迁移代码。临时 Firebase 占位配置已移除。

最终编译后仅清理了未使用日志 import/常量、行尾空格及函数参数前空格，没有改变业务逻辑。相关源码 `git diff --check` 通过。

## 限制

暂无完整 LearnerProfile 表及创建/切换 UI；没有两人在 Android 页面中的实测证据。上游记忆和未有所属权限的共享设备/工作区/MCP 工具在聊天运行层停用，不代表这些功能已完成档案迁移。材料、成果、收藏、文件夹元数据、备份和所有 Web/运行入口仍需后续专项审查。Mate 50 未运行。
