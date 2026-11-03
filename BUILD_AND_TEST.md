# 构建与测试指南

## Release 构建接线（A07）

协议和命令见 `docs/a07-release-build-protocol-v1.md`；实际结果和未验收范围见
`HXai_CHANGE_REVIEW/A07-RELEASE-20261021/`。先生成 Debug/Release Flutter AAR，再构建宿主。
无正式密钥时允许生成 unsigned Release；不会自动使用 Debug 签名。

```powershell
.local/gradle-9.6.0/bin/gradle.bat -p client :app:assembleDebug :app:assembleRelease -PagentNativeTest=false --offline --console=plain
python scripts/verify_flutter_apk.py --mode release --output release-package.json
```

已签名包用 `--apk` 指定，`--aar` 可绑定冻结 AAR。Release 核验资源/AOT/全部 native ABI，
构建和包核验仍须分别补实际启动、正式签名、目标设备及完整发布门禁。

## A04 持久合成 MCP（仅 nativeqa）

协议：`docs/a04-durable-mcp-protocol-v2.md`；实际裁决与原始失败在 `HXai_CHANGE_REVIEW/A04-DURABLE-MCP-20261021/`。
确认独占的 `mathstudy-ab-merge-20261021` AVD / `emulator-5590` 已启动，新run-id/run-dir不存在后：

```powershell
python scripts/verify_agent_android.py --build --serial emulator-5590 --run-id a04-durable-NEXT --classes me.rerere.rikkahub.feature.agent.McpInstallationRuntimeTest,me.rerere.rikkahub.feature.agent.SyntheticMcpRuntimeTest,me.rerere.rikkahub.feature.agent.SyntheticMcpChatStoryTest,me.rerere.rikkahub.feature.agent.McpManagementSurfaceTest
python scripts/verify_mcp_persistence.py --serial emulator-5590 --run-dir HXai_CHANGE_REVIEW/A04-DURABLE-MCP-20261021/process-NEXT --mode unknown
```

第一条按原脚本归档后重置QA数据、离线构建/安装；不改普通App。第二条不清库，核已安装APK的实际SHA/身份，等待持久ready与live PID后真实强停、新进程读回，mode可为installation/revoked/old_approval/unknown；每次使用新run-dir。此probe不能直接当普通JUnit类批跑。`--install`可显式安装已构建QA APK，先核包名；不安装依赖。工具发现/读取仍只在QA，固定只读工具逐任务仍须审批；不是通用MCP或脚本沙箱验收。

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
