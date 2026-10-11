# 智能 Chat 连续阅读优化

日期：2026-10-10。

本轮根据现有搜索、图片、数学绘图、交互界面的实现，优化消息列表的连续阅读体验。业务入口已接入 `ChatTimeline`。

## 改动

- 流式更新按布局帧合并定位，不再为每次消息更新重复启动滚动动画。延迟图片、卡片高度变化和键盘导致的消息区尺寸变化均重新检查底部位置。
- 用户上滑阅读历史时暂停跟随；新消息及布局变化保留当前阅读位置。拖动中的用户操作优先，卡片内部滚动不改变外层跟随状态。
- 点击“回到最新消息”恢复跟随；按钮位于消息区右下角，随输入框高度变化保持在输入框上方，增加提示与无障碍标签。
- 保留关闭自动跟随的设置：仍可手动返回最新位置，后续内容不会自动跟随。
- 滚动列表拖动可收起键盘。以档案、会话、context epoch、会话选择版本作为列表身份，切换后重置滚动与历史阅读状态，过期布局回调退出。

## 验证

- Flutter 全量 **183 项通过**：[日志](flutter-tests.log)。
- 新增阅读专项 **10 项通过**：[日志](reading-tests.log)。覆盖长会话的惰性列表、流式增长与延迟布局、历史阅读、手动返回、可视区域变化、设置关闭、身份切换、组件销毁、嵌套滚动、拖动期间更新。
- Flutter analyze：**0 error / 0 warning，81 条 info**；新 `chat_timeline.dart` 与专项测试无分析提示：[日志](flutter-analyze.log)。
- Flutter Debug AAR 与 Android Debug APK 构建成功：[模块日志](flutter-build.log)、[Android 日志](android-build.log)。
- ARM64 / x86_64 APK 各 **32 个 Flutter 资产**与当前 AAR 一致：[ARM64](apk-arm64-verification.json)、[x86_64](apk-x86-verification.json)。

## 安装包与范围

- [ARM64 Debug APK（Mate 50）](../../client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)
- [x86_64 Debug APK](../../client/app/build/outputs/apk/debug/app-x86_64-debug.apk)

本轮验证为 Flutter 自动化与 Android 构建、资产核验。未执行真机触摸体验验收或真实模型请求，键盘场景通过改变消息区可视高度验证布局行为。
