# 构建与测试指南

## 2026-11-03 统一存储入口（优先于下方历史执行命令）

先阅读 [存储生命周期](docs/storage-lifecycle-v1.md)。不得按历史命令重新复制 SDK、AVD、完整 QA 源码树。

```powershell
python scripts/project_storage.py report
python scripts/project_storage.py check
python scripts/project_storage.py install-hook
python scripts/project_storage.py run --feature STORAGE --kind test --disposable --reserve-mib 256 -- python -m unittest discover -s scripts/tests -p test_project_storage.py
```

原生 Gradle 可显式将构建产物导向本批临时目录（需要项目外已配置工具链）：

```powershell
$storageInit = (Resolve-Path scripts/storage.init.gradle).Path
python scripts/project_storage.py run --feature NATIVE --kind build --disposable --reserve-mib 6144 -- cmd /d /c client\gradlew.bat -p client -I $storageInit :app:assembleDebug --offline --no-daemon
```

2026-11-04 已验证：绝对路径 `-I` 会把 `:app:generateDebugBuildConfig` 写到 `HXAI_RUN_DIR/gradle/_app`，且不改已有 `client/app/build` 的 Debug APK。相对路径会被 `-p client` 解析到 `client/scripts/`，到不了门禁。这不是 Release、Mate 50 或完整前端新建通过。

## R27-05 前台确认与离线停止接续

协议更新为 [v3](docs/r27-cloud-device-protocol-v1.md)。本批旧确认越代授权、离线 Stop 丢失及迟到 claim 覆写 controls 的反例保留在 `HXai_CHANGE_REVIEW/R29-05-FOREGROUND-BINDING-20261029/`。新派发须绑定确认框所见动作/输入/授权代次/任务修订；已有 lease/receipt 仅按原身份结算。跨 App 与截图继续关闭。

使用该批最终冻结的 `qa-final/`、原 R29 专属设备归属文件和新目录运行：

```powershell
python scripts/verify_r27_android.py --ownership HXai_CHANGE_REVIEW/R29-TLS-HOSTING-DEVICE-20261029/OWNERSHIP_API34.json --apk-dir HXai_CHANGE_REVIEW/R29-05-FOREGROUND-BINDING-20261029/qa-final --run-dir .local/r29-05-api34-NEXT --scenarios stale_confirmation offline_claim lock_pending lock_claim stop_claim profile_claim claim_restart receipt_restart
```

API26 换为同目录 `OWNERSHIP_API26.json`，不得复用已存在的 run-dir；先按归属文件恢复对应 AVD，不能操作外来模拟器。改源码后须重新冻结/构建 AAR 和宿主；同版本号 AAR 可被外部构建替换，必须用实际 APK/冻结 AAR 的资源/engine 校验，不以当前 mutable Maven repo 代替构建输入。准确运行状态与当前剩余门禁只取本批 TEST_RESULTS/HANDOFF，不授整个05、完整App或生产通过。

## R27-06–09 质量接续（2026-10-29）

本机任务快照/32题导入、OCR公式候选评分和Office跨应用读回，见
[合同1](docs/quality-06-09-contract1.md)及`HXai_CHANGE_REVIEW/R29-QUALITY-06-09-20261029/`。
任务详情右上角可明确保存/核验原Chat任务证据，页面重开从原材料意图发现原请求；不调用模型或自动外传。
已核对项目记录中的固定37项真实模型授权，沿原06–09专用工作树和加密App配置续测；与根目录增量分别记录，不自动授予同源集成资格。独立参考、免确认OCR、真实手写/MinerU新范围、WPS实际运行分别另验。

```powershell
python -m pytest -q scripts/tests/test_import_agent_evaluation.py scripts/tests/test_score_b_math_evaluation.py scripts/tests/test_verify_b_ocr_corpus.py scripts/tests/test_verify_b_windows_ocr.py scripts/tests/test_verify_b_office_roundtrip.py
```

只用新输出目录和已核归属的独占QA模拟器复验，先读脚本的安装/归档/清理范围。
`verify_agent_evaluation_process.py`只验证已安装匹配SHA的QA包，不安装/清库；真实退出后只读原件。
WinRT默认预处理保持none；ink-crop在固定样本未改善，不作产品自动fallback。

## R27-04/05 云执行与手机协作（2026-10-28/29）

