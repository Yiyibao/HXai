# 图片创作模块实施记录

后续已完成原创样式、用途配置与个人模板闭环，详见 [图片样式与个人模板交付](image-creation-templates-20261011.md)。

日期：2026-10-11（Asia/Taipei）。按已采纳的 [四屏样式稿](ui-design/image-system-v1/README.md) 实施。

## 已实现

- 侧栏“图片”接入真实图片库：搜索、按日期双列展示、空态和任务失败入口。
- 聊天附件菜单“创建图片”；参考图输入、比例和经济/标准/精细质量，每次一张。
- 聊天中展示真实生成状态、停止、生成图片、修改、保存和系统分享。
- 全屏缩放、来源聊天、版本缩略图、基于所选版本修改；保留旧图片与版本关联。
- 独立本机图片配置，复用已有加密提供商凭据，前端不读取明文密钥。
- 通过现有 StudyPalette 和全局主题读取高亮色，按钮、选中标签、版本边框、文字光标随高亮色设置同步，支持深色。
- 任务按档案持久化，原始请求 ID 防重复；进程重开将未完成任务标记中断，必须手动重试。关闭页面不取消本进程任务，后台进程被系统终止时不宣称仍在执行。
- 图片经解码、尺寸限制、PNG 落盘和文件摘要校验后才登记成功。生成任务关联原会话，独立节点更新使用会话锁和原档案归属。
- 聊天中既有 generate_material_image 工具采用单张、1024×1024、low 质量，并将成功图片登记到图片库。

## 实际模型与接口决定

用户最初要求 gpt-6.1-sol／低推理等级／Responses。新生图密钥的 `/models` 仅返回 gpt-image-2.5-sunburst 与 gpt-image-2.5-flare；对 gpt-6.1-sol 的最小 Responses 请求返回 404 model_not_found，提示该分组没有支持账户。用户随后明确“使用该密钥支持的方式生图”。

因此模拟器新增独立的“OpenAI 图片创作”提供商，配置上述两个图像模型，默认 sunburst；生成走 `/images/generations`，参考图修改走 `/images/edits`。聊天模型与原凭据保留，当前助手推理等级设为低。直接图像模型没有聊天推理等级参数；经济质量映射为 quality=low，不能声称它使用 gpt-6.1-sol。

模块另保留 Responses 图像工具路径，使用 reasoning.effort=low、store=false，适用于另行配置且服务实际支持的 Responses 模型；不使用 previous_response_id，不自动重发付费请求。

## 验证及限制

- Flutter 全量回归 269 项通过；生图和高亮色专项覆盖即时切色、浅/深色、320px 大字体、失败保留草稿、空库、档案切换撤销查看。
- 图像协议 JVM 测试 5 项通过，覆盖 Responses、直接图像接口、单张低质量、最终结果判定和无效参数拒绝。
- Android 专项 2 项通过。本地 HTTP 服务测试实际执行生成与 multipart 编辑，验证 PNG 文件、原图摘要、版本关系、跨档案读取拒绝、重复请求不重复提交、取消请求，以及恢复不自动发请求。
- 模拟器界面入口测试通过：侧栏→图片库→创建图片→聊天图片创作输入框，没有触发付费请求。实拍见 [图片库](../qa/image-creation-20261011/image-library-runtime.png) 与 [创作输入框](../qa/image-creation-20261011/image-composer-runtime.png)。截图中的蓝色为当时的本机高亮色。
- 真实服务测试：一次 gpt-6.1-sol 能力拒绝；两次 sunburst 最小图像生成请求（第二次移除 output_format 排查兼容性）和一次 flare 最小生成请求。两个图像模型均返回 upstream_error / Upstream service temporarily unavailable，没有获得真实生成图片。没有把 HTTP 200 的错误 JSON 当成功，没有继续反复请求。
- 真实生成效果、真实参考图修改、具体计费和目标 Mate 50 验收尚未完成；本地模拟接口测试不能替代这些验证。
- 首版仅整体参考图编辑；区域涂选、批量创作、图片库删除和版本并排比较未加入本轮。

## 主要实现

- Kotlin：feature/imagecreation/ImageCreation.kt；Flutter：pages/image_creation_page.dart、providers/image_creation_providers.dart。
- 接入：StudyHostFacade、ChatService、ChatToolFactory、MaterialTools、Flutter Chat/侧栏/附件菜单/路由。
- 新桥接通过已有 frontend_features 通道执行，所有命令继续检查档案与上下文版本；保存分享复用现有受保护文件接口。
- 验证日志：`.local/image-creation-*.log`。QA 中的 live-generation-metadata.json 仅含脱敏接口结论，无密钥。

当前交付为客户端模块与模拟器构建；更换服务后真实生成接口已返回图片，应用界面到真实服务的完整链路及真实参考图修改仍待验收。APK 位于 `client/app/build/outputs/apk/debug/`；Flutter 资产与最终 APK 对应关系见 `qa/image-creation-20261011/apk-verification.json`。模拟器覆盖安装保留原应用数据。


## 更换生图服务（2026-10-11）

用户另提供 apinode 服务与新凭据，要求更换生图模型。模型目录实际返回 `gpt-image-2`；模拟器原“OpenAI 图片创作”提供商已切换至 `https://apinode.ltd/v1`，默认选择该模型，使用直接 Images 接口。原聊天配置保留。新凭据只存入应用加密凭据文件，文档与源码不含密钥。

