# P0 补齐与真实联合验收（2026-10-09）

本轮完成了富 Office 文件的有界编辑、全文历史搜索与用户消息编辑分支、交互 UI v3，以及超过单次 5 页的文档批次续行。**不能宣称全部 P0 已闭合**：Office 保真转换仍缺排版渲染器；FD07/FD11 的部分条件只有受控回归，尚未全部在生产 MinerU 上验证。

## 已实现与实际证据

| 范围 | 实现与验证 | 证据 |
|---|---|---|
| Word/PPT 富内容编辑 | 检查并替换指定文本运行，保留其他样式、图片、表格、公式及所有未选择的 ZIP 条目；拒绝宏/签名及 Word 域结果编辑 | `P0RichFileTest`、`collected/p0-rich-output/`、`artifact-verification.json` |
| Excel 常量修改 | 保留公式、样式及图片；拒绝修改公式单元格，清除旧公式结果缓存，标记 Office 打开后重算 | 同上；不宣称客户端已执行公式计算 |
| 真实转换 | 图片像素生成真实 PDF；PNG 渲染人工检查无裁切。Office 转 PDF 明确失败；纯文字副本另命名 `export_material_text_pdf` | `collected/p0-rich-output/image-preserved.pdf`、对应 PNG |
| 全文历史 | SQL 搜索全部持久化标题与消息正文，档案隔离，保留查询分页及晚到请求保护 | 原目标位于 55 条更新会话之后，桥接回归仍能搜到；Flutter 查询/分页测试 |
| 已发消息编辑 | 新版本分支保留旧消息，旧下游路径归档，幂等请求，拒绝改变已提交请求与过期上下文 | `FlutterChatBridgeTest`、`process-state-final-*.log` |
| 交互 UI v3 | 可选择图表、受限表达式多参数实时计算、明确组件行布局和窄屏自适应；状态落盘、继续动作去重 | Flutter `p0_surface_test.dart`、原生 v3 桥接测试、真实进程重启测试 |
| 文档批次 | 持久化范围/源 SHA/配置/任务 ID；逐批校验覆盖后提交下一批；整批取消；全部完成只续聊一次；重试保留完整页范围 | `P0DocumentBatchTest`、真实 7 页 MinerU 联调 |

## FD01–FD11 分开记录

| 场景 | 本轮结论 | 证据/边界 |
|---|---|---|
| FD01 可读 PDF | 真实模型通过，库存 21 正确，零远端任务 | `collected/p0-routing/FD01.json` |
| FD02 扫描表格 | 真实 MinerU + 真实模型通过 | `live-dispatch-repaired.log`；不是模拟 OCR |
| FD03 混合可读/扫描 | 真实模型两次读取页图覆盖 1–5、6–7，最终重跑通过，独立重开实际 Excel 核对全部七条名称/数量/价格 | `mixed-coverage-final.log`、`collected/p0-live-mixed/`；视觉路径不强制调用 MinerU；旧强制远端断言失败日志保留 |
| FD04 图片问答 | 真实视觉模型识别蓝色方形、橙色圆形，零远端任务 | `collected/p0-routing/FD04.json` |
| FD05 文字生成 | 真实模型生成 docx/xlsx/pptx/pdf 四种实际成功文件，零远端任务；路由测试通过 | `live-mixed-final.log` 中 `readablePdfAttachmentOnlyAndLocalRestrictionDoNotUpload` 成功；最终结果 JSON |
| FD06 扫描生成 Excel | 真实 MinerU → 自动续聊 → 实际 Excel，七行名称、数量、价格可独立重开验证 | `collected/p0-live-dispatch/output.xlsx`；`artifact-verification.json` |
| FD07 缓存/版本/范围 | 去重、已下载缓存读、配置和源摘要约束有受控设备回归 | 不宣称已执行生产环境源变更/重复上传全组合 |
| FD08 限制/不可用 | 真实“仅本地”零上传；拒绝权限后的下一批及其他服务失败边界为受控测试 | `FD08.json`、权限回归、批次测试 |
| FD09 仅附件 | 真实模型未默认上传 | `FD09.json`、零远端任务断言 |
| FD10 超 5 页全文 | 真实 MinerU 两批覆盖物理页 1–7，模型自动交付，未要求重发任务 | 7 页结果 JSON、实际 Excel 与会话工具记录 |
| FD11 取消/恢复 | 批次持久化重建、取消阻止新提交、丢失回执同键恢复为受控 HTTP 测试；真实杀进程验证聊天分支和交互状态 | 不把模拟状态机计作生产 MinerU 排队/运行中取消或杀进程完整验收 |

## 最终构建与测试

- Flutter：78 项通过；analyze 无问题（`flutter-tests.log`、`flutter-analyze.log`）。
- JVM：app 343、ai 202 项；最终业务构建和 Lint 成功（`retry-range-final-build.log`、`verified-business-build.log`）。Lint 0 错误、46 警告。前次 Lint 工具异常保留，不用旧报告替代失败。
- 最终 APK 设备回归 30 项全部通过（`final-device-regression.log`），包含实际 Flutter 按钮打开、DocumentsUI 另存及跨 UID 分享，接收内容 SHA 一致。
- 真实进程重启：seed PID 5803，verify PID 5894；两阶段通过（`process-state-final-seed.log`、`process-state-final-verify.log`）。核验当前编辑版本、原版本、全文搜索与旧路径图表状态，完成后删除夹具。
- x86_64 / arm64 APK 均与当前 AAR 的 32 项 Flutter 资产一致（`flutter-*-assets.json`）。实际设备测试仅 x86_64 模拟器；不称已在 Mate50/arm64 真机验收。
- x86_64 APK SHA-256：`64dfc9fb29087885da96decbf5e109d7f2a142d7525aba1e91326389bbe49e18`。
- arm64 APK SHA-256：`bd42659f47b738e3c5c375512d837852dab65c610964a824bfb0bd2df7c154ef`。

## 凭据与范围

用户授权后，从现有源服务器发行独立测试身份，没有使用既有学习者凭据。令牌只进入服务器 0600 文件、本机忽略的 `.local` 文件（ACL 仅本用户）和应用私有 0600 缓存；没有打印或写入 QA。只上传本轮生成的合成材料。独立身份已撤销，服务器、本地和设备凭据副本均已删除，见 `credential-cleanup.json` 和日志。既有身份没有变化。

首次测试错误地通过常规设置更新切换档案，停在准备阶段并被中断。临时助手已经通过唯一原助手匹配恢复（`setup-recovery.log`），后续使用正式档案接口并在 finally 恢复。首轮未保存原 `enableSuggestion` 值，不能证明该偏好已恢复为中断前的值；未凭猜测改回。其他失败夹具（创建会话标志、JUnit 返回类型、混合 PDF 强制远端断言）保留原失败日志，修正后重跑，不计为通过。

没有提交、推送或部署生产服务变更；已有大量工作区改动保留。Office 输出用独立 Python 库重开和结构核对，未在 Word/WPS/PowerPoint 桌面应用做最终排版检查。PPT 当前 `slide` 定位物理 `slideN.xml`，不支持按被重新排序后的展示顺序定位；长文本溢出仍需 Office 核对。复杂模板自动改版、忠实 Office 转 PDF 及完整 FD07/FD11 真实外部组合仍为后续工作。
