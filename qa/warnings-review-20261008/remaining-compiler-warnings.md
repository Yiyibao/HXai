# 剩余编译器警告逐项记录

源证据：final-compiler-full.log 强制重新编译全部依赖及 app/AndroidTest。最后日志隐私修改的 ContextUtil 诊断以 final-privacy-build-tests-lint.log 的实际重编译覆盖；其他警告源文件没有后续改动。

## 1. 旧消息序列化兼容

位置：`client/ai/src/main/java/me/rerere/ai/ui/Message.kt:242:30`

原始诊断：'data class ToolCall : UIMessagePart' is deprecated. Use UIMessagePart.Tool instead.

影响：当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。

保留原因：弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。

已做检查：核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。

所需条件：提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。

## 2. 旧消息序列化兼容

位置：`client/ai/src/main/java/me/rerere/ai/ui/Message.kt:243:30`

原始诊断：'data class ToolResult : UIMessagePart' is deprecated. Use UIMessagePart.Tool instead.

影响：当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。

保留原因：弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。

已做检查：核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。

所需条件：提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。

## 3. 旧消息序列化兼容

位置：`client/ai/src/main/java/me/rerere/ai/ui/Message.kt:244:30`

原始诊断：'data object Search : UIMessagePart' is deprecated. Deprecated.

影响：当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。

保留原因：弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。

已做检查：核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。

所需条件：提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。

## 4. 旧消息序列化兼容

位置：`client/ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt:144:26`

原始诊断：'data class ToolCall : UIMessagePart' is deprecated. Use UIMessagePart.Tool instead.

影响：当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。

保留原因：弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。

已做检查：核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。

所需条件：提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。

## 5. 旧消息序列化兼容

位置：`client/ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt:144:37`

原始诊断：'data class ToolCall : UIMessagePart' is deprecated. Use UIMessagePart.Tool instead.

影响：当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。

保留原因：弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。

已做检查：核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。

所需条件：提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。

## 6. 旧消息序列化兼容

位置：`client/ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt:145:20`

原始诊断：'constructor(toolCallId: String, toolName: String, arguments: String, approvalState: ToolApprovalState = ..., executionState: ToolExecutionState = ..., metadata: JsonObject? = ...): UIMessagePart.ToolCall' is deprecated. Use UIMessagePart.Tool instead.

影响：当前兼容历史数据；移除旧分支可能使旧会话/备份不可读取。警告本身未证明当前数据损坏。

保留原因：弃用类型用于读取历史 ToolCall/ToolResult/Search 消息；直接删除分支可能损坏旧会话和备份。

已做检查：核对类型 serialName、旧分支及向 UIMessagePart.Tool 的转换；198 项 ai 单元测试通过。

所需条件：提供版本化旧数据迁移及含旧工具消息的跨版本备份/恢复样例，再移除兼容分支。

## 7. Coil 实验性缓存 API

位置：`client/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt:182:33`

原始诊断：This declaration needs opt-in. Its usage should be marked with '@coil3.annotation.ExperimentalCoilApi' or '@OptIn(coil3.annotation.ExperimentalCoilApi::class)'

影响：缓存策略接口可能不兼容变更；去掉策略会影响网络缓存，当前未确认因此导致缺陷。

保留原因：CacheControlCacheStrategy 提供现有网络缓存合同；项目依赖版本将其标为实验性。去掉策略会改变缓存/请求行为，添加 opt-in 仅确认风险而非修复，因此没有为清零添加注解。

已做检查：核对 RouteActivity ImageLoader 的缓存配置与依赖版本；APK 构建及图片视觉链路结果见主报告。

所需条件：稳定版提供同等 Cache-Control 行为，或有明确缓存合同及请求/离线回归后单独迁移。

## 8. Coil 实验性缓存 API

位置：`client/app/src/main/java/me/rerere/rikkahub/RouteActivity.kt:184:55`

原始诊断：This declaration needs opt-in. Its usage should be marked with '@coil3.annotation.ExperimentalCoilApi' or '@OptIn(coil3.annotation.ExperimentalCoilApi::class)'

影响：缓存策略接口可能不兼容变更；去掉策略会影响网络缓存，当前未确认因此导致缺陷。

保留原因：CacheControlCacheStrategy 提供现有网络缓存合同；项目依赖版本将其标为实验性。去掉策略会改变缓存/请求行为，添加 opt-in 仅确认风险而非修复，因此没有为清零添加注解。

已做检查：核对 RouteActivity ImageLoader 的缓存配置与依赖版本；APK 构建及图片视觉链路结果见主报告。

所需条件：稳定版提供同等 Cache-Control 行为，或有明确缓存合同及请求/离线回归后单独迁移。

