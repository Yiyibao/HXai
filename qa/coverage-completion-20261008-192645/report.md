# 本地覆盖补齐与修复报告

本轮继续处理上一报告的可在当前模拟器/本地验证的重点缺口，修复 **2 项新确认生产代码缺陷**、**2 项测试缺陷**，并补齐 Word 单元格、审批非空队列、服务 RUNNING 任务、重复交付和证据分离的验证。详见 [发现及源码位置](findings.md)、[逐项矩阵](verification-matrix.md)、[机器结果](summary.json)。用户选择“先完成当前模拟器与本地验证”，本轮没有真实远端模型/GitHub 请求，没有声明全项目验收通过。

## 修复结果

1. **CF01 P2：审批重试成功后内存/DB 队列暂停状态不一致。** 前两次落盘都失败时，内存安全暂停、数据库 Pending 保持不变是预期降级；但成功重试原来仍保存 DB 旧队列。现在 `ConversationSession.commitToolApproval` 在队列 mutex 内读取当前消息和队列；`ConversationRepository.commitToolApproval` 同事务保存决定和队列，提交成功才发布内存，并在锁内拒绝已失效决定。普通消息保存仍保留自身原有队列合同。修复前设备失败见 [final-android/seed.log](final-android/seed.log) 的暂停断言；修复后同进程重试和独立进程恢复均通过。

2. **CF02 P2：技能卸载/替换清理可能沿目录链接删除外部文件。** `SkillTreeDeletion` 使用不跟随 symlink 的 walkFileTree，技能根目录拒绝同级别名。Android 实际 Os.symlink 负例验证：替换 backup 清理和卸载均只删链接，外部合成哨兵字节保持，同级别名不能卸载另一技能；正常 ZIP、资源超限、二进制字节和默认读取边界仍通过。触发需已有 app 私有技能目录的链接；没有声称普通 ZIP 能创建链接。

3. **CF03 P2：上轮证据目录迁移遗漏样例写入。** 补齐五格式合成样例创建，并用生产 Writer/Reader 逐个确认新文件非空及中文实际内容。该遗漏是上轮测试改造引入的回归。本轮没有发模型请求，不把本地夹具修复当成历史模型失败根因修复。

4. **CF04 P2：文本图片失败可能混入复合方法 PASS。** 图片生成拆成独立方法/UUID；缺图片配置明确跳过，有配置要求真实图片、新 SUCCEEDED PNG、解码及尺寸。当前只运行“强制无配置”的安全失败负例，断言 FAILED、无 Image、未创建材料目录；真实正例 NOT_RUN/缺配置，不计成功。

Word 新增源/目标所有表格、行、单元格顺序和明确值比对；值变更、删除行、重排、空表均拒绝。真实 Word 方法接入该断言但本轮不执行远端。交付测试扩展方法/UUID finalized manifest，SOURCE 记录仅合成材料 UUID 和所属 QA 档案，连续执行不覆盖证据。

## 当前版本与实际执行范围

当前源码见 [source-hashes.json](source-hashes.json)，生产输入另存 [production-hashes.json](production-hashes.json)。开始 APK 哈希、两次失败候选版本、最终三 ABI 和测试包均分别保存。最终主 x86_64 APK SHA-256：`A9B22B310E5ED5F91D5C97AD0988D9E7C4FA4337DFF698EFF58597E10384D8F8`；测试包见 [apk-final.json](apk-final.json)。两类采集都重新计算设备已安装主/测试包哈希，绑定日志哈希、方法/UUID、时间窗、mtime 和文件内容；设备为 emulator-5554 / API34 / x86_64 / 4096 字节页。

|本轮独立批次|实际结果|对应范围|
|---|---|---|
|最终生产 app JVM|328 PASS、0 FAIL/ERROR/SKIP|44 suites；含新增审批提交/失败不发布/失效决定断言；XML 在 jvm-approval-final|
|最终 Android 核心批次|21 PASS、0 FAIL/SKIP|Skills 3、Word 7、诊断 6、剪贴板/URL 1、安装只读和显式启用 2、seed/独立 verify 2；8 次 invocation 分开记录|
|重复交付|2 PASS、0 FAIL/SKIP|同一方法两个 UUID，每次五格式；不是两个不同方法|
|精确交付哈希|10/10|上述两方法运行内的格式组；不额外算 10 项测试；SOURCE/VIEW/SEND/Download 确切 UUID 文件一致|
|服务端|28 PASS|真实 Store/SQLite/main/claim/cleanup；新增 RUNNING 行和目录/字节保留；平台 flock、Docker 和外部 worker 被替换|
|采集器 Python|6 PASS|独立本地合成拒绝/保全测试，使用 QA 下临时目录|
|构建/Lint|PASS，0 错误/42 警告|离线三 ABI debug/测试包；部分构建、分析缓存命中|
|真实教学/材料/图片/GitHub及额外平台|NOT_RUN / 缺配置|按用户本轮本地范围限制；不得沿用历史通过计入本批|

