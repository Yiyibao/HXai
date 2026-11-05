# 子代理审查发现

同一执行体系的子代理审查，不是独立外部审查。HEAD `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`，工作树脏。

## 已关闭

- 文本交付不会绕过 `acceptancePromotes`。
- 新凭据不清除暂停、设备确认或输入等待。

修复后复验：谓词单测 `BUILD SUCCESSFUL`。API26 `HostAcceptanceRuntimeTest` 在谓词收紧后 11 项里先有 1 个断言过时，改为“开放义务在问模型前拒绝”后，`conicIntersectionAndUniformConvergenceStayUnverified` 单独重跑 `OK (1 test)`。其余 10 项在同一轮已通过。

- 超时不释放 UNKNOWN。
- 旧 executor 不能发布。
- `HOSTED_KEYS` 仍是 `ABSENT`。
- 启用和 OAuth 不是逐工具授权。撤销、旧 id、旧代次不能再派发。授权 A 不能调用 B。备份键名排除 grant/凭据/任务库。前端错误不回显 token。
- 分号切开的三次区间正例、以及已有否定词覆盖的闭式，审查员标为已关闭。

## 已修

| ID | 严重度 | 问题 | 修复 |
|---|---|---|---|
| F1 | S1 | 题干复述、点值、事后收回仍被当成闭式；排除 `n<0` 的正确说明被拒 | `affirmativeFormulas` 跳过复述和点值，收回则清空；`domainAllows` 只拒绝未排除的扩域 |
| F2 | S1 | 同一句里方向词和区间标记共存即通过；否定的半径或 `2*sqrt(a)` 仍匹配 | 方向词只看自己的区间片段；半径必须是证书系数且未被否定 |
| F3 | S1 | 分号连接的正确区间被拒 | 按 `。` 和 `；` 切句 |
| F4 | S1 | 另一句的“不能”抵消几何结论肯定 | 几何极性按句判断 |
| F5 | S1 | 无证书的交点/一致收敛仍可走计划验收成功 | `blocksOpenObligation` 在 `accept` 入口拒绝 |
| F6 | S2 | “反例成立”被当成肯定原命题 | `成立` 的主语是反例时不计肯定 |
| UI-STOP-1 | S2 | 批取消标志单独被画成已取消 | 只有每个子任务都 `cancelConfirmed` 且状态为 `CANCELLED` 才显示已取消 |
| UI-STOP-2 | S2 | 远端返回 `SUCCEEDED` 也记 `cancelConfirmed` | 只有返回状态是 `CANCELLED` 才确认；卡片不再只看确认标志 |

## 未改

`SandboxMcpChatStoryTest` 仍 `NOT_RUN`。没有独立审查。没有提交。
