# 智能 Chat 数学可视化：实施与自审

日期：2026-10-10。范围：将文字/图片数学题识别与函数、圆锥曲线辅助绘图接入现有智能 Chat。

结论：四步实施完成，本地自审与修复复验通过，两道真实图片题分别通过严格核对。外部回答流曾中断，椭圆最终专项通过一次安全继续完成；不能把本轮说成所有连续运行均无失败。

## 顺序实施

1. 内置 `math-visual-explainer` Skill，自动加入可发现技能清单。无需用户手动启用；读图后核对题干、组织推导，适合配图时调用工具。长期记忆保留既有用户偏好与学习记录职责，不存执行逻辑。
2. 实现受限公式解析器和确定性绘图工具 `render_math_plot`。原生端生成真实 PNG，写入当前档案成果库，复用已有预览、保存、分享与哈希验证；失败返回失败，不交付伪图。
3. 接入消息中的 `math_plot`，支持显式函数、参数曲线、数值隐式曲线、等比例坐标、图例、固定关键点、参数滑块、双指缩放与继续追问。重开保留交互状态；默认参数 PNG 不随拖动改写。
4. 逐项审查数学正确性、断点、公式输入边界、两端协议、性能、状态持久化和模型自动调用。修复后运行回归、真实模型图片测试及模拟器界面验收，核对 APK 内实际资源。

本轮选择本地确定性解析与绘图，常见高中数学不新增服务器依赖。符号推导由模型讲解，数值图像用于辅助，工具不宣称提供符号证明。

## 验证记录

| 验证 | 结果与证据 |
|---|---|
| Android app JVM 全量 | 364 项，失败 0、错误 0；[日志](logs/math-plot-verification-final.log)、[XML](unit-results/) |
| 新增数学 JVM | 10 项；覆盖优先级、函数与常量、脚本拒绝、定义域、圆锥曲线方程、断点、隐式零点与极点、schema/保留名、消息协议 |
| Flutter 全量 | 173 项通过，包含新增无障碍标签检查；[最终日志](logs/math-plot-flutter-semantics-closure.log) |
| 新增 Flutter 数学 | 9 项；两端采样、等比例、参数更新、后台隐式任务与过时结果处理 |
| 新数学 Dart 文件及测试静态分析 | 无问题；[日志](logs/math-plot-new-code-analyze-final.log) |
| Debug APK 与 Android Lint | 构建成功；0 errors、56 warnings；[最终构建](logs/math-plot-accessibility-build.log)、[Lint](logs/lint-results-debug.txt)。剩余警告不等同于全仓库无警告 |
| 最终模拟器 | 原生 PNG、Flutter 消息图像、无障碍标签、参数保存及重开恢复、非法输入与成果复用通过；[记录](logs/math-plot-runtime-accessibility-closure.log)中的第二项 status=0 |
| 真实模型 | 首次完整两题成功，后续严格方程断言也成功；最终二次函数核对通过，椭圆专项安全继续后通过 `OK (1 test)`，含默认方程与 k=2 的核对；[最终专项](logs/math-plot-live-ellipse-resume.log)、[两题结构化记录](math-visual-live-final-cases.json)。中断运行不计通过；两题最终证据来自分别运行 |
| 安装包资源一致性 | ARM64 APK 的 32 项 Flutter 资源与当前 AAR 一致；[校验](arm64-apk-flutter-verification.json) |

## 自审与修复

