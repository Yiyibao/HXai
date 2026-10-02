# S0 执行与证据

执行状态见 [S0 记录](../../docs/s0-execution.md)。`.local/` 保存安装工具、官方 APK、下载归档和原始构建日志；`qa/s0/private/` 保存用户设备/API 测试结果，两者均不入库。

## 本地检查

```powershell
python scripts/verify_baseline.py
python -m unittest discover -s qa/s0 -p 'test_*.py' -v
```

前一命令逐文件核对下载的固定归档，需 `.local/rikkahub-2.5.6.zip` 和颜色库子模块归档。模型候选校验测试仅验证结构、枚举、身份字段拒绝和来源 allowlist，不能替代 Android 提交事务或真实抽取精确率测试。

## 真机

连接 Mate 50，开启开发者 USB 调试并在手机确认本电脑。已有 RikkaHub 的数据请先自行备份；脚本使用上游原包名，`install -r` 会更新同包名应用，签名不一致时失败；不卸载已有应用。

```powershell
pwsh -File scripts/device_s0.ps1
# 确认设备信息后执行 S0 上游 APK 安装：
pwsh -File scripts/device_s0.ps1 -InstallBaseline
```

APK 是上游 2.5.6，SHA256 为 `f0a2cbe2224a22f439921f66c65682f43e8a3009347754291ea6a0a83de57af6`，不是 MathStudyAgent 成品。

手机上手动打开 → 关闭/再次打开 → 检查中文/分式/积分/矩阵 → 从文件选择器导入 PDF/DOCX → 配置接口 → 文本、长流式、取消、断网和重试 → 真正工具调用 → 印刷及手写题图。记录完整系统构建号、每项实际结果及必要截图；不得只因 adb install 成功就标记 G01/G03 完成。

## 真实中转

先确认服务是否兼容 Chat Completions。脚本仅支持此已明确选择的协议，不自动尝试其他协议。API base URL 应包含实际前缀，例如 `https://服务域名/v1`，脚本追加 `/chat/completions`；自定义请求头、路径和其他协议需在确认后适配。

在同一个本机 PowerShell 终端临时配置：

```powershell
$env:MATHSTUDY_S0_BASE_URL = 'https://服务域名/v1'
$env:MATHSTUDY_S0_MODEL = '实际模型名'
$s0KeyInput = Read-Host 'S0 API key' -AsSecureString
$env:MATHSTUDY_S0_API_KEY = [Net.NetworkCredential]::new('', $s0KeyInput).Password
try {
    python scripts/probe_provider.py --protocol openai-chat
} finally {
    Remove-Item Env:MATHSTUDY_S0_API_KEY -ErrorAction SilentlyContinue
    $s0KeyInput.Dispose()
}
```

共四个正常请求：文本一次、流式一次、工具请求与结果续答两次；不自动重试。只注册固定加法工具，不执行模型正文指令。拒绝重定向，避免凭据转发。结果仅保存状态、错误类别和计数，不保存请求头/密钥/原始模型输出。超时 45 秒，响应读取上限 2 MB。

桌面探针通过只证明对应接口行为；Android UI 取消/错误显示、图像、来源真实性和完整 Agent 往返仍需真机。Structured Output 不要求专用模式；真实抽取输出须通过本地结构和来源校验，后续 S2 再核验事实质量与原子提交。

## Android 构建

JDK 21、Gradle 9.6.0、SDK 37.2/37.0、Build Tools 37.0.0、NDK 28.2.13676358、CMake 3.22.1、Node 24 与 pnpm 已核对版本要求并安装。上游 `gradle/gradle-daemon-jvm.properties` 固定 JetBrains JDK 21，因此不能只根据 CI setup-java 的 17 推断最终 daemon。

```powershell
python scripts/setup_android_tools.py
python scripts/setup_android_tools.py --command-line-version 19.0
.local/android-sdk/cmdline-tools/19.0/bin/sdkmanager.bat --sdk_root=.local/android-sdk --licenses
.local/android-sdk/cmdline-tools/19.0/bin/sdkmanager.bat --sdk_root=.local/android-sdk 'platforms;android-37.2' 'platforms;android-37.0' 'build-tools;37.0.0' 'cmake;3.22.1'
python scripts/install_ndk.py
# client/local.properties 使用本机 sdk.dir，不能加入 Git。
cd client/web-ui
pnpm install --frozen-lockfile
pnpm run build
cd ..
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

若 Java 未读取本机 HTTP(S) 代理，在当前终端设置 JAVA_TOOL_OPTIONS 的代理参数后重试；不得将代理认证信息写入仓库。下载 Gradle 的失败可用同版本官方归档和 SHA256 校验后直接运行 `.local/gradle-9.6.0/bin/gradle.bat -p client ...`，不能以换版本绕过。

上游要求 Firebase/Google Services 配置。本地编译探针可使用公开占位配置，它不连接有效 Firebase 项目，也不证明运行能力：

```powershell
# 在项目根目录执行；仅需要代理时添加 -ProxyHost / -ProxyPort。
pwsh -File scripts/build_baseline.ps1 -UsePlaceholderFirebase
```

脚本保留已有 Google Services 配置，不覆盖它；若指定占位参数但已有配置则拒绝运行。临时占位副本在构建结束后删除。S1 关闭非必要遥测时须单独核验真实运行路径，不借用别人或 MathStudy 的真实凭据。
