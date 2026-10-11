# 核心需求 V1.0 开发完成记录

按用户授权完成 R0–R5 的顺序实施、逐项自审和修复。通用 Chat 主入口、动态交互消息、文件工具自动调度及实际成果交付、首批文件编辑、Skills 与备份入口均已接入 Kotlin 的真实业务。原有会话、教学模式和学习记忆保留。既有未提交工作区未重置，没有提交、推送或正式发布。

实施与自审入口：[执行记录](../../docs/core-v1-execution.md)、[R1](r1-review.md)、[R2](r2-review.md)、[R3](r3-review.md)、[R4](r4-review.md)、[R5](r5-review.md)。

## 最终验证

| 检查 | 结果 | 证据 |
|---|---|---|
| app JVM | 341 通过，无失败或跳过 | r5-jvm-summary.json、r5-final-build.log |
| AI SDK JVM | 202 通过，含两条工具协议适配 | 同上 |
| Flutter | 静态分析无问题，48 测试通过 | r5-flutter-analyze.log、r5-flutter-test.log |
| 当前 APK 模拟器验收 | 16 通过 | r5-final-runtime.log |
| 已配置真实模型 | gpt-6.1-sol 实际生成 Word 与有效交互界面；滑块、计算和继续按钮均存在 | core-v1-live-model.json |
| 实际 Flutter 系统交付 | 点击打开、另存、分享；独立 UID 接收及 DocumentsUI 保存后重读摘要均与源文件一致 | core-v1-flutter-delivery.json、core-v1-flutter-file.png |
| 文件增强产物 | Word/Excel 原件与未修改包部件保留；PDF 页序与文字转换通过；4 产物摘要一致 | editor-artifacts/、audit_editor_artifacts.py |
| 加密备份 | 交互定义、revision 和已保存控件状态随数据库恢复；旧删除规则与任务预算回归通过 | BackupReliabilityTest，包含于 16 项 |
| Skills | 说明、引用资料、文件资产分别验证；固定社区样例加载及脚本依赖提示；安装边界和跨档案/过期操作拒绝 | CoreSkillsWorkflowTest、SkillsInstallBoundaryTest，包含于 16 项 |
| 提交断线恢复 | 持久化待提交意图、同标识恢复、配置改变拒绝再次上传、非法页数确定失败 | DocumentSubmissionRecoveryTest，包含于 16 项；合成网络拦截，非在线服务 |

工具验证失败现在返回明确错误结果，模型可修正输入；不会生成交互面板或成果文件来冒充成功。远端未收到确认的提交仍显示 SUBMITTING，已有真实意图可恢复，但不冒称服务器已确认或已识别。

## 可安装文件

- [ARM64 手机 Debug APK](../../client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)：SHA-256 `ec88c60303bdee339b6d6a01fbdcc1f44793134d61ab197a1f7b45b8ecea61ad`。
- [x86_64 模拟器 Debug APK](../../client/app/build/outputs/apk/debug/app-x86_64-debug.apk)：SHA-256 `2fac79c0d887f65cab6d58bb90982ab719a66909f04392798b5333eff0656142`。

两包均包含与最终 Flutter AAR 一致的 32 项界面资源，见 r5-build-manifest.json 和 r5-arm64-build-manifest.json。ARM64 包含 Flutter 原生库，DEX 与已运行的模拟器包一致；这属于构建核对，不能代替真机运行。

## 已实现范围与未验收项

动态 UI 使用受限 JSON 组件目录 v1/v2：卡片、表格、图表、参数滑块、选择、文本输入、线性计算及继续按钮，支持消息内状态持久化与筛选。它不宣称完整 GenUI/A2UI 协议兼容；两条协议适配的本地测试也不等于所有模型在线验收。

Word 首批编辑简单同字符样式段落，Excel 首批编辑常量单元格，PDF 按页合并/拆分；文字版 PDF 转换明确不保留原版式。拒绝复杂段落、宏/签名包和含公式工作簿等不支持范围。PPT 生成沿用已有工具，没有新增 PPT 保真编辑。独立接收程序验证真实文件授权和正文读取，不是 WPS/Word 全格式排版验收。

社区 Skills 可加载说明、引用和支持的资产；Python/Node/Shell/桌面依赖缺失时显示部分兼容，完整脚本流程尚不能执行。没有建设大型技能商店或 MCP 平台。

本批没有可用的独立 MinerU 测试凭据，真实服务与模型自动续聊联合验收未执行；任务权限、已校验缓存续聊、损坏缓存拒绝、失败重试及提交恢复由合成测试验证。旧 HTTPS 部署记录仍保留，不能替代本次联合流程验收。Mate 50、两台真实设备迁移、复杂第三方文档保真和正式签名发布仍待验证。当前交付为 Debug 开发包。