普通App入口：设置 → 云端协作与手机执行；任务中心 → 任务详情 → 云端协作。版本、授予范围、唯一权威退役、换钥/前台确认、限制和直接复验命令见 [R27协议v1](docs/r27-cloud-device-protocol-v1.md)。本轮证据在 `HXai_CHANGE_REVIEW/R27-CLOUD-DEVICE-20261028/continuation-04-05-final/`，原失败与早期Launcher截图保留，不冒充云UI证明。

新驱动 `scripts/verify_r27_android.py` 要求 `--ownership`、`--apk-dir` 与未使用的 `--run-dir`。只接受本批专属AVD/serial，冻结并核两QA包及安装SHA，归档后仅清nativeqa，真实force-stop手机进程，自有loopback权威/Provider/worker随结束关闭。不得在用户设备或普通包上运行该清库/故障流程；没有依赖安装、真实模型或生产调用。

本批当前双AAR、普通Debug/unsigned Release与双lint/engine/AOT绑定已实跑；普通Debug冷启/云配置及API34 QA完整主故事分别取证。源码变化须重建对应冻结包；不能用这里的构建资格宣称Release已安装、正式签名、Mate50、跨App或生产通过。JVM/Flutter/Python/native准确结果见本批TEST_RESULTS，不累计历史计数。


## B27 数学回答封存与离线评分（2026-10-27）

新增 `scripts/score_b_math_evaluation.py seal/template/score`，复用原32题分离包。输入字段、Unicode证据定位、全部义务与完整分母、数学/工程结果分离及可直接执行命令见[评分合同1](docs/b27-math-obligation-scoring-contract1.md)。本批证据在 `HXai_CHANGE_REVIEW/B27-MATH-OBLIGATION-SCORING-20261027/`；只授本机离线一致性资格，不授真实模型、独立裁决、完整App或数学质量通过。真实调用仍沿原授权App，不能把合成烟测回答当真实成绩。

## B27 文件与数学质量复验（2026-10-27）

先读[领域合同2](docs/b27-file-math-quality-contract2.md)与 `HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/SOL_BLOCKERS.md`。真实Office与本机OCR使用新目录，不安装/联网/调用模型，也不接管用户Office实例：

```powershell
python scripts/verify_b_microsoft_office.py --run-dir .local/b27-msoffice-NEXT
python scripts/verify_b_windows_ocr.py --source HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/corpus-final/source.pdf --out .local/b27-winocr-NEXT --language zh-Hans-CN
python scripts/prepare_b_math_evaluation.py --out .local/b27-math-prompts-NEXT
```

Office驱动创建自有Microsoft进程，遇现有同类用户实例即拒绝；不支持WPS。全逻辑页PDF只改变未保存的只读渲染视图，原PPTX隐藏设置和hash保留。OCR成功仅表示引擎运行，不表示数学转录合格；真实成绩/失败见本批证据。题干包装不授权模型调用。

Android使用本批独占API26/API34设备、冻结QA两包和新run-id；需要逐方法隔离的Chat/设置恢复场景用已有 `scripts/verify_agent_android.py --apk-dir FROZEN_QA_DIR --serial OWNED_SERIAL --run-id UNUSED --classes me.rerere.rikkahub.feature.agent.MathSharedChatRuntimeTest --isolate-tests`，先归档再仅清nativeqa。不可用其他设备或旧源码包替代；同进程混合FAIL不得被隔离PASS抹去。


## A24-01 当前Release接线（2026-10-24）

当前工程接入Release Flutter AAR、无发行密钥时unsigned产物和Debug专属QA开关；原数学/OAuth/Linux说明保留。版本/来源/回滚与精确命令见 [协议v2](docs/a24-release-integration-v2.md) 和 `HXai_CHANGE_REVIEW/A24-01-RELEASE-INTEGRATION-20261024/`。实际运行状态只取本批TEST_RESULTS，不借旧A07隔离副本的通过。

先生成当前源码的Debug/Release两种AAR，再构建宿主。APK核验保持旧Debug参数兼容，可用 `--mode release --apk ... --aar ... --flutter-sdk .local/flutter --output NEW_REPORT.json` 绑定实际产物。包核验、真实启动/Host回调、正式签名与目标设备分别取证；本机测试签名副本不作为分发版本。

