# AI Agent 项目现状摘要

审计日期：2026-10-11，Asia/Taipei。工作区：`D:/Office/Study/code/MathStudyAgent`。完整报告：[PROJECT_CURRENT_STATE_FULL.md](PROJECT_CURRENT_STATE_FULL.md)。本摘要可独立供后续模型理解项目；详细代码索引、功能矩阵、API、覆盖清单与十幅 Mermaid 图见完整报告。

## 审计口径与仓库状态

本次是只读当前工作树审计，没有改业务代码、配置、依赖、数据库，没有启动App、构建、运行会写产物的测试、调用付费模型或访问生产服务器。唯一新增文件是这两份报告。直接代码证据称“已验证”，但**不是本次真机/线上运行通过**；“代码推断”不等于已复现，“未发现实现”不等于绝对不存在。

根Git分支 `master`，HEAD `f603719909f342fabfcedf5c9a0cfcb9116194bd`；根remote列表为空。初始工作区已有8,378项Git状态：192修改、23删除、8,163未跟踪，含产物。因此结论对应包含大量未提交工作的现状，不能简单用HEAD重现。未切换/提交/清理这些改动。

## 项目定位与运行形态

这是从RikkaHub演进的Android手机AI聊天与工具应用。应用ID `org.hxsi.agent`，版本声明 `0.1.0-dev`，许多Kotlin包/Gradle名称仍是rikkahub。当前以通用Chat为入口，数学学习场景通过教学提示、档案、学习记忆、符号核对与数学图形工具加强。

实际系统分三部分：

1. **Flutter主UI + Kotlin Android宿主**。Flutter通过Pigeon下发命令，通过EventChannel接收变化；ChatService、Agent循环、Provider、Room、材料、凭据在宿主。部分设置/Skills导入/备份跳转Compose旧页面。
2. **独立Linux文档服务**。FastAPI + SQLite jobs + 单dispatcher，调用隔离的MinerU/LibreOffice worker，负责文件解析和Office排版PDF转换，不是聊天后端。
3. **保留但停用/未闭合的扩展**。React SPA与手机Ktor Web、workspace shell、MCP工具等有源码，但当前主Chat不开放相应能力。

主Chat是“Flutter → 同进程Kotlin → 外部模型/工具 → 本地持久化 → EventChannel → Flutter”，没有通过Python聊天服务。Manifest Launcher为 `StudyFlutterActivity`，Application为 `RikkaHubApp`。后者在Koin初始化前处理pending restore，再启动记忆Runner、WorkManager和目录/缓存清理，因此本次没有通过启动App来做只读验证。

定位证据：`client/app/src/main/AndroidManifest.xml:105–115`；`client/app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt:56–110`；`client/app/src/main/java/me/rerere/rikkahub/flutter/StudyFlutterActivity.kt:71–151`。

## 核心技术栈与外部依赖

Android声明minSdk26/targetSdk37/compileSdk37/Java17，Kotlin2.4.20、AGP9.4.0、Compose、Koin、Room2.8.5、DataStore、OkHttp5.5.0、Ktor3.5.2、WorkManager。Flutter声明Dart `^3.13.5`、Riverpod `^3.4.3`、GoRouter `^18.0.2`、Pigeon、Flutter math/Markdown/highlight、fl_chart。React保留工程声明React19.2.4、Router7.13.0、TypeScript/Tailwind/Zustand/KaTeX等。以上是仓库声明，不是本次安装验证的版本。

Python服务依赖FastAPI0.142.2、uvicorn0.54.0、httpx/Pillow等，有哈希锁文件；解析明确使用 **MinerU4.0.10**。Office明确使用 **LibreOffice headless + Python UNO**，历史QA记录LibreOffice25.2.3.2，当前生产版本未验证。Docker worker离线使用本地模型目录；模型权重、真实镜像、服务证书和认证配置未检查。

外部依赖还包括用户配置的LLM/图像/OCR/语音服务、搜索与网页、GitHub skill下载、Android SAF/Keystore和Linux Docker/systemd/nginx。没有发现当前文档任务使用Redis/Celery或分布式队列。

主要位置：`client/settings.gradle.kts:33–53`、`client/gradle/libs.versions.toml:1`、`client/flutter_ui/pubspec.yaml:1`、`server/document-service/requirements-worker.in:1`、`server/document-service/worker/run.py:110–129`、`server/document-service/office_worker/run.py:23–84`。

## 已接入的主要能力与部分完成的能力

