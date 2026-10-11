# 逐项验收矩阵

状态定义：PASS=本轮直接运行的对应断言或明确注明的源码检查满足；FAIL=本轮负例未满足合同；SKIP=已启动测试主动跳过；NOT_RUN=本轮未运行或无直接断言；MISSING_CONFIGURATION=缺少所需配置，不算成功。以下同一行中的部分 PASS 不关闭整条清单。

|项|本轮判定与直接证据|剩余条件/未验证子场景|
|---|---|---|
|A01 标题/建议|PASS：标题/建议 × 流式正文/编辑/分支六组受控门控；进程重开保存结果。seed.log、verify.log|实际模型回调与每个编辑 UI 入口 NOT_RUN；本轮注入的是 production session 层方法|
|A02 删除终态|PASS：历史/聊天两删除入口、取消无效的迟到结果、搜索与来源无复活、另一会话保留；重开删除仍不存在|更广泛并发删除/恢复 UI 时序 NOT_RUN|
|A03 审批恢复|PASS：approve/deny/answer 双落盘失败时目标通知、DB/内存 Pending；同进程和独立进程重试、冲突决定与另一 Pending 保留|原始错误对象/独特文本核对、非空队列双失败恢复、成功工具执行次数、另一活动任务 Job 不变 NOT_RUN；不关闭整项|
|A04 附件|PASS：写库失败后正文及 SHA-256 保持；另一分支/队列引用保留，最后引用清理；私有库保留；重开比对|跨会话引用专项未重新运行；一般 I/O 清理故障重试合同 NOT_RUN|
|A05 服务清理|PASS：27项服务合同；metadata/request、insert、commit 补偿；inflight enqueue 与 cleanup 锁；真实 main 启动及第二轮清理、宽限期、QUEUED 行/文件保留|周期入口中的 RUNNING worker 活动任务负例及真实 Docker/挂载/部署环境 NOT_RUN；测试替换了平台锁/Docker/循环终止点|
|A06 Word|PASS：4个方法，明确分式/幂值、等价外括号、错误值/嵌套/纯文本负例、原图字节一致及图片缺失/变更拒绝；使用 MaterialWordContent|本轮真实材料整条 NOT_RUN；表格单元格内容逐项保真 NOT_RUN，空表存在不等于保留内容|
|B01 档案/预览|PASS：MaterialPageTest 切 A→B，旧成果正文消失|读取未结束切换、缺失/损坏、DOCX 全部 UI 子场景 NOT_RUN|
|B02 材料可信度|NOT_RUN|片段核对/人工修正/稍后/重开/错误来源哈希/筛选未重新跑；JVM通过不替代这些场景|
|B03 文件交付|PASS：两方法各5格式 SOURCE/VIEW/SEND/精确另存目标 10/10；exact-delivery-hashes.json|损坏/取消/途中切档/目标重读失败 NOT_RUN，不能用正常10组关闭这些负例|
|B04 隐私与入口|PASS（源码/Skills局部）：工厂不开放 shared workspace/MCP；未启用技能不进入工具列表|剪贴板/URL/共享DocumentsProvider隐私负例 NOT_RUN；Skills ZIP 安装资源边界 FAIL（IR01）|
|B05 工程行为|NOT_RUN|图库/API34失败清理、滑块UI、OAuth实际流程未重跑；JVM仅覆盖自身断言|
|B06 拒绝/审批|PASS：A03的本地保存失败边界；生产异常进入固定诊断结构可见|包装HTTP400/401/429、真实正常数学澄清→工具审批→完成 NOT_RUN|
|B07 workspace|NOT_RUN|Windows4项、Linux20项真实symlink未重跑；不声称学习聊天开放Shell|
|C01 门禁|PASS：新执行 app JVM322项；Debug三ABI/测试包目标与lintDebug成功（构建/lint分析有缓存）；0错误/42警告；Web typecheck和react-router build成功；server27项|首次缺google-services环境失败保留；复跑使用合成占位配置，非正式配置验收；非app JVM全模块、正式 Web copy.ts 复制步骤及强制全量编译 NOT_RUN|
|C02 聊天/恢复|PASS：受控独立seed→force-stop→verify（18条存在、2条删除），新PID；app JVM相关断言|四模式真实请求、网络/EOF/停止/队列编辑的Android整套回归 NOT_RUN|
|C03 记忆/教学|PASS（局部）：删除来源抑制和无复活断言；app JVM相关方法|30条真实正反来源、新会话四轮教学、独立记忆恢复Android整条 NOT_RUN|
|C04 数据/安全|PASS（局部）：app JVM加密/备份/凭据等直接单元断言|实际迁移/备份取消与回滚/诊断ZIP与清除的完整Android流程 NOT_RUN；DiagnosticsTest因IR03未执行|
|C05 页面/材料|PASS（局部）：成果页面切档及正常交付，生成DOCX交付文件可用|四主入口/放大字体/模型持久化/导入上限/损坏/越权/分页/全部基础格式保真/伙伴去重 NOT_RUN；SkillsZIP上限 FAIL|
|O01 分享稳定性|PASS：本轮分享页及两套交付三方法一次，各有当前执行记录|历史窗口根因未重建；没有足够多次或长时稳定性样本，不能称历史原因已修复|
|O02 报告口径|PASS：本报告分批，历史44/18/首次真实2不加入本轮数量|旧README/验收矩阵没有改写；本轮新结论以此独立目录为准；旧采集器陈旧拒绝 FAIL（IR02）|
|D01 图片生成|MISSING_CONFIGURATION；本轮探针 NOT_RUN，上一轮缺配置事实保留|需要已授权真实图片模型、解码/尺寸/产物验证；未生成图片|
|D02 增强解析|NOT_RUN|专用合成身份、真实worker、远端整条合同缺直接证据；health不算成功|
|D03 平台|NOT_RUN|Mate50/HarmonyOS、旧系统、API37、16KB设备|
|D04 交付可靠性|NOT_RUN|正式签名Release、双真机迁移、明确时长后台和断电/低电量恢复|
|D05 复杂材料|NOT_RUN|先确认支持合同，再跑复杂公式/图表/中文手写/模糊图正反例|
|D06 扩展/迁移/视觉|NOT_RUN|实际提供商搜索/语音/翻译/JS、迁移版本、专用视觉夹具|
|D07 Skills当前合同|PASS：已有本地文件安装、查看只读VM、制作save拒绝、显式启用工具、未启用不开放；源码未发现自动草稿生成入口；shared workspace/Web后置能力未因此打开|卸载清理、真实GitHub、ZIP边界全面負例、符号链接与真实包内容审计 NOT_RUN；安装资源边界 FAIL（IR01）；不沿用“所有Skills必须禁用”旧断言|
|重点9 安全诊断|PASS（源码检查）：不使用异常message/toString、URL/正文/文件名；字节容量按最旧到最新裁剪逻辑可见|实际Android诊断负例及最新故障容量复验 NOT_RUN（IR03），历史6项不算本轮通过|

本轮 instrumentation 没有 SKIP；NOT_RUN 和缺配置均不进入通过数量。IR01独立资源边界方法为 FAIL；IR02采集器两个合成负例分别 FAIL/PASS。不能将它们与app JVM或Android正例合并为一个全量计数。
