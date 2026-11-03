# 06–09 组合接续合同 2 — 2026-10-30

承接 `quality-06-09-contract1.md`、`r27-final-plan-repair-contract1.md`、`r29-response-delivery-office-recovery-v1.md`、`r29-planner-narrative-v1.md`，不改 Pro 原始要求和历史 FAIL。

当前隔离工作树 `C:/Users/Hfff/.codex/worktrees/r30-quality-completion/MathStudyAgent`，分支 `codex/r30-quality-completion-20261030`，HEAD `070f3d83c294ba0d4a2197d591055e75cff59ac3` 加已冻结继承修改与本批增量。源码身份必须读 `HXai_CHANGE_REVIEW/R30-QUALITY-COMPLETION-20261030/ROOT_INHERITANCE.json`、`MERGE_APPLIED.json`、`SOURCE_FINAL.json`；HEAD 本身不代表候选代码。

## 06/07：同源主链和评估记录

主目录云/设备/快照与旧专用质量分支按共同基线逐文件三方合流。`ChatService → ConversationSession → GenerationLoop → TaskPlanner → AgentTaskStore/ConversationRepository → Flutter` 为同一执行链；不新建并行执行器。保留当前 Planner 的 Android context/共享语义资产加载及云端围栏。初始规划经 `PlanProposal.decode` 兼容严格受限的 null-tool 说明；说明不获得派发位或验收资格。原最终规划、整目标核验和持久回复交付条件保持。

共享语义资产仍 version 6，新增 contractRevision 2，明确 JSON 字段类型、文件父版本和字面子串语义。要求云/手机都只在 explanation 放说明，实际工具保持非空注册名称；云端没有被扩成接受 null 工具。原生语法兼容不能授云端新能力或修改授权。

`response_delivery` 是受限的附加计划谓词，只证明当前分支持久可见回复已交付，数学仍 NOT_EVALUATED；已要求工具、源引用、文件或独立证明时不得用纯文本满足。`ConversationRepository` 在原会话 incarnation/owner 与 Room 事务中读回并保护发布，`AgentTaskStore` 再检查模型、goal revision、grant/fence 和未决调用。原 slot/UNKNOWN、模型快照、用量未知、暂停和等待条件不变；无 Room33/TaskStore13/Pigeon 迁移。

QA `LiveQualityEvaluationTest.save` 和 `QualityCoordinatorObserver` 统一调用 `QualityEvidenceFiles`。先同目录临时文件分段写入和 fd.sync，再在进程 monitor + OS FileLock 保护下检查最终名不存在并进行 ATOMIC_MOVE，目录 fsync 后独立字节回读。所有参与 QA 写入者使用同一锁；不防拥有同 UID 私有目录写权的恶意绕锁进程。目录 `.evidence.lock` 是持久互斥元数据，不是最终 JSON。中断遗留 `.pending` 不计入答卷；旧空 JSON 不覆盖。Android 禁止 hard link 的真实失败保留，不把桌面 API 可用当 Android 资格。

`QualityEvidenceFilesTest` 检验写中断/丢 ACK/并发同名/旧空记录和 Unicode 正常输入。`QualityEvidenceProcessProbe` 只由 host 明确运行，在半写、文件同步、发布后实际 exit(77)，新 PID 读回；不能作为普通批量测试运行。命令：

```powershell
python scripts/verify_quality_evidence_process.py --ownership HXai_CHANGE_REVIEW/R30-QUALITY-COMPLETION-20261030/OWNERSHIP_API34.json --apk-inputs HXai_CHANGE_REVIEW/R30-QUALITY-COMPLETION-20261030/APK_INPUTS_RENAME.json --serial 127.0.0.1:5641 --out HXai_CHANGE_REVIEW/R30-QUALITY-COMPLETION-20261030/evidence-process-NEXT
```

该命令不安装/清库/发模型请求，检查自有 AVD 和实际安装 APK 哈希；loopback transport 只能指向该自有 AVD 的 console+1 端口。原付费 5576 磁盘、task f64c9418-9d2f-3cee-aa9a-e53daa941154 / call 5401be10-96b9-4f74-b70a-1d8519892ce3 的 UNKNOWN 和 fence303 不改、不重放。用户允许费用近似，不等于有原请求的真实完成回执。旧 13 封存/2 未封存/22 未派发的证据仍绑定旧代码，不授本批真实质量资格。

