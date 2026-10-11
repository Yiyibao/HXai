# S0 执行记录

开始：2026-10-02，用户授权“开始执行s0”。状态：执行中，尚未达到退出条件。

## 已执行

- 保全根目录规划，在本机初始化 Git 单仓库，无 GitHub 远端；固定源码置于 `client/`。
- 使用 Git 远端标签核对 2.5.6 = `447bb7e89710d31f1204d7a2973baa19fdbd5b28`。下载固定提交归档，SHA256 `447dfad8f36f7699bf412494bd3e86fd23c97baaeab7566859a8d5258880ab11`。
- 通过 GitHub tree API 获取颜色库子模块 gitlink `6fd88eb3e95ba1d457842e2a2bf847d06b3a018a`，下载该提交。1423 个主源码文件和 385 个子模块文件与归档逐字节一致。保留上游许可证、来源与代理指引，未修改 Android 业务源码。
- 获取上游 arm64 APK，40,255,950 字节；SHA256 与发布 API digest 一致。APK 位于 `.local/`，仅用于上游真机验证。
- Gradle 官方归档 SHA256 `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`；Gradle 9.6.0 可启动。Launcher JDK 为本机 Oracle 21.0.9；上游 daemon 自动选择 JetBrains 21（日志为 21.0.10）。
- Android command-line tools 23.0.0、platform-tools 37.0.1 已下载并核对官方仓库 checksum；ADB 1.0.41 可启动，设备列表为空。新版平台安装器长时间停滞，转用官方 command-line tools 19.0 完成 SDK 37.0/37.2、Build Tools 37.0.0 与 CMake 3.22.1 安装；本机 SDK 许可证已通过 sdkmanager 处理。
- NDK 28.2.13676358 的完整官方 Windows 归档 748,118,221 字节，SHA1 `086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d` 与仓库元数据一致。使用分段下载绕过长连接停滞，安装到项目内 SDK；未改上游原生依赖版本。
- Node 24.11.1、pnpm 11.19.0；`pnpm install --frozen-lockfile` 与前端 `pnpm run build` 成功。生成静态文件排除入库。
- [数据合同 v1](data-contract-v1.md) 冻结字段、枚举、身份/来源/分支版本和事务约束；产品条件项与备份算法参数保留待决。
- 本地校验测试 11 组通过：6 组候选结构检查，覆盖合法/空结果、畸形/截断 JSON、额外身份字段、错误枚举、未知或跨人来源、重复来源/事实/键和长度限制；5 组离线协议反例检查，覆盖完整/截断流式、工具结果关联、正文伪调用和错误工具/参数。只证明本地结构与协议层，未证明真实模型能力、事实质量或 Android 实现。[结果日志](../qa/s0/local-validation.log)
- 准备 [真机脚本](../scripts/device_s0.ps1)、[中转探针](../scripts/probe_provider.py) 与 [运行说明](../qa/s0/README.md)。没有读取 MathStudy 密钥、没有真实模型调用、没有设备安装。

## 构建结果与处理

1. 上游 wrapper 首次下载 Gradle 连接超时；通过官方归档校验后本地启动同版本。
2. Java 未自动读取系统代理；仅当前构建进程添加代理参数，保留上游文件。
3. 构建逻辑编译通过，完整构建在 `:workspace` 配置阶段因 SDK location not found 失败。已写入被忽略的 `client/local.properties` 指向项目内 SDK。
4. 复验定位到 NDK 许可证/缺组件；已完成许可证与 SDK/NDK/CMake 安装。工具 19.0 对仓库 XML v4 有兼容提示，固定包实际下载和校验成功。
5. 通过 [build_baseline.ps1](../scripts/build_baseline.ps1) 重跑完整构建；使用 [公开占位 Firebase 配置](../qa/s0/google-services.example.json)，不连接有效 Firebase 项目，构建结束后移除临时副本。这是编译探针，不能作为真实 Firebase 或真机运行通过证据。AGP 自动安装并实际使用默认 Build Tools 36.0.0；37.0.0 同时保留在工具链中。

原始日志：`.local/baseline-build.log`、`.local/baseline-build-proxy.log`、`.local/baseline-build-sdk.log`、`.local/baseline-build-complete.log`、`.local/sdk19-install.log`、`.local/sdk19-platforms.log`、`.local/ndk-install.log`、`.local/web-ui-install.log`、`.local/web-ui-build.log`。源码依据见 [固定来源清单](upstream-lock.json)、[GitHub API 元数据](../qa/s0/upstream-api.json)。

## S0 门禁现状

| ID | 状态 | 实际证据 / 下一步 |
|---|---|---|
| G01 | BLOCKED | 设备型号/系统已有截图依据；ADB 无设备；需 USB 调试连接或用户手机手动操作并补完整系统号 |
| G02 | PASS（2026-10-06） | 固定源码逐文件复核；完整 assembleDebug 成功（7m 1s）；arm64/x86_64/universal APK 签名验证通过，见 baseline-build-20261006.json |
| G03 | BLOCKED | 未完成真机安装/运行；Google/Firebase 依赖不能只靠源码判 PASS |
| G04 | 部分 PASS（桌面真实 Responses）；Android NOT_RUN | gpt-6.1-sol / medium 文本、624 个增量片段及流式终态一致性通过；Android 取消/错误显示尚待模拟器 |
| G05 | 部分 PASS（桌面真实往返）；Android NOT_RUN | 实际 s0_add 调用、参数校验、结果 42、无服务端存储的续答通过；Android 工具路径尚待模拟器 |
| G06 | BLOCKED | 印刷/手写图的真实模型与真机显示待测 |
| G07 | 部分 PASS（桌面真实合成抽取）；Android NOT_RUN | 合法偏好候选与来源白名单通过；同一结果跨档案来源拒绝；Android 原子事务与真实学习质量未测 |
| G08 | 格式范围已确认；保真待验 | 用户选择首版 PDF + Markdown，Word 导出后置；DOCX 输入仍保留，同机/各自设备及认证需求未确认 |

