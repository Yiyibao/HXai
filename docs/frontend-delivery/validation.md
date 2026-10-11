# 前端复审修复验证

日期：2026-10-08（Asia/Taipei）。本记录取代原 Gemini 验证报告中未经运行的静态渲染、全页完成及“无 AVD”结论。

## 构建与单元回归

在仓库根运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test_frontend_review_fixes.ps1 -BuildAndroidTests
```

最终结果：`BUILD SUCCESSFUL`。APK、Android 验收测试包均构建成功。脚本保留已有 Firebase 配置，仅在不存在配置时创建占位文件，并在 finally 中删除本次创建的文件。本次结束后没有占位 `google-services.json` 残留。

| 套件 | 用例 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| StudyChatContractTest | 10 | 0 / 0 / 0 |
| MemoryContractTest | 37 | 0 / 0 / 0 |
| DocumentContractTest | 4 | 0 / 0 / 0 |
| 合计 | 51 | 0 / 0 / 0 |

测试调用生产分组、状态映射、模型保存与记忆编辑 reducer，涵盖同名模型的提供商隔离、禁用过滤、保存等待及失败、取消传播、去重记录重建、持久化写入失败、编辑版本冲突与历史保留。

原始构建日志、JUnit XML、APK 大小和 SHA-256 见 [evidence/summary.json](evidence/summary.json) 及 `evidence/`。

## Android 模拟器验收

实际设备：本地 `mathstudy-s0` AVD / `emulator-5554`，Android API 34。不是物理真机。

安装本次 APK 和 Android 测试 APK 后执行：

```powershell
.local/android-sdk/platform-tools/adb.exe -s emulator-5554 shell am instrument -w -r -e class me.rerere.rikkahub.service.FrontendModelPickerTest,me.rerere.rikkahub.service.FrontendDeliveryRoutesTest org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner
```

普通字号：`OK (3 tests)`。系统 `font_scale=2.0`：`OK (3 tests)`。大字体验收结束后已恢复原系统字体比例。

三项运行测试：

- 模型抽屉等待保存；存储失败后保留旧选择和错误提示；重试成功后关闭。
- 正式 P12→P13→P14 路由可达。P13 通过真实 HTTP 客户端访问测试端点，服务端确实收到 `GET /v1/models HTTP/1.1`；成功后显示实际模型数。未保存草稿退出确认及最终保存通过。
- P06 使用真实记忆存储：编辑过程中外部更新，再重建 Activity，草稿仍保留；旧 revision 保存被拒绝；重新打开编辑器后成功保存新版本。

HTTP 端点是仅供 androidTest 的本地 fixture，不是商业提供商连通性验证，也不是生产模拟成功。

截图检查发现长设置标签在大字体下挤占开关，本轮已为标签分配有限宽度并允许换行；提供商操作使用 FlowRow，编辑页沿用白粉主题。最终普通和 200% 字体截图均已重新采集并逐张检查。

## 进程恢复去重

分别用同一个 UUID fixture 执行 seed / verify，在两阶段之间 `am force-stop org.mathstudy.agent.debug`：

```powershell
.local/android-sdk/platform-tools/adb.exe -s emulator-5554 shell am instrument -w -r -e class me.rerere.rikkahub.service.FrontendFeedbackProcessTest -e feedbackPhase seed -e feedbackFixture <UUID> org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner
.local/android-sdk/platform-tools/adb.exe -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
.local/android-sdk/platform-tools/adb.exe -s emulator-5554 shell am instrument -w -r -e class me.rerere.rikkahub.service.FrontendFeedbackProcessTest -e feedbackPhase verify -e feedbackFixture <同一UUID> org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner
```

两阶段均为 `OK (1 test)`，重启后的消费被拒绝。原始日志见 `evidence/frontend-review-feedback-*.log`。

## 实际证据与验收范围

- `runtime-screenshots/`：P06 版本冲突、P12、P13 真实请求、P14 的普通字号和 200% 字体实际模拟器截图，共 8 张。内容使用明确的 QA 数据。
- [recordings/frontend-review-large-font.mp4](recordings/frontend-review-large-font.mp4)：最终 200% 字体定向运行录屏。
- `screenshots/`：原设计参考图，不是实际应用截图。

本轮没有完成全量 P01–P16 业务截图、完整 IME/读屏测试、所有动效开关与生命周期验证、真机性能或 OEM 输入法适配。P04 回写与 P08 高级导出仍待后续接线。这些项目保持待验收，不能从定向测试推断全部通过。未运行全项目 JVM 测试与 lint。
