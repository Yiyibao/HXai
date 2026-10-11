# 学习者档案管理验证

日期：2026-10-06。环境：无 GMS AOSP API 34 x86_64 模拟器，org.mathstudy.agent.debug。所有 QA 名称和会话均为合成测试数据。

## 自动检查

- `python qa/s1/test_profile_queries.py`：4 项通过。
- `python qa/s1/test_favorite_profiles.py`：5 项通过。
- `python qa/s1/test_folder_profiles.py`：4 项通过，包括两种开发列名迁移保留归属值、旧文件夹归入默认档案、跨档案读取/删除/重命名拒绝及删除只清除所属会话。
- 最终 APK 与 Kotlin 验证：`.local/s1-profile-ui-final-validation.log`，BUILD SUCCESSFUL in 1m 21s，282 个任务（17 executed、265 up-to-date）。ChatServiceTest 8 项、LearnerProfileCompatibilityTest 1 项，0 failure/error/skipped。后者检查旧 nickname 读取、重命名后 ID 和禁用的记忆偏好保留。
- 补充会话生命周期回归：`.local/s1-profile-runtime-tests.log`，BUILD SUCCESSFUL in 25s。上述两个测试类加 ConversationSessionTest 15 项、ConversationSessionManagerTest 6 项，共 30 项，0 failure/error/skipped；覆盖取消后已收到内容保存、会话保活与回收、队列及生成生命周期。连同宿主 SQL 检查共 43 项通过。

## Android 实际操作

1. 覆盖安装遇到已有开发库列名与当前 schema 不同，启动失败；诊断日志 `.local/s1-profile-upgrade-crash.log`，原库快照 `.local/s1-pre-upgrade-rikka_hub.sqlite`。没有清除应用数据。
2. 在原 26 版库增加合成 `QA-Legacy-A` 会话与 `QA-Default-folder` 文件夹后，安装修复 APK。真实 Room 迁移至 27；旧会话 ID、标题及 default-learner 归属保留，文件夹可见。
3. 从侧栏创建 `QA-New-C`；切换后进入空白会话。侧栏显示 No conversations，不显示默认档案的 QA 会话和文件夹。
4. 重命名为 `QA-Renamed-C`；再次覆盖安装最终 APK、force-stop 后冷启动，当前选择与重命名保留，仍不显示默认档案历史和文件夹。
5. 切回默认档案，原 QA 会话和文件夹重新显示；期间 crash buffer 为空。最终源码差异空白检查通过，临时 Firebase 占位配置移除。

界面截图：[档案选择与重命名](profile-picker-20261006.png)、[默认档案旧数据](profile-default-20261006.png)。截图已查看，名称、按钮与操作说明可读。

## 局限

未在 Android 界面配置真实 Responses 请求并进行流式回复中切换、待工具审批切换、进程中断恢复或网络故障验证；底层会话回归不能替代这些端到端项目。没有 Mate 50 证据。旧库实际迁移为 learner_profile_id 变体；标准 profile_id 变体的保留检查目前在宿主 SQLite 中通过，尚未另建 Android 26 版标准快照测试。

S1 最小档案暂存在 DataStore，没有宣告完整 learner_profile 合同、独立凭据、同机认证或备份恢复通过。上游全局头像、助手与 Provider 设置仍共享；材料、成果与 Web 的完整隔离继续审查。
