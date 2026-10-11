# 独立复审问题修复报告

本轮独立复审发现的三项问题已修复：IR01（P2，Skills 资源上限）、IR02（P2，陈旧测试证据采集）、IR03（P3，诊断测试隔离）。另补齐 Skills 默认 SKILL.md 读取的符号链接边界。见 [逐项矩阵](verification-matrix.md) 和 [机器结果](summary.json)。这是本轮发现项的关闭记录，不是全项目验收结论。

## 改动与证据

**IR01：确定生产代码缺陷。** 压缩约 128 KB、展开 128 MiB 的技能 ZIP 原先导致 64 MB JVM OOM。新增 `SkillImportLimits.kt`，文件、ZIP 和 GitHub 流均有读取上限；输入/总展开内容 8 MiB、单文件 4 MiB、SKILL.md 256 KiB、API JSON 1 MiB、128 个条目、8 层路径。超限、重复路径和越界路径在安装提交前拒绝，原技能保留。安装目录和默认/指定文件读取均经过 canonical 路径边界。8 MiB 范围内内容仍在内存中解析，没有引入无限临时文件存储。

源码：`SkillsVM.kt`、`SkillManager.kt`、`SkillsTools.kt`、`SkillImportLimits.kt`。原探针不改断言，使用当前生产解析器和 64 MB 堆复跑，返回受控 `LimitException`，不再 OOM：[日志](original-64mb-probe.log)。Android 公共导入入口实测超限拒绝、原技能保持、正常 ZIP 的 321 字节二进制逐字节保留；真实 Android 符号链接负例拒绝越界默认读取：[设备日志](skills-bounds.log)。五项 JVM 边界测试覆盖合计膨胀、条目/路径、元数据以及读取 limit+1 后停止。

**IR02：确定测试/采集缺陷。** 新增 `QaEvidenceRun.kt`，真实教学、学习和材料测试按方法和运行 UUID 生成独立目录，结束后记录状态、文件 SHA-256、大小和时间。复用合成材料输入也复制到本次目录并显式标记。`scripts/collect_run_evidence.py` 验证当前已安装 APK 对、运行上下文、日志哈希、唯一方法/UUID、时间窗、设备 mtime/内容及本地缓存。重复采集也重新读取设备，不信任旧 manifest。旧 `collect.py` 转为显式上下文参数的兼容入口；原代码保存在 [collector-before.py](collector-before.py)。

六项 Python 拒绝/保全测试通过：[日志](collector-tests-final.log)。新测试包的两个无模型设备方法产生不同 UUID 证据，首次及重复采集成功；合成失败产物的 `FAILED` 状态被原样保留，不改成 PASS：[manifest](collected/fixture-manifest.json)、[首次](collector-device-first.log)、[重复](collector-device-repeat.log)。这两个 JUnit 方法通过仅证明采集合同，不代表真实模型成功。本轮没有重放真实远端请求。

**IR03：确定测试隔离缺陷。** `DiagnosticsTest.kt` 改用每方法独立 UUID 的 ContextWrapper、目录及偏好名；flush 后临时切换诊断目标，结束恢复。逐测试比较原诊断记录及原导出缓存的 SHA-256，断言已有文件未改变；只清理本次注册的确切 UUID 文件和偏好，不清空生产目录，也不导出原正文。六项诊断设备测试通过，包括脱敏和容量场景：[日志](diagnostics.log)。该断言限于测试前后，不宣称整个 APK 安装/进程生命周期原目录零新增。

## 当前版本与分批验证

最终 APK 与探针后哈希一致：[构建产物](final-apk-hashes.json)、[复核](post-probe-apk-hashes.json)。设备采集再次核对已安装主/测试包，主 x86_64 SHA-256 为 `8199A461E35A1B667B42EC98E617B6C423880B969F48A67A3B78DB0E0957BB96`，测试包为 `4F0E193E7C457290FD6AD9B09E6A3C91BE5A7CFB602B0A22B715D1EEDF3836A3`。设备 emulator-5554、API34、x86_64、4096 字节页；源码最终哈希见 [final-inputs.json](final-inputs.json)。

|批次|实际结果|范围|
|---|---|---|
|app JVM|327 PASS，0 FAIL/ERROR/SKIP|44 个 suite；含新增 5 项资源边界；jvm-final XML，首次 XML 另存|
|Android|14 PASS，0 FAIL/SKIP|诊断 6、Skills 资源/路径 2、既有安装与显式启用 2、证据 2、跨进程 seed/verify 2；8 次 invocation UUID 各自记录|
|原 64 MB 资源探针|1 PASS|原始输入 130787 字节、展开 134217728 字节；只算本次探针|
|采集器 Python|6 PASS|本地合成 ADB 负例；两次测试日志保留，不加成 12|
|构建与 Lint|PASS，0 错误/42 警告|三 ABI debug APK 和 androidTest；部分任务缓存命中|
|真实教学/材料、图片生成|NOT_RUN / 缺配置|真实测试改动已编译；本轮未发模型请求；图片缺模型配置不是成功|
|服务端、Web、正常分享交付|NOT_RUN|本次未修改这些生产范围；前轮结果仅作历史证据|

`android-results.json` 保留每次方法、UUID、起止时间和退出码；`run-context.json` 将采集绑定到最终 APK 对及两个独立方法。seed/verify 为确切合成档案和 QA 标题的受控边界，不是全体真实档案恢复。历史 44 项回归、18 项候选关键边界和首次修复包 2 项真实合同仍各自独立，不能与本表相加冒充一次全量执行。

## 首次失败及复跑

`build-first.log` 成功后继续集成设备边界测试。`build-final.log` 保留新增测试对 `SkillMetadata` 构造参数使用错误导致的 Android 测试编译失败；改为具名参数后 `build-test-fix.log` 成功。这是本次测试代码集成错误，未删除原日志。最后调整仅涉及测试隔离哈希和测试构造调用，生产源码与 327 项 JVM 实际执行版本一致。原独立复审 OOM 及环境失败日志保留在前轮独立 QA 目录，没有改写为通过。Gradle 采用仓库合成 google-services 示例临时配置，finally 删除本次创建的确切配置文件，未覆盖真实配置。

## 未验证条件

审批成功准确执行次数、另一活动任务隔离、非空队列双保存失败后的独立恢复、真实 GitHub 下载/真实安装内容、Word 单元格保真及完整 B/C/D 子场景仍有覆盖缺口，详见 [前轮矩阵](../independent-re-review-20261008-180657/acceptance-matrix.md)。新增路径和资源负例、诊断设备复验只关闭相应范围。全局指令包未发现自动拼入学习档案的代码路径，但没有导出真实安装包作内容审计。

真实教学/材料历史失败根因仍未知，历史后来通过不能声明根因已修复；图片生成缺配置。分享窗口没有长期稳定性新证据。保留用户既有修改，无提交、推送、部署；没有导出个人聊天/凭据、自动重发被过滤请求或重放不确定工具。清理限于本轮确切 UUID、所属合成档案、QA 标题和注册文件。证据和首次失败保留。
