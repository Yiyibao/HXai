# 搜索可靠性完善（2026-10-10）

参考公开产品行为与官方规范，不推断 ChatGPT 或 Claude 的内部实现：
- [ChatGPT web search](https://learn.chatgpt.com/docs/web-search)：按需联网、记录搜索过程、结果作为不可信输入。
- [OpenAI web search](https://developers.openai.com/api/docs/guides/tools-web-search)：可点击正文引用、来源列表、域名筛选。
- [Claude web search](https://support.claude.com/en/articles/10684626-enable-and-use-web-search)：显示搜索过程、综合多个来源、引用原出处、直接读取网页。

## 本次实现

1. 默认 Bing 按普通 HTML → RSS → DuckDuckGo 顺序尝试，每个请求最长 8 秒，最多三个入口，输出实际使用的 provider 和切换提示。支持 includeDomains/excludeDomains；查询附加域名条件，并按返回来源的实际主机名再次筛选，不把相似域名当作命中。
2. search_web 和 scrape_web 用统一边界处理错误。搜索内部 30 秒截止返回结构化 timeout；无结果、网络失败、HTTP 错误、参数错误返回 status/error/nextAction，不再在生成循环里变成执行结果未知。用户停止与外层取消继续向上传播。
3. 搜索服务没有网页抓取能力时，scrape_web 使用通用公开网页阅读器。跟踪并检查重定向；读取正文和真实页面元数据，去掉导航、脚本等内容。最多 1 MB 页面、16,000 字符正文；私网、带凭据 URL、异常端口和非文本内容拒绝访问。不猜测发布日期。
4. 文字搜索不等待 Bing 图片端点。配图使用独立 search_images/extract_web_images；已有图片的验证最多 5 秒，超时保留网页证据。来源去重、无效地址过滤、文本限制、来源稳定 ID，保留提供方摘要与发布日期。
5. 提示词要求按需搜索、尊重用户不联网要求、拆解复杂查询、优先一手来源、必要时读原文、交叉核验并标注实际链接。网页指令不能当作系统指令。失败时说明限制，避免反复空搜和编造已核实事实。
6. Flutter 显示搜索关键词和真实来源数量，区别搜索完成与搜索无结果/暂不可用。展开搜索步骤展示可打开的来源列表；回答下方直接显示来源入口；来源列表保留摘要和发布日期。

## 验证与边界

后端定向 JVM 回归通过（含入口降级、域名边界、无结果、不循环、正文提取、私网重定向阻断、可恢复错误、内部超时与外层取消区别、去重和稳定 ID）。Flutter 定向用例验证错误状态、真实计数、来源链接入口、来源摘要/日期与去重。构建及最终设备结果见同目录日志。

模拟器实际调用生产搜索和网页阅读工具，Bing 返回 10 条来源，MathWorld 导数原文成功读取。设备测试不修改用户设置或聊天，也不调用付费模型。该测试验证工具链；未以真实模型评测每一条生成结论的引用正确性。

外部免费搜索入口会随网络/地区或反机器人机制变化；DuckDuckGo 在主机探测中返回验证页，因此备用入口不是可用性保证。三个入口都失败时会明确降级。配置的 API 搜索源仍使用其原有 schema 和账户配置，没有自动调用其他付费搜索账户。复杂检索拆解和证据判断是模型提示策略；未实现独立的深度研究调度器。

通用阅读器支持静态 HTML/纯文本，不执行 JavaScript，不解析 PDF。更完整的文档或网页需要对应服务。现有 Flutter 分析有历史 info 级提示，本次所查文件无 error 或 warning。

最终交付：35 项定向 JVM 检查、3 项 Flutter 界面用例、2 项 Android 设备用例通过；Debug APK 与测试 APK 已安装至 emulator-5554。32 个 Flutter 资源逐项与本次 AAR 一致（见 apk-verification.json）。定向 Dart 分析只有 14 条 info 提示，未出现 error/warning。
