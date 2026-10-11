# 文档服务启用与实测

2026-10-11。在已连接的 Android 14 x86_64 模拟器 `emulator-5554` 上，为 `org.hxsi.agent.debug` 当前档案 `default-learner` 启用 `https://hxnf.top/documents`。使用本机已有 learner-1 独立凭据，通过应用 DocumentServiceRepository 保存到 CredentialVault；临时凭据文件已经删除。凭据没有写入报告或 APK。

- HTTPS 鉴权能力查询通过，Basic 解析和 Office 转 PDF 均报告 ready。
- Android 实际调用聊天共用的 `parse_material_enhanced` 工具，上传五页合成扫描 PDF，任务 SUCCEEDED，下载并校验五页、20 个识别块，重新构造仓储后读取一致；远端清理确认通过。专项 `OK (1 test)`，见 android-test-final.log 和 android-evidence.json。
- 应用冷重启后，从 Flutter 设置页实际点击“测试连接”，显示“连接成功，服务已就绪”，见 connection-ui.png。
- 独立服务探针的 PNG 和五页 PDF 均成功，分别耗时 36.5 和 39.7 秒，见 server-probe.json。

聊天没有单独的全局上传开关：服务启用后，聊天根据用户消息中的上传许可和限制筛选工具。本次验证了“允许上传”消息对应的实际远端解析工具执行；没有调用聊天模型完成自然语言对话，也没有替用户修改既有聊天的上传限制。

两次初始 Android 测试失败源于测试传输：直接向 adb shell stdin 写二进制导致 PDF 被截断，错误为 parse_failed。修正为 Base64 传输并逐字节核验后复测通过。失败日志保留，远端损坏测试副本已清理；它们不是有效 PDF 的解析失败证据。

仅模拟器配置完成，未连接实体手机。服务配置属于安装后的私有档案数据，不内置于 APK；实体手机仍需独立配置。Office 此次仅验证能力就绪，没有提交真实 Office 转换任务。
