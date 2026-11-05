# 本地接续

旧 37 题工程分母不改，仍是 28 PASS / 9 FAIL。没有重跑 `run_live.py`，没有调用模型。

本批 JVM 已通过：密封区间写法的三次含参讨论可被 `CubicParameterGoal.quoted` 接受，颠倒区间被拒绝；椭圆/直线、一致收敛、缺初值「同上」题面不能借文本交付变成成功。`BackupExclusions` 拒绝 grant、凭据、任务库和 OCR 缓存键名。`flutter analyze --no-pub` 无问题。

圆锥参数交点和一致收敛仍没有新证书。缺密封真值时不授 OCR 质量。`HOSTED_KEYS` 仍是 `ABSENT`。

OCR 夹具 `b24-synthetic-scan-v1` 的非歧义页规范字符数是 43+33+17=93。这是夹具文本，不是密封 PDF 的识别分数。三则 WPS 视觉失败仍未用同输入复现。

下一步若继续本地工作：补 API26 上 `HostAcceptanceRuntimeTest` 的 grant/revoke 仪器测试。不要为了补分重跑 live cohort。
