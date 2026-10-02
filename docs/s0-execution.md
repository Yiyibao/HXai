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
| G02 | FAIL（修复后复验中） | 固定来源与 Gradle/前端通过；SDK/NDK/CMake 已安装；完整 APK 构建正在重跑 |
| G03 | BLOCKED | 未完成真机安装/运行；Google/Firebase 依赖不能只靠源码判 PASS |
| G04 | BLOCKED | 未提供中转地址/模型/协议与安全配置；桌面及手机流式、取消/错误待测 |
| G05 | BLOCKED | 加法工具探针已准备，真实往返与 Android 校验待测 |
| G06 | BLOCKED | 印刷/手写图的真实模型与真机显示待测 |
| G07 | NOT_RUN（本地结构校验 PASS） | 真实抽取、来源语义及 Android 拒绝提交未测 |
| G08 | BLOCKED | 已询问首版 Word/公式要求与使用者设备分工，尚无答复 |

## S1 必须处理的基线差距

- 上游 applicationId 与应用名尚未修改；本项目需独立标识和独立签名，避免被上游更新覆盖。
- 上游 API key 字段仍在 provider 配置；Keystore 分离和日志/备份检查是本项目任务，不能把现状声明为已保护。
- 已存在多模态/工具/Skills 能力不代表满足本项目的自动抽取、档案隔离、删除抑制与文件验收。
- 本批不将版本降级、不改 SDK 要求、不借用原项目密钥。备份加密参数须在可构建基线上核验后补合同。

退出条件：G01–G08 必需项有真实通过证据或明确且已复验的替代路线。当前不能宣告 S0 完成，也不推进 S1 业务实现。