## 专业数学合同17：认证高数与已有解析候选（2026-10-22）

新增入口、严格误差范围及schema1/2保全见[合同17](docs/professional-math-contract17.md)。本批 `HXai_CHANGE_REVIEW/P2-CALCULUS-OCR-20261022/` 保存旧版失败、当前源码CLI及独立320位参考、Flutter/Android/实际界面证据。复验用该目录 `smoke/run_verification.py` 的 analyze、flutter-tests、aar、nativeqa、ordinary 模式和新后缀；`verify_numeric.py`、`replay_existing.py`复用并核对该批engine-01的编译封印，源码变化须重新编译，不能跳过一致性检查。

本批专属新AVD `hxai-c17-math-20261022` / `emulator-5592`，起始已有5588不动。原生类及完整命令见合同17；先冻结QA两包、核EMULATOR_OWNERSHIP，原驱动只归档并清nativeqa合成资料。前台多页批改可从本档案同版本已下载结果载入候选，不发生新上传或自动确认；真实OCR质量与真实Provider数学质量仍单独验收。

## 专业数学合同16（2026-10-22）

先读[专业数学协议](docs/professional-math-contract16.md)与 `HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/`。仅预装工具、专属合成模拟器5588及新目录/后缀：

```powershell
python scripts/verify_b_rational_math.py --worktree . --run-dir HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/engine-NEXT --cases HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/ENGINE_EXTENDED_CASES.json
python HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/smoke/run_integrated.py flutter NEXT
python HXai_CHANGE_REVIEW/P2-PROFESSIONAL-MATH-20261022/smoke/run_integrated.py nativeqa NEXT
```

运行原生场景可用现 `verify_agent_android.py --serial emulator-5588 --run-id p2-NEXT --classes me.rerere.rikkahub.feature.math.ProfessionalMathRuntimeTest,me.rerere.rikkahub.feature.material.MathWorkInputsRuntimeTest,me.rerere.rikkahub.feature.math.ProfessionalPlotRuntimeTest,me.rerere.rikkahub.feature.agent.MathSharedChatRuntimeTest`。先核本批EMULATOR_OWNERSHIP和已构建QA APK；脚本先归档再仅清nativeqa，不碰普通App。旧4267条合同语料另用B04/B05的QUALITY_CASES重放。真实模型/OCR质量、Mate50、TalkBack语音、跨Office与Release不由这些工程检查授予通过。

## A04 通用 MCP / Skills（2026-10-22）

先读 [协议v1](docs/a04-general-mcp-skills-protocol-v1.md) 和本批 `HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/TEST_RESULTS.json`。
普通App：设置 → MCP 与 Skill 计算服务。所有工具先安装/逐工具范围确认、启用，再逐任务输入审批；只读一致性及自有沙箱receipt是本版验收边界。OAuth交互授权和任意外部写入不能用只读重复调用替代。

仅专属API26模拟器5584/已有私有Linux daemon、预装依赖及新run-id/run-dir：

```powershell
python scripts/verify_agent_android.py --build --serial emulator-5584 --run-id a04-general-NEXT --classes me.rerere.rikkahub.feature.agent.ExtensionRuntimeTest,me.rerere.rikkahub.feature.agent.GeneralMcpChatStoryTest --isolate-tests
python scripts/verify_general_mcp_persistence.py --serial emulator-5584 --ownership HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/EMULATOR_OWNERSHIP.json --run-dir HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/process-NEXT --mode unknown
python scripts/verify_a04_sandbox.py --run-dir HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/linux-NEXT
python scripts/verify_a04_sandbox_chat.py --run-dir HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022/chat-NEXT
```

第一条按既有规则先归档后只清QA；驱动先冻结两包、验包名/ZIP，再安装并核设备SHA，防共享build outputs替换。可用`--apk-dir`指定含主/测试APK的冻结目录（与--build互斥）。Probe不清库，mode另有installation/revoked/old_approval；Probe和脚本故事也支持`--qa-main/--qa-test`指定冻结包，默认使用当前构建输出并核QA身份，普通Debug输出会被拒绝。最后一条归档后只清nativeqa，启动/结束自有loopback MCP和adb reverse，实际走Android Chat→Python容器→独立回执。不能并发操作同一模拟器；Probe不可直接批跑。Linux脚本拒绝默认/外来Docker环境。普通Debug另用 `-PagentNativeTest=false` 构建，不能以QA的loopback权限和管理组件授普通包通过。没有生产部署、依赖安装或真实账号/模型调用。

