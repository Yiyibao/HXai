# Pro 交付包研究与后续开发基线

建立日期：2026-10-12（Asia/Taipei）。本轮范围是研究交付包、核对版本/完整性/可复验范围，并将用户指定的提示词与审查制度持久化为项目规则；未启动整套原生功能实现或全项目审查。

### 用户再次提供的开发前需求：2026-10-12 确认

已完整读取 `C:/Users/Hfff/Downloads/02_FINAL_PRODUCT_REQUIREMENTS.md` 与 `03_P0_SMART_CHAT_SPEC.md`，两份文件与交付包 inputs 同名文件逐字节/SHA256 一致，无新增差异。它们定义最终目标，Pro 交付的 PARTIAL/NOT_RUN 仅说明当前成果，不能成为需求上限；技术选型可调整，产品冻结决定及验收目标继续有效。

| 必须持续实现的目标 | 开发理解与验收重点 |
|---|---|
| 统一 Agent Chat | 默认进入 Chat、闲聊正常回答；行动目标真实理解→持久任务→可修订计划→授权工具→独立复核→成果→总结。确定性演示不能代替模型自主规划 |
| 协作与全局调度 | 同任务补充要求更新原计划，保留已验证步骤；无关请求排队。暂停/取消/等待/未知效果按真实状态处理，设备恢复优先排队且不抢占 |
| 手机与服务器 | 仅真正托管并获授权的服务器步骤能在手机离线时继续；手机动作检查点等待，跨 App 恢复前台确认；文档服务不等同云 Agent |
| 文件系统 | 持久资料库可授权跨会话引用；Word/PPT/Excel/PDF 同等目标，编辑保原件、可追版本、验证内容/结构/版式/重算，聊天删除不误删独立材料/成果 |
| 专业数学 | 江苏高考及大学高数/数学分析/实变等严格推导、定理条件、手写首错、可核对教材引用、符号/数值独立核验、交互图；当前 Q[x] 只是起步能力 |
| 扩展与长期数据 | 网页、Skills/MCP、计算沙箱、记忆与备份为核心支撑；独立授权与撤权有效，删除抑制不因恢复复活，学习记忆区分阅读/提示/独立完成 |
| 模型、费用和界面 | 手动锁模型/推理，不暗中路由；无费用硬预算但安全有界；上报/估算/未知用量透明；可展开实际操作证据，图表控件真正续接任务 |

P0 文档的四个目标场景保留为端到端验收：A 授权目录内两份讲义合成真实可编辑 Word 并可预览/核对来源；B 同任务新增章节整理要求产出新版本而不重复发送；C 后台文档与等待手机外发分阶段，设备恢复确认、未知发送不重发；D 模型不支持工具时如实解释并待用户决定，不伪执行/换模型。本轮仅确认需求，不把这些场景写成已执行或当前 App 已支持。

来源校验与本次文档复审见 [requirements-confirmation.json](../qa/pro-baseline-20261012/requirements-confirmation.json)。

## 1. 必须沿用的指令

执行入口为根目录 [AGENTS.md](../AGENTS.md)，client 的规则也已指向该入口。权威原文保留在交付包：

| 工作 | 必须一起使用的文件 |
|---|---|
| 实现与接续 | `12_SOL_CONTINUATION_PROMPT.md`、`11_HANDOFF_TO_GPT6_1_SOL.md`，结合 `inputs/12_GPT6_1_SOL_CONTINUATION_PROMPT.md` |
| 每批功能/修复/重构 | `10_REVIEW_SYSTEM/NEW_FEATURE_REVIEW_STANDARD.md`、`PROMPT_NEW_FEATURE_REVIEW.md` |
| 全项目与架构/数据库/权限/发布里程碑 | `10_REVIEW_SYSTEM/PROJECT_FULL_REVIEW_STANDARD.md`、`PROMPT_FULL_PROJECT_AUDIT.md` |
| 裁决与出处 | `REVIEW_EVIDENCE_AND_SEVERITY.md`、`REVIEW_SCOPE_AND_SOURCE_MAP.md`、审查制度 README v1.1 |
| 产品目标 | `inputs/01—04`；验收目标为 `inputs/13`，来源规则为 `inputs/14` |