**代码已接入**：普通/教学Chat，三类模型协议，多轮工具调用，审批/AskUser，流式文字/思考/工具显示，幂等输入与持久队列，中断恢复与取消，长对话摘要，网页搜索/文字读取/图片候选，学习档案与记忆，Skills安装/启用/use_skill，文件材料库/真实生成/有界编辑/版本，数学Markdown/曲线/表格/交互组件，图像生成任务/参考图修改/版本/模板，加密备份/恢复与诊断。

**部分验证或能力范围有限**：复杂Office格式/排版处理、数学题图片/手写/符号正确识别、数学解题正确性、图像API兼容和计费取消、远程MinerU/UNO生产链、ASR/TTS逐供应商完整链、主Flutter的单独会话fork入口。代码有实质实现，但本次未完成运行验证；“支持格式”不能泛化为完整Office编辑器。

**未发现有效当前实现**：独立Chat/Work双模式、消息意图分类自动模型路由、自动Provider fallback、独立planner/DAG、Agent并行工具执行、分布式任务平台、向量记忆RAG、任意Skill脚本/依赖自动执行、mask选区图片编辑、数学CAS/形式证明器、全链金额预算/费用账本、独立iOS发布与通用云会话同步。

`StudyMode` 的CHAT/GUIDED/HINT/CHECK/EXPLAIN是教学风格，不能作为Work模式证据。MCP不能写成完全没代码：客户端/授权/transport真实存在，只是主Chat因profile权限契约未闭合而不加入工具。

## 智能Chat与Agent的核心机制

Flutter快照文字/附件与profile/epoch，Pigeon调用StudyHostFacade。宿主校验owner和epoch，requestId与内容指纹形成幂等receipt，附件必须是固定profile的verified材料；再 `sendMessageCommitted` 入会话/队列。ChatService选择 `assistant.chatModelId ?: settings.chatModelId`，构造工具、记忆、prompt/transformers，调用GenerationLoop。

循环最多256步：模型stream → 普通输出或tool calls → 按需审批 → 校验参数 → **顺序执行**工具 → 持久化结果 → 下一轮模型。执行前RUNNING和执行后结果都有durable checkpoint，先DB commit再ack。副作用结果未知时中断并要求用户处理，默认不自动重放；进程重启标INTERRUPTED。特殊IOException有限重试，收到部分stream后不自动重试，没有模型/Provider自动切换。

工具选择由当前LLM在开放catalog中决定，属于具备持久控制的多步Chat Agent；没有独立规划器。默认工具审批为false，并非所有文件操作都必须用户逐次同意。当前factory关闭旧memory、workspace和MCP tools，但仍提供搜索、材料、远程文档（配置/policy允许时）、数学图、交互、Skills和少量可选本地工具。

系统提示由assistant/conversation prompt、通用/教学规则、learning memory、文件调度、联网/交互及各tool prompt拼装。上下文按selected branch，过滤隐藏来源；预算按字符估算，默认16,000、可设4,000–64,000，用当前Chat模型有界摘要，不是精确tokenizer或自动读取模型window。标题/建议、手动压缩、OCR、图像分别用各功能配置，这些角色配置不等于智能模型router。

证据：`client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt:807–855`；`client/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:753–935`；`client/app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt:79–351`；`client/app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt:16–99`。

## 模型体系

ProviderManager注册OpenAI、Google、Claude适配器。OpenAI支持Completions/可选Responses、embedding/image等；Google支持Gemini、可选Interactions及Vertex；Claude有messages/stream但明确不支持原生图像生成。Compatible endpoint的真实服务商/能力取决于配置。

Model含modelId/UUID、CHAT/IMAGE/EMBEDDING、TEXT/IMAGE模态、TOOL/REASONING、customHeaders/body、内置工具。Registry是名称匹配的能力默认规则，不是已启用模型清单。实际设备模型和真实凭据没有读取，不能断言当前在用哪一款模型。TokenUsage有统计，未发现覆盖摘要/OCR/记忆/图像等全部请求的金额账本。

## 记忆现状

现行学习记忆为Room `learning_memory_state(profile_id,payload)` 的JSON状态，不是向量数据库。来源只从当前分支非空USER文字建立，含conversation/node/message、HMAC、revision与本地时间；类型分背景/偏好/进展/困难/表现/复习建议，并记录evidence、来源与版本历史。

无异常Chat完成后8个来源一批异步抽取；沿用当前Chat模型，90秒超时、每日20次、最多3attempts、lease/epoch/revision检查。strict JSON限制候选和来源，reducer做去重/冲突处理；无冲突非表现自述可ACTIVE，其他保留CANDIDATE，冲突DISPUTED，手工编辑不自动覆盖。

