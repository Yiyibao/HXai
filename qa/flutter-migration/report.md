# MathStudyAgent Flutter + Kotlin 迁移验收与交付报告

- 日期：2026-10-09
- 目标平台：华为 Mate 50 / HarmonyOS 4.2.0 (Android APK, ARM64-v8a)
- 交付产物：完整宿主集成 Debug APK (非独立 Flutter demo)

---

## 1. 交付物基本信息与校验和

| 产物文件 | 绝对/相对路径 | 大小 (字节) | SHA256 校验和 |
|---|---|---|---|
| **ARM64 主包 (Mate 50 目标)** | `client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 144,098,679 | `C8699A2A13D6A6BEFDAF4972F6007DE11FE524DCF21D1C40A93601972175564A` |
| **通用包 (Universal)** | `client/app/build/outputs/apk/debug/app-universal-debug.apk` | 167,088,168 | `3CC5396B3B3733D30F38A1054F7273343A4143F88B339BFF3088CB58CC8F8A67` |
| **x86_64 模拟器包** | `client/app/build/outputs/apk/debug/app-x86_64-debug.apk` | 137,116,773 | `E8AC986CE6195C6EF9F9836365F5E97DDF85E2DF7D4D73D55A6CD2A6F0025D89` |

---

## 2. 锁定工具链与环境

- **JDK**: OpenJDK 21.0.6 (Zulu 21.40.17)
- **Flutter SDK**: 3.47.6 (Dart 3.4.3)，锁定在 `.local/flutter/`
- **Gradle**: 9.6.0，锁定在 `.local/gradle-9.6.0/`
- **Android Gradle Plugin (AGP)**: 9.4.0
- **Kotlin**: 2.1.20-Beta1 / 2.0.21
- **Android SDK**: Compile SDK 35, Min SDK 26, Target SDK 34, Build-Tools 35.0.0

---

## 3. 架构设计与桥接协议

### 3.1 架构分层
- **宿主层 (Android client/)**: 继续作为实际运行宿主，管理系统生命周期、Room 数据库、网络连接、权限与加密凭据。
- **UI 呈现层 (client/flutter_ui/)**: 作为 Flutter Module (Add-to-App)，通过本地 Maven 仓库依赖嵌入宿主工程。采用 Riverpod 管理状态，go_router 管理四主入口与子页面导航。
- **业务中枢 (Kotlin Facade)**: `StudyHostFacade.kt` 实现 Pigeon 生成的 `StudyHostApi`，将 25 个类型化命令无缝桥接到 `ChatService`、`ConversationRepository`、`LearnerProfile`、`LearningMemoryStore`、`MaterialFiles`、`ProviderManager`、`SettingsStore` 与 `Diagnostics`。
- **双向流通信**: 
  - 下行：`me.rerere.rikkahub/study_events` EventChannel 流式传输增量 chunk、node 状态变化、生成完成与档案切换事件。
  - 上行：Pigeon 类型化接口，保证命令的幂等性与参数校验。

### 3.2 档案隔离与上下文纪元 (Context Epoch)
- 切换档案时重置前端缓存与流式监听，由 Kotlin 端校验数据所属的真正 profileId，前端传递的 profileId 仅作为请求标识，不作为最终授权凭证。
- 每次档案切换递增 `contextEpoch`，丢弃过期的历史异步回调。

---

## 4. 关键交互与视觉规范

- **白粉视觉主题**: 严格遵照 accepted-chat.png 与 accepted-mascot.png 规范，主背景 `#FFFFFF`，浅粉卡片 `#FFF1F5`，浅粉边框 `#F8D5DE`，主玫瑰粉 `#925568`，文字色 `#27232A`。支持系统深色模式适配。
- **“页芽”吉祥物 (StudyMascot)**: 矢量绘制 9 种教学状态（`idle`, `thinking`, `happy`, `explaining`, `error`, `working`, `celebrating`, `warning`, `curious`），支持 `reduceMotion` 减弱动画。
- **聊天滚动机制 (Non-greedy Scrolling)**:
  - 用户向上翻阅时，流式输出自动暂停跟随滚动，保持阅读锚点不跳动。
  - 界面右下角浮现“回到最新”悬浮按钮，带有未读消息点提示，点击平滑滚回底部。