- 修正幂与负号优先级；拒绝未知变量、任意代码、超限公式与保留名称冲突。Kotlin/Dart 均拒绝字符串伪装布尔值。
- 显式函数跨无定义点与极点断开，避免把渐近线连成曲线；隐式边界根经二分与残差核对，拒绝把分母极点当作零点。圆、椭圆优先参数表示。
- 固定坐标标注必须真实固定；滑块探索不改变题目原始条件。PNG 明示默认参数快照，避免把静态文件当成交互后的结果。
- Flutter 隐式采样移至后台 isolate，80ms 合并快速操作，用版本号拒绝过时计算；显式/参数采样不随普通页面重建重复执行。
- 限制 PNG 图例宽度，使用等比例视窗。使用实际 Android Canvas PNG 验收，核对尺寸、曲线像素与文件完整性。
- 修复既有主题的六项 Android API 资源 Lint 错误：导航栏与系统启动页属性分别放入 v27/v31 资源。
- 首次真实模型测试将测试图片直接放缓存目录，被应用材料所有权验证拒绝。测试改为先导入档案材料库，再发送；保留[首次失败](logs/math-plot-live.log)与[修复复验](logs/math-plot-live-fixed.log)。这是测试输入问题，不通过放宽文件隔离来绕过。
- Flutter 后台测试使用有界等待处理 isolate 调度，替换脆弱的单次固定延迟，完整回归通过。
- 最后一轮连续测试暴露聊天图像未进入可见语义树，保留[初次结果](logs/math-plot-runtime-final.log)。补齐配置恢复等待、默认会话断言，提前连接验收无障碍服务；数学图像成为独立语义节点，Flutter 语义测试和最终模拟器验收通过。
- 后续真实模型运行出现生成中断，工具状态为 `use_skill:INTERRUPTED`；保留[两项初次失败](logs/math-plot-runtime-closure.log)与[无障碍修复后的结果](logs/math-plot-runtime-accessibility-closure.log)。未将中断改作成功，也未关闭工具结果不确定时的保护机制。
- 随后[定位到 HTTP/2 StreamResetException](logs/math-plot-live-ellipse-diagnostic.log)：Skill 和绘图均已完成，回答流中断。最终测试在没有不确定工具结果的 IO 中断下，只调用一次既有继续回答功能，保留原图并完成核对。最终 `network_resumed=true`，不是首请求无中断；结果不确定的工具不会因此被重放。

## 真实模型验收范围

使用已经配置的 `gpt-6.1-sol` 图片模型，发送两张清晰合成题图，请求中文讲解。用户提示不包含技能名称或工具名称，临时测试助手关闭记忆并不手动启用数学 Skill。

- 二次函数 `f(x)=x²-2x-3`：自动识题、读取 Skill、绘图、真实 PNG；核对 x=-1、1、3 时的函数值 0、-4、0。
- 椭圆 `x²/9+y²/4=1` 与 `y=kx+1`：自动读取 Skill、产生滑块和曲线；取 k=2 后核对椭圆残差与直线方程，避免仅凭“工具调用成功”判断正确。
- 原生/Flutter 验收检查真实 PNG、相同输入成果复用、消息嵌入图像、参数存储与重开恢复、非法表达式失败。清理测试会话与材料，恢复原助手配置。

证据只归档合成题与绘图，不归档密钥或用户设置。见[二次函数 PNG](math-visual-live-0.png)、[椭圆 PNG](math-visual-live-1.png)、[默认参数图](math-visual-default.png)、[聊天图像与成果卡](math-visual-chat.png)、[两题结构化记录](math-visual-live-final-cases.json)。截图保留了当前滚动位置；完整 PNG 可独立查看。

## 支持与限制

当前覆盖常见显式函数、分段定义域、参数曲线及数值隐式曲线。最多 6 条曲线、20 个固定标注；公式受长度、节点、深度和变量白名单约束。完整协议与使用方式见[实现说明](../../docs/math-visualization.md)。

数值采样可能漏掉极窄、高频、网格以内闭环、偶重根和退化结构，歧义鞍点不强行连线；图像不替代代数推导。自动触发依赖模型的视觉与工具调用能力。本轮未覆盖所有供应商、模糊照片、复杂手写识别或华为 Mate 50 真机；安装包仍为 Debug。

## 交付

[ARM64 Debug 安装包](hxsi-agent-math-visual-arm64-debug.apk)。SHA-256：`c9f634d13cb79931f3171701f5fad5bddae8cc0509b4bd9faad116e15b76b42a`。

使用时直接发送数学图片或文字题并请求讲解，适合配图时自动绘制。参数可在消息内拖动，继续追问；PNG 从成果卡预览、保存或分享。
