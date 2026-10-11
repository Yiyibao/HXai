# 图片检索、网页提图与聊天嵌入

完成链路：`search_images` / `extract_web_images` → GET 下载与 Android 位图解码验证 → 返回图片直链和来源 → `display_image` / Markdown → Flutter 聊天正文。

- 新增独立 Bing 图片检索工具；不依赖普通网页搜索成功。
- 网页提图支持 OG/Twitter 图片、img、懒加载地址和 srcset（含 picture source）；相对地址按最终重定向页面解析，保留最终页面 URL、标题及 alt。未知作者、日期不猜测。
- 搜索和提图返回的 images 只包含验证通过的图片，imageStatus 为 verified；没有通过验证的图片时为 unavailable。附 width、height、contentType、verifiedAt。部分失败不丢弃文字搜索证据。
- 验证使用 GET 下载和实际解码，不以 HEAD、后缀或 MIME 单独判定成功。最多检查 10 张、并发 3、单图片最多 8 MiB、单请求超时 8 秒。403、网页内容、损坏图片和超大文件被过滤；取消时取消 HTTP 请求。
- display_image 在嵌入前再次验证，输出图片、caption、来源链接和验证元数据。Flutter 桥接把来源文字与图片一起展开到正文；持久化后仍可读取。
- 工具工厂和生成提示已接线。用户可要求“搜索图片并展示”或“从这个网页提取图片并展示”。

验证：

- 14 项相关 JVM 测试通过：图片检索端点/来源解析、重定向、GET/解码/错误过滤、网页提图、增强搜索契约、提图到图片与来源输出。
- Android APK 和 instrumentation APK 构建成功，安装至 emulator-5554。
- Android instrumentation 1 项通过（36.516 秒），涵盖 5 个实际聊天场景：Markdown、工具配图、Base64、本地图片、交互界面图片。工具场景从本地 HTTP 网页提图开始，使用生产 Android 解码器，核对 240×120 尺寸、持久化桥接来源文本，再用实际 Flutter 截图像素验证图片显示。
- 已人工查看 [聊天截图](chat-image-source.png)，图片、说明和蓝色来源链接在正文中可见。
- 记录：[构建](build.log)、[图片检索测试](search-tests.log)、[模拟器运行](runtime.log)。

测试使用可控 HTTP 页面和图片，不调用付费模型；Bing 图片搜索测试用拦截器将真实请求路径转到本地夹具。本次未追加实网 Bing 可用性或图片内容相关性验收。外部图片之后仍可能失效；需要 JavaScript 才出现的图片不在静态提图范围。
