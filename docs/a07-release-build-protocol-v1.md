# A07 Release 构建合同 v1（2026-10-21）

来源：`HXai_CHANGE_REVIEW/A07-RELEASE-20261021/CHANGE_CONTRACT.md`，base/head
`5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加该批 SOURCE_FINAL 所列工作树。
完整产品、真实 Provider、Mate50/HarmonyOS4.2、正式签名、发布和独立审查均须另验。

`client/app/build.gradle.kts` 的 Release 使用 `flutter_release:1.0`，Debug 使用
`flutter_debug:1.0`。两者从当前 Flutter 模块 Maven 仓库解析，分别生成后才构建宿主。
R8/优化保持启用。未配置发行密钥时产物为 unsigned APK；完整配置密钥时沿用既有
release signingConfig，不用 debug 密钥补位，不读取或复制密钥进证据。

`SYNTHETIC_MCP` 默认 false，只有 Debug 在 `agentNativeTest=true` 时可启用；
Release 即使误携该构建属性也不能启用。QA 源目录和 Manifest 仍只属于 Debug。
原 TaskStore/Room33/sidecar9/Pigeon164、模型快照、owner/profile/epoch、授权、
材料版本、墓碑、备份和 UNKNOWN 无迁移。正式包不因此启用通用 MCP/脚本沙箱。

真实入口链：Release runtimeClasspath → release embedding/AAR → AOT/engine/Flutter
assets → Manifest `StudyFlutterActivity` → Pigeon `StudyHostFacade` → 现有统一 Chat。
资源一致不证明启动成功，启动画面不证明真实模型规划质量。

`scripts/verify_flutter_apk.py` 保持默认 Debug 命令兼容；`--mode release` 对每个包内
native ABI 检查 ELF engine 和与 AAR 字节一致的 AOT，核对所有 Flutter assets，
拒绝多余资源、Debug 快照和 ZIP 重复条目。可用 `--aar` 固定实际构建 AAR。
引擎以构建所用 `--flutter-sdk` 的对应 ABI/mode 为独立依据；Android 会去除调试符号，
因此比较架构/入口/程序表及全部 SHF_ALLOC 节地址、属性和内容，允许仅非加载调试表变化。
原始全文件哈希仍记录，不能只检查 ELF 魔数或文件存在。合法 Debug 的首次原始字节比较
误拒与修复后读回均保留在本批证据中。
默认 Release 路径为 `app-x86_64-release-unsigned.apk`，已签名包用 `--apk` 指定。

构建命令（仓库根目录；工具/依赖已安装，不升级版本）：

```powershell
Push-Location client/flutter_ui
../../.local/flutter/bin/flutter.bat --suppress-analytics pub get --offline
../../.local/flutter/bin/flutter.bat --suppress-analytics build aar --debug --release --no-profile --no-pub
Pop-Location
.local/gradle-9.6.0/bin/gradle.bat -p client :app:assembleDebug :app:assembleRelease :app:lintDebug -PagentNativeTest=false --offline --console=plain
python scripts/verify_flutter_apk.py --mode release --output release-package.json
```

Flutter 官方命令未提供 Gradle `--offline` 透传。本批使用已安装 Flutter 的
`aar_init_script.gradle` 与其 `buildGradleAar` 的等价参数，直接运行
`assembleAarDebug assembleAarRelease --offline`；精确命令在批次 `aar-build.json`。
首轮 Release 缺失固定变体曾补取既有 Maven 依赖，之后必须另跑离线宿主构建。
任何失败和测试签名副本以本批原始日志/TEST_RESULTS 为准，不因本文命令存在而判 PASS。

回滚仅撤本批 Gradle/核验脚本增量，保原 Debug 及用户数据；停用未验收的 Release
交付，不用旧版程序降库或重放 UNKNOWN。
