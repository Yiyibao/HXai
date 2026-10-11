# 智能 Chat 展示效果专项问题记录

记录日期：2026-10-11（Asia/Taipei）。来源：用户在当前模拟器逐项测试后的反馈。状态：7 项待专项优化，本轮仅记录并定位代码和既有测试，没有修改实现或重新执行测试。

测试环境：项目 AVD `mathstudy-s0`，应用 `org.hxsi.agent.debug`，Flutter 主入口 `StudyFlutterActivity`；当前选择 `gpt-6.1-sol`、低推理等级、Responses。接口与简单工具协议曾单独实测通过，不等于以下组件展示已通过验收。本记录不包含密钥。

## 1. 问题与手动测试项目对应

编号沿用本次聊天给出的 1–23 项组件测试列表。

| 问题 ID | 用户反馈 | 对应测试 | 关联组件 | 后续目标与检查重点 |
| --- | --- | --- | --- | --- |
| UI-01 | 正文与卡片区别太小，卡片不明显 | 第3项：普通文本与卡片 | `text`、`card`，v4 样式包装 | 卡片需具有清晰容器边界和适当背景、留白，能直接区别于正文；检查浅色/深色及 inline/panel |
| UI-02 | 方案比较样式平庸，标题与内容区别不明显，各方案没有明确边界 | 第7项：方案比较 | `comparison` | 加强方案标题层级，建立方案间边界与间距；手机纵排、宽屏多列均需清楚；具体视觉方案后续设计 |
| UI-03 | 代码块展示效果不行，要求 VS Code 的 One Dark 风格 | 第8项：代码块；连带普通聊天代码围栏 | `code_block`、Markdown fenced code | 采用 One Dark 视觉方向，统一深色代码区域、语法配色、字体和工具栏；保留折叠、复制、横向滚动及未知语言降级；配色细节待专项确定 |
| UI-04 | 折线图样式不够好，横纵坐标似乎未显示，其他图表似乎也有同样问题 | 第10项折线图、第11项柱状图、第12项散点图 | `line_chart`、`bar_chart`、`scatter_chart` | 三类图表统一核查坐标轴线、刻度数字、轴标题和单位；检查数据定义是否缺标签、边界裁切、对比度及间距。用户用了“似乎”，尚未独立复现，不直接判定具体原因 |
| UI-05 | 数学绘图直接失败 | 第16项：`y=a*x^2`，a∈[-3,3]，默认1 | `render_math_plot`、`math_plot`、滑块 | 先收集失败工具输入、状态与脱敏诊断，区分服务请求、定义校验、数学采样和渲染失败；图形成功后再核对滑块重算、坐标与全屏。当前失败原因未知 |
| UI-06 | 概览页不满意 | 暂关联第18项：“概览/明细”标签页；关联第4项指标及第20项组合布局 | `tabs`、`metric`、analysis 模板、网格布局 | 重做概览的信息层级、指标分组、空间与内容组织。用户尚未说明不满意的细节；“概览页”是否指第18项标签页，后续专项时确认，不将此映射当作已确认事实 |
| UI-07 | 点击“基于当前成果修改”后，聊天出现 JSON，疑似没有正确展示修改成果 | 第23项：成果修改与旧版保留 | 修改草稿入口、`revise_interactive_surface`、工具输出到交互渲染 | 新成果应实际调用修改工具并渲染为可操作界面，原版保留。先区分模型仅回复 JSON、工具调用未执行、工具失败、结果未被渲染等路径，不先假定为前端解析缺陷 |

## 2. 已定位的实现与自动测试

下表是现有回归入口，并不表示它们已经覆盖用户提出的视觉标准或本次真实失败。