标准定义门槛，提示词定义执行动作，本轮报告定义实际事实。inputs/05、06 是制作任务书；inputs/13 默认需求用例 NOT_RUN；旧 README 不覆盖最新冻结决定。提示词作为工程工作规则使用，不直接当 App 的 Chat 系统提示词替换。

## 2. 本轮版本与完整性

- 本地分支起点 master，完整 HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`；本轮新建 `codex/pro-baseline-20261012`，HEAD 未移动。原 tracked 工作树无改动，交付包为 untracked。
- 只读 `git ls-remote --symref origin HEAD refs/heads/main refs/heads/pro/hxai-safety-delivery-20261012`：远端默认 main 与本地同 SHA；Pro 安全分支为 `d7e319be42b2d5c1d3adf52ea9d09cc881f4cf59`。本轮未重新读取 PR 元数据，Draft/未合并字段仍来自 Pro 历史快照；当前 main 尚不含该安全提交。
- 真实枚举 225 个文件，manifest 记录 224 个其他文件。按精确路径及字节 SHA256 校验：222 项 PASS，另外 2 项路径被改名，但改名文件大小及哈希均精确匹配原条目；无发现内容不匹配。因此内容可用，严格路径完整性为 CONDITIONAL。
- Windows 大小写碰撞对应关系：`evidence/tests/document_baseline_fixed.log` → `document_baseline_fixed (2).log`；`runtime_math_final.log` → `runtime_math_final (2).log`。大写 FINAL/FIXED 文件也存在且分别匹配自身条目。原包未改名、未覆盖，后续可在支持大小写区分的文件系统恢复原路径。
- manifest 自身 SHA256：`12b700793884602939f4f938598ff3d827fa769708b36645dad744c4c20dca9f`。详情见 [integrity.json](../qa/pro-baseline-20261012/integrity.json)。清单自身不在其校验范围；本轮无外部 ZIP/.sha256。
- 输入 README 提到的 `inputs/09_PRO_ONE_TURN_MASTER_PROMPT.md` 确实缺失；已有根目录新版续作提示词可用，不编造该文件内容。

复核命令：`python scripts/verify_pro_delivery.py HXai_Pro_Delivery --output qa/pro-baseline-20261012/integrity.json`。精确路径不完整时退出 1，保持显式异常；它只读交付包，不自动修复命名。

## 3. 交付成果与当前产品必须区分

| 领域 | Pro 成果 | 本轮确认的状态/边界 |
|---|---|---|
| Android 主链 | Flutter→Pigeon→ChatService→Session→GenerationLoop/ToolFactory | 现有真正工具链应保留；本轮抽查 ChatService 执行阶段读取 Settings，尚无本轮候选 TaskStore 接线证据 |
| 安全六文件 | OCR 日志/取消、文档事务撤权栅栏、测试与 QA 说明 | 单独 PR/patch；当前本地 OCR 仍有正文和 URL 日志，安全修复未接入本地产品；patch `git apply --check` 通过，仅可应用性证据 |
| P0 Agent | Python v0.3.0 / SQLite schema2、ModelLock、单 slot/fence、journal、等待/授权、验证、用量 | 可信宿主的本机协议，未接 Android、不含 LLM 规划器/公网鉴权/云凭据库；不可替代原生任务实现 |
| 文件 | XML/PPT/工具投影 Kotlin 助手、版本化 Markdown、Flutter 文件动作候选 | 当前 Editor 仍按 slideN 文件名并用 UTF8 预检查后解析原 bytes；助手未接线；Markdown 不代表专业 Office 保真 |
| 数学 | Q[x] 精确恒等、导数、等式链首错/反例 | 不支持一般高考/分析/实变证明、手写识别和教材索引；UNSUPPORTED 保持未知 |
| UI | 96 张原图、8 张联系表；5 张 Pro 图、HTML；4 个 Dart 源文件含测试 | 已目视看四屏总览；设计资料与候选源码有清晰状态边界；原生任务页/TaskDto 尚未接入，不可用画板计数冒充实际队列 |
| 发布 | 构建建议与 release AAR 锚点 | 本轮未运行完整 Gradle/Flutter/App/Release/Mate50/服务容器/真实 Provider，全部保留 NOT_RUN |

本轮已读根目录分析/架构/实现/测试/安全/交接与续作、七份审查制度、核心需求与来源规则、集成/验证脚本、协议关键源码和当前本地风险入口；未逐行审完 225 个文件、96 个原图或完整仓库。完整项目仍不具备放行证据。

## 4. 本机复验：保留失败，限定证据范围

原始交付目录只读；将其复制到 `tmp/pro-replay-*/HXai_Pro_Delivery`，在副本运行原始 `07_CODE/run_verification.py`。Python UTF8 开启，原生产服务/模型/私有材料均未调用。新日志在 [qa/pro-baseline-20261012](../qa/pro-baseline-20261012/)，被副本继承但未新生成的历史证据已从本轮归档移除，避免混用。

| 本轮实跑 | 结果 | 证据/解释 |
|---|---|---|
| 文档撤权契约 | 15 passed | E3-local，本机 ASGI/SQLite，非 worker/Docker |
| 原撤权负对照 | 2 failed，符合 expected exit1 | 原缺陷重现；不是修复通过计数 |
| 修复后撤权回归 | 2 passed | E2/局部 Store |
| runtime + math | 63 passed，8 failed | 7 项涉及 Windows `os.open(directory)` PermissionError；1 项创建 symlink 因 WinError1314 无权限失败；不升级历史 71 passed 为本机 PASS |
| 交付保护 | 5 passed，1 failed | symlink 创建权限不足；保持 FAIL，不能删除该安全用例迎合环境 |
| 本机 demo | FAIL | 报告硬链接发布后，目录同步 `os.open(folder, O_RDONLY)` 在 Windows 失败；已有写入不能假设未发生或盲重试 |
| 原验证 runner | exit1，未正常完成 | demo 失败后无 evidence.json，runner 再次读取缺失文件中止；不能把副本旧 test_runs.json 当本轮结果，已另建 TEST_RESULTS.json |
| patch check | exit0 | 未 apply，不代表产品已修/构建通过 |
| 全候选集成 dry-run | exit1 | 原始字节 blob 检查遇到 CRLF；HEAD 四锚点 blob 都匹配，前三磁盘 CRLF、Git LF，规范化后内容一致；未跳过保护或 apply |

具体索引：[TEST_RESULTS.json](../qa/pro-baseline-20261012/local-verification/TEST_RESULTS.json)、[runner.log](../qa/pro-baseline-20261012/local-verification/runner.log)、[anchor-analysis.json](../qa/pro-baseline-20261012/anchor-analysis.json)、[integration-dry-run.log](../qa/pro-baseline-20261012/integration-dry-run.log)。Pro 原始 71/6/24 的通过记录仍有效于原环境与源码范围；以上新失败是 Windows 适配/权限及验证入口错误，不推断为 Android 全链失败。

环境：Windows 11 / Python 3.13.13 / SQLite 3.50.4 / pytest 9.1.1 / JDK21。kotlinc、Flutter、ADB 不在当前 PATH；`.local/flutter`、`.local/android-sdk` 等工具目录存在，尚未核实可用版本。NOT_RUN 不能解释为本机完全没有 SDK，也不能照搬 Pro 当时缺完整 checkout 的理由。

## 5. 后续开发顺序与明确验收

1. **集成前置/安全边界**：新建实现 feature 分支/工作树，核查当前远端与本地。审查六文件安全 patch，复用文档撤权测试，补 OCR 取消/敏感日志断言。新 LF checkout 或逐文件三方审核解决 CRLF 锚点问题，禁止为兼容绕过 blob 保护。验证 runner 的失败收集须独立修复；Python 报告落盘需确认平台持久化合同，不能简单跳过目录 fsync 后宣称同等抗断电。
2. **AGENT-01 + MODEL-02**：最小原生闭环，优先 sidecar TaskDatabase；在 ChatService 接受任务时快照 provider/model/reasoning/configRevision/secretReference，Scheduler 统一包住现 GenerationLoop，保留 ConversationSession 检查点。逐 dispatch journal/epoch/fence 与独立 verifier，杜绝旧 job 绕过执行位。验收两会话/两档案唯一运行、排队后改 Settings、写后杀进程、丢 ACK、错误验收谓词。
3. **阻断相关使用的 S1 并行排入批次**：WEB-04 修公共网页/图片全 DNS/redirect/代理/UI 二次下载链；FILE-05 接 XML/PPT 安全助手并运行完整 OOXML；TOOL-06 只截 Provider 投影，完整持久结果保持。OCR cache 按 profile/source/modelRole 隔离及删除传播。各批先合同、反例、实现，再按新增审查提示词复审。
4. **任务桥与 UI-07**：版本化 TaskDto/Pigeon 快照与操作，生成双端；Flutter 显示真实 task/receipt/blockedOn/STOP_REQUESTED。既有 artifact preview/open/save 复用可信 owner/version/hash，旧 epoch 回执丢弃。没有任务真源前禁做假全局任务 UI。
5. **AUTH-03 及支撑**：可信 UI grant、独立等待、云 vault/设备执行器和权威 fence 移交；未有真实账号/部署授权时先隔离 fake/mocks。Memory/Skills/MCP/备份分别补读和回归，不删除已有成果或绕过停用边界。
6. **P1→P2→BUILD-09**：专业 Office 真实重算/全页渲染/保真、MinerU 覆盖/页码；数学核验器注册到统一任务体系并标范围，扩展题库/OCR/教材/证明；完整 AAR/Debug/Release 和目标 Mate50 验收。

下一轮先探测项目已有 `.local/flutter/bin/flutter.bat`、Android SDK 与 Gradle 缓存，读取构建脚本后设置任务专用环境变量。对应可直接执行的门禁：

```powershell
# 在副本/隔离分支复验，不改原始交付 evidence
python HXai_Pro_Delivery/07_CODE/apply_delivery.py <isolated-LF-checkout>
# 本机 Python 修复批次以副本 cwd 为准：
python -m pytest -q tests/test_runtime.py tests/test_math.py
# Flutter/Android 工具与依赖核实后，在各自工作目录执行：
flutter analyze
flutter test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :search:testDebugUnitTest
flutter build aar --debug --release --no-profile
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

Python 恢复失败须保留 UNKNOWN/slot/现有文件；Windows symlink 用例需要支持该权限的受控环境，或有等价平台攻击用例及明确范围，不用 SKIP 冒充 PASS。完整架构/数据库/权限变更批次追加全审；目标设备、真实收费模型/账号、生产仍按既有明确授权执行。

## 6. 本轮裁决与回滚

规则采纳和交付内容研究完成；原文未改、产品功能未宣称接线完成。本轮文档/校验范围可用，路径完整性及跨平台复验为 **CONDITIONAL**；全候选 Windows 集成/原生产品/发布 **不放行**。本轮作者同时复审，非外部独立审查。

回滚本轮仅移除新增规则/文档/证据/只读校验脚本，并撤销 client/AGENTS 的补充段落；不清空数据库、原交付包、用户文件或已有任务。研究产出不移动 HEAD、不推送、不合并安全 PR。后续实现遵守原 Pro 提示词规定的六类 SOL 交付和按批审查证据。
