# 智能 Chat 选图实施记录

日期：2026-10-11（Asia/Taipei）。用户提供六张参考图，确认图 1–6 的方向；第 7 项授权自行研究优化。参考图已原样保存于 [选图目录](ui-design/chat-style-selected-20261011/)，避免临时剪贴板文件失效。

## 所选样式与实现

| 项目 | 用户决定 | 当前实现 |
| --- | --- | --- |
| 信息卡片 | 图 1 | v4 card 恢复真实容器：16px 留白、细边框、16px 圆角，正文继续支持 Markdown/公式。多指标用 panel + metric + composition，不在正文中伪造卡片。 |
| 两方案 / 多方案 | 图 1 / 图 2 | comparison 有两项时用带水平分隔线的对照表；明确且一致的“字段：内容”按字段对齐，普通 Markdown 则保留为内容行。三项以上用独立卡片，标题加粗、标题下分隔线、字段对齐。极窄屏对照表可横向滚动。 |
| 代码 | 图 3 | 普通 Markdown 围栏与 code_block 共用 One Dark，浅色聊天也使用深色代码区域，保留复制、折叠、横向滚动与未知语言降级。 |
| 数据图表 | 图 4，重点是坐标明显 | 折线、柱状、散点统一开启高对比左轴/底轴，保留浅网格、标题和单位；正值数据从零起点展示，横轴端点与零刻度可见。既有工具提示和数据选择保留。 |
| 函数图 | 图 5 | 当前参数实时替换公式，强化坐标轴/箭头/原点标识、使用易读刻度、曲线加粗。数学滑块在小范围内采用0.1间隔并提供端点、-1、0、1快捷值。工具提示提供完整最小二次函数定义，固定x=-5..5、y=-80..80，函数探索使用equalAspect:false；几何等比例能力保留。 |
| 概览 | 图 6 | analysis 自动按关键结论、主指标、辅助指标、趋势组织；显式composition仍按定义顺序。概览/明细标签采用等宽胶囊选择；analysis指标网格在手机上可三列，大字体退回纵排。 |
| 成果修改 | 自行优化 | 修改入口的草稿明确要求调用revise_interactive_surface、交付可预览新版、保留旧版，并禁止把JSON作为交付。修改工具增加相同系统指令；已有严格来源/版本校验继续生效，失败不能声称成功。 |

本轮未更换模型、凭据、提供方或用户自定义系统提示词。新模型工具说明影响后续请求；历史成果只有符合对应组件协议时才采用新样式，不会把所有历史Markdown自动转换成卡片。

## 代码运行评估

图 3 本身展示了复制按钮，不能从截图确认运行按钮或背后的实现。公开的 OpenAI [Code Interpreter 文档](https://developers.openai.com/tracks/building-agents#code-interpreter)说明，它使用专门环境执行Python，并可处理输入文件及生成输出文件；这支持执行环境方案，不能据此断言ChatGPT截图使用同一实现。

当前项目有模型调用的JavaScriptEngine工具，但代码展示组件没有执行入口，Responses适配也没有现成的code_interpreter完整链路。Python运行需要处理执行环境、超时/中止、输出、文件和提供方能力；它不是仅增加一个按钮。按用户“麻烦则先放一放”的决定，本轮后置代码运行，不添加不可用的运行按钮。后续若做，单独选择手机本地Python或支持该工具的服务，再接入真实结果。

## 验证与证据

- Flutter全量286项通过；最终坐标调整后13项相关回归通过。[全量日志](../qa/chat-style-selected-20261011/flutter-tests.log)、[最终回归](../qa/chat-style-selected-20261011/final-render-tests.log)。
- Kotlin绘图、v4协议、版本修改14项通过，0失败/错误。[结果](../qa/chat-style-selected-20261011/kotlin-results.json)。
- 静态分析无错误或警告，76条信息级诊断（流控制花括号等）仍保留。[日志](../qa/chat-style-selected-20261011/analyze.log)。
- 实际Flutter渲染截图：8种场景的浅/深色共16张，已检查卡片、表格、代码、坐标、函数和概览；字体测试替身问题已纠正。[截图目录](../qa/chat-style-selected-20261011/)。
- Android模拟器专项测试通过：v4二次函数生成实际PNG，原版和修改版通过持久化/Flutter桥接，两版界面定义可取得且来源不变。[运行日志](../qa/chat-style-selected-20261011/runtime.log)。这是本地工具调用测试，不是模型在线调用验收。
- 最终双ABI Flutter debug AAR、Android APK与测试APK构建通过；ARM64与x86_64包均核对38个Flutter资源一致。[ARM64校验](../qa/chat-style-selected-20261011/apk-arm64.json)、[x86_64校验](../qa/chat-style-selected-20261011/apk-x86_64.json)。
- 已更新现有模拟器应用，无清除用户数据或更改模型配置。

历史“数学绘图失败”的具体请求/错误仍未取得，不能确认历史失败根因；当前最小二次函数、参数重算和实际PNG链路通过。第7项原先模型直接回复JSON的场景仍需在线重现复测，本轮已加强工具指令且确认工具生成新版到Flutter数据的链路正常。未进行Mate 50真机验收，选图也不等于用户已经复审新实现。

## 预览与交付

[信息卡片](../qa/chat-style-selected-20261011/card-light.png) · [两方案](../qa/chat-style-selected-20261011/two-light.png) · [多方案](../qa/chat-style-selected-20261011/many-light.png) · [One Dark](../qa/chat-style-selected-20261011/code-light.png) · [柱状坐标](../qa/chat-style-selected-20261011/bar-light.png) · [函数图](../qa/chat-style-selected-20261011/math-light.png) · [概览](../qa/chat-style-selected-20261011/overview-light.png)。

安装包：`client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`；模拟器用x86_64包。均为debug开发版。
