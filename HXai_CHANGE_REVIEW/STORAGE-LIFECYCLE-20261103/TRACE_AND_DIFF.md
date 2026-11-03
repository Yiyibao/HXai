# Trace and diff
Base 54696b85beab3b3c7077d4e9becdc80f2b9ac925; branch codex/storage-lifecycle-20261103.
入口→Storage.check/start/run→common registry+OS lock→payload marker→CLOSED/GC_PENDING/quarantine→absence readback→CLEANED。
Gradle wrappers与四个旧验证main在副作用前调用check/enforce；本地hook检查实际暂存blob。policy是唯一阈值来源。新源码schema1与App schema无关。
完整变更见 FIXES.patch；源文件hash在TEST_RESULTS.json。产品owner/profile/epoch、授权、Token、UI、Pigeon、数据库均无变化，相关产品迁移/设备门禁不适用。

FIXES.patch使用零行上下文以避免patch空白上下文行被视为尾随空格；应用时须 git apply --unidiff-zero，仍先核对base和blob。原始logs用精确gitattributes -text保留字节，源码物理hash与暂存blob hash分别记录。
