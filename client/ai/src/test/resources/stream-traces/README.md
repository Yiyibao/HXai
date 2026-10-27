# Stream trace fixtures

每个目录保留 `events.jsonl`：每行一个序列化后的 `SseEvent`，记录在 HTTP 客户端完成 SSE
分帧之后、Provider 解码之前。不要记录 Authorization 等请求头。

`StreamTraceReplayTest` 离线回放原始事件，验证完成事件、并行工具的身份与参数、服务端工具
状态转换、图像格式和可续接的思考签名；不访问网络。不要用完整消息 JSON 快照固定模型措辞、
metadata 的内部布局或缺失用量的旧默认值。

用量回归验证部分更新不会保留陈旧总数、明确报告的零可以覆盖旧值，以及未知/溢出不能产生
已知合计。Android Room 与原生账本场景另行验证缺失字段保持 NULL，不能显示为免费调用。

新增轨迹须保留 Provider 返回的 `id`、`event` 和 `data`，并为消费者可见的协议行为添加
明确断言。Gemini 图像轨迹逐字比较 Provider 原始签名与解码后的 metadata，验证续接所需
签名未被流式转换修改；图像数据只保留在原始事件中。