手工CRUD和显式“记住”有真实实现；显式保存须匹配用户文字/已知来源。删除item/history并保留HMAC抑制、增加epoch、隐藏相关prompt来源，避免旧消息自动复活，但不等于删除源聊天或全盘安全擦除。召回是词法/同义词/二元片段、最多8项、约3,600字符内容，不调用embedding。WorkManager是后台辅助，不保证force-stop或电池限制下实时运行。

证据：`client/app/src/main/java/me/rerere/rikkahub/feature/memory/LearningMemoryStore.kt:112–260`；`client/app/src/main/java/me/rerere/rikkahub/feature/memory/MemoryState.kt:62–198`；`client/app/src/main/java/me/rerere/rikkahub/feature/memory/MemoryExtractionRunner.kt:20–122`。

## Skills与MCP现状

Skills支持MD/ZIP/GitHub导入、安全frontmatter、canonical路径和大小/层数/entry限制，staging/backup/rename安装、同名替换、卸载。当前Flutter导入页跳Compose；assistant.enabledSkills和内置数学skill组成catalog，模型通过use_skill读取正文/允许文本，资产导入当前profile材料后交给现有工具。

`.py/.js/.sh/.ps1`可作为文本读取，**没有Skill专属脚本runtime/依赖安装器**；allowed-tools只是元数据，没有确认完整权限enforcement。Compatibility评级是静态判断。`SkillsVM.saveSkill`返回false，创建/编辑未闭合，builtin skill-creator不能改变结论。Skills库/assistant启用关系部分共享，不能称为每profile独立插件环境。

MCP保留Client、SSE/StreamableHTTP、listTools/callTool、OAuth/重连/配置观察；保存配置可能仍尝试连接。但ChatToolFactory明确不加入其工具，Flutter完整MCP入口未确认；stdio/完整prompts/resources消费未发现当前有效链。

证据：`client/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt:25–99`、`:195–241`；`client/app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt:18–117`；`client/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsVM.kt:39–139`；factory `:97`。

## 文件处理与独立文档服务

材料按profile/UUID保存私有副本+元数据/SHA，30MiB、verified打开/保存。txt/md/csv/json严格UTF-8；PDF用MuPDF text layer与独立page raster；DOCX读取main XML/表格/部分OMML，XLSX读sheet/cell/公式文本与cache，PPTX按relationship读逻辑slide文字。DOC/XLS/PPT可导入保管但当前reader不解析。

create_material_file生成真实md/pdf/docx/xlsx/pptx。手工OOXML writer支持基础layout/表格/图片/有限可编辑OMML、单sheet表格、简单文字slides；不是完整Office应用。编辑需expectedSHA/预期原文，输出新版本：简单段落/text run、非公式cell、PDF组页。公式cache清理后靠Office重算。文本导出PDF与原Office排版转换明确区分。

远程PDF/image解析是 **MinerU basic**，最多200页、每request5页，结构blocks/text/可用latex/图片与coverage；没有可靠bbox时明确warning。Office OOXML转PDF是 **LibreOffice UNO**，包含Calc calculateAll、renderer/fonts/page信息和有界package/output检查。客户端配置默认关闭，HTTPS/Bearer owner、sourceSHA、幂等key、download manifest/assets验证；任务落盘后提交，上传未知不后台盲重传，下载后可用稳定消息续接Chat。

Server使用SQLite owner队列/storage reservation、单dispatcher、固定无网络/nonroot/read-only/CPU内存限额容器、隔离validator。重启将旧RUNNING任务失败，不自动重复执行；结果有限期保留，delete/cancel与物理cleanup有时间差。文档上传regex policy只管这个服务，不能当成普通Chat/OCR绝不发送数据的全局local-only设置。

证据：`client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialReader.kt:19–142`；`client/app/src/main/java/me/rerere/rikkahub/feature/material/MaterialWriter.kt:18–224`；`client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceRepository.kt:263–367`；`server/document-service/api/main.py:51–158`；`server/document-service/dispatcher/main.py:59–192`。

## 图片、数学与交互输出

vision输入经FileEncoder方向/采样/压缩/base64处理；非vision OcrTransformer调配置OCR模型，未配置可退化占位。扫描PDF要页图或MinerU，不能把text layer提取当OCR。模糊符号核对和数学提示规则存在，但手写/几何/教师考试题识别及解题没有本次benchmark或CAS/proof保证。

