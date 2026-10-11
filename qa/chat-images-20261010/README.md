# Chat 图片展示修复（2026-10-10）

本轮修复回答中的图片展示基础链路，并已将新 APK 安装到 emulator-5554。

- 增加 display_image 工具，将已有真实图片 URL 返回为图片消息，并透传说明。
- Markdown 图片、独立图片消息、交互界面图片和全屏查看使用统一加载器，支持 HTTP(S)、本地文件和 Base64 光栅图片；失败可重试。
- 交互协议与两端校验增加 image(url, caption)；图片不产生交互状态。
- 新对话只注册已经接通状态保存与续聊的 render_interactive_ui，移除未接通的重复工具注册，更新配图引导。
- 补齐历史 InteractiveSurfaceRef 的桥接显示；其旧状态协议未在本轮迁移。

验证：Flutter 全量 84 项通过；Kotlin 相关 10 项通过；Android APK 构建通过。模拟器 instrumentation 通过 5 个真实聊天页面场景，检查解码后的图片像素：Markdown 网络图片、display_image 工具输出、Base64 经现有生成后存储转换的图片、本地图片、交互界面图片。截图 android-*.png 使用合成蓝色 PNG 作为测试图片，验证的是桥接与渲染，不代表模型自主生成或选择配图的质量。Flutter 单独验证了 Base64 直接显示及全屏解码。

全量回归中的旧附件隔离测试仍查找 attach_file 图标，已改为当前“添加文件”按钮的 tooltip；附件功能实现未改。

范围：不新增图像生成服务，不保证外站防盗链、鉴权或失效 URL 能加载；SVG/HTML 不属于这次光栅加载器支持范围。模型自动选择配图仍需真实模型对话评测。