一次真实 `/images/generations` 请求（n=1、quality=low、1024×1024、PNG）成功，HTTP 200 返回单张 base64 PNG。已检查解码及图像内容；测试图见 [真实测试图](../qa/image-creation-20261011/apinode-low-test.png)，脱敏结果见 `qa/image-creation-20261011/apinode-generation-metadata.json`。未重复发送或测试付费编辑。此次验证为真实生成接口测试，尚不能替代应用 UI 完整链路与参考图修改验收；此前 xinyue 上游失败记录保留。


## 继续开发与真实链路验证（2026-10-11）

- 输入框改为监听控制器实际文本变化，使程序或无障碍写入提示词后及时更新提交状态；选择位置变化不改变幂等请求 ID。提交桥接期间只读，防止提交中的提示词被修改。
- 聊天图片预览按请求比例预留空间，解码期间展示加载指示，避免高度为零造成跳动；图片保留完整内容并增加版本无障碍标签。
- 直接 multipart 参考图编辑不再生成无用 base64/Responses 请求副本，降低大图内存开销；Responses 路径继续保留其必要编码。
- 新增显式启用的 Android 真实服务 UI 测试，默认不会发送付费请求；支持复用已保存的原图与编辑版本验证界面，输入必须与测试文本一致才允许提交。

本轮从 Flutter 界面提交的真实生成成功，并实际完成一次参考图编辑，生成 v2，与原图关联同一会话。原图的 SHA-256 与编辑前一致，两张图片内容不同。重开应用后复用这些结果完成 UI 验证：图片库、全屏 v1/v2 切换、来源聊天、真实图片预览，测试通过且不再发送请求。实拍见 [聊天结果](../qa/image-creation-20261011/image-live-chat.png)、[版本查看](../qa/image-creation-20261011/image-live-edit.png)、[图片库](../qa/image-creation-20261011/image-live-library.png)。

本轮共实际发出两次生成与一次编辑，均为 n=1、quality=low：第一次生成因测试错误提前结束应用进程，没有获得结果，费用未知；第二次成功。编辑测试通过模拟器键盘注入的英文被中文输入法改写，服务实际使用改写后的提示词生成，不能将该图片视为“蓝色圆形改绿色”的效果验收。已移除这条注入方式并增加提交前文本一致性检查，后续没有重发付费编辑。任务成功、PNG 落盘、参考图传输和版本关联已经验证，精确修改意图及目标真机仍待验收。

Flutter 最新生图与高亮色专项 13 项通过；图像协议 JVM 5 项通过；Android 本地接口测试仍覆盖生成、multipart 编辑、隔离、幂等、取消和恢复。最终 APK 已覆盖安装到模拟器，32 项 Flutter 资产与 AAR 完全匹配。构建与测试日志见 `.local/image-creation-next-*.log`，复用真实结果的界面测试见 `.local/image-creation-live-ui-persisted.log`。


## ChatGPT 风格加载动效（2026-10-11）

按用户明确的 ChatGPT 生图加载样式需求实现接近的等待动效：低对比度模糊色块缓慢流动、柔光扫过与固定细颗粒；图片完成并解码后 650ms 从轻微模糊渐显。该动效为本地装饰占位，不模拟完成百分比或冒充服务生成的中间图片。聊天卡片、图片库等待缩略图与最终图片解码等待共用组件。

色块使用 StudyPalette 当前高亮色，支持浅色和深色；系统减少动画设置启用时停止循环并直接显示完成图片，隐藏页面遵循 TickerMode。动画绘制独立 RepaintBoundary，通过 CustomPainter repaint 避免每帧重建聊天。Flutter 与高亮色专项 14 项通过，新增减少动画、主题更新与销毁覆盖。模拟器预览专项通过，临时等待状态在 finally 中恢复，没有生图接口请求；[动效预览](../qa/image-creation-20261011/image-loading-preview.gif) 是原生界面录制的 8 帧示例。

实现：`client/flutter_ui/lib/widgets/image_generation_loading.dart`。最终构建覆盖安装保留凭据与图片，APK 资产校验记录已更新。


## 按截图校正为点阵波纹（2026-10-11）

用户提供 38% 与 70% 两张 ChatGPT 参考截图，明确要求固定规则点阵、圆点大小和透明度形成移动波纹、右下进度胶囊。此前云雾理解有误，本轮将等待绘制器替换为白底点阵（深色对应主题背景）；圆点固定位置、随缓慢移动的二维波场改变大小与透明度，颜色直接取全局高亮色。完成图片渐显、减少动画和隐藏页面停帧能力保留。

组件新增可选 progress（0..1）以承接真实服务进度。现有非流式图像请求没有百分比数据，所以界面胶囊显示“生成中”，不按时间伪造 38%/70% 或 90%。只有传入真实 progress 时显示对应百分比。Flutter 专项 15 项通过，覆盖 38%、70% 与未知进度三种展示、高亮色、主题与减少动画。此次样式校正与预览不调用生图接口。

点阵版本模拟器预览测试通过，8 帧动效见 [点阵加载预览](../qa/image-creation-20261011/image-dot-preview.gif)。覆盖安装与 32 项 Flutter 资产校验已通过，预览任务记录已恢复。
