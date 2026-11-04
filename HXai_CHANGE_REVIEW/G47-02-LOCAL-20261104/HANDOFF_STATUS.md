# HANDOFF_STATUS

同一执行者。外部独立审查 NOT_EVALUATED。没有调用付费模型，没有部署生产，没有接管用户 Office。

| 任务 | 本批 | 结果 | 跳过原因 | 下一条 |
|---|---|---|---|---|
| G47-02 本地合同 | `pytest` 两个质量脚本 | PASS，12 项 | 无 | 已完成这一小段 |
| G47-02 真实 cohort | 未跑 | NOT_RUN | 任务书不授新的付费模型。固定模型是 `gpt-6.1-terra`，不能续跑旧 UNKNOWN | 有明确 endpoint 授权后再跑 `LiveQualityEvaluationTest`，先封存答案再评分 |
| G47-03 本机协议 | authority/cloud/tls/validation/documents | PASS_SCOPED | 合成回环，不是跨主机 | 见各 `verify_r29_*.py.result.json` |
| G47-03 跨主机 | 未跑 | NOT_RUN | 没有新的主机、证书和部署授权 | 先冻结主机/CA/进程用户，再跑错 CA、错主机名、过期证书 |
| G47-04 WPS | 未跑 | NOT_RUN | `C:\Program Files\Kingsoft\WPS Office` 与用户本地 Kingsoft 目录都不存在 | 安装并确认不接管已有实例后，按原 SHA 复现三类失败 |
| G47-04 Office 实机 | 未跑 | NOT_RUN | 本机有 Microsoft Office，但本批不关闭、不接管已有实例 | 用自有新文档对象跑 `scripts/verify_b_microsoft_office.py`，先读脚本副作用 |
| G47-05 新回答 | 未跑 | NOT_RUN | 依赖 G47-02 的锁模型真实回答 | 先有新封存回答，再逐义务评分；不改原 32 题分母 |
| G47-06 新语料 | 未跑 | BLOCKED | 没有新的已授权多页手写真值 | 先定语料合同和许可，不伪造学生样本 |
| G47-07 真实服务/账单 | 未跑 | NOT_RUN | 没有新的 MCP 服务、OAuth 服务或账单授权 | 先选最小已授权 HTTPS 服务；SSRF 只用合成 DNS |