- **输入与组合输入法保护 (IME Composition Guard)**:
  - 软键盘弹起自适应避让 (`adjustResize`)。
  - 中文拼音合成过程中（未完成选词）不误触发发送。
  - 附件选择返回后完好保留当前输入框草稿。
- **数学与 Markdown 排版 (MathMarkdownView)**:
  - 支持 KaTeX 行内 (`$...$`) 与块级 (`$$...$$`, `\[...\]`) 公式。
  - 支持长公式与复杂矩阵局部横向滚动，不撑破屏幕宽度。
  - 流式生成过程中若遇未闭合的 TeX 标签，安全退回显示原文文本，不崩溃、不丢失消息正文。

---

## 5. 验收测试矩阵与执行结果

### 5.1 自动化测试结果
- **Flutter 代码静态分析 (`flutter analyze`)**: **0 错误、0 警告**
- **Flutter 单元与组件测试 (`flutter test`)**: **34/34 全部通过**

### 5.2 32 项 LaTeX / Markdown 固定用例（用例编号 C05）验收清单

| 编号 | 类别 | 测试内容 | 渲染与退回行为 | 结果 |
|---|---|---|---|---|
| 1 | 行内公式 | `$x^2 + y^2 = z^2$` | 正常渲染行内 LaTeX | PASS |
| 2 | 希腊字母 | `$\alpha + \beta = \gamma$` | 正常渲染希腊符号 | PASS |
| 3 | 分式 | `$\frac{a}{b} + \frac{c}{d}$` | 正常分式对齐 | PASS |
| 4 | 根号 | `$\sqrt{x^2 + 1}$` | 正常开方显示 | PASS |
| 5 | 块级公式 | `$$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$$` | 居中块级渲染 | PASS |
| 6 | 括号块级 | `\[\int_{0}^{\infty} e^{-x^2} dx = \frac{\sqrt{\pi}}{2}\]` | 块级定积分渲染 | PASS |
| 7 | aligned 多行 | `\begin{aligned} ... \end{aligned}` | 等号对齐多行公式 | PASS |
| 8 | pmatrix 矩阵 | `\begin{pmatrix} a & b \\ c & d \end{pmatrix}` | 圆括号 2x2 矩阵 | PASS |
| 9 | 行列式 | `\begin{vmatrix} 1 & 2 \\ 3 & 4 \end{vmatrix} = -2` | 竖线行列式 | PASS |
| 10 | cases 分段函数 | `f(x) = \begin{cases} ... \end{cases}` | 大括号分段条件 | PASS |
| 11 | 极限与求和 | `\lim_{n \to \infty} \sum_{k=1}^n \frac{1}{k^2}` | 上下标正常定位 | PASS |
| 12 | 矢量分析 | `\nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}` | 矢量算子与粗体 | PASS |
| 13 | 超长单行公式 | 10 项多项式求和公式 | 容器内支持横向单指滑动 | PASS |
| 14 | 连分式嵌套 | `\cfrac{1}{1 + \cfrac{1}{2 + ...}}` | 多层级连分式嵌套正常 | PASS |
| 15 | 中文混排行内 | `设函数 $f(x) = \sin(x)$，积分为 $2$。` | 中英文字距与行高平滑 | PASS |
| 16 | 中文混排块级 | 柯西不等式说明 + 块级公式 | 文本与公式卡片间距舒适 | PASS |
| 17 | 金额转义 | `这件商品售价 \$100 美元，打折后是 \$80 美元。` | 正常识别为美元符号，不误作公式 | PASS |
| 18 | 多金额未转义 | `收入为 $50 与 $75 之间` | 安全容错，作为普通文本显示 | PASS |
| 19 | 流式半截行内 | `正在计算：$x^2 + 2x +` (未闭合) | 安全退回原文，不报语法异常 | PASS |
| 20 | 流式半截块级 | `推导中：$$\int_0^1 f(x) dx = ` (未闭合) | 安全退回原文，保持流式输出 | PASS |
| 21 | 语法错误 TeX | `$$\frac{1}{$$` (括号不平衡) | 容错处理，展示原文或错误指示器 | PASS |
| 22 | 未知 TeX 宏 | `$$\unknownMacro{123}$$` | 错误边界捕获，正文完整保留 | PASS |
| 23 | Markdown 标题 | `# 标题一` / `## 标题二` / `### 标题三` | 视觉层级分明，字体大小阶梯 | PASS |
| 24 | 强调样式 | `**粗体** *斜体* ~~删除线~~` | 字体样式正常生效 | PASS |
| 25 | 列表 | 无序列表与有序数字列表 | 缩进与项目符号对齐 | PASS |
| 26 | 块级引用 | `> 这是一个引用说明` | 左侧粉色装饰条与浅背景 | PASS |
| 27 | 行内代码 | `` `python solve.py` `` | 等宽字体与圆角浅底色 | PASS |
| 28 | 代码块围栏 | ```` ```python ... ``` ```` | 代码高亮与背景框 | PASS |
| 29 | Markdown 表格 | 2x3 参数说明表 | 单元格线与表头高亮，横向适配 | PASS |
| 30 | 超链接 | `[数学维基](https://...)` | 玫瑰粉链接样式 | PASS |
| 31 | 教学推导复合体 | 步骤、中文、勾股定理公式综合 | 段落与公式逻辑清晰 | PASS |
| 32 | 超宽矩阵方程 | 3x5 权值矩阵乘法长公式 | 单独局部横向滚动，不破坏全局布局 | PASS |

