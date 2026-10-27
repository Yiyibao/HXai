# 当前工程Release接线协议 v2（A24-01）

来源：`HXai_CHANGE_REVIEW/A24-01-RELEASE-INTEGRATION-20261024/`。base/head为 `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批精确增量及SOURCE清单，不能仅用HEAD代表实际产品。v2继承A07隔离候选，接续当前A04/A05与专业数学合同16/17。实际运行结果只取本批TEST_RESULTS，本文不预授通过。

## 构建与运行合同

`app/build.gradle.kts` 的Debug使用 `flutter_debug:1.0`，Release使用 `flutter_release:1.0`，来自当前Flutter模块的Maven输出。先从固定源码分别生成AAR，再构建宿主，保持优化/R8和既有依赖版本。无完整分发签名配置时生成unsigned Release，不用Debug密钥补位。安装烟测只使用另存的LOCAL-TEST-ONLY副本，不能作为正式发布签名。

`SYNTHETIC_MCP` 默认false，只在Debug且 `agentNativeTest=true` 时开启。Release即使误传该属性也须false；QA Manifest/源仍限Debug。通用MCP/OAuth的正常用户授权入口不因此关闭或获得QA的loopback权限。当前Room33、TaskStore10、记忆payload1/2和既有Pigeon类型/nullable费用字段不迁移；模型锁、owner/profile/epoch、授权、墓碑、journal/receipt/UNKNOWN不改变。

真实链：variant runtimeClasspath→对应Flutter AAR/engine/AOT/assets→Manifest StudyFlutterActivity→Pigeon/StudyHostFacade→原TaskStore/ChatService→Flutter主Chat。资源匹配不等于启动，启动不等于真实模型质量或完整任务成功。

## APK独立读回

`scripts/verify_flutter_apk.py` 默认Debug命令兼容；新增 `--mode release`、`--aar`、`--flutter-sdk`。核所有Flutter资产（含多余/缺失/陈旧及重复ZIP）、所有包内native ABI的engine、Release的AOT和Debug快照污染。

engine以所选预装SDK对应mode/ABI的flutter.jar为依据，比较ELF架构/入口/程序表和全部SHF_ALLOC节的地址、属性及内容，允许Android正常strip非加载调试表；原始字节SHA仍保存。Release libapp.so须与固定AAR中对应AOT一致。核验器不执行APK、不访问账号、不自行安装工具。

```powershell
# 从仓库根目录；先生成本版本两种AAR并确认离线缓存
.local/gradle-9.6.0/bin/gradle.bat -p client :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :ai:testDebugUnitTest :app:lintDebug :app:lintRelease -PagentNativeTest=false --offline --console=plain
python scripts/verify_flutter_apk.py --mode release --apk PATH_TO_APK --aar PATH_TO_FROZEN_AAR --flutter-sdk .local/flutter --output NEW_REPORT.json
```

Flutter AAR直接离线Gradle构建的结构化命令见本批 `aar-build.json`；避免PowerShell将带小数/逗号的属性拆成额外任务。`work.py spec`读取JSON原参数，无shell拼接。只在新目录和匹配SDK生成，不借旧A07 AAR证明当前Dart源码。

本批以完整当前源码副本 `.local/a24-01-release-20261024` 和专属新AVD验证，不修改用户普通App数据。Web静态资源沿现有buildWebUi/copy.ts生成到该副本，非直接复用历史输出；构建日志和锁文件hash记录依赖与实际命令边界。

## 来源及回滚

合同17后7份A所属共享文件与A04/A05最终冻结来源逐字节核对，见A_SHARED_PROVENANCE；这些文件本批不重写。B所属数学测试和合同17整批收尾仍归B24-01；不能借本批通过宣称其全部完成。schema总版本继承到10，不修改原历史协议。

回滚仅撤本批Gradle/核验器/文档增量，停受影响入口，保留原文件、材料版本、任务/账本/墓碑/新schema/receipt/UNKNOWN；不reset/clean/stash或恢复已撤权限。Mate50/HarmonyOS4.2、TalkBack实际语音、真实Provider/OCR、正式签名/发布/生产分别验收；独立与完整全项目审查未执行保持NOT_EVALUATED。
