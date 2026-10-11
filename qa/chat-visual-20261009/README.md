# Chat 视觉优化验收

参考：`docs/ui-design/chatgpt-inspired-v1/two-page-preview.png` 与 `smart-chat-three-scenes.png`。

## 实现

- Chat 顶栏、居中空状态、三张轻量快捷入口、白底正文、右对齐灰色用户气泡。
- 修正重复水平留白、操作栏无界宽度下的 Spacer、键盘高度重复扣除；模型名支持截断。
- 正文 16px；标题、列表、代码、表格采用统一 Markdown 排版。
- 附件用类型图标与真实文件名；成果卡主操作为预览/保存，分享在文件菜单。不再展示伪造的“示例文件”大小。
- 工具完成步骤收紧为灰色状态行；思考内容保留折叠入口。
- 智能卡片采用白底细边框、黑色操作与结果、灰色来源、蓝色图表；包含坐标、网格、数据点、选中提示、全屏展开。
- v3 layout 中并排 slider 渲染为有范围校验的数值输入；单列 slider 保留滑块。计算、继续对话、状态保存沿用原有桥接。无效输入阻止提交；恢复默认不会触发模型请求。
- 空输入为语音按钮，输入内容后变为发送箭头；语音返回结果校验当前档案与会话。
- 普通聊天使用双行输入框；含智能卡片的会话使用单行输入栏，大字/窄屏自动回退。
- 更新模型工具说明，指导模型生成适合手机的卡片，同时明确 select 不会自动筛选未绑定的图表。

## 验证与图片

相关 Flutter 回归 28 项通过，覆盖富文本、发送失败保留草稿、会话隔离、文件操作、状态恢复、参数重算、范围校验、恢复默认和图表选点。修正了旧图表测试用最后一个 CustomPaint 作为点击目标的问题，改成选择实际图表并断言有效选点。

`ChatVisualReviewTest.captureReferenceScenes` 通过真实 Kotlin→Flutter 消息桥接展示普通聊天、销售趋势、月供试算，并验证实际渲染语义、保存模拟器截图。所有消息、销售数据和来源名均为合成验收内容，不是实际读取用户文件的证据；不调用模型服务。验收会话在结束后删除。

- [普通聊天](chat.png)
- [新聊天首页](home.png)
- [销售趋势](analysis.png)
- [月供试算](parameters.png)
- [真实文件成果卡](file.png)

真实文件卡验收通过：Flutter 按钮→Kotlin→系统文件选择器/独立测试接收应用，预览、保存、分享的 SHA-256 均与源文件一致，见 `file-delivery.json`。首次验收因测试提前点到 Flutter 的同名保存按钮而失败，已增加系统 DocumentsUI 前台等待并重跑通过（`chat-visual-file-runtime-test.log`）。原失败日志保留。

最终 APK 已安装到模拟器；本地 APK 与设备安装的 APK 哈希一致，见 `build-identity.json`。合成验收会话与临时源文件在结束后清理，保留原有用户会话与设置。

Android 构建和检查记录：`.local/chat-visual-android-build.log`、`.local/chat-visual-validation-build.log`；Flutter 检查记录：`.local/chat-visual-tests.log`、`.local/chat-visual-analyze.log`；实际运行记录：`.local/chat-visual-runtime-test.log`。

Android Lint 完成，0 错误、46 警告；Flutter analyze 没有 error/warning，仍报告风格级 info（主要为已有单行 if 的花括号规则）。验收日志副本保存在本目录。

当前图表筛选仍遵循已有协议：select 直接筛选绑定的 table，图表点选与控件状态可提交给模型继续分析。此次未增加图表文件导出能力。已有历史卡片的排列仍取决于其保存的 layout，新工具说明作用于后续生成。