### 5.3 功能门禁与宿主集成验收

| 检查项 | 验证内容 | 结果 | 备注 |
|---|---|---|---|
| **F0 工具链锁定** | Flutter 3.47.6 + Gradle 9.6.0 + AGP 9.4.0 兼容 | **PASS** | 锁定于 `.local/`，宿主成功编包 |
| **F1 宿主桥接** | Pigeon 25 项命令 + EventChannel 事件流往返 | **PASS** | `StudyHostFacade` 实现全覆盖 |
| **F1 视觉系统** | 白粉 Tokens、9 状态吉祥物、四主入口导航 | **PASS** | 主入口切换为 `StudyFlutterActivity` |
| **F2 聊天与数学** | 消息分支、工具调用、审批、停止、重试、锚点 | **PASS** | 32 个数学用例全部自动化验证通过 |
| **F3 材料管理** | 材料列表、状态、SHA256、原页符号核对弹窗 | **PASS** | 映射至 `MaterialFiles` |
| **F3 记忆画像** | 记忆条目展示、自动记忆开关、版本冲突、删除确认 | **PASS** | 映射至 `LearningMemoryStore` |
| **F3 成果管理** | 生成成果列表、原生查看、系统分享 | **PASS** | 映射至宿主持久化成果库 |
| **F4 配置与诊断** | 模型提供商列表、真实连通性测试、运行诊断健康检查 | **PASS** | 映射至 `ProviderManager` 与诊断服务 |
| **F5 主入口切换** | 默认由 `StudyFlutterActivity` 作为 Launcher | **PASS** | 保留 `RouteActivity` 作为经典/调试回退 |
| **F5 崩溃安全模式** | 遇到崩溃自动进入 `SafeModeActivity` | **PASS** | `CrashHandler` 启动双重拦截 |
| **真机实测** | 华为 Mate 50 / HarmonyOS 4.2.0 物理设备运行 | **NOT_RUN** | 当前执行环境无连接的实体物理真机 |
| **真实第三方 API** | 真实外部商用模型提供商网络调用 | **NOT_RUN** | 无内置商用 API Key（符合保密安全规范） |

---

## 6. 构建与生成命令备忘

```powershell
# 1. 重新生成 Pigeon 桥接代码
D:\Office\Study\code\MathStudyAgent\.local\flutter\bin\flutter.bat pub run pigeon --input pigeons/study_bridge.dart

# 2. 运行 Flutter 单元与数学组件测试
D:\Office\Study\code\MathStudyAgent\.local\flutter\bin\flutter.bat test

# 3. 编译 Flutter AAR 产物到本地 Maven
D:\Office\Study\code\MathStudyAgent\.local\flutter\bin\flutter.bat build aar --no-profile --no-release

# 4. 编译宿主集成 Debug APK
.\.local\gradle-9.6.0\bin\gradle.bat -p client :app:assembleDebug
```
