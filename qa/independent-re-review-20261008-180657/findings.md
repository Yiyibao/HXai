# 修复后独立复审发现（2026-10-08）

本轮没有修改生产源码。优先级仅用于以下可执行问题；覆盖缺口单列，不冒充生产失败。

## IR01 — P2：Skills ZIP 安装可被小体积压缩包耗尽内存（确定代码缺陷）

- **位置**：`client/app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsVM.kt:59`、`:155–170`；GitHub 下载也在 `:309` 后无界读取。
- **触发**：从现有安装入口选择一个含高压缩率附属文件的 ZIP。压缩输入、单条解压大小、累计大小、条目数量均没有上限；`zipInput.readBytes()` 将每个部件全部展开并保留在 Map。
- **影响**：内存耗尽；`OutOfMemoryError` 不属于入口所捕获的 `Exception`，不会可靠地变成“安装失败”回调。没有声称真实用户设备已经发生崩溃。
- **本轮证据**：独立 64 MB JVM 测试堆内，反射调用**当前生产** `importSkillsFromZip`；130,787 字节 ZIP 包含 134,217,728 字节合成条目，产生 `java.lang.OutOfMemoryError`。[失败 XML](skill-import-probe-xml/TEST-independent.review.IndependentReviewProbeTest.xml)、[探针](probes/IndependentReviewProbeTest.kt)、[实际日志](skill-import-probe-source-retry.log)。测试绕过 ViewModel 构造以避免 Android 初始化，不安装文件，不操作档案，也没有网络请求；限制是没有 Android UI/整机崩溃实测。
- **修复建议**：对输入和下载流、每条解压字节、累计解压字节、条目数及递归深度设定上限；使用有界流及临时文件，不同时保留所有大部件；超限时在提交安装前补偿并给出可读错误。仅捕获 OOM 不解决根因。

## IR02 — P2：已有 manifest 能绕过陈旧产物拒绝（确定测试证据缺陷）

- **位置**：`qa/review-fixes-20261008/collect.py:25–31`。
- **触发**：采集目录已存在 `fixture-manifest.json`，其中同名条目及本地 SHA-256 匹配。此时直接 `continue`，不检查本次方法、运行 UUID、设备 mtime 或本次 APK。
- **影响**：昨天的证据可被继续接受为当前采集内容。新的 StudyRealModelTest 方法/UUID 文件命名解决了两方法相互覆盖，但不保证整个采集流程拒绝旧证据。
- **本轮证据**：[collector-probe.json](collector-probe.json)。使用原采集器的同一哈希版本、合成文件及模拟 ADB：已有旧 manifest 时 **FAIL**（接受旧产物、ADB 调用为 0）；无 manifest 时 **PASS**（拒绝同样旧产物）。没有因此推定上一轮实际通过证据是伪造或陈旧。
- **其他残余**：`ProjectReviewTeachingTest.kt:34` 的 `review-real-teaching.md` 和 `MaterialRealModelTest.kt:115` 的 `material-real-model.md` 仍用固定文件名；不同运行会覆盖设备缓存。采集器使用全局起点及 90 秒容差，未绑定每个方法自己的窗口；`fidelity.docx` 是明确复用的合成输入，须与新输出区分。
- **修复建议**：按方法/运行 UUID 建立不可变证据目录，manifest 绑定本次 APK、方法、UUID、开始/结束时间；再次采集也应重新核验这些绑定，旧条目不得直接通过。本轮正常交付另用 [独立新采集器](collect-fresh-delivery.py)，没有复用该漏洞分支。

## IR03 — P3：诊断测试隔离仍清空整个既有目录（确定测试隔离缺陷；未实际清空）

- **位置**：`client/app/src/androidTest/java/me/rerere/rikkahub/service/DiagnosticsTest.kt:58–66`。
- **触发**：在有既有诊断记录的应用上运行该类。`@Before` 将原文件保存在内存，然后删除目录全部直接子文件；`@After` 再整体删除并恢复。`Diagnostics.export` 还会处理既有导出缓存。
- **影响**：超出本次“仅清理确切 UUID、所属档案、QA 标题及已记录文件”的范围；若进程中断，内存中的原记录不能恢复，可能丢失首次失败证据。这是测试隔离问题，不是本轮已经发生的数据丢失。
- **证据/判定**：源码直接确认。本轮该类 **NOT_RUN**，没有为了证明风险去删除既有记录。
- **修复建议**：将 Diagnostics 的存储与导出目录注入到本次 UUID 隔离目录，或在专用无个人数据的应用安装上运行；测试只删除自己创建并登记的文件。

## 历史观察项与覆盖缺口

- **RR01/RR06**：历史真实教学、材料失败仍保留；上一修复轮相同配置路径通过，历史根因未知。本轮没有重发远端真实合同，不能写成已证明根因修复。
- **RR03/O01**：本轮分享页及两套交付方法通过；当前等待应用过渡窗口退出的测试修正有源码依据，但没有重建历史失败窗口时序，也没有长期稳定性证明。
- **审批精确一次**：受控案例始终保留第二个 Pending blocker，因此不进入完整成功工具执行路径。没有直接计数成功工具调用，也没有阻塞另一活动任务来断言其 Job 不变。
- **审批队列恢复**：三种决定的通知、Pending、同进程重试、独立进程重试已有证据；故障场景没有向该审批会话放入实际队列消息，不能外推非空队列双重落盘失败的完整恢复合同。原始异常对象/独特错误文本也未由专项断言直接比对。
- **Skills 路径**：安装写入和可选 `path` 的 canonical 边界可见；默认 `SKILL.md` 读取及列表读取没有同等逐文件 canonical 校验（`SkillsTools.kt:72–74`、`SkillManager.kt:66–88`）。未证明公开安装入口能构造越界链接，列为符号链接负例及内容审计缺口，不声称已泄露学习档案。
- **Word 表格**：当前断言只要求存在 `w:tbl`，空表即可满足（`WordFormulaAssertions.kt:80` 附近及 WordFormulaAssertionTest 的空表正例）；没有比较源/目标单元格内容，不能据此验收表格内容保真。
- **全局指令包**：本轮静态检查工厂仅取显式启用技能元数据及技能目录文件，未见自动拼接学习档案的代码；未导出真实已安装包的内容，不能保证所有既有包都不含用户手工置入的数据。