数学工具/内置skill可生成函数、参数、隐式曲线与PNG；Surface双侧版本1–4/大小/组件/数值校验，支持表格/图表/slider/select/button/calculation，受限表达式不是任意JavaScript/HTML执行。PNG快照不自动跟随用户后来调参更新。

图像生成有两条路径：Chat工具调用Provider；图像页ImageCreation独立OpenAI-compatible HTTP。后者支持generations、参考图edits、或Responses image_generation，单张/尺寸质量/最多4引用、任务和版本模板、PNG验证后落材料并稳定插入Chat。没有mask选区链。每profile一个RUNNING，5分钟HTTP，付费失败/中断不自动重试；cancel无法证明服务商已停止收费，进程恢复为INTERRUPTED而非自动取回远程结果。

## 已确认的问题与架构约束

以下事实有代码证据，影响仍可能需要复现；不是本次优化方案：

| 事实 | 当前影响/限制 |
|---|---|
| Flutter依赖只有debugImplementation（`client/app/build.gradle.kts:326`），主Launcher是Flutter | release链不对称；本次未构建，不能报告release已通过或必然失败 |
| OCR完整识别文字写Log.i（`client/app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt:109`） | 潜在私人内容进入logcat；未读取真实日志 |
| 通用tool output截断在无workspace_shell时不执行（GenerationLoop `:644`） | 主Chat没有统一32K tool结果保护；分页工具仍有局部上限 |
| PPT reader用逻辑relationships，而editor用slideN.xml（MaterialReader `:87–102` / MaterialEditor `:32–57`） | 重排文件的页序可能不一致，未复现 |
| 图像页独立HTTP缺通用Provider customHeaders/body等配置复用（ImageCreation `:176–180`） | 特殊compatible endpoint可能表现不同，未在线测试 |
| PageReader有公网DNS/IP/redirect防护，WebImage默认客户端只有基本URL/大小/解码验证 | 图片URL私网/redirect边界风险为代码推断，未网络探测 |
| Tool InputSchema主要给模型使用，循环仅解析JSON，execute各自校验参数 | 无已确认统一runtime schema enforcement；部分校验错误发生在RUNNING之后可中断为unknown |
| Chat模型固定配置、context字符估算、无统一金额账本 | 无已确认的语义模型router、真实token window或全链成本治理 |
| Skills/MCP/workspace部分配置共享，MCP/workspace当前关闭 | profile数据隔离不等于所有扩展/配置完全独立 |
| ChatService/HostFacade/Flutter大页面多领域耦合，双侧schema与多UI保留 | 同步与回归范围大为代码组织推断；未测性能 |
| 普通DB/材料无已发现统一内容加密，Vault与App backup分别加密 | 各存储/备份边界不同；OS backup只allow旧upload目录 |
| Office apt重建不全部锁版本、server单worker/basic tier | 历史固定image与未来重建不同；有容量/tier约束 |

关键网络证据：`client/search/src/main/java/me/rerere/search/WebPageReader.kt:25–76`；`client/search/src/main/java/me/rerere/search/WebImageService.kt:27–79`、`:122–127`。完整报告还列出存储增长、文档授权regex、图像付费未知状态及测试/文档债等边界。

## 验证结果、未知与后续阅读边界

本次83个选定Python文件AST解析未发现SyntaxError，不能代表依赖/逻辑运行通过。已有历史JUnit产物可见不同模块的零failure suite，但app只有很小子集，没有重跑，不是最新dirty tree全部测试通过。选定QA报告只支持对应日期/设备/服务场景。

未知包括设备当前模型/key/端点、最新debug/release与真机体验、生产文档服务/权重/Office字体、图像和语音协议实际兼容、后台限制/计费取消、复杂Office保真与数学准确率、真实用户规模/性能、加密备份round-trip和安全边界实测。全部留在完整报告第22节，没有凭README补全。

已深查主要Chat、Provider、记忆、Skills、材料/图像、Flutter bridge/UI、DB/Vault/备份及Python文档执行链；speech/oauth、部分search供应商、native/vendor及辅助UI/CLI覆盖较浅。没有穷尽所有自有边角分支、第三方算法、QA原始日志/大二进制，也没有读取真实私密数据。交付前直接路径/行号引用核验通过；扣除两份报告后的Git状态与1,850个既有源码/配置/文档选集指纹均与审计开始一致。完整报告第23节列出详细覆盖和校验边界。

接续研究应先依据完整报告的E01–E70定位表与功能矩阵理解实际入口、停用点和未知条件。当前系统已有可用实现基础，但自动router、Work模式、脚本Skill平台和数学正确性保障仍不能写作已经完成。