## A04 持久合成 MCP（仅 nativeqa）

协议：`docs/a04-durable-mcp-protocol-v2.md`；实际裁决与原始失败在 `HXai_CHANGE_REVIEW/A04-DURABLE-MCP-20261021/`。
确认独占的 `mathstudy-ab-merge-20261021` AVD / `emulator-5590` 已启动，新run-id/run-dir不存在后：

```powershell
python scripts/verify_agent_android.py --build --serial emulator-5590 --run-id a04-durable-NEXT --classes me.rerere.rikkahub.feature.agent.McpInstallationRuntimeTest,me.rerere.rikkahub.feature.agent.SyntheticMcpRuntimeTest,me.rerere.rikkahub.feature.agent.SyntheticMcpChatStoryTest,me.rerere.rikkahub.feature.agent.McpManagementSurfaceTest
python scripts/verify_mcp_persistence.py --serial emulator-5590 --run-dir HXai_CHANGE_REVIEW/A04-DURABLE-MCP-20261021/process-NEXT --mode unknown
```

第一条按原脚本归档后重置QA数据、离线构建/安装；不改普通App。第二条不清库，核已安装APK的实际SHA/身份，等待持久ready与live PID后真实强停、新进程读回，mode可为installation/revoked/old_approval/unknown；每次使用新run-dir。此probe不能直接当普通JUnit类批跑。`--install`可显式安装已构建QA APK，先核包名；不安装依赖。工具发现/读取仍只在QA，固定只读工具逐任务仍须审批；不是通用MCP或脚本沙箱验收。

## B06 真实 Linux Office worker 资格

已准备的专属 WSL / 私有 Docker 环境、启动与完整重放命令见
`docs/parallel-development-20261019/B06-LINUX-OFFICE-PROTOCOL.md`。
入口 `scripts/verify_b_linux_office.py` 拒绝默认 Docker endpoint、错源码封印和复用 run-dir；
只操作本批合成材料，真实执行受限容器/UNO/独立 validator，并进行杀进程、丢 ACK 与 socket 故障注入。
必须独占本批 daemon，不可用于生产或用户 Docker Desktop。最终24场景及149项服务回归通过；
不是 WPS/MS Office、完整 App/API26、Mate50、MinerU/OCR 或生产验收。原失败及源码/镜像/产物 SHA
见 `HXai_CHANGE_REVIEW/B06-LINUX-OFFICE-20261021/`。无 Swap 限制不能只信 Docker 参数，须核内核 cgroup。

## 快速开始

### Windows
```batch
scripts\test-intelligent-chat.bat
```

### Linux/Mac
```bash
bash scripts/test-intelligent-chat.sh
```

## 手动步骤

### 1. 构建APK
```bash
cd client
./gradlew :app:assembleDebug
```

输出: `client/app/build/outputs/apk/debug/app-debug.apk`

### 2. 安装
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配置
1. 启动应用
2. 进入设置
3. 添加API密钥(OpenAI/Claude/Gemini)

### 4. 测试
发送消息: "帮我理解二次函数 y=ax²+bx+c"

### 5. 验证
- [ ] AI生成交互界面
- [ ] 界面包含滑块、图表、按钮
- [ ] 滑块可拖动
- [ ] 图表实时更新
- [ ] 点击按钮触发新对话
- [ ] AI收到参数状态

## 完整测试用例

见 `docs/intelligent-chat-test-cases.md`

## 故障排查

### 问题: 构建失败
**解决**: 检查Java版本 (需要JDK 17+)
```bash
java -version
```

### 问题: 界面不显示
**解决**: 查看logcat
```bash
adb logcat | grep -i "surface\|interactive"
```

### 问题: 按钮无响应
**解决**: 检查网络连接和API密钥配置

## 性能测试

### 测试大数据量
```
创建一个包含500个数据点的折线图
```

### 测试多个界面
```
在同一对话中创建3个不同的交互界面
```

## 成功标准

- ✅ APK成功构建
- ✅ 安装无错误
- ✅ 至少1个测试用例通过
- ✅ 无崩溃或卡顿
- ✅ 内存占用正常

