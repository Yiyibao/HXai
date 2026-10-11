# Opus 5.5 实现交接：MathStudyAgent 智能 Chat 页

日期：2026-10-09  
项目根目录：D:/Office/Study/code/MathStudyAgent  
文档性质：用户已确认设计方向的实现任务书，包含完整设计要求、实现入口、验收清单及可运行原型源码。无需依赖上一轮聊天记录理解任务。

## 1. 可直接交给 Opus 的任务指令

请在现有 MathStudyAgent 项目中实现本文件定义的智能 Chat 页面。用户已经采纳白底、黑灰文字、浅灰气泡、底部输入框的 ChatGPT 风格，并明确要求：检索资料时，助手自然选取少量相关图片，配合文字解释。优先完成真实 Flutter 聊天页面及必要的 Kotlin/桥接接线，复用现有聊天、模型、文件、交互组件和任务系统。

先读取仓库实际适用的 AGENTS.md 和当前代码，核查哪些能力已实现；保留工作区已有修改。不要依据旧报告中的“已完成”字样假定功能可用，也不要把所有能力推倒重写。以本文的用户已确认视觉方向和产品要求指导本轮实现；实现细节可结合现有代码调整，但不得退回旧粉色主视觉或数学专用教学主页。

直接推进到可运行、可检查的结果。最终提供实际改动、构建/验证结果、运行截图，以及尚未接通的具体能力。不要只给方案、图片或在 WebView 中嵌入本文 HTML 来代替 Flutter 实现。

## 2. 用户明确确认的需求与边界

### 已确认

1. 参考 ChatGPT 手机端，以自然对话为中心。
2. 已采纳新的白/黑/灰风格；首要实现的是持续对话 Chat 页面。
3. 智能 Chat 包含动态交互图表、参数控件、可操作卡片以及图文混排。
4. 检索资料时应主动、自然地选少量有帮助的图片；不要求用户每次特别说“配图”。
5. 上传、读取、理解、处理、生成和交付文件要融入原对话。
6. 项目为个人通用 AI 助手，数学是一个使用场景；不默认强制教学模式。
7. 多模型仍沿用已有提供商/模型能力，界面不能把示例模型写死成真实可用模型。

### 本文件的具体设计建议

1–3 张配图预算、控件尺寸、四个演示场景、卡片与全屏组织方式，是依据上述要求整理的实现基线。文案、示例数据和精确尺寸可按实际任务和屏幕适配，核心行为需保持。

本轮不要求建设大型 Skill 商店、完整 MCP 生态、多 Agent 平台或远程电脑控制。文件处理复用现有文档服务；不要为了改页面重复建设服务。旧页面与资料先保留，不删除无关功能。

## 3. 视觉规范速查

| 元素 | 实现基线 |
| --- | --- |
| 主背景 / 助手正文底色 | #FFFFFF |
| 主文字 / 主要操作 | #171717 |
| 次级文字 | #737373；实际字号下满足可读性和对比度 |
| 用户气泡 / 输入框 | #F4F4F4，用户气泡可用 #F1F1F1 |
| 卡片边框 / 分隔线 | #E5E5E5 |
| 图表数据色 | 可用克制的灰蓝 #426A92；多序列需可辨识，不把主界面染色 |
| 字体 | 平台系统中文无衬线；正文 15–16 sp，行高约 1.65–1.8 |
| 正文横向留白 | 16–20 dp |
| 内嵌卡片 | 16 dp 圆角，16–20 dp 内边距，细边框 |
| 输入框 | 约 24 dp 圆角，随多行文字自适应，达到合理上限后内部滚动 |
| 操作触达范围 | 至少 44–48 dp；图标视觉尺寸约 20–24 dp |
| 展开动效 | 180–220 ms，并适配系统减少动态效果 |

顶部：左侧历史菜单、中部简洁标题与必要选择入口、右侧新聊天。模型选择沿用已有入口，并在输入框显示真实当前模型。顶部标题选择和模型选择不可产生两个互相矛盾的模型状态。

不要添加底部“聊天/材料/记忆/成果”四个同级 Tab 到这个主 Chat 页；历史走侧栏，文件/成果在消息中交付，相关库可保留次级入口。不要放大吉祥物、粉色操作面板、渐变、装饰性仪表盘或在每条助手回复外包一层大卡片。深色模式若已存在应保持可用，用中性对应色适配，不把旧粉色主色继续带入 Chat。

原型顶部四个场景切换按钮、外部设计说明、假状态切换按钮和虚构手机状态栏属于预览工具，不能复制到正式产品。

## 4. 代码入口与实现注意

下表为本次在当前工作区查到的真实文件，列出检查入口，不等于这些路径都已验证端到端可用。

| 范围 | 仓库内路径 |
| --- | --- |
| Flutter Chat | client/flutter_ui/lib/pages/chat_page.dart |
| 历史侧栏 | client/flutter_ui/lib/pages/conversation_drawer.dart |
| 模型选择 | client/flutter_ui/lib/pages/model_picker_sheet.dart |
| 主题 | client/flutter_ui/lib/theme/study_theme.dart |
| 富文本 / 数学 | client/flutter_ui/lib/widgets/math_markdown.dart |
| 交互组件渲染 | client/flutter_ui/lib/widgets/interactive_surface.dart |
| 交互消息包装 | client/flutter_ui/lib/widgets/interactive_surface_message.dart |
| Flutter providers | client/flutter_ui/lib/providers/study_providers.dart |
| Pigeon 源协议 | client/flutter_ui/pigeons/study_host_api.dart |
| Flutter 桥接客户端 | client/flutter_ui/lib/bridge/bridge_client.dart |
| Kotlin 桥接服务 | client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt |
| 消息转换 | client/app/src/main/java/me/rerere/rikkahub/flutter/converters/MessagePartConverter.kt |
| 平台消息类型 | client/ai/src/main/java/me/rerere/ai/ui/Message.kt、UIMessagePart.kt、InteractiveSurface.kt |
| 宿主交互协议 | client/app/src/main/java/me/rerere/rikkahub/feature/interactive/InteractiveSurface.kt |
| 交互状态管理 / 工具 | client/app/src/main/java/me/rerere/rikkahub/data/ai/InteractiveSurfaceManager.kt、data/ai/tools/InteractiveSurfaceTool.kt |
| 文件工具 | client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialTools.kt、MaterialEditTools.kt |
| 文件服务器 | server/document-service/ |

当前 theme 仍有旧 white-pink tokens；本轮需要调整 Chat 使用的中性主题及其控件，避免只改页面背景而保留粉色按钮、图表控件和选择态。注意共享主题对其他页面的影响。

Chat 源码已有自动跟随滚动逻辑，以及交互消息/工具结果的展示路径；先审查复用，不要额外建立竞争的状态流。当前交互包装与宿主协议中存在 surface ID / toolCallId、整数 revision / 字符串 revision 等不同表达，必须依据真正消息来源及宿主合同核对，而不是机械转换或假定所有路径等价。

