# 智能 UI 优化与验证

日期：2026-10-10（Asia/Taipei）。本轮在现有 Flutter + Kotlin 架构上完成优化，未重建业务系统。

## 已完成

- 统一 `render_interactive_ui` 与旧 `create_interactive_surface` 工具结果的展示和状态合同。旧引用结果通过所属工具 ID 与定义摘要恢复，避免把随机 surface ID / 数值版本发到要求工具 ID / SHA-256 版本的接口。旧界面仍可保存、重开及提交续聊。
- 智能消息统一使用桥接客户端与失败校验。加载失败保留错误并禁用交互，不再伪装成默认状态；失败命令即使缺少 error 字段也会报失败。
- 界面身份包含档案、会话、消息、工具、定义版本和 context epoch。定义更新重新加载；过期加载结果不能覆盖新界面。排队保存捕获原回调及状态快照，避免切换界面后把修改写入新会话。
- 控件修改显示待保存、保存中、同步完成和提交状态。延迟保存串行执行，旧保存结果不能清除后续修改的待保存状态。离开组件或进入后台时补存尚在延迟窗口内的合法修改；失败保留当前值并可重试。
- 展开界面不再允许嵌套展开，关闭前保存并等待完成，返回后同步正文控件。提交失败重试复用相同请求 ID，后端按既有幂等机制避免重复用户消息。
- 卡片改为轻边框、明确的标题与状态层次；主按钮使用清晰的白色文字。指标面板与表头适应浅色/深色背景。长表格限制展示高度并支持横向、纵向滚动。图表增加高度，单点居中、边缘柱形留白，极大/极小数值使用短科学计数格式。

## 验证结果

| 验证 | 结果 | 证据 |
|---|---|---|
| Flutter 全量测试 | 94 项通过 | [flutter-tests.log](flutter-tests.log) |
| Flutter 静态分析 | 无 error / warning；47 条 info 级代码风格提示 | [flutter-analyze.log](flutter-analyze.log) |
| Kotlin 定向单元测试 | 8 项通过 | 两份 TEST XML；[构建日志](android-build-tests.log) |
| Android 模拟器桥接/真实 Flutter 操作 | 4 项通过 | [android-runtime.log](android-runtime.log) |
| 最终 Android APK 构建 | 通过 | [android-final-build.log](android-final-build.log) |
| APK 与当前 Flutter AAR 一致性 | 32 个资产全部一致 | [apk-verification.json](apk-verification.json) |

前端回归覆盖加载失败/恢复、保存失败/重试、慢保存与后续编辑、销毁前补存、过期加载、身份切换、展开保存关闭，以及窄屏/大字号/深色主题下的 200 行表格。

Android 验证覆盖：保存并重建 facade 后恢复；过期版本、越界状态及旧分支拒绝；图表选点持久化；旧引用结果兼容；重复提交只产生一次用户消息。实际 Flutter 验证从默认 2 恢复已保存的 7，通过无障碍滑块操作改为 7.45，关闭展开界面后仍为 7.45，提交消息携带完全相同的状态并完成一次模型服务请求。

截图已逐张检查：[展开界面](smart-ui-expanded.png)、[正文界面](smart-ui-inline.png)。样例数据为合成预算数据，图表作为独立展示组件，未声明与人数滑块联动。

## 交付范围

模拟器安装包：`client/app/build/outputs/apk/debug/app-x86_64-debug.apk`。
Android ARM64 安装包：`client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。

本轮模型续行使用本地模拟模型服务，验证真实应用、桥接、持久化及模型请求链路；未以此宣称真实模型的选工具成功率或多供应商兼容性。未执行实体手机验收。延迟窗口内修改的后台/离开补存属于正常生命周期处理，不能保证操作系统突然强杀前尚未落盘的修改被保存。