## 隔离的原生任务 / Chat 回归

使用已安装的 `.local/gradle-9.6.0`、Android SDK 和已运行的 `emulator-5554`，
在仓库根目录执行；每次 `run-id` 必须是未使用的新名字：

```powershell
python scripts/verify_agent_android.py --build --run-id native-chat-next-unused
```

此入口只安装、运行和重置 `org.hxsi.agent.nativeqa` 合成 QA 包，不清生产或普通
debug 包，不调用真实模型。构建使用 `--offline`，不自动安装依赖。

默认任务回归仍批量执行；`ChatNetworkFaultTest` 和 `FlutterChatBridgeTest`
自动从 Android runner 枚举并逐方法隔离。断流用例保留 UNKNOWN / 全局执行位，
不通过伪造回执、跳过权限或重放请求让测试通过。每例重置前先停止 QA 进程，
归档用户数据和截图，仅跳过从 APK 再生的 app_flutter / app_dxmaker_cache 代码缓存；
归档失败即停止，不清库。最后一例只归档，不清理。
其他指定类也可用 `--isolate-tests` 逐方法运行。

日志、源码 / Room schema / runner / APK 哈希、方法清单和原始状态归档保存在
`HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/runs/<run-id>/`。
发现阶段的 `OK (N tests)` 仅表示枚举成功，实际裁决取执行日志；
ADB exit 0、发现成功或构建成功均不能代替方法 PASS。
归档包含实际 Flutter 冒烟截图，不是设计参考图。

本批变更、失败对照与适用边界见
`HXai_CHANGE_REVIEW/CHAT-LEASE-REGRESSION-20261013/`。
本机合成验证不能代替真实模型、专业 Office 引擎、Release 或 Mate50 验收。

## XLSX 主 Chat 与真实本机重算

独占合成模拟器、已安装 LibreOffice/SDK/依赖后，从根目录执行：

```powershell
python scripts/verify_xlsx_chat_android.py --build --run-dir HXai_CHANGE_REVIEW/A-OFFICE-CHAT-20261019/runs/next-unused --serial emulator-5554 --port 18871
```

仅使用合成工作簿/本机模型及 loopback FastAPI/UNO，按场景隔离服务身份，不提升真实存储限额。
该命令会构建 Flutter AAR/原生 QA、安装并归档后清 nativeqa 数据、启动并结束自有 gateway，
逐场景验证审批/版本/真实公式回读/最终验收竞态/丢 ACK；UNKNOWN 场景最后运行且保留执行位。
不触碰普通 App、真实账号、付费模型或生产；不证明 Linux 容器、MS Office/WPS 或目标真机。
原 PDF、数学、章节、来源/记忆回归仍使用上面的 `verify_agent_android.py` 新 run-id。


## B04/B05 资料库与数学来源

协议与源码映射：`docs/parallel-development-20261019/B04-B05-LIBRARY-MATH-PROTOCOL.md`。
本轮原始失败、实际运行与最终裁决：`HXai_CHANGE_REVIEW/B04-B05-COMPLETE-20261020/`。
所有命令在仓库根目录执行；只使用已核归属的独占合成设备、新run-id/run-dir及匹配的QA/test APK。

```powershell
python scripts/verify_agent_android.py --serial emulator-5584 --run-id b04-b05-next-unused --classes me.rerere.rikkahub.feature.material.MaterialLibraryLifecycleRuntimeTest,me.rerere.rikkahub.feature.material.MaterialMathQualityRuntimeTest,me.rerere.rikkahub.feature.material.MaterialLibrarySurfaceRuntimeTest --isolate-tests
python scripts/verify_b_library_process.py --serial emulator-5584 --run-dir HXai_CHANGE_REVIEW/B04-B05-COMPLETE-20261020/process-next-unused
```

先按既有说明构建Flutter AAR及`:app:assembleDebug :app:assembleDebugAndroidTest -PagentNativeTest=true`；
第二条会真实杀死nativeqa进程，不安装/清库/连模型，必须在其他设备验证退出后单独运行。
普通Debug使用`-PagentNativeTest=false`，测试DocumentsProvider不应进入正常包。
完整受影响33类及另存测试清单取本轮证据；固定4267条工程语料及独立证书命令在交付math-quality-cli/math-independent-certificates。
PDF夹具按原始SHA和binary属性保留；导出可应用patch须使用`--no-textconv --no-ext-diff --binary`并在副本重放核对字节，不能应用pdftotext预览。
工程/loopback通过不授予真实模型/OCR准确率、TalkBack语音、API26/目标设备、跨Office、Release或生产通过。