| 问题 | 实现入口 | 既有测试与证据入口 |
| --- | --- | --- |
| UI-01 | `client/flutter_ui/lib/widgets/interactive_surface.dart` 的 `card` 分支；`surface_layout.dart` 的样式包装 | `client/flutter_ui/test/interactive_surface_test.dart`；`surface_layout_v4_test.dart`；`surface_rendering_visual_test.dart` |
| UI-02 | `client/flutter_ui/lib/widgets/surface_semantics.dart` 的 `comparison` 分支 | `client/flutter_ui/test/surface_rendering_v4_test.dart`：`Many comparison items use readable bounded columns`、`Rich semantics render on narrow dark screens with large text`；`surface_rendering_visual_test.dart` |
| UI-03 | `client/flutter_ui/lib/widgets/surface_semantics.dart` 的 `SurfaceCodeBlock`；普通代码路径 `math_markdown.dart` | `client/flutter_ui/test/surface_rendering_v4_test.dart`：`Unknown code language falls back and copy keeps source`；`chat_upgrade_stage1_test.dart`：`Completed code widget is retained while trailing prose grows`；`core_chat_rendering_test.dart` |
| UI-04 | `client/flutter_ui/lib/widgets/surface_data_views.dart` 的 `SurfaceChartView`；旧版路径 `interactive_surface.dart` 的 `_ChartPainter` | `client/flutter_ui/test/surface_data_v4_test.dart`：`Charts render all variants, preserve selection and legend visibility`；`surface_rendering_visual_test.dart`：`Capture mobile and wide light/dark rendering`；原生 `client/app/src/test/java/me/rerere/rikkahub/feature/interactive/SurfaceRenderingV4Test.kt` |
| UI-05 | Flutter `client/flutter_ui/lib/widgets/math_plot.dart`；Kotlin `client/app/src/main/java/me/rerere/rikkahub/feature/interactive/MathPlotTool.kt`、`MathPlot.kt`、`InteractiveSurface.kt` | `client/flutter_ui/test/math_plot_test.dart`：`surface slider recomputes plot and supports saved state`、`parameters recompute and definition limits are respected`；原生同目录 `MathPlotTest.kt`；设备测试 `client/app/src/androidTest/java/me/rerere/rikkahub/service/MathVisualRuntimeTest.kt` |
| UI-06 | `client/flutter_ui/lib/widgets/surface_layout.dart`、`surface_semantics.dart`、`interactive_surface.dart` | `client/flutter_ui/test/surface_layout_v4_test.dart`：`Tabs preserve controls while switching presentation`、`Analysis template groups metrics and applies semantic tone`；`surface_rendering_visual_test.dart` |
| UI-07 | `client/flutter_ui/lib/pages/chat_page.dart` 的“基于当前成果修改”入口；`interactive_surface_message.dart`；Kotlin `client/app/src/main/java/me/rerere/rikkahub/feature/interactive/SurfaceRevisionTool.kt`、`InteractiveSurface.kt` | `client/app/src/test/java/me/rerere/rikkahub/feature/interactive/SurfaceRevisionToolTest.kt`：`preservesSourceAndRejectsStaleRevision`；Flutter `surface_bridge_test.dart`、`chat_upgrade_workspace_test.dart` 为相关状态/预览回归；应补专门的真实修改请求→工具调用→新版渲染验收，不能用相关测试替代 |

## 3. 初步源码线索与证据限制

- UI-01：v4 的 `card` 分支直接返回 `MathMarkdownView`；外观依赖外层 variant 等样式。这是正文与卡片接近的可检查线索，不是对本次模型生成定义的确认。
- UI-02：比较项当前以标题、正文及间距组合，窄屏纵排、宽屏按列换行；可读布局测试不等于标题对比与方案边界达到用户要求。
- UI-04：v4 图表已配置左/下数字刻度，轴标签与单位按定义字段条件显示，同时图表边框关闭。因此后续要分别核对“轴线”“刻度”“标题/单位”，不能笼统认定没有实现坐标。
- UI-05、UI-07：当前只有用户真实反馈，尚未采集对应请求的失败定义或脱敏记录。既有数学/版本校验测试通过，不能否定这次问题。
- 未对未提及的测试项目宣布通过；后续继续按用户实际测试记录结论。

## 4. 后续专项复测清单

1. 第3项：同一句话分别用正文与卡片展示，直接比较容器边界。
2. 第7项：步行、骑车、公交三方案；检查标题与方案边界。
3. 第8项及普通代码回复：Python 示例；检查 One Dark 风格、复制及折叠。
4. 第10–12项：显式提供横纵轴标题与单位，用固定演示数据检查三种图表；重复浅色/深色、手机/全屏。
5. 第16项：最小 `y=a*x^2` 示例；保留实际工具错误，再测试滑块和绘图保存。
6. 第18项：概览指标、明细表格双标签；先确认用户指的是该概览，再设计与复测。
7. 第23项：修改已存在图表，检查真实工具调用、新版界面及旧版保留；JSON 仅作为工具参数，不应替代最终交互成果。

专项实施顺序尚未决定。后续优化以用户新指令为准。

相关背景：[v4 渲染协议](chat-rendering-v4.md)、[四阶段实现说明](intelligent-chat-upgrade-implementation-20261010.md)、[渲染验收记录](../qa/rendering-upgrade-20261010/report.md)。