首次 327 项 JVM 是发现新审批问题前的当前版本验证；新增生产修复后重新 cleanTestDebugUnitTest，实际运行最终 328 项。两批不相加。之后只修正 Android 测试断言/交付证据目录，没有修改生产代码或 JVM 测试输入。历史 44 项、18 项关键边界、首次真实 2 项、上一独立修复 327/14/6/探针均保留为历史批次，不与本表合并为一次全量回归。

核心 [Android 批次](acceptance-android/android-results.json) 验证审批 approve/deny/answer：原始错误标记不被第二暂停错误覆盖，内存暂停、DB Pending、完整非空队列不丢；同进程成功重试同步暂停状态，重复/冲突/迟到决定不改已接受结果，另一会话实际挂起 Job 与数据库状态不变。接受状态交给真实 GenerationLoop 的受控单步合成工具，计数为 approve=1、deny=0、answer=0。**该计数是 service 决定与 loop 执行的分段验证，不是带真实模型及工厂工具的完整教学流程。** seed/verify PID 为 19853/19945；新的会话、档案及文件 UUID 以本轮 fixture 清单为准。

两轮 [交付运行](delivery-runs/results.json) 各约 42 秒，实际外部 UID 查看/分享和 DocumentsUI 另存。仅从已验证 manifest 读取登记的确切 `QA交付-<uuid>.<ext>` Download 文件，不枚举个人目录。结果见 [exact-delivery-hashes.json](exact-delivery-hashes.json)；采集 [首次](collector-delivery.log) 和 [重复](collector-delivery-repeat.log) 均通过，32 份产物按两个 UUID 保存。两轮说明本样本等待成立，不构成长时稳定性或历史窗口根因证明。

## 原始失败及复跑说明

- `server.log/server.xml`：沙箱临时目录建立失败，1 个直接测试通过、27 个环境 setup 错误，不是 27 个产品断言失败。指定 QA basetemp 后本地 Store 测试可运行，但 FastAPI TestClient 在沙箱内停滞；`server-retry.log` 保留，任务中断。主机受控本地复跑 `server-host.log/server-host.xml` 为 28 通过，弃用警告未隐藏。
- `seed.log`：新故障注入器误用已不存消息的 `nodes` 列区分写入，导致错误标签断言失败。根据当前 `queue_state` 改正后 `final-android/seed.log` 才实际发现生产 CF01；该失败及 `apk-queue-failure-candidate.json` 保留。
- `verified-android/seed.log`：CF01 暂停断言已通过，但新增整对象比较误把内存纳秒时间和 DB 毫秒时间差异当成影响另一任务。改为比较同一落库基线后，最终 `acceptance-android` 的 seed 和独立 verify 通过。生产源码未为这个测试精度差异改动。
- `exact-delivery.log`：本轮 QA 核对脚本默认 GBK 解码 UTF-8 收据失败。原日志和脚本版本保留，显式 UTF-8 后只读取原来同一批证据/目标，`exact-delivery-utf8-retry.log` 为 10/10；没有重发工具或模型请求。
- Gradle 每次仅在缺配置时创建仓库合成 google-services 示例，finally 删除本次确切文件，未覆盖真实配置。build/build-final/build-test-retry/build-approval-final/build-delivery-final/build-timestamp-test-fix 全部原日志保留。

## 剩余条件与清理

没有把历史真实模型失败的未知原因改成“已修复”。真实配置、真实 GitHub、远端教学/材料/图片、Docker worker/挂载、HarmonyOS/其他 API/16 KB/双真机/正式签名发布仍未验。当前可运行范围内也没有穷举所有编辑 UI 入口、跨会话附件与 I/O 清理故障、DocumentsUI 取消/途中切档/目标重读失败、迁移与备份整条流程、复杂材料和搜索/语音/翻译提供商路径；这些是覆盖缺口，不是已确认未修复生产缺陷。全部分类详见矩阵及前轮完整矩阵。

全局 Skills 工厂没有新增学习档案拼接路径；未导出真实已安装包作内容审计，不能保证所有用户安装内容无个人数据。制作/编辑/自动草稿继续关闭，安装/查看/显式启用/卸载合同保留。

每次失败 seed 都按本次 manifest 的确切创建 ID、所属合成档案和 QA 标题运行 cleanup；最终 verify 清理相同范围。Skills 外部哨兵、别名及 fixture 文件是新建的确切 UUID，先 unlink 再清理；诊断每方法验证原记录/导出缓存哈希保持。合成交付副本和已登记材料留作证据。没有删除未知档案、个人聊天或凭据；没有自动重发被过滤请求、重放不确定工具、提交、推送或部署。用户原有修改保留。