## 9. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/AssistantPicker.kt:218:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 10. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:253:72`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 11. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:255:45`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 12. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:255:56`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 13. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:260:41`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 14. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:260:48`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 15. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:260:62`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 16. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:260:69`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 17. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:261:58`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 18. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:261:75`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 19. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:262:53`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 20. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:262:70`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 21. Haze 实验性视觉 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt:264:41`

原始诊断：Experimental Haze API

影响：视觉接口可能变更；当前绘制路径可运行，但不能保证未来版本或全部设备效果。

保留原因：聊天输入框的 progressive/blur 样式仍依赖当前 Haze 的实验接口；删除效果属于功能变化，opt-in 也不会使接口稳定。

已做检查：核对 ChatInput 模糊启用条件及相关样式；实际导航、聊天与大字体检查见模拟器日志。

所需条件：依赖提供稳定等价接口，或经批准的视觉迁移并验证滚动/键盘/减少动画下的绘制。

## 22. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ExtensionContent.kt:48:13`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 23. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ExtensionContent.kt:82:13`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 24. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ExtensionContent.kt:125:13`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 25. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ExtensionContent.kt:166:13`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 26. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt:194:9`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 27. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt:224:9`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 28. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt:329:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 29. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/McpPicker.kt:178:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 30. 协程 Flow 预览 API

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt:678:14`

原始诊断：This declaration is in a preview state and can be changed in a backwards-incompatible manner with a best-effort migration. Its usage should be marked with '@kotlinx.coroutines.FlowPreview' or '@OptIn(kotlinx.coroutines.FlowPreview::class)' if you accept the drawback of relying on preview API

影响：搜索节流 API 未来可能变更；错误替代会影响快速输入、取消和更新顺序。

保留原因：模型列表搜索使用 debounce 的既有节流行为；手写替代会改变取消和调度语义，标记 opt-in 不能消除 API 稳定性风险。

已做检查：核对 ModelList 的 debounce 与检索消费端；模型选择器页面测试通过。

所需条件：稳定等价 debounce API，或经过虚拟时钟、取消、快速输入与档案切换测试的局部替代。

## 31. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceCwdPicker.kt:127:21`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 32. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceSelectSheet.kt:90:13`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 33. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceSelectSheet.kt:120:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 34. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt:117:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 35. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/UpdateCard.kt:167:29`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 36. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt:140:21`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 37. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt:166:21`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 38. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt:185:25`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 39. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt:199:25`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 40. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsPage.kt:403:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 41. 终端软键盘旧常量

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSession.kt:266:71`

原始诊断：'static field SHOW_IMPLICIT: Int' is deprecated. Deprecated in Java.

影响：未来输入法显示行为可能不同；终端当前未开放，不作为已支持的学习功能。

保留原因：上游终端仍兼容 API 26；旧常量表示用户间接触发的输入法显示。无条件改为新的输入法合同可能改变键盘行为；共享终端当前未开放。

已做检查：核对 WorkspaceTerminalSession 的调用位置及入口关闭状态；没有声称真实终端/各输入法回归通过。

所需条件：终端开放前在 API 26 与目标版本、多种 IME 下验证等价 WindowInsets 控制迁移。

## 42. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryPage.kt:236:9`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 43. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingThemePage.kt:323:5`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 44. Material3 列表 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/share/handler/ShareHandlerPage.kt:98:21`

原始诊断：'fun ListItem(headlineContent: ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: ComposableFunction0<Unit>? = ..., supportingContent: ComposableFunction0<Unit>? = ..., leadingContent: ComposableFunction0<Unit>? = ..., trailingContent: ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

影响：未来依赖移除旧 API 会影响编译；当前迁移可能改变布局、交互状态和无障碍语义。

保留原因：新版采用 InteractiveListItem、状态化颜色/形状/高度；旧版 Surface、槽位装饰和 mergeDescendants 语义不是简单同名参数映射。批量替换可能改变设置/选择器布局与无障碍语义。

已做检查：已下载当前精确版本 1.5.0-alpha29 官方源包，对比两种实现，证据 material3-listitem-source.txt；当前页面回归保留。

所需条件：逐页迁移并验证禁用/选中状态、触摸目标、布局和 TalkBack 合并语义，避免借迁移删除设置功能。

## 45. UsageStats AppOps API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/utils/ContextUtil.kt:85:16`

原始诊断：'fun unsafeCheckOpNoThrow(p0: String, p1: Int, p2: String): Int' is deprecated. Deprecated in Java.

影响：未来权限查询 API 行为可能变化，可能误判特殊权限；本轮没有旧版真机授予/撤销证据。