若需扩展 Pigeon，修改源协议并用项目规定流程重新生成 Dart/Kotlin；不要只手工改生成文件。文件打开/另存/分享、任务取消、模型选择、消息发送必须通过已有业务入口，不能只显示成功提示。

### 消息数据与图片接线

优先扩展/复用既有消息 parts，而不是把所有展示存成不可解析的 HTML。至少要能保留：
- 正文与内容顺序。
- 稳定 messageId、图片 ID、图片在回答中的次序、配图插入锚点。
- 图片原 URL/本地资产身份、原网页 URL、图注、alt、作者/机构、必要日期、来源类型。
- 交互定义、来源数据/版本、surface 身份、revision、用户输入与筛选。
- 文件资产 ID、真实 MIME/大小、版本与存在状态。
- 实际工具任务身份、阶段与成功/失败/取消状态。

上述是数据需求，不要求创造一个平行于已有体系的数据库。按现有存储和桥接合同落地。重开历史、消息分支或重新生成后不能把旧图片/控件的上下文错接到新回答。

检索到相关网页后，若图片有解释价值，应按段落意图挑图并插入回答。不能仅在 UI 上支持 Markdown 图片、却没有候选图检索/提取、来源核对及选择路径，就宣称“自动配图已完成”。先核查现有搜索提供商、网页读取和图片工具，再补足必要路径。

“这张图”追问至少携带图片 ID、来源和当前消息。支持视觉输入的模型可附实际图片；不支持视觉的模型根据已核实图注与文本回答，不能声称已经看到了图片。检索到的图片、用户附件和生成示意图应有可辨识的来源类型。

## 5. 推荐实施顺序

1. 核查实际启动入口、Flutter/Kotlin 边界、消息 parts 与可复用工具；确认改的是用户运行的 Chat。
2. 完成主题、消息排版、输入框、侧栏、真实模型选择、流式输出与滚动行为。
3. 完成图文混排、来源引用、原图展开及带图追问；接通自然的少量检索配图。
4. 完成交互卡片主题与状态、图表点选/筛选、参数联动、展开与对话续行。
5. 完成真实文件任务活动、成果预览/保存/继续修改，以及失败/取消恢复。
6. 在真实宿主运行，验证持久化、模型兼容、窄屏/大字/键盘/滚动等行为。

可分步实现，但最终交付必须诚实列出完成与未完成项。不要用静态演示通过真实能力验收。必要时在开发预览中使用明确标注的 fixture，正式模式必须使用实际返回数据。

## 6. 验收清单

| 场景 | 通过要求 |
| --- | --- |
| 普通多轮聊天 | 真实模型调用与流式内容、上下文、停止、失败重试和草稿保留正常 |
| 富文本 | 中文、标题、列表、公式、代码、长表格可读；长内容不会导致页面横向溢出 |
| 自动配图 | 用户仅问“极光为什么有不同颜色？”等适合图解的问题，回答按需选出相关图片并在段落附近展示；不要求固定张数 |
| 图片来源 | 每张图有可追溯原出处和准确图注；不存在拿 AI 生成图充当检索原图 |
| 图片展开与追问 | 完整查看原图，能打开来源；追问携带当前图身份，返回对话保留草稿与阅读位置 |
| 图片失败 | 占位稳定，可查看图注和来源；图片慢不阻塞正文；历史重开仍能辨识原图 |
| 交互图表 | 数据、单位、来源准确；筛选/点选立即更新；明确提交当前选择后才触发模型续行 |
| 参数工具 | 数值输入和滑块同步、计算结果准确、范围和假设清楚；模型续行使用提交时的真实值 |
| 状态与幂等 | 历史重开保留已保存状态；旧版本操作不覆盖新版本，重复提交不重复续行 |
| 文件处理 | 上传任务进入真实工具路径；步骤、取消/重试与错误来自实际执行，未完成时不显示假成果 |
| 成果交付 | 真实文件可打开、预览和保存；继续修改产生新版本，原件保留；保存失败不报成功 |
| 移动端 | 360/390/430 宽度适配，键盘上方输入框可见，安全区与大字体正常，重要操作不被遮挡 |
| 滚动与动效 | 用户在底部才自动跟随；向上阅读不抢滚动；图片/组件加载避免大跳动，减少动态设置有效 |
| 多模型/降级 | 当前模型来自实际配置；不支持的能力明示或自然降级，不展示虚构控件结果 |
| 最终证据 | 运行版本与改动一致；适当构建/测试结果及真实运行截图；模型/网络不可测时记录具体限制 |

本文件最后包含原型验证记录，它证明交互设计原型的行为；不能替代以上客户端端到端验收。

## 7. 完整产品与交互设计

以下保留本轮设计正文。读本文件即可获得全部文字规范。


# 智能 Chat 展示设计 v1

日期：2026-10-09。基于用户已采纳的 ChatGPT 风格移动端设计。新增用户要求：检索资料时自然穿插少量相关图片。此目录是设计与交互预览，不代表 Flutter/Kotlin 已接通这些功能。

## 已确认的视觉方向

白色主背景、黑灰文字、轻量线性图标、浅灰用户气泡、助手正文直接排在白底、底部圆角输入框。Chat 为中心，历史在侧栏，文件与成果通过对话呈现。取代本轮设计范围内旧白粉页面的视觉方案；保留旧稿供追溯。

## 官方参考与项目取舍

