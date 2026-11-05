# G48 逐项裁决

HEAD `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`，分支 `codex/storage-lifecycle-20261103`，改动未提交。独立审查全部 `NOT_EVALUATED`。不给完整 App 或生产放行。

| ID | 状态 | 证据 | 裁决 | 下一条 |
|---|---|---|---|---|
| G48-00 | PARTIAL | 快照和 `SOURCE_SHA256.txt` 已写。API26 `HostAcceptanceRuntimeTest` 9/9 PASS，8.541s | CONDITIONAL | Flutter analyze/test 与 unsigned Release 未在本批重跑 |
| G48-01 | WIRED | 基线 5 失败，修复后 JVM 25/25；API26 宿主全类 9/9 | PASS_SCOPED | 不是 sealed 真模型 |
| G48-02 | WIRED | 前台 grant/revoke 和代次复核已接线；JVM 准入谓词通过 | PASS_SCOPED | 仪器测试授予→调用→撤销仍 NOT_RUN |
| G48-03 | PARTIAL | 旧 37 题不改。新 cohort 无授权，NOT_RUN。等待状态不再被新运行标成 FAIL | CONDITIONAL | 不重跑 `run_live.py`。圆锥交点和一致收敛仍无证书 |
| G48-04 | PARTIAL | 资料链已有。三则 WPS 视觉失败未用同输入复现 | NOT_EVALUATED | 独占自有 WPS 实例后再跑 `scripts/verify_b_wps.ps1` |
| G48-05 | PARTIAL | 五页规格仍在。没有密封 PDF/真值，不能授手写质量 | NOT_EVALUATED | 补许可明确的真值后再评分 |
| G48-06 | PARTIAL | `HOSTED_KEYS` 仍是 ABSENT。没有把标签改成可用 | NOT_EVALUATED | 没有托管授权前不部署、不改标志 |
| G48-07 | PARTIAL | 用量三态显示已有。本机 estimated 写入未改 | NOT_EVALUATED | 不把任意非空 token 计数改记为 reported 之前先复现 |
| G48-08 | PARTIAL | 未做 unsigned Release、API34 和 Mate50 | CONDITIONAL | 正式签名、合并、分发、部署不执行 |

产品总体仍是 CONDITIONAL。S0 未在本批新复现。G48-01 的假成功已在 JVM 复现并拒绝。