## API26 与当前 A04 合流复验（2026-10-22）

协议 `docs/api26-a04-integration-20261022.md`，结果 `HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/HANDOFF.md`。
先核该批 `EMULATOR_OWNERSHIP.json` / `OWNERSHIP_ORIGIN.json`，按原AVD启动说明恢复专属5584，不wipe；确认AVD/API26/ABI/boot后使用全新run-dir。
既有 `final-COMMAND.json` 保存完整139方法原命令，`final-SELECTORS.json`保存选择器。
`verify_b_domain_compatibility.py`须绑定当前源码/QA主包/测试包manifest；重建后重新封印，不能借旧SHA证明新包。

四种live模式逐个执行，不并发、不直接JUnit批跑Probe；下面NEXT须替换为未使用的目录名：

```powershell
python scripts/verify_mcp_persistence.py --serial emulator-5584 --ownership HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/EMULATOR_OWNERSHIP.json --run-dir HXai_CHANGE_REVIEW/API26-A04-INTEGRATION-20261022/process-NEXT --mode installation
```

mode另有revoked/old_approval/unknown。驱动不清库，核实际安装SHA、持久ready与live PID后只强停QA并新进程读回。保原件/墓碑/receipt/UNKNOWN；仅自有合成模拟器。普通Debug仍 `-PagentNativeTest=false`，不能将QA管理组件/Provider带入正常包。当前API26大字/深浅色/TalkBack、Release/目标设备/真实模型/生产不据此通过。


## A04 OAuth / A05 可信费用（2026-10-22）

先读 `docs/a04-oauth-a05-trusted-cost-v1.md` 和本批 `TEST_RESULTS.json`。使用已核归属的 hxai-p2-math-20261022 / emulator-5588（API34），新 run-id/run-dir。普通 App 保持严格 HTTPS；QA 的 HTTP loopback 不进入普通权限合同。

```powershell
python scripts/a045_worklog.py new-offline-build .local/gradle-9.6.0/bin/gradle.bat -p client :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :ai:testDebugUnitTest -PagentNativeTest=true --offline --console=plain -x :web:buildWebUi
python scripts/verify_agent_android.py --serial emulator-5588 --run-id a045-NEXT --classes me.rerere.rikkahub.feature.agent.ExtensionOAuthRuntimeTest,me.rerere.rikkahub.feature.agent.TrustedCostRuntimeTest,me.rerere.rikkahub.feature.agent.TrustedCostBaselineTest --isolate-tests
python scripts/verify_a045_persistence.py --serial emulator-5588 --run-dir HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/process-NEXT
```

第一条依赖既有 Flutter AAR；Pigeon 按源 `client/flutter_ui/pigeons/study_host_api.dart` 用本机 dart run pigeon 生成两端。重建 Flutter 用已有 SDK `flutter build aar --debug --no-release --no-profile --no-pub`。第二条先归档再只清 nativeqa，禁止与同设备其他测试并发；第三条不清库，保存当前 QA 包状态与 hash，准备持久金额/未知请求/OAuth 未知兑换后真实强停、新进程核验。Probe 不能直接批跑，其 prepare 等待 host 停止。驱动核 AVD、冻结 SHA 与 QA 身份；不用真实账号或模型。普通 Debug 另以 `-PagentNativeTest=false` 构建并检查 QA 组件隔离。


## A26-R01 冻结组合包（2026-10-27完成）

当前冻结组合：App 522 / AI 214 / 搜索 33 项JVM、Flutter416项和analyze、Android 147 方法全部通过；两种lint无错误（Debug 30、Release 28 警告）。三次真实QA进程退出后读回通过。普通Debug/测试签名Release冷启、任务中心真实Host查询、安装SHA及全部普通包Flutter/QA隔离和故障副本反证均有当轮证据。

仅授开工冻结SOURCE_FINAL的账户+B26组合本机资格；共享根目录后来B26-ALL-DOMAINS增量未纳入，不借本批包证明当前根目录或完整App。Release原件为unsigned，测试签名副本仅本机。真实模型/OCR、独立云、目标设备/正式发布与独立/完整项目审查分别未验。

