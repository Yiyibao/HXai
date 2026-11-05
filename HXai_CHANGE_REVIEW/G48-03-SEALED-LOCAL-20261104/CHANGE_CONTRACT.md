# CHANGE_CONTRACT

FeatureID: G48-03-SEALED-LOCAL-20261104

用户故事: 密封题面里已经能被现有证书判定的三次含参讨论，不能因为写成区间而不是 `|x|>` 就被拒；不能被现有证书覆盖的交点、一致收敛和缺初值「同上」题，不能借文本交付变成成功。备份清单不能重新装入工具授权或凭据库。

入口: `TaskPlanner.accept` → `UncertifiedGoal.blocksTextSuccess`；`CubicParameterGoal.quoted`；`BackupManager.buildBackup` → `BackupExclusions.rejects`。

不改 schema、owner、epoch、模型快照或授权状态。不调用模型，不改 `HOSTED_KEYS`。

成功: 区间写法且半径为 `sqrt(a)` 的正确三次讨论被接受；颠倒外侧/中间的区间写法被拒绝；三则密封题面被 `blocksTextSuccess` 拦住；备份键名含 grant/credential 库时 `rejects` 为真。

失败: 半径缺失、区间颠倒、或把交点/一致收敛/缺初值题面写成聊天成功。备份排除只在写入清单前拒绝键名，不证明 zip 从未包含 `noBackupFilesDir` 以外的路径。

回滚: 恢复 `CubicParameterGoal.quoted` 的 `|x|>` 窗口匹配，去掉 `BackupExclusions` 调用。不要重跑 live cohort。