保留原因：API 29 起查询 OPSTR_GET_USAGE_STATS 的实际授权状态，旧版本另有 checkOpNoThrow 分支；替代 note/start 系列会增加访问记录或操作语义。

已做检查：核对 API 分支、UID/package 参数及特殊权限设置入口；未声称 API 26 真机授予/撤销实测通过。

所需条件：明确新 API 的仅查询合同，并在权限拒绝/授予/撤销及多 Android 版本验证后迁移。

## 46. Wi-Fi 连接信息 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/web/NsdServiceRegistrar.kt:118:41`

原始诊断：'val connectionInfo: WifiInfo!' is deprecated. Deprecated in Java.

影响：未来网络查询受限或返回非预期地址；Web 当前未开放，开放前必须重新验证网络生命周期。

保留原因：当前查询用于本机 Web 服务局域网地址。新版 NetworkCapabilities/LinkProperties 需处理 VPN、蜂窝/Wi-Fi、多网络和回调生命周期，单纯换 getter 不能保证地址同义；Web 服务未开放。

已做检查：核对 NsdServiceRegistrar 的 IPv4 转换和后备地址；没有开启服务或改变用户连接配置。

所需条件：Web 服务完成档案边界后，以有线/Wi-Fi/VPN/断网与权限拒绝样例验证网络地址和 NSD 生命周期迁移。

## 47. Wi-Fi 连接信息 API 弃用

位置：`client/app/src/main/java/me/rerere/rikkahub/web/NsdServiceRegistrar.kt:119:35`

原始诊断：'val ipAddress: Int' is deprecated. Deprecated in Java.

影响：未来网络查询受限或返回非预期地址；Web 当前未开放，开放前必须重新验证网络生命周期。

保留原因：当前查询用于本机 Web 服务局域网地址。新版 NetworkCapabilities/LinkProperties 需处理 VPN、蜂窝/Wi-Fi、多网络和回调生命周期，单纯换 getter 不能保证地址同义；Web 服务未开放。

已做检查：核对 NsdServiceRegistrar 的 IPv4 转换和后备地址；没有开启服务或改变用户连接配置。

所需条件：Web 服务完成档案边界后，以有线/Wi-Fi/VPN/断网与权限拒绝样例验证网络地址和 NSD 生命周期迁移。

## 48. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/BackupPageTest.kt:21:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 49. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/DiagnosticsPageTest.kt:21:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 50. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/DocumentServicePageTest.kt:20:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 51. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/FrontendDeliveryRoutesTest.kt:28:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 52. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/FrontendModelPickerTest.kt:19:29`

原始诊断：'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 53. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/LearningMemoryPageTest.kt:30:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 54. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/LegacyMemoryEntryTest.kt:17:29`

原始诊断：'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 55. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/MaterialDeliveryTest.kt:23:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 56. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/MaterialPageTest.kt:22:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 57. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/MaterialPaginationTest.kt:22:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 58. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/ProjectReviewAuditTest.kt:31:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 59. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/ProjectReviewDeliveryTest.kt:23:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 60. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/StudyNavigationTest.kt:13:29`

原始诊断：'fun <reified A : ComponentActivity> createAndroidComposeRule(effectContext: CoroutineContext = ...): AndroidComposeTestRule<ActivityScenarioRule<A>, A>' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。

## 61. Compose 仪器测试 API 弃用

位置：`client/app/src/androidTest/java/me/rerere/rikkahub/service/WarningFixSliderTest.kt:21:29`

原始诊断：'fun createComposeRule(effectContext: CoroutineContext = ...): ComposeContentTestRule' is deprecated. Use `androidx.compose.ui.test.junit4.v2.createComposeRule` instead. The v2 APIs use StandardTestDispatcher instead of UnconfinedTestDispatcher, which aligns with standard coroutine behavior by queuing tasks rather than executing them immediately. Tests relying on immediate execution may require explicit synchronization. Please refer to the migration guide for more details.

影响：影响未来测试编译和调度器同步；不直接影响发布应用。未经验证的迁移可能改变测试可信度。

保留原因：v2 从 UnconfinedTestDispatcher 改为 StandardTestDispatcher；迁移会改变实际页面测试的任务队列和空闲判定。当前保留断言，避免未经整套同步验证修改测试行为。

已做检查：编译器消息说明调度器变化；本轮实跑此文件所属测试，具体通过/失败/跳过见 test-summary.json，不把仅能编译当作通过。

所需条件：按文件迁移 v2，检查 runOnIdle/waitUntil、ActivityScenario 与系统窗口交互，并完整实跑原断言。