源码/产物/复验边界见[组合资格v1](docs/a26-r01-combined-build-v1.md)与HXai_CHANGE_REVIEW/A26-R01-COMBINED-BUILD-20261026/HANDOFF.md；原指南与旧失败保持。


## A27 设置发布与当前组合包（2026-10-27批次）

本批关闭SettingsStore乐观发布/并发函数更新竞态，并修复旧QA接受夹具的schema13收尾。原API26混合212方法及平台混合回归、实际进程退出、同源普通Debug/Release包资格见`HXai_CHANGE_REVIEW/A27-SETTINGS-COMBINED-20261027/HANDOFF.md`；协议与复验约束见`docs/a27-settings-publication-v1.md`。Release原件unsigned，临时测试签名副本仅本机。TaskStore13/Room33/Pigeon保持；真实云/模型/OCR/目标设备与独立/完整审查分别未验，不覆盖原失败。

## AB28 当前 A/B 线集成（2026-10-28）

当前本地集成分支 `codex/ab28-integration-20261028`；A27设置/组合修复、B27文件数学领域与离线逐义务评分均按最终字节封印继承。来源与可执行复验命令见 [AB28集成基线](docs/ab28-integration-20261028.md)，本轮新证据取 `HXai_CHANGE_REVIEW/AB28-INTEGRATION-20261028/TEST_RESULTS.json`。

本轮实际运行 JVM App525/AI214/搜索33、Flutter416/analyze、Python123及22子用例、文档服务154、API26混合31方法及普通Debug任务中心Host查询。APK/AAR/Release没有本轮重建；Android复用所有client输入一致的A27冻结包并核实际安装SHA，不因离线评分CLI而代授真实模型数学质量。原有构建/签名/归属/QA隔离门禁不变，历史证据和失败保留。

## R27-05 离线安全控制接续（2026-10-30）

协议与源码映射为 `docs/r27-cloud-device-protocol-v1.md` v4；本批证据在 `HXai_CHANGE_REVIEW/R30-05-DURABLE-CONTROLS-20261030/`。使用其中冻结的 `qa-final/` 两包和原 R29 的专属 AVD 归属文件，新的 run-dir；源码变化必须重新构建，不能借旧包授予当前工作树资格。

```powershell
python scripts/verify_r27_android.py --ownership HXai_CHANGE_REVIEW/R29-TLS-HOSTING-DEVICE-20261029/OWNERSHIP_API34.json --apk-dir HXai_CHANGE_REVIEW/R30-05-DURABLE-CONTROLS-20261030/qa-final --run-dir .local/r30-05-api34-NEXT --scenarios offline_pause offline_cancel offline_revoke offline_pause_restart lock_pending lock_claim stale_confirmation offline_claim stop_claim profile_claim claim_restart receipt_restart
```

API26 使用同目录 `OWNERSHIP_API26.json` 及另一全新 run-dir，不能在同一 AVD 上同时运行。驱动核 AVD/API/ABI/包名和实际安装 SHA，先归档再仅清 nativeqa；真实断 claim ACK、离线控制、强停/重启、PIN 锁屏、单一 DOCX 的独立 XML/hash 读回，不调用真实模型/私料/生产。`offline_cancel/revoke` 验证零 DOCX 和仅前两次请求，不强制其继续至 SUCCEEDED。

每个 AVD 只保持一个本地 ADB transport。若共享 ADB 重启后高端口实例丢失，按归属重启自有 AVD并保留用户数据，不启动同 AVD 第二实例、不 wipe、不杀共享 ADB、不接管其他会话的模拟器。API26 的多传输 reverse 错误及 API34 失效连接安装超时是本批保留的环境失败，不计产品通过。

冻结副本 `.local/r30-05-frozen/client` 的实际构建为 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :ai:testDebugUnitTest :app:lintDebug -PagentNativeTest=true --offline --no-daemon --console=plain --max-workers=2 -x :web:buildWebUi`，AAR在其 `flutter_ui` 以 `flutter build aar --debug --no-release --no-profile --no-pub` 新建。最终两包/38项Flutter资源与引擎绑定及分项结果取本批 JSON；不是普通最终 Release、Mate50、真实无线网或完整项目/独立审查的放行。
