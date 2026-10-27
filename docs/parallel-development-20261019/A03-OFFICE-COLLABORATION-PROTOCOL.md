# A03 Office 完整任务与协作协议 v1.28（2026-10-20）

来源：`HXai_CHANGE_REVIEW/A02-A03-COMPLETION-20261020/CHANGE_CONTRACT.md` 与最终源码/测试证据。继承 B02/B03，不重放历史累计共享补丁，不增加执行器。

## 创建、引用与版本

既有 `create_material_file` 的 content 新增可选 `sources:[{id,sha256,offset,count,quote}]`。工具按 owner 从固定版本重新提取，每个 quote 必须等于指定 UTF16 区间，生成真实 source_receipt 并写入 `MaterialFile.sourceReferences`。最多20区间、总引用24000单位；源哈希、真实 PDF 物理页/PPT 逻辑页、覆盖限制来自提取器。DOCX 不据段落猜物理页或印刷页。

可编辑正文附真实版本/摘要/位置及覆盖说明；PPT 引用列单独来源页，原有格式资源上限保持。成对 `parent_id/parent_sha256` 创建已有成果的新版本；父版本必须属于当前 owner、格式匹配且摘要正确。省略 sources 时继承父引用并重新核对。原件、上一版本与已验证任务步骤保留，重复任务命令 ACK 不重复执行。

`MaterialSourceBinding` 属材料元数据增量，旧无字段材料按空引用读取；新增字段不由旧严格解码消费者消费，回退前保全新元数据。带来源/父版本创建缓存为 `material-write-provenance-v1`；原无引用创建合同保持。编辑缓存升 `edit-v6`，PDF 组页升 `pdf-assembly-v2`，避免旧缓存冒充新来源合同。旧缓存不擅删。

## 独立核验与完整目标

TaskResultVerifier 将创建输入、父版本与实际来源绑定，编辑核原输入源 hash/parent；PDF 组页从持久原始 sources/pages 独立重开所有源和输出，核文字/几何/实际栅格。文本编辑继承引用，组页记录每个实际来源，不只第一源。

TaskPlanner 为所有实际输出收集独立正文、覆盖率和 sourceReferences，所有 artifact/remote 回执在最终语义请求的物理 admission 与回包后再核对。仍要求全部计划步骤 VERIFIED、完整目标谓词、模型回执 revision/evidenceHash 匹配和 task fence/授权有效。工具成功、PDF 页数或模型一句“完成”均不能单独发布任务成功。

主入口仍为 ChatService→原 Scheduler/GenerationLoop→可信 Registry/工具→不可变材料版本→独立验收→既有 Host 预览/保存/版本面板。没有新 Pigeon 字段，Pigeon164 与 sidecar9 不变；继承 A02 的 Room33。

## 可验收故事与边界

两份本机已授权讲义版本合成真实可编辑 Word，引用实际 PDF 源页；真实宿主预览、保存后逐字节回读；同任务追加“定理正文/题目附录”生成父子新版本，原回执和旧文件保持。PPT 局部运行修改与两源 PDF 原页组装分别走真实主 Chat。源变化/缺失、伪来源/跨 owner 或目标不满足不得成功。

固定输出还须本机 LibreOffice 实际导出、独立 PDF 渲染与检查；其证据不能代替 MS Office/WPS、完整扫描 OCR、真实 Provider 规划质量、Linux 隔离或 Mate50/Release。目录授权导入属 B04b，A03 不伪造系统目录 grant。准确已运行/未运行状态取交付 TEST_RESULTS。

回滚只停新入口或逆明确增量；保原件/版本/来源回执、Room33、任务/UNKNOWN及删除约束，不能重新信任旧缓存或把局部通过升级为完整 P0/发布通过。独立审查仍 NOT_EVALUATED。