## 08：真实笔迹的许可与失败保全

本机非商业评估取得 Google MathWriting excerpt；[发布说明](https://github.com/google-research/google-research/blob/master/mathwriting/README.md) 标数据为 CC BY-NC-SA 4.0，[数据说明](https://github.com/google-research/google-research/blob/master/mathwriting/archive_readme.md) 区分 human 与合成笔迹。固定选择原 test split 中按 ID 排序的前五个 human 样本，选择发生在 OCR 前；保原 InkML、作者标签、归一化标签、栅格变换、源/图/结果哈希。它们是人写单式放在合成页面上，不是学生多页解答，也不是逐符号独立裁决。首式图像与作者题干括号存在解释边界，不能当作无歧义真值。

既有 WinRT adapter 以 en-US/none 实际识别该五页，均无文字；非白页没有被谎称 blank，未给予免确认资格。原五页 93 字符基线和所有质量 FAIL 保留。没有使用 LaTeX 参考喂给 OCR 或把标签写成机器结果；数据仅留评估目录，不装入产品或对外分发。

## 09：实际 WPS 与读回修复

`verify_b_wps.ps1` 只接受已准备 bundle 的固定 ASCII 身份和同 SHA 文件，每个新 COM application 先要求 Documents/Workbooks/Presentations.Count=0；只关闭自己打开的 document 对象，不 Quit 共享进程，不覆盖已有输出目录。真实调用 KWPS/KET/KWPP，另存、关闭、重开、导出 PDF；Excel 实际 CalculateFullRebuild；PPT 只在未保存的只读渲染视图临时取消隐藏以导出全部逻辑页。原保存的隐藏标记另验。UI 操作先尝试已装 Windows computer-use；弹窗控制失败后使用该原生 API，不以 UI 动画认定成功。

运行示例（新目录，不能重用原输出）：

```powershell
powershell.exe -NoProfile -STA -File scripts/verify_b_wps.ps1 -Bundle HXai_CHANGE_REVIEW/R30-QUALITY-COMPLETION-20261030/office-bundle -OutputDirectory HXai_CHANGE_REVIEW/R30-QUALITY-COMPLETION-20261030/wps-NEXT
```

实际 WPS 12.0 automation / exe 12.1.0.29161 对五副本均完成操作；不等于保真。原始 EXACT cache 比较 FAIL 保留；readbackRevision2 针对已证实的正确数值误拒增加单个 binary64 ULP 容差，仅用于同公式、同数值类型、非日期的已知重算缓存；非公式数据、类型、日期、null/未知、公式和表身份仍精确匹配。它不是数学证明，也不泛化为任意精度容差。原 MSOffice 实际驱动本来对 0.1+0.2 使用 1e-14；这里的新界限更窄且显式标出。

`office_roundtrip_contract.xlsx_roundtrip` 把 WPS 对既有图例常量 `Count` 写成 `strRef f="\"Count\""` 的表示视为同一常量，前提是唯一缓存值/数量/索引全部吻合。真实工作表引用保持原检查，并新增常量图例被修改的拒绝测试；不删除真实 cell/range 绑定。

PDF 抽取出现非法 Unicode 标量时明确 `pdf_text_invalid_unicode`，不替换字符后授 PASS。实际 Word OMML/正文结构保留，但 PDF 数学 ToUnicode 和排版有问题；Excel 重算/原值/公式/日期/引用在修复读回范围通过，实际 PDF 严重缩小/内容不可读；PPT 五逻辑页和隐藏设置虽保留，WPS 选择了不支持的 Choice 并丢失 VISIBLE FALLBACK，结构与视觉均失败。不能把 WPS 整体兼容写为通过。源件哈希均不变。

## 验收边界与回滚

命令、旧 FAIL、源码/APK/产物和三轴裁决只取本批真实证据；不累加历史计数作完成率。无子代理，独立审查及完整全项目审查 NOT_EVALUATED；适用架构边界由同一执行者定向验证，不冒充独立签核。

回滚停新 QA 生产器/原生 WPS 驱动，仅逆精确增量；保原任务库/UNKNOWN/授权、材料原件/版本、已发布快照、失败、WPS 输出和公开许可说明；不清旧库，不降 schema，不重发旧请求。完整真实模型/一般专业数学、真实学生多页整故事和 WPS 保真仍按各自失败/缺口裁决，不能由工程验证替代。
