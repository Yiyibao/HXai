# B04a 资料库真实源读取协议 v1

来源：[CHANGE_CONTRACT](../../HXai_CHANGE_REVIEW/B04A-LIBRARY-READ-LIFECYCLE-20261020/CHANGE_CONTRACT.md)。适用 base/head 5d826d5d7d8755ed42db834d6cd6dc52ae914c69 加当前未提交 A/B 树；feature branch codex/b04a-library-read-lifecycle-20261020；精确字节见同目录 SOURCE-MAP 与交付 SOURCE_FINAL。旧 Pro/领域合同不降门槛。

## 可达链与真实语义

既有 MaterialTools 的 read_material_file、无 expected_sha256 的 read_material_text_page，以及 DocumentAsPromptTransformer/Compose MaterialPage，仍调用同一 MaterialFiles.readContent。没有新工具、执行器或共享桥接。普通读取也核验源，但未传显式摘要仍不签发来源 receipt；需要引用继续用已有摘要/定位/章节/来源工具。coverage 与 PDF 页/空白/未识别限制来自同一 MaterialReader，不声明整文读完、OCR正确或数学证明。

read-v6 磁盘 JSON 不绑定实际源，原版真实反例可使普通工具返回 FORGED_DISK_BODY 与伪完整页数。新路径完全不消费/写入该缓存；旧文件保留，不自动删用户历史或要求重新导入。

冷读复用原 withSourceText 的独立冻结、源 hash/size、真实解析、最后源/谱系读回、finally 临时清理；该私有 helper 泛型化，不复制第二套解析器。最近一次成功 MaterialContent 仅存于当前 MaterialFiles 实例，绑定 owner/id/hash/size/extension/parent；热读仍核真实文件字节和当前元数据，重开实例/进程重新提取。内存缓存至多一份，不能授权、签发来源或恢复任务。不同档案、版本与同ID新hash不会借用旧正文。

提取成功/失败/取消写回以当前元数据为基础，只更新仍匹配的源；删除记录不重建，替换源不覆盖，符号校对与其他并发非源字段保留。CancellationException 原样传播。这里不承诺防御同UID恶意进程任意改私有文件的瞬间竞态，也不是用户删除/备份墓碑协议。

## 接口与兼容

MaterialFile/MaterialContent 持久 JSON、工具输入/响应、Room32/sidecar9/Pigeon164不迁移；模型锁、授权/UNKNOWN/单执行位、B02/B03格式合同保持。仅普通分页工具描述更新，不再宣称沿用磁盘缓存。旧UTF8、Office/PDF、子版本、来源/章节/库检索依旧可用。没有模型请求/私有外传新增授权；本轮实际模型运输仅QA loopback合成服务。

## 本轮证据与复验

501 App JVM PASS、53 native方法PASS（8新增、45既有）、普通Debug/lint及QA/AndroidTest构建成功。真实宿主发送同一材料至两个不同Chat，独立从实际HTTP请求读回源ID/hash/正文/coverage，原件SHA一致；辅助标题请求单列，物理请求数不是断言目标。原版缓存FAIL与首轮错误请求计数断言FAIL独立保留，后者已删，不将其当产品故障。

已具备SDK/JDK/缓存、独占合成B模拟器后，先检查5584归属再启动既有B AVD（ANDROID_AVD_HOME=.local/omp-b1b-avd，AVD mathstudy-omp-b1b-20261014）。从根目录运行，run-id必须新建：

```powershell
python scripts/verify_agent_android.py --serial emulator-5584 --run-id b04a-replay-unused --build --classes me.rerere.rikkahub.feature.material.MaterialReadLifecycleRuntimeTest,me.rerere.rikkahub.feature.material.MaterialReadChatRuntimeTest --isolate-tests
```

runner只操作 org.hxsi.agent.nativeqa，先归档再清QA数据；不装作普通App，也不触碰其他设备。原始日志/方法/源码/APK/独立回读绑定见交付 TEST_RESULTS/NATIVE_RESULTS/CHAT_READBACK；本批自建5584已归档、清合成QA并关闭。

## 范围裁决与下一批

本子批 PASS_SCOPED，仅当前本机合成读取/状态保全；完整B04仍PARTIAL。目录授权导入、文件夹/标签、用户删除入口/备份删除约束、长文跨会话覆盖率产品呈现另立B04b合同，保既有字面检索/章节工具，不新造索引真源。真实Provider/OCR、进程杀停中途恢复、MS Office/WPS/Linux、API26/Mate50/Release/生产未由本批验收；独立/全项目审查 NOT_EVALUATED。回滚逆本批明确增量但不得恢复伪缓存为正文权威；保原件/新版本/metadata/receipt/UNKNOWN。
