# S1 开发完成记录 · 2026-10-07

结论：S1 在已确认的构建、模拟器及真实 Responses 开发门禁范围完成。目标 Mate 50 / HarmonyOS 4.2.0 不在本次连接设备中，安装、无 GMS、设备网络及目标视觉验收保留到 S6；本报告不宣告整个 v1 或发布完成。

## 实现与边界

- 独立包名、教学默认助手、学习者档案及会话/收藏/文件夹/聊天工具所属身份隔离。
- 聊天/材料/记忆/成果四入口，原创静态书签伙伴、真实运行状态、可达停止按钮。后三个入口明确能力尚未开放，等待 S2–S4。
- 引导/提示/检查/完整讲解模式持久化，Room 28→29 自动迁移；模式固定于每次生成，明确用户要求优先，历史分支继承模式。
- 流式、取消、中断保全、暂停队列、逐工具检查点、未知效果人工核查。编辑/选择/重生成保留旧路径，移除新分支后续派生回复，暂停旧队列。
- AndroidKeyStore AES/GCM 配置与 no-backup 引用，失败分区恢复 UI，先归档后重置，聊天和档案保留。备份、QR/分享模板及导入清除凭据。
- 模型、搜索、语音、网络和崩溃诊断不输出原始正文/异常消息。Web 和全局文件浏览因上游档案边界不足而关闭；聊天内附件仍可访问。
- 默认减少动画；键盘显示时隐藏底部四入口。伙伴为静态向量，完整动作系统后续实现。

S2 记忆派生失效、材料解析、成果生成、跨设备备份迁移、外部写入工具真实效果核查、生产十分钟超时和签名发布不属于本次完成声明。

## 最终包验证

设备：AOSP API 34 x86_64，emulator-5554。应用：org.mathstudy.agent.debug，开发 Debug 签名。最终构建日志 `.local/s1-completion-auxiliary-build.log`：BUILD SUCCESSFUL，1m16s。

| 检查 | 结果 | 本地证据 |
|---|---|---|
| JVM app / ai / search | 248 / 198 / 16，合计 462，零失败 | 各模块 build/test-results/testDebugUnitTest；构建日志 |
| Android 凭据、分支/模式、Compose 导航、网络故障 | 30 执行通过；1 可选视觉 fixture 跳过（runner 显示 OK 31） | `.local/s1-completion-android-final.log` |
| force-stop 后凭据回归 | 10 通过 | `.local/s1-completion-credentials-cold.log` |
| 两阶段跨进程恢复 | seed / verify 各 1 通过；PID 7252→7356 | `.local/s1-completion-process-seed.log`、`s1-completion-process-verify.log` |
| 真实加密配置冷启动、四模式与审批 | 同一最终 APK 复测 1 通过，30.237s；前一次超时失败保留 | `.local/s1-completion-real-model-retry.log` / `s1-completion-real-model.log` |
| 三架构 APK 签名与哈希 | PASS | [包清单](s1-apks-20261007.json) |

复现：`scripts/test_s1_completion.ps1 -RealModel`，只允许模拟器；真实模型检查显式 opt-in，读取设备已有加密配置，不打印密钥。已有包可加 `-SkipBuild`。真实响应有网络和模型非确定性，测试不替代持续教学质量评估。

真实样例：[四模式与审批结果](s1-real-teaching-20261007.md)，已从最终 APK 成功复测取回。先前两轮真实检查已通过；初始测试曾因 fixture ID/前台条件错误失败，修正测试后复测。最终综合 runner 的真实阶段出现 120 秒超时，因此该次 runner 不记整体 PASS；同一 APK 单独复测 30.237 秒通过，未更改运行代码。超时原因未确定，不以通过覆盖失败记录。真实网络、强杀、队列与工具接续证据另见 [网络与恢复](real-responses-recovery-20261006.md)，其失败与修复记录保留。

## 页面检查

以下为已安装界面截图；数学富文本截图使用明确标记的合成视觉样例，独立于真实教学样例。视觉 fixture 已清理，系统字体配置已恢复。

- [首页与四入口](s1-home-20261007.png)
- [矩阵、表格与长公式](s1-richtext-20261007.png)
- [横向滚动到公式末端](s1-formula-end-20261007.png)
- [200% 系统字体](s1-font200-20261007.png)
- [200% 字体下键盘与输入](s1-keyboard200-20261007.png)

长公式末端可通过横向滚动访问；大字体下控件与输入可访问，键盘打开时四入口隐藏。此证据只覆盖模拟器和所用样例，不等同目标手机所有字体、深色和辅助技术验收。

## 安装产物

- arm64：`client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，SHA256 `7fdde5433eca9e27c403a67a6335973e2f80e1f914a4a6edebf8d1a566ac8f40`。
- universal：`client/app/build/outputs/apk/debug/app-universal-debug.apk`，SHA256 `2d17ba5f34b4083894dc121f18500dd10c0f3205ac1ad940bb7485def86d2017`。
- x86_64（本次模拟器最终包）：SHA256 `a8c420afff0fae1527804cdd5aab124bcfabc6e5cab1bf1ef59fdde70e366a04`。

临时占位 google-services.json 已移除。未提交远端、未发布。
