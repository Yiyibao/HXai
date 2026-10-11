# 收藏档案隔离检查

日期：2026-10-06。

`python qa/s1/test_favorite_profiles.py`：5 项通过。执行生产 FavoriteDAO SQL，覆盖同助手之外的会话归属、未知档案、按引用读取、跨档案删除、错误节点组合、节点或会话缺失，以及会话删除后禁止重新访问。

`python qa/s1/test_profile_queries.py`：已有 4 项历史/搜索查询回归通过。

源码检查：收藏恢复使用 Room 事务核验节点归属及已有 ID；仓库验证序列化引用与索引引用一致。Flow 切换档案时发出空列表，再读取新档案。

最终构建日志：`.local/s1-favorite-final-build.log`，`:app:assembleDebug` BUILD SUCCESSFUL in 36s，265 个任务（13 executed、252 up-to-date）。Room/KSP 及 Kotlin 编译通过；有既有 ExperimentalNavigation3Api opt-in 标记未解析警告。临时 Firebase 占位配置已移除。相关源码 `git diff --check` 通过。

这些宿主 SQLite 检查验证生产 SQL 的边界，不是 Android 页面两人切换实测，也不验证 Room 事务并发、教学质量或 Mate 50 行为。档案切换 UI、运行中的会话、Web、文件夹元数据和材料等仍需后续检查。
