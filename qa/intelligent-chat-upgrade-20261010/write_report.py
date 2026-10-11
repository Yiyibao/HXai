from pathlib import Path
import json,re,hashlib
r=Path.cwd(); q=r/'qa/intelligent-chat-upgrade-20261010'
assert 'OK (6 tests)' in (q/'closure-network-recovery.log').read_text(encoding='utf-8-sig')
assert 'me.rerere.rikkahub.service.FlutterChatBridgeTest:............' in (q/'final-android-regression.log').read_text(encoding='utf-8-sig')
assert 'OK (1 test)' in (q/'closure-real-model-context.log').read_text(encoding='utf-8-sig')
assert '+218: All tests passed!' in (q/'closure-final-flutter-tests.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in (q/'verified-native-build.log').read_text(encoding='utf-8-sig')
stats=json.loads((q/'native-test-counts.json').read_text(encoding='utf-8'))
assert sum(n['tests'] for n in stats.values())==737
assert sum(n['failures']+n['errors'] for n in stats.values())==0
assert sum(n['skipped'] for n in stats.values())==1
arm=json.loads((q/'arm64-apk-verification.json').read_text(encoding='utf-8'))
x86=json.loads((q/'x86_64-apk-verification.json').read_text(encoding='utf-8'))
assert not arm['mismatches'] and not x86['mismatches']
apk=q/'MathStudyAgent-chat-upgrade-arm64-debug.apk'
assert hashlib.sha256(apk.read_bytes()).hexdigest()==arm['apk_sha256']
report='''# 智能 Chat 四阶段完成与自审报告

2026-10-10。结论：按第一至第四阶段依次实现，发现的问题已修复，本轮功能回归通过。交付为 Debug APK；模拟器运行验证不等同于 Mate 50 真机性能验收。

## 四阶段交付

| 顺序 | 已完成内容 | 主要验证 |
| --- | --- | --- |
| 1. 流畅稳定阅读 | 消息级累计补丁、40ms 显示合并、独立行订阅；稳定 Markdown/代码块、复制和高亮；720 阅读宽度、180ms 入场、触觉反馈；沿用智能滚动 | 高频事件/缓存/重复与缺口、旧行身份不变、代码块保留、32 类 Markdown、滚动/字体/明暗主题 |
| 2. 可信任务过程 | 首段等待和真实处理状态；可读工具结果/轻过渡；失败内容保留、继续/新分支重生成；不重放未知工具；真实来源链接 | 6 项中断/审批/恢复场景、折叠推理、来源失败、失败按钮/忙碌防重入 |
| 3. 可迭代成果 | 原成果引用/版本验证的 revise_interactive_surface；新版保留原版；选中文字追问、少量相关建议；真实文件版本；手机全屏和宽屏并排预览保存恢复 | 版本冲突/来源隔离、原版保留、参数续聊防重复、返回保存、宽屏关闭/分支切换 |
| 4. 上下文和 Agent | 档案独立预算；保留完整轮次的自动摘要与精确缓存；失败保留原文；自然表达与真实交付规则；不替换自定义提示词或模型 | 长历史摘要、重复缓存/编辑失效、失败不发最终请求、原聊天保留；已配置真实模型保留三个关键条件 |

详细操作及技术边界见 [实现与使用说明](../../docs/intelligent-chat-upgrade-implementation-20261010.md)，原设计见 [升级方案](../../docs/intelligent-chat-upgrade-plan-20261010.md)。

## 自审发现与修复

| 发现 | 修复与复验 |
| --- | --- |
| 快照加载中收到的流式片段可能被清空 | 留存队列，初始数据就绪后应用；桥接真实异步测试通过 |
| 快照已经包含某个片段时，延迟补丁可能让文字回退 | 仅接受同一前缀的累计增长；旧片段不替换较新正文；重复/异常分支分别忽略或重取 |
| A→B→A 的延迟请求、旧订阅和关闭回调可能覆盖新聊天 | 前端 lifetime、原生 watchVersion 共同保护；关闭也使旧订阅失效；档案/会话回归通过 |
| 全局事件缺口出现在其他类型通知上时可能未修复当前聊天 | 先处理全局 sequence 缺口，再按会话过滤；补测不同会话/材料通知触发快照恢复 |
| Markdown 缓存可能沿用生成期降级样式或旧选择模式 | 完成状态、选择模式和主题变化清理相应缓存；完整渲染与代码稳定性测试通过 |
| 恢复按钮可能取最后分支而非选中分支 | 使用 selectIndex 对应的最后消息；原生再次核对消息 ID 和工具状态 |
| FAILED/error 工具输出可能显示成功图标，失败原因可能晚于状态缓存 | 标签、颜色和图标统一按结果失败展示；完整会话状态通知也刷新处理/错误状态 |
| 摘要超时可能被误归类为主动取消 | 内部摘要超时转为明确失败；保留历史与旧缓存，不静默裁掉当前任务 |
| 并排预览关闭/缩窄/切换分支可能丢参数或受到旧回调影响 | 关闭前保存、窄屏保留隐藏预览、身份/异步版本保护；宽屏保存恢复测试通过 |

初次编译/测试失败均保留。修复了 Riverpod listenSelf 的调用位置和一处非法增量正则。网络验收时发现模拟器 Wi-Fi 未连接和临时代理影响 localhost；冷启动、恢复 AndroidWifi 并清除临时代理后重测。UI 自动化改为仅点击可见区域，并可双向滚动寻找按钮；不是删除断言。额外网络回归暴露旧夹具未标记 newConversation，已按当前持久化合同修正后全部重测通过。

## 最终验证

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Flutter 全量 | 218 通过、0 失败 | [日志](closure-final-flutter-tests.log) |
| Debug JVM 全模块 | 737 项：736 通过、1 环境跳过、0 失败 | [构建/测试](verified-native-build.log)、[模块计数](native-test-counts.json) |
| Flutter 静态分析 | 0 error、0 warning，150 info（主要为代码风格提示） | [日志](closure-analyze.log) |
| Android Lint | 0 error、56 既有警告 | [成功记录](verified-native-build.log) |
| Flutter 原生桥接/页面 | 12 通过 | [首次完成运行](closure-runtime-bridge.log)；最终 APK 上的合并运行中 12 项亦通过，见下行说明 |
| 六项故障恢复 | 6 通过 | [修复夹具后复验](closure-network-recovery.log) |
| 已配置真实模型 | 1 通过，43.314s；自动摘要后保留预算、禁止覆盖及 file-123 | [日志](closure-real-model-context.log)、[结果](chat-upgrade-live-context.json) |
| x86_64 / ARM64 资产 | 每个 APK 的 32 个 Flutter 资产与当前 AAR 全部匹配 | [x86_64](x86_64-apk-verification.json)、[ARM64](arm64-apk-verification.json) |

Android 共 19 个不同用例通过（12 桥接/页面 + 6 故障恢复 + 1 真实模型）。[最终 APK 的 18 项合并日志](final-android-regression.log)保留了夹具修复前的 8 个失败条目：其中 12 个桥接/页面用例全部通过，4 个旧网络夹具各出现执行/清理失败；6 项故障恢复随后整体复验通过，不将失败记录改写为成功。

JVM 的唯一跳过是 RootfsInstaller 的符号链接集成测试：此 Windows JVM 缺少创建符号链接的特权。测试先实际探测权限，仅对该限制做 JUnit assumption；其他 IO 错误继续失败。Android/Linux 的生产解压代码未改动。其他 workspace 用例通过。

真实模型验收使用合成历史和当前配置的模型 ID `gpt-6-astra`，不证明任意长度/任意任务的摘要都不会遗漏信息。预算是保守估算，工具协议、图片、输出与模型容量需自行预留；本轮未测 Mate 50 帧率、长时间热稳定性或 Release 签名包。没有将设计中的 16.7ms 等性能目标写成实测结论。

## 交付与可视证据

- [ARM64 Debug APK](MathStudyAgent-chat-upgrade-arm64-debug.apk)
- [手机全屏交互界面](smart-ui-expanded.png)
- [保存并返回聊天](smart-ui-inline.png)
- [推理默认折叠](reasoning-collapsed.png)、[展开推理](reasoning-expanded.png)

ARM64 SHA-256：`ARM_HASH`。

模拟器：mathstudy-s0 / emulator-5554；已切换为可见窗口。安装包验证后打开 StudyFlutterActivity；原模型设置和用户聊天由测试清理/恢复保留。
'''.replace('ARM_HASH',arm['apk_sha256'])
(q/'report.md').write_text(report,encoding='utf-8')
p=r/'README.md'; s=p.read_text(encoding='utf-8')
s='''2026-10-10 智能 Chat 四阶段升级已按顺序完成并通过自审修复：消息级流式与稳定 Markdown、真实任务状态和安全恢复、成果版本迭代/宽屏预览、自动上下文预算与摘要。218 项 Flutter、736 项 JVM 和 19 项 Android/真实模型验证通过；1 项 Windows 符号链接权限用例跳过，Lint 0 错误/56 既有警告。模拟器已安装验收版并打开。见 [自审报告与 ARM64 APK](qa/intelligent-chat-upgrade-20261010/report.md) 和 [实现/使用说明](docs/intelligent-chat-upgrade-implementation-20261010.md)。Mate 50 性能及 Release 包未验收。

'''+s
p.write_text(s,encoding='utf-8')
print('Report, checks, APK hash and README updated.')