当前查阅的 [OpenAI UI guidelines](https://developers.openai.com/plugins/concepts/ui-guidelines) 是 ChatGPT 中插件/应用的展示指南，不是完整 ChatGPT 手机端最新版本的逐像素规范，也不意味着其所有展示模式都对所有账户开放。

借鉴：轻量内嵌卡片、丰富内容全屏展开并保留输入框、交互状态保留、少量主要操作、避免卡片内部深层导航和嵌套滚动、系统字体及克制配色。

官方内嵌应用展示位于模型回答之前。本 Agent 按内容用途安排：交互工具结果可先展示，随后给简短解读；说明性图片紧随相关文字，成果卡片放在交付说明之后。这是项目自身的设计选择。

## 回答的基本组织

每条回答按需要组合正文、图片、来源引用、交互组件、任务活动与成果。没有必要的图片或操作时就是普通文字。一次回答不强行包含所有组件。

- 用户：右侧浅灰气泡；附件有文件名、类型与可核实大小；发送失败保留草稿和重试入口。
- 助手：白底自然排版；段落与小标题组织观点；公式、表格、代码按其实际内容呈现。
- 工具活动：一行可折叠的真实状态，例如“正在读取文件”“已分析数据”；展开显示步骤与范围。不展示内部思维链，不虚构百分比。
- 操作条：回答结束后显示复制、反馈、朗读和更多；仅提供实际可用操作。流式生成时停止入口可见。
- 来源：引用紧贴支持的句子，文件引用含原页；来源与配图出处分别保留。

## 图片是解释材料

图片要因内容需要自然出现，用户不必每次说“配图”。一般回答目标为 1–3 张；这是选择预算，不是必达数量。没有能帮助理解的可靠图片时允许不配图。默认第一张最有解释价值的主图放在相关段落后；第二张补充另一点，放在对应后文。首屏不被多图挤满，文本先给读者一个方向。

选择逻辑：
1. 根据问题与回答段落寻找候选图片；核对原网页、图注、拍摄/发表时间及对象。
2. 从候选中挑解释作用互补的图片，去除重复图、无关封面、广告、模糊缩略图。
3. 优先直接来源、论文原图、官方机构照片及用户上传材料中的原图；可用性与允许展示范围需要在接入时确认。
4. 为图片写一句中文图注，说明读者应注意什么；同时保留原来源、作者或机构。
5. 原图是事实材料，不能用生成图片冒充。生成示意图另行标注“AI 生成示意图”；论文原图标注图号及出处。

展示：
- 单图：宽度适配正文，通常 16:9 预览，但不裁掉图表坐标、正文标注和关键对象。图解、论文图默认完整适配。
- 两图比较：需要同时对照时并排；移动端必须保持标注可读，否则用一张主图和后续第二张。
- 横向图组：仅用于同类候选或多视角对比，保持清晰的张数与来源，默认不使用自动轮播。
- 图注：11–12 sp 灰字，一至两行；原出处可点。图与对应文字间距约 12–16 dp。
- 放大：展示完整图片、来源及图注，保留“针对这张图继续追问”的输入入口；发送时携带具体图片身份及来源。
- 加载失败：稳定的占位与原出处入口；图片慢不阻塞正文流式输出。文本引用与图片元信息不能丢失。
- 互动：用户可说“第二张是什么意思”“比较这两张”；按持久化的图片次序与 ID 解析，不依赖当前可见滚动位置。

本预览用两个真实 NASA 原始来源验证布局，未执行自动图片检索管线：
- [NASA 极光照片原页](https://science.nasa.gov/earth/earth-observatory/aurora-australis-observed-from-the-international-space-station-44348/)：国际空间站 2010 年 5 月 29 日拍摄的南极光。
- [NASA Auroras](https://science.nasa.gov/sun/auroras/)：NASA / Lacey Young 于 2024 年 10 月 10 日在马里兰州拍摄的极光。
远程图片直接从 NASA 加载，没有下载镜像；离线或来源不可达时可查看文字与原出处。

## 交互内容

轻量交互卡片一张解决一个明确问题：趋势筛选、参数试算、候选选择。标题 + 来源/条件 + 可视内容 + 少量操作。主要操作最多两个，一主一次；不在卡片内再造聊天输入框。输入框是整段对话的统一入口。

- 图表：标明单位、范围、数据出处；筛选后结果与总计同步更新。点选数据点显示数值；“分析选中月份”将筛选、点选和当前数据版本带入下一轮。
- 参数：数值输入与滑块联动，明确假设、范围与单位。纯本地公式能计算的立即更新；操作控件不会每次都调用模型。点击“按当前参数解释”才提交状态。
- 选择/表单：仅在用户目标存在影响结果的歧义时出现；提交后给自然语言回执并继续原任务。
- 全屏：复杂表格、文档预览、图解和多参数工具可展开；状态与内嵌版本一致，返回保留滚动位置及输入草稿。
- 状态持久化：正式接入要保存 surface ID、revision、输入、筛选和选择状态，防止重开丢失及旧操作覆盖新版本。
- 降级：模型或组件不支持时保留文字/表格与可继续追问入口。

## 文件任务和成果

上传后按用户任务自动调度工具；仅上传且未提任务时不启动全文处理。进度按实际阶段显示读取、排队、解析、生成、校验；中断保留已取得的结果。未有文件时不能显示可下载成果。

成果卡片显示实际文件名、类型、大小与版本；“预览”“保存”是默认两项操作，打开、分享和版本操作在更多中。继续改写产生新版本并保留原件。点击引用页可核对 PDF 原页。文件预览中允许直接输入“把这一节缩短”，携带当前文件和版本继续原对话。

## 动效与滚动

- 文本流式逐步出现，不每个 token 重播动画；段落排版稳定。
- 新组件在内容就绪后轻微淡入，避免大块占位反复跳动；图片提前保留尺寸。
- 仅当用户在底部时跟随生成；用户向上阅读时不抢滚动，显示回到底部入口。
- 图片/卡片展开约 180–220 ms；尊重系统减少动态效果。
- 键盘出现时输入框贴键盘上缘，保留最后相关内容；不得覆盖图表操作。
- 大字体下容许正文和卡片增高；交互触达区域至少 44–48 dp；正文约 15–16 sp、行高 1.65–1.8。

## 交付与限制

- smart-chat-preview.html：四个可点击设计场景。切换场景、图片展开/来源/追问、筛选与点选、参数计算、状态续行、示例下载、文件进度/失败样式。
- smart-chat-three-scenes.png：内置 image_gen 生成的三场景视觉板；图中文字、图表与数字供构图参考，以 HTML 的实际计算和数据为交互依据。
- two-page-preview.png：此前用户采纳的基础两页。
- prompts.md：此前生成提示词。

预览无后端模型、真实文件任务或自动检索；发送只展示本地演示回复。原型的“保存”明确导出 Markdown 示例，“导出数据”导出筛选后的示例 CSV。正式客户端仍需按实际能力接线和验收。


## 8. 原型验证记录（不是正式客户端验收）
# 设计预览验证

2026-10-09。通过 Codex 内置浏览器验证本地 smart-chat-preview.html。

- 390 × 844 移动端：documentWidth=390、documentHeight=844；输入框底部=844，无页面级横向溢出。
- 两张 NASA 来源图片加载成功；点击第一张可打开全图与原出处。
- 展开图片后输入“图中的光带是什么？”：回到对话，演示消息携带“从空间站看绿色极光”上下文。
- 华东筛选：累计销售额 ¥77,700；点选 6 月：¥18,000；分析续行：较 5 月变化 -10.0%。
- 参数：120 万、30 年、3.6% 时，本地公式月供 ¥5,455.74；点击解释后当前参数进入演示续行。
- 1200 × 1000：桌面设计说明与场景切换可见；失败样式保留摘要且无成果卡；完成样式可打开文件预览。
- 移动端截图：image-chat-mobile.jpg。临时 viewport 设置验证后已重置。

以上验证只覆盖本地设计原型。未测试真实模型、自动检索、Flutter/Kotlin 接入、文件任务或生产成果下载。


## 9. 完整可运行原型源码

以下 HTML/CSS/JavaScript 是视觉与交互行为参考，可完整复制保存为 smart-chat-preview.html 并用浏览器打开。无需构建、npm 或额外代码文件；真实 NASA 图片通过网络加载。正式客户端仍应使用 Flutter 实现。

原型已经刻意标注示例数据、演示回复和示例下载。顶部场景切换与设计说明只是预览工具，正式 Chat 不包含这些控件。源码中 design-spec.md 的辅助链接可忽略：完整设计已经包含在本文件第 7 节。

```html
<!doctype html>
<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>MathStudyAgent · 智能 Chat 设计预览</title>
<style>
:root{color-scheme:light;--ink:#171717;--muted:#737373;--line:#e5e5e5;--soft:#f4f4f4;--blue:#426a92}*{box-sizing:border-box}body{margin:0;background:#f1f2f3;color:var(--ink);font:15px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}button,input,select,textarea{font:inherit;color:inherit}button{cursor:pointer}button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid #426a92;outline-offset:3px}button{border:0;background:none}a{color:inherit;text-underline-offset:3px}.studio{max-width:1120px;margin:32px auto;display:grid;grid-template-columns:260px 430px 260px;gap:42px;align-items:start}.aside{padding:24px 0}.eyebrow{font-size:11px;letter-spacing:2px;color:var(--muted)}h1{font-size:25px;line-height:1.4;margin:12px 0}h2{font-size:17px;margin:0 0 8px}p{margin:12px 0}.aside p,.aside li{color:#626262;font-size:13px}.scenarios{display:grid;gap:8px;margin:28px 0}.scenarios button{text-align:left;padding:13px 16px;border-radius:12px;color:#666}.scenarios button.active{color:#fff;background:#171717}.scenarios small{display:block;font-size:11px;opacity:.7}.phone{height:884px;background:white;border-radius:22px;overflow:hidden;display:flex;flex-direction:column;border:1px solid #e7e7e7;position:relative}.status{display:flex;justify-content:space-between;padding:14px 25px 4px;font-size:12px;font-weight:600}.top{display:flex;align-items:center;justify-content:space-between;padding:8px 15px 12px}.top strong{font-size:19px}.icon{width:40px;height:40px;display:inline-flex;align-items:center;justify-content:center;border-radius:50%}.icon:hover{background:var(--soft)}svg.i{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.thread{flex:1;overflow-y:auto;padding:4px 20px 20px;scrollbar-width:thin}.user{margin:7px 0 22px auto;max-width:88%;padding:13px 16px;background:#f1f1f1;border-radius:20px;line-height:1.7}.attachment{display:flex;gap:10px;background:white;border-radius:12px;padding:10px;margin-bottom:9px;font-size:12px;align-items:center}.attachment small{display:block;color:var(--muted)}.activity{font-size:12px;color:#777;margin:13px 0 17px}.activity summary{cursor:pointer;list-style:none}.activity summary:before{content:"✓";background:#969ba1;color:white;border-radius:50%;padding:0 3px;margin-right:7px}.activity .detail{padding:10px 16px;border-left:1px solid var(--line);margin:8px 0 0 6px}.answer{line-height:1.85;font-size:15px}.answer p{margin:14px 0}.answer h3{font-size:16px;margin:20px 0 8px}.answer ul{padding-left:20px}.cite{font-size:11px;background:#f2f3f4;border-radius:7px;padding:2px 6px;text-decoration:none;margin-left:4px;white-space:nowrap}.media{margin:16px 0 20px}.image-button{padding:0;display:block;width:100%;text-align:left;border-radius:13px;overflow:hidden;background:#eee;position:relative}.image-button img{display:block;width:100%;height:172px;object-fit:cover}.image-button .expand{position:absolute;right:9px;bottom:9px;background:#fffE;border-radius:50%;width:30px;height:30px;display:grid;place-items:center}.media figcaption{font-size:11px;line-height:1.6;color:#737373;margin-top:7px}.media a{color:#666}.card{border:1px solid var(--line);border-radius:16px;padding:16px;margin:16px 0}.card-head{display:flex;justify-content:space-between;align-items:center}.card .sub{font-size:11px;color:var(--muted);margin-bottom:14px}.card .actions{display:flex;gap:9px;margin-top:14px}.actions button,.action{border:1px solid #dedede;border-radius:9px;padding:9px 12px;font-size:12px;flex:1}.actions .primary,.primary{background:#191919;border-color:#191919;color:white}.metric{padding:12px 14px;background:#f7f7f7;border-radius:10px;margin:12px 0}.metric small{display:block;color:#777;font-size:11px}.metric strong{font-size:25px;letter-spacing:-.5px}.filter{border:1px solid #ddd;border-radius:8px;padding:5px 9px;font-size:12px;background:white}.chart{width:100%;display:block;margin:12px 0}.chart text{font-size:10px;fill:#777}.chart button{cursor:pointer}.hint{font-size:11px;color:#777}.feedback{display:flex;gap:1px;color:#555;margin:16px 0}.feedback .icon{width:34px;height:34px}.feedback svg{width:17px;height:17px}.composer-wrap{padding:10px 12px 14px;background:white;border-top:1px solid #fafafa}.composer{background:#f4f4f4;border-radius:24px;padding:10px 12px}.composer textarea{width:100%;background:none;border:0;resize:none;outline:none;height:35px;line-height:1.6;padding:3px;font-size:13px}.composer-tools{display:flex;align-items:center;gap:8px}.composer-tools select{margin-left:auto;border:1px solid #e3e3e3;border-radius:22px;padding:5px 8px;background:#fff;font-size:11px;max-width:120px}.send{background:#171717;color:white;width:34px;height:34px}.send:hover{background:#333}.footer{color:#888;font-size:9px;text-align:center;padding-top:9px}.home-indicator{margin:0 auto 9px;width:110px;height:4px;border-radius:3px;background:#222}.inputs{display:flex;gap:10px}.inputs label{border:1px solid #ddd;border-radius:9px;padding:8px 10px;flex:1;font-size:11px;color:#737373;min-width:0}.inputs input{display:block;width:100%;border:0;padding:2px 0;color:#171717;font-size:16px;background:transparent}.range-label{display:flex;justify-content:space-between;margin:16px 0 2px;font-size:13px}input[type=range]{width:100%;accent-color:#171717}.muted{color:#777;font-size:12px}.reference-list{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.reference-list a{font-size:11px;background:#f5f5f5;padding:4px 9px;border-radius:18px;text-decoration:none}.toast{position:absolute;left:20px;right:20px;bottom:140px;background:#202020;color:white;font-size:12px;padding:12px 15px;border-radius:12px;z-index:8;display:none}.toast.show{display:block}.new-turn{border-top:1px solid #eee;margin-top:20px;padding-top:12px}.prototype-label{font-size:11px;color:#777;border:1px solid #ddd;padding:8px 12px;border-radius:9px;display:inline-block}.screen-modal{position:absolute;inset:58px 0 0;background:white;z-index:6;display:none;flex-direction:column}.screen-modal.open{display:flex}.modal-top{display:flex;align-items:center;gap:8px;border-bottom:1px solid #eee;padding:10px 12px;font-size:14px}.modal-body{padding:18px;overflow:auto;flex:1}.modal-body img{max-width:100%;height:auto;object-fit:contain;border-radius:10px}.modal-caption{color:#777;font-size:12px}.modal-composer{padding:12px;background:white}.modal-composer input{width:100%;border:0;background:#f4f4f4;border-radius:24px;padding:13px 16px;font-size:12px}.sidebar{position:absolute;inset:58px 65px 0 0;background:#fafafa;z-index:10;padding:20px;box-shadow:50px 0 0 15px #0003;display:none}.sidebar.open{display:block}.sidebar button{display:block;text-align:left;padding:12px 4px;width:100%;font-size:13px}.aside .rule{padding:12px 0;border-top:1px solid #ddd}.aside .rule strong{font-size:13px}.state-tools{margin:15px 0;display:flex;gap:6px;flex-wrap:wrap}.state-tools button{border:1px solid #d4d4d4;padding:5px 8px;border-radius:8px;font-size:11px}.sources-title{font-size:12px}.loading{animation:pulse 1.4s infinite}@keyframes pulse{50%{opacity:.45}}@media(prefers-reduced-motion:reduce){.loading{animation:none}}@media(max-width:1050px){.studio{grid-template-columns:230px 430px;justify-content:center;gap:28px}.right-aside{display:none}}@media(max-width:700px){body{background:white}.studio{display:block;margin:0}.aside{padding:14px 15px}.aside h1,.aside p,.aside .eyebrow,.aside .prototype-label,.aside .state-tools{display:none}.scenarios{display:flex;gap:5px;margin:0;overflow:auto}.scenarios button{white-space:nowrap;padding:7px 10px;font-size:12px;border-radius:20px}.scenarios small{display:none}.phone{width:100%;height:calc(100dvh - 65px);border:0;border-radius:0}.status,.home-indicator{display:none}.top{padding-top:4px}.thread{padding:4px 16px 18px}.screen-modal,.sidebar{top:52px}.image-button img{height:175px}}
@media(max-width:700px){.studio{display:flex;flex-direction:column;height:100dvh;overflow:hidden}.aside{width:100%;flex-shrink:0;padding:8px 12px}.scenarios{scrollbar-width:none}.scenarios::-webkit-scrollbar{display:none}.phone{flex:1;min-height:0;height:auto}.thread{min-height:0}.composer-wrap{flex-shrink:0}}
</style></head><body><main class="studio">
<aside class="aside"><div class="eyebrow">MATHSTUDYAGENT / CHAT DESIGN</div><h1>让回答<br>变得可以看、可以用。</h1><p>沿用采纳的 ChatGPT 风格。文字、图片、交互与文件，在一段自然的对话里衔接。</p><div class="prototype-label">交互设计预览 · 示例数据 · 未连接模型</div><nav class="scenarios" aria-label="预览场景"><button data-scene="images" class="active">01 图文检索<small>真实来源图片 · 原图查看 · 图中追问</small></button><button data-scene="files">02 文件与成果<small>进度展开 · 预览 · 继续修改</small></button><button data-scene="data">03 交互式分析<small>筛选 · 点选图表 · 带状态追问</small></button><button data-scene="parameters">04 参数探索<small>本地即时计算 · 明确提交</small></button></nav><div class="state-tools"><button data-status="working">处理中的样式</button><button data-status="error">失败的样式</button><button data-status="ready">完成的样式</button></div><p>点击顶部菜单可切换示例会话。展开内容时仍可继续输入。保存按钮导出设计示例 Markdown。</p></aside>
<section class="phone" aria-label="移动端聊天预览"><div class="status"><span>9:41</span><span>▮▮▮  ◉  ▰</span></div><header class="top"><button id="menu" class="icon" aria-label="打开历史侧栏"></button><strong>Chat <span style="font-size:13px;font-weight:400">⌄</span></strong><button id="newchat" class="icon" aria-label="回到当前示例"></button></header><div id="thread" class="thread"></div><form id="composer-form" class="composer-wrap"><div class="composer"><textarea id="draft" aria-label="消息输入" placeholder="继续追问，或添加文件" rows="1"></textarea><div class="composer-tools"><button type="button" class="icon" id="attach" aria-label="添加文件"></button><select aria-label="选择示例模型"><option>当前模型</option><option>示例模型 A</option><option>示例模型 B</option></select><button type="submit" class="icon send" aria-label="发送消息"></button></div></div><div class="footer">AI 的回答可能有误，请核对重要信息</div></form><div class="home-indicator"></div><div id="toast" class="toast" role="status"></div><div id="modal" class="screen-modal" role="dialog" aria-modal="true" aria-label="展开内容"><div class="modal-top"><button id="close-modal" class="icon" aria-label="返回对话">←</button><strong id="modal-title"></strong></div><div id="modal-body" class="modal-body"></div><form id="modal-form" class="modal-composer"><input id="modal-draft" aria-label="针对展开内容继续追问" placeholder="针对当前内容继续追问…"></form></div><nav id="sidebar" class="sidebar" aria-label="示例会话"><button id="close-sidebar">← 返回对话</button><h2>最近的聊天</h2><button data-scene="images">极光为什么会有不同颜色？</button><button data-scene="files">总结研究报告</button><button data-scene="data">季度销售分析</button><button data-scene="parameters">月供参数试算</button><p class="hint">示例会话，无真实聊天历史</p></nav></section>
<aside class="aside right-aside"><div class="eyebrow">DISPLAY PRINCIPLES</div><h2 style="margin-top:16px">内容决定展示方式</h2><div class="rule"><strong>图片靠近它解释的段落</strong><p>通常 1–3 张。每张图都有解释任务，不重复、不靠堆图营造丰富感。</p></div><div class="rule"><strong>来源随内容走</strong><p>配图显示原出处与图注，正文引用紧贴结论。检索照片与生成示意图明确区分。</p></div><div class="rule"><strong>轻量内容留在对话里</strong><p>一张卡片解决一个操作问题；复杂内容点开全屏，保留追问入口。</p></div><div class="rule"><strong>操作即时，提交明确</strong><p>筛选、点选和滑块先在本地更新。点击“分析”或发送消息，才继续对话。</p></div><div class="rule"><strong>进度来自真实任务</strong><p>步骤可展开；失败保留已完成内容。没有确定进度时不显示虚构百分比。</p></div><p><a href="design-spec.md" target="_blank">查看完整设计说明 ↗</a></p></aside>
</main><script>
const icons={menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',compose:'<path d="M12 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7M14 4l3-3 4 4-3 3-8 8-4 1 1-4z"/>',plus:'<path d="M12 4v16M4 12h16"/>',send:'<path d="M12 19V5M6 11l6-6 6 6"/>',expand:'<path d="M14 4h6v6M20 4l-7 7M10 20H4v-6M4 20l7-7"/>',copy:'<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M15 8V4H4v11h4"/>',like:'<path d="M7 10v10H3V10zM7 10l5-7 2 1v6h6l-2 10H7"/>',dislike:'<path d="M7 14V4H3v10zM7 14l5 7 2-1v-6h6L18 4H7"/>',audio:'<path d="M10 4L5 8H2v8h3l5 4zM14 8q4 4 0 8M17 5q7 7 0 14"/>',more:'<circle cx="4" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="20" cy="12" r="1"/>',doc:'<path d="M5 2h9l5 5v15H5zM14 2v6h5M8 12h8M8 16h6"/>'};
const ico=n=>'<svg class="i" viewBox="0 0 24 24" aria-hidden="true">'+icons[n]+'</svg>';
document.querySelector('#menu').innerHTML=ico('menu');document.querySelector('#newchat').innerHTML=ico('compose');document.querySelector('#attach').innerHTML=ico('plus');document.querySelector('.send').innerHTML=ico('send');
const nasa1='https://assets.science.nasa.gov/dynamicimage/assets/science/esd/eo/images/imagerecords/44000/44348/ISS023-E-58455_lrg.jpg?crop=faces%2Cfocalpoint&fit=clip&h=960&w=1440';
const nasa2='https://assets.science.nasa.gov/dynamicimage/assets/science/hpd/itm/Aurora_Smarphone_Lacey%20Young.jpg?crop=faces%2Cfocalpoint&fit=clip&h=3024&w=4032';
const source1='https://science.nasa.gov/earth/earth-observatory/aurora-australis-observed-from-the-international-space-station-44348/';
const source2='https://science.nasa.gov/sun/auroras/';
const pictures=[{src:nasa1,title:'从空间站看绿色极光',caption:'2010 年 5 月 29 日，国际空间站拍摄的南极光。图像由 NASA 提供。',url:source1},{src:nasa2,title:'同一片天空中的粉色与绿色',caption:'2024 年 10 月 10 日，马里兰州的极光。摄影：NASA / Lacey Young。',url:source2}];
let scene='images',selected=4,region='all',taskStatus='ready',toastTimer,modalContext='',modalOrigin;
let parameters={amount:100,years:30,rate:3.5};
const data={all:[12400,15600,18400,21800,31200,29000],east:[7400,9000,11200,12100,20000,18000],west:[5000,6600,7200,9700,11200,11000]};
const drafts={};const savedThreads={};
const money=n=>new Intl.NumberFormat('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}).format(n);
const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const feedback=()=>'<div class="feedback">'+['copy','like','dislike','audio','more'].map((n,i)=>'<button class="icon" aria-label="'+['复制示例回答','喜欢','不喜欢','朗读示例','更多'][i]+'" data-feedback="'+n+'">'+ico(n)+'</button>').join('')+'</div>';
function activity(label,detail){return '<details class="activity"><summary>'+label+'　⌄</summary><div class="detail">'+detail+'</div></details>'}
function user(text,file){return '<div class="user">'+(file?'<div class="attachment">'+ico('doc')+'<div>'+file+'<small>示例附件</small></div></div>':'')+text+'</div>'}
function figure(i){let p=pictures[i];return '<figure class="media"><button class="image-button" data-image="'+i+'" aria-label="放大：'+p.title+'"><img src="'+p.src+'" alt="'+p.title+'" loading="lazy" referrerpolicy="no-referrer"><span class="expand">'+ico('expand')+'</span></button><figcaption>'+p.caption+' <a href="'+p.url+'" target="_blank" rel="noopener noreferrer">原出处 ↗</a></figcaption></figure>'}
function imageScene(){return user('极光为什么会有不同颜色？')+activity('已检索资料 · NASA','本预览使用预先选定的 NASA 来源。正式产品显示实际检索的资料和选图结果。')+'<article class="answer"><p><strong>颜色取决于被激发的气体，以及粒子与大气相互作用的条件。</strong>先看绿色极光的实际照片。</p>'+figure(0)+'<h3>绿色和红色，主要来自氧</h3><p>氧可以发出绿色或红色的光。上图的绿色光带让这类颜色更容易辨认。<a class="cite" href="'+source1+'" target="_blank" rel="noopener noreferrer">NASA ①</a></p><h3>粉色和蓝色，与氮有关</h3><p>氮也会贡献不同颜色。下面这张照片同时出现粉色和绿色，可以直观看到极光不只有一种颜色。<a class="cite" href="'+source2+'" target="_blank" rel="noopener noreferrer">NASA ②</a></p>'+figure(1)+'<p>照片帮助辨认现象；单凭照片的颜色，不能确定当时的全部大气条件。</p><div class="reference-list"><a href="'+source1+'" target="_blank" rel="noopener noreferrer">① NASA Earth Observatory ↗</a><a href="'+source2+'" target="_blank" rel="noopener noreferrer">② NASA Science ↗</a></div>'+feedback()+'</article>'}
function fileScene(){let act=taskStatus==='working'?'<div class="activity loading">◌ 正在整理笔记… <button data-cancel>取消</button></div>':taskStatus==='error'?'<div class="card"><strong>文档生成中断</strong><p class="muted">已保留报告摘要；当前没有可下载文件。</p><div class="actions"><button class="primary" data-retry>重试生成</button><button data-use-summary>使用已有摘要</button></div></div>':activity('已读取 12 页 · 已生成文档','读取文本 → 提取结论 → 生成文档 → 检查文件；全部为设计示例。');return user('总结这份报告，整理成 Word。','研究报告.pdf')+act+'<article class="answer"><p>报告围绕研究背景、方法与结果展开。以下展示摘要与成果在对话中的排列方式。</p><h3>结论摘要</h3><ul><li><strong>研究方法：</strong>说明采用的方法及适用范围。<button class="cite" data-source-page="3">第 3 页</button></li><li><strong>主要结果：</strong>提取关键发现，保留证据来源。<button class="cite" data-source-page="8">第 8 页</button></li></ul>'+(taskStatus==='ready'?'<div class="card"><div class="card-head"><strong>'+ico('doc')+' 报告阅读笔记.docx</strong></div><div class="sub">Word · 18 KB · 版本 1 · 示例</div><div class="actions"><button data-preview>预览</button><button class="primary" data-save>保存</button></div></div><p>需要我再压缩成一页摘要吗？</p>':'')+feedback()+'</article>'}
function chart(){let a=data[region],points=a.map((v,i)=>[34+i*51,150-v/400]);let line=points.map(p=>p.join(',')).join(' ');return '<svg class="chart" viewBox="0 0 320 195" role="group" aria-label="可点选月份的销售趋势图">'+[0,1,2,3,4].map(i=>'<line x1="34" x2="295" y1="'+(150-i*25)+'" y2="'+(150-i*25)+'" stroke="#eeeeee"/><text x="4" y="'+(154-i*25)+'">'+i+'</text>').join('')+'<text x="4" y="30">万元</text><polyline points="'+line+'" fill="none" stroke="#426a92" stroke-width="2.5"/>'+points.map((p,i)=>'<g tabindex="0" role="button" aria-label="选择'+(i+1)+'月" data-month="'+i+'" style="cursor:pointer"><rect x="'+(p[0]-22)+'" y="35" width="44" height="145" fill="transparent"/><circle cx="'+p[0]+'" cy="'+p[1]+'" r="'+(selected===i?6:3.5)+'" fill="#426a92" stroke="white" stroke-width="2"/><text x="'+(p[0]-8)+'" y="175">'+(i+1)+'月</text></g>').join('')+'</svg>'}
function dataCard(){let a=data[region],total=a.reduce((x,y)=>x+y,0);return '<div class="card" id="data-card"><div class="card-head"><strong>销售趋势</strong><button class="icon" data-expand-data aria-label="展开销售趋势">'+ico('expand')+'</button></div><div class="sub">来源：季度销售.xlsx · Sheet1 · 示例数据</div><select class="filter" id="region" aria-label="按地区筛选"><option value="all"'+(region==='all'?' selected':'')+'>地区：全部</option><option value="east"'+(region==='east'?' selected':'')+'>地区：华东</option><option value="west"'+(region==='west'?' selected':'')+'>地区：华西</option></select><div class="metric"><small>累计销售额</small><strong>¥ '+total.toLocaleString('zh-CN')+'</strong></div>'+chart()+'<div class="hint">已选择 '+(selected+1)+' 月 · ¥ '+a[selected].toLocaleString('zh-CN')+' · 点选月份查看数值</div><div class="actions"><button class="primary" data-analyze>分析选中月份</button><button data-export>导出数据</button></div></div>'}
function dataScene(){return user('看看哪些月份增长最快。','季度销售.xlsx')+activity('已分析数据','载入数据 → 建立筛选与图表。当前使用固定的设计示例数据。')+dataCard()+'<article class="answer"><p>5 月环比增长最快。你可以先切换地区、点选月份，再让我分析当前选择。</p>'+feedback()+'</article>'}
function payment(rate=parameters.rate){const principal=parameters.amount*10000,n=parameters.years*12,r=rate/1200;return r?principal*r/(1-Math.pow(1+r,-n)):principal/n}
function paramsCard(){return '<div class="card" id="params-card"><div class="card-head"><strong>房贷月供试算</strong><button class="icon" data-expand-params aria-label="展开参数探索">'+ico('expand')+'</button></div><div class="sub">等额本息 · 本地公式计算 · 仅作估算</div><div class="inputs"><label>贷款金额 / 万<input id="amount" type="number" min="1" max="10000" value="'+parameters.amount+'" aria-label="贷款金额，万元"></label><label>期限 / 年<input id="years" type="number" min="1" max="50" value="'+parameters.years+'" aria-label="贷款期限，年"></label></div><label class="range-label" for="rate"><span>年利率</span><strong id="rate-value">'+parameters.rate.toFixed(1)+'%</strong></label><input id="rate" type="range" min="0" max="10" step=".1" value="'+parameters.rate+'" aria-label="年利率"><div class="metric"><small>预计月供</small><strong id="payment">¥ '+money(payment())+' / 月</strong><div class="muted" id="comparison">利率 3.0% 时：¥ '+money(payment(3))+' / 月</div></div><div class="actions"><button class="primary" data-explain>按当前参数解释</button><button data-reset>恢复默认</button></div><p class="hint">调整参数即时计算；点击解释才提交当前参数。</p></div>'}
function paramScene(){return user('利率变化会怎样影响我的月供？')+activity('已建立计算模型','固定利率、等额本息；未计入费用、浮动利率等因素。')+paramsCard()+'<article class="answer"><p>拖动滑块，月供会随利率立即变化。需要解释时，把当前参数带回对话。</p>'+feedback()+'</article>'}
function toast(text){let e=document.querySelector('#toast');e.textContent=text;e.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>e.classList.remove('show'),3000)}
function render(){document.querySelector('#thread').innerHTML=({images:imageScene,files:fileScene,data:dataScene,parameters:paramScene}[scene])()+(savedThreads[scene]||'');document.querySelectorAll('[data-scene]').forEach(b=>b.classList.toggle('active',b.dataset.scene===scene));document.querySelector('#draft').value=drafts[scene]||''}
function switchScene(next){drafts[scene]=document.querySelector('#draft').value;scene=next;closeModal();document.querySelector('#sidebar').classList.remove('open');render();document.querySelector('#thread').scrollTop=0}
function addTurn(prompt,answer){const content='<div class="new-turn">'+user(escape(prompt))+'<div class="hint">交互演示回复 · 未调用模型</div><article class="answer"><p>'+escape(answer)+'</p></article></div>';savedThreads[scene]=(savedThreads[scene]||'')+content;document.querySelector('#thread').insertAdjacentHTML('beforeend',content);document.querySelector('#thread').scrollTo({top:document.querySelector('#thread').scrollHeight,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'})}
function openModal(title,content,context){modalOrigin=document.activeElement;modalContext=context;document.querySelector('#modal-title').textContent=title;document.querySelector('#modal-body').innerHTML=content;document.querySelector('#modal').classList.add('open');document.querySelector('#thread').inert=true;document.querySelector('#composer-form').inert=true;document.querySelector('.top').inert=true;document.querySelector('#close-modal').focus()}
function closeModal(){document.querySelector('#modal').classList.remove('open');document.querySelector('#thread').inert=false;document.querySelector('#composer-form').inert=false;document.querySelector('.top').inert=false;if(modalOrigin?.isConnected)modalOrigin.focus()}
function download(name,content,type){let url=URL.createObjectURL(new Blob([content],{type}));let a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
document.addEventListener('click',e=>{let t=e.target.closest('button,[data-month]');if(!t)return;if(t.dataset.scene)return switchScene(t.dataset.scene);if(t.dataset.status){taskStatus=t.dataset.status;return switchScene('files')}if(t.dataset.image!==undefined){let p=pictures[Number(t.dataset.image)];return openModal(p.title,'<img src="'+p.src+'" alt="'+p.title+'"><p class="modal-caption">'+p.caption+' <a href="'+p.url+'" target="_blank" rel="noopener noreferrer">查看原出处 ↗</a></p>',p.title)}if(t.dataset.month!==undefined){selected=Number(t.dataset.month);if(document.querySelector('#modal').classList.contains('open'))document.querySelector('#modal-body').innerHTML=dataCard();else render();return}if(t.hasAttribute('data-expand-data'))return openModal('销售趋势',dataCard(),'销售趋势：'+region+'地区，'+(selected+1)+'月');if(t.hasAttribute('data-expand-params'))return openModal('房贷月供试算',paramsCard(),'月供试算');if(t.hasAttribute('data-preview'))return openModal('报告阅读笔记 · 版本 1','<article class="answer"><div class="hint">内容预览示例；没有生成真实 DOCX 文件。</div><h2>报告阅读笔记</h2><h3>研究背景</h3><p>这里显示文档摘要，保留标题、段落与引用。</p><h3>方法与结果</h3><p>原页引用可返回来源核对。用户可以在下方直接要求缩短、改写或补充。</p></article>','报告阅读笔记 · 版本 1');if(t.hasAttribute('data-save')){download('报告阅读笔记-设计示例.md','# 报告阅读笔记（设计示例）\n\n本文件是交互预览导出的 Markdown，不是真实报告摘要或 Word 成果。\n','text/markdown;charset=utf-8');return toast('已导出设计示例 Markdown；真实产品保存实际成果')}if(t.hasAttribute('data-analyze')){let a=data[region],v=a[selected],prev=a[selected-1];closeModal();render();return addTurn('分析'+(selected+1)+'月，地区：'+({all:'全部',east:'华东',west:'华西'}[region]),'当前销售额 ¥ '+v.toLocaleString('zh-CN')+(prev?'，较上月变化 '+((v/prev-1)*100).toFixed(1)+'%。':'；没有前一月数据可计算环比。'))}if(t.hasAttribute('data-export')){download('销售数据-设计示例.csv','月份,销售额\n'+data[region].map((v,i)=>(i+1)+'月,'+v).join('\n'),'text/csv;charset=utf-8');return toast('已导出当前筛选的示例数据')}if(t.hasAttribute('data-explain')){closeModal();render();return addTurn('解释当前方案：'+parameters.amount+' 万，'+parameters.years+' 年，年利率 '+parameters.rate.toFixed(1)+'%','按等额本息公式，预计月供 ¥ '+money(payment())+'。本示例只演示参数带入对话，不调用模型。')}if(t.hasAttribute('data-reset')){parameters={amount:100,years:30,rate:3.5};if(document.querySelector('#modal').classList.contains('open'))document.querySelector('#modal-body').innerHTML=paramsCard();else render();return}if(t.hasAttribute('data-cancel')){taskStatus='error';render();return toast('已取消示例任务，摘要保留')}if(t.hasAttribute('data-retry')){taskStatus='working';render();return toast('已切换到处理中设计状态；不执行文件任务')}if(t.hasAttribute('data-use-summary'))return toast('已有摘要可继续阅读；没有可下载成果');if(t.dataset.sourcePage)return openModal('来源 · 第 '+t.dataset.sourcePage+' 页','<p>正式产品在此展示原 PDF 页面及相关位置。</p><p class="hint">预览未附加真实 PDF，不伪造原页。</p>','报告第 '+t.dataset.sourcePage+' 页');if(t.dataset.feedback==='copy'){navigator.clipboard?.writeText(document.querySelector('#thread').innerText).then(()=>toast('已复制示例内容')).catch(()=>toast('浏览器未允许复制'));return}if(t.dataset.feedback)return toast('这是 '+({like:'评价',dislike:'评价',audio:'朗读',more:'更多操作'}[t.dataset.feedback])+' 入口的样式示例')});
document.addEventListener('change',e=>{if(e.target.id==='region'){region=e.target.value;if(document.querySelector('#modal').classList.contains('open'))document.querySelector('#modal-body').innerHTML=dataCard();else render()}});
document.addEventListener('input',e=>{if(['rate','amount','years'].includes(e.target.id)){let v=Number(e.target.value),min=e.target.id==='amount'?1:e.target.id==='years'?1:0,max=e.target.id==='amount'?10000:e.target.id==='years'?50:10;if(!Number.isFinite(v)||v<min||v>max){e.target.setCustomValidity('请输入 '+min+' 到 '+max+' 之间的数值');return}e.target.setCustomValidity('');parameters[e.target.id]=v;document.querySelector('#rate-value').textContent=parameters.rate.toFixed(1)+'%';document.querySelector('#payment').textContent='¥ '+money(payment())+' / 月';document.querySelector('#comparison').textContent='利率 3.0% 时：¥ '+money(payment(3))+' / 月'}});
document.addEventListener('keydown',e=>{if(e.key==='Escape'){closeModal();document.querySelector('#sidebar').classList.remove('open')}if(e.target.hasAttribute('data-month')&&(e.key==='Enter'||e.key===' ')){e.preventDefault();e.target.dispatchEvent(new MouseEvent('click',{bubbles:true}))}});
document.querySelector('#menu').onclick=()=>document.querySelector('#sidebar').classList.add('open');document.querySelector('#close-sidebar').onclick=()=>document.querySelector('#sidebar').classList.remove('open');document.querySelector('#newchat').onclick=()=>{savedThreads[scene]='';render()};document.querySelector('#attach').onclick=()=>toast('正式产品在此选择图片、文件或拍照；预览不上传');
document.querySelector('#close-modal').onclick=closeModal;
document.querySelector('#composer-form').onsubmit=e=>{e.preventDefault();let input=document.querySelector('#draft'),text=input.value.trim();if(!text)return;addTurn(text,'消息已进入演示对话。正式产品将调用当前模型，并按需要组合文字、图片、交互内容和文件。');input.value='';drafts[scene]=''};
document.querySelector('#modal-form').onsubmit=e=>{e.preventDefault();let input=document.querySelector('#modal-draft'),text=input.value.trim();if(!text)return;let context=scene==='data'?'销售趋势，'+region+'，'+(selected+1)+'月':scene==='parameters'?parameters.amount+'万 / '+parameters.years+'年 / '+parameters.rate+'%':modalContext;closeModal();render();addTurn(text+'（针对：'+context+'）','已携带当前内容与状态返回对话。此处演示交互路径，未调用模型。');input.value=''};
document.addEventListener('error',e=>{if(e.target.tagName==='IMG'){e.target.alt='来源图片暂时无法加载，请打开原出处查看';e.target.style.height='90px';e.target.style.objectFit='contain'}},true);
render();
</script></body></html>

```

## 10. 交付约定

实现完成后，请交付：改动文件清单、真实用户路径说明、与本文件验收清单对应的验证结果、运行截图及具体剩余限制。完成的功能必须有实际行为证据；不可测项明确说明原因。不要把原型截图、演示回复、旧 APK 或只挂载 Widget 的测试当作当前真实能力证明。

本文件可单独交给 Opus 5.5。仓库中的 two-page-preview.png（用户采纳的基础图）、smart-chat-three-scenes.png（视觉板）及 image-chat-mobile.jpg（原型截图）为可选补充，不是理解和还原本文设计的必要依赖。视觉板的示例图表可能存在生成偏差，数值逻辑以本文件原型源码和真实返回数据为准。