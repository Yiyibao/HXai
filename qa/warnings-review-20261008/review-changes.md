# 本轮修改与检查范围

工作对象为已有未提交修改的工作区，初始状态保存在 [initial-status.txt](initial-status.txt)。没有重置、提交、推送、发布或部署。

| 类别 | 修改及原因 | 验证依据 |
|---|---|---|
| 通知兼容 | API 33 以下查询通知开关；API 33 起查询 POST_NOTIFICATIONS，避免旧系统被新权限判断误拦截 | 编译与 Lint；本轮模拟器为 API 34，旧设备分支仅静态核查，未冒充真机实测 |
| 诊断生命周期 | 静态持有目录 File，移除静态 Context 引用；保留事件队列、原子写入及脱敏合同 | DiagnosticsTest、DiagnosticsPageTest；进程恢复回归 |
| 图片保存 | 关闭输入/输出流后完成保存；检查插入、编码、发布结果；失败删除 pending MediaStore 行；旧系统用 MediaScannerConnection | gallery-first.log、gallery-final.log：两种保存方式字节与同一原件一致，缺失源文件不留空行；API 28 以下未实测 |
| 状态及资源 | 原始类型 Compose 状态、删除空 super 调用、KTX 创建图片及受控 clip、lambda offset、去除确实无引用的资源 | 单元测试、构建、Lint 与实际导航/聊天/材料操作；unused-resource-audit.json |
| 数量与文案 | 15 处数量文本改复数资源及真实 quantity；处理俄语/阿拉伯语类别与中日韩 other；展示省略号/区间排版修正，保留模型名/资源标识 | 首次 MissingQuantity 失败保留，修复后 Lint；完整页面回归 |
| 图标 | 正确的 drawable-nodpi 分类、minSdk 26 适应图标、逐密度相同 bitmap 用资源别名保留原名称 | icon-alias-audit.json；当前 APK 实际安装启动，未声称全部密度/厂商主题真机验证 |
| Kotlin 编译器 | 依据编译器证明删除冗余空安全操作；显式迁移参数/集合类型；Locale/Tooltip/窗口尺寸/边框/Multipart API 局部迁移 | 强制完整编译 final-compiler-full.log；不删除旧消息序列化兼容分支 |
| 滑块/剪贴板 | 状态 API 保留外部受控值、steps/range/完成回调与原绘制；两处复制采用 LocalClipboard 的挂起 API | slider-final.log 覆盖外部值更新、范围变化、拖动、完成回调、禁用；复制页面的编译通过，未声称上游全部设置页面端到端验收 |
| OAuth | 回调关闭服务前留出响应排空时间，防浏览器响应被立即截断 | before-build.log/first-jvm/oauth 保留原失败；本轮两项 OAuth 单元测试通过 |
| Gradle DSL | 更新 assets.directories 写法；移除已不存在的 ExperimentalNavigation3Api opt-in 标记 | 全量构建及最终构建；没有关闭有效检查 |
| Web 编译 | 仅移除 8 个诊断文件中 SPA 不使用的 use client 指令；没有改变检查阈值、升级依赖或删组件 | web-directive-fixes.json、web-build-after.log、web-typecheck-after.log；9 条 source-map 定位诊断消除，4 个大 chunk 保留 |
| 档案安全边界 | 共享 workspace 尚无学习档案隔离，禁用仍被外部发现的 DocumentsProvider；保留原代码及用户数据 | workspace-boundary-first-failure.log 与 workspace-boundary-final.log；此能力此前已明确未开放，不是删除已支持功能 |

公共工具函数的复制文本/完整 URL 日志问题在完整复审中发现。修复及专项结果另见最终 [复审报告](report.md)，修复前失败保留。

没有增加 baseline、批量 suppression 或严重级别调整，没有为警告数量升级整套依赖。测试和交付采用命名明确的合成 QA 档案及文件；凭据继续留在设备加密配置中，不导出个人数据库/连接设置。