## S1 必须处理的基线差距

- 上游 applicationId 与应用名尚未修改；本项目需独立标识和独立签名，避免被上游更新覆盖。
- 上游 API key 字段仍在 provider 配置；Keystore 分离和日志/备份检查是本项目任务，不能把现状声明为已保护。
- 已存在多模态/工具/Skills 能力不代表满足本项目的自动抽取、档案隔离、删除抑制与文件验收。
- 本批不将版本降级、不改 SDK 要求、不借用原项目密钥。备份加密参数须在可构建基线上核验后补合同。

历史退出条件为 G01–G08 必需项全部通过。2026-10-06 用户在无 Mate 50 情况下明确继续 Android 开发，现按 v0.4 方案分开开发与交付门禁：G02、模拟器基础运行、中转文本/流式/工具/候选拒绝路径和格式范围验证后可进入 S1；Mate 50 G01/G03 及目标真机检查保留到交付前。固定基线构建、模拟器基础安装/冷启动/重开、桌面真实中转文本/流式/工具/候选来源拒绝及首版格式范围已通过，开发门禁通过，进入 S1。Android 流式/取消/工具和材料公式 UI 仍在 S1/S3 待验；不宣告完整 S0 或目标设备通过。


## 2026-10-06 接续执行

- 用户讨论 Apple 路线后选择继续 Android；本轮未迁移到 iOS。首版导出 PDF + Markdown，Word 后置。
- 原构建日志无终态、无产物且残留占位 Firebase 文件。哈希确认与公开样例一致后清理，再由脚本临时创建；未覆盖真实配置。10:13 启动的复验因路线讨论于配置阶段主动停止，日志中的 daemon disappeared 是本次停止导致，不作源码失败结论。
- 恢复构建增加可选 Info 与单次 HTTP 30 秒连接/读取超时。未修改上游版本或业务源码。完整 :app:assembleDebug 成功，269 个任务（265 执行、4 up-to-date），7m 1s；占位配置已自动移除。
- 三份 Debug APK 的路径、大小、SHA256 和签名通过记录见 [构建证据](../qa/s0/baseline-build-20261006.json)。它们是固定上游的调试基线，使用 me.rerere.rikkahub.debug，不是 MathStudyAgent 成品，也不证明 Firebase、真机或业务功能。
- 再次核对固定归档：1423 个主源码文件和 385 个子模块文件一致。本地候选/Chat/Responses 反例共 17 组通过（含候选跨档案来源拒绝）；见 [本地验证日志](../qa/s0/local-validation.log)。
- 用户本次为本项目提供 Responses 中转与认证信息；密钥通过隐藏终端输入，仅在探针进程使用，不保存到仓库或结果。首次默认 Python User-Agent 请求得到 403；调整为明确的应用 User-Agent 后文本、流式和工具往返通过。403 原因未取得服务端证据，不能断言只是 User-Agent。
- 实际模型 gpt-6.1-sol、reasoning.effort=medium、store=false；流式 624 个非空增量、980 字符，终态与增量内容一致；加法工具 17+25=42，原输出与 call_id 接续成功。结果在忽略目录 qa/s0/private/provider-responses.json，只留状态与计数。store=false 是请求参数，不保证第三方留存政策。
- Android 模拟器准备中：项目内 emulator 与 AOSP API 34 x86_64 镜像；WHPX 已检查可用。不含 GMS 的模拟器结果只作开发证据，Mate 50/HarmonyOS 安装、文件、后台和锁屏表现仍待交付前补验。

日志：.local/baseline-build-20261006.log（主动停止）、.local/baseline-build-20261006-resume.log（成功）、.local/emulator-install-20261006-proxy.log。真实模型图像与 Android 取消/错误、真实学习质量和记忆原子事务仍未验。


### 开发门禁通过，目标真机仍待验

AOSP API 34 x86_64 模拟器安装固定基线 APK 成功；冷启动 4622 ms，force-stop 后重新打开 4380 ms，聊天首页与侧边栏可见，应用进程存活，crash buffer 无记录。镜像未安装 com.google.android.gms。见 [模拟器基础证据](../qa/s0/emulator-baseline-20261006.json)；这些结果不覆盖公式、文件、联网 UI 或 HarmonyOS。

桌面真实合成记忆抽取返回一条合法偏好，来源白名单有效；把同一结果用于另一档案来源白名单时被拒绝。证据位于 qa/s0/private/provider-responses-extra.json；未执行 Android 数据提交事务，亦非真实学习质量评估。

按 v0.4 的开发门禁允许进入 S1，首批内容见 [S1 执行记录](s1-execution.md)。G01/G03 目标 Mate 50 及 G06 真机图像仍为未验证，完整交付前必须补齐。原固定基线 APK 已另存 .local/s0-baseline-apks/，与公开证据校验值一致，后续业务构建不覆盖此保全副本。
