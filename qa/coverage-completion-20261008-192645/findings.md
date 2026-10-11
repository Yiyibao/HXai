# 可执行问题及修复

|ID/优先级|分类|位置|触发、影响与修复|证据|
|---|---|---|---|---|
|CF01 P2|确定代码缺陷|`ChatService.kt` 审批保存；`ConversationRepository.kt:408`；`ConversationSession.kt` commitToolApproval|审批保存与暂停保存都失败，队列有待发消息；内存暂停但 DB 未暂停。随后同进程审批成功使用普通消息保存，仍保留 DB 旧队列，导致内存/数据库不一致。改为审批状态和 mutex 下当前队列同事务提交，成功后发布；迟到决定在锁内再检查，不落盘或继续生成。|修复前 `final-android/seed.log` 在暂停断言失败；该版本哈希 `apk-queue-failure-candidate.json`；原日志未覆盖。最终结果按 report/summary 为准。|
|CF02 P2|确定代码边界缺陷|`SkillManager.kt:105/200/210/213`；`SkillPaths.kt:14`；`SkillTreeDeletion.kt:12`|已安装目录中存在外部目录符号链接时，旧 File.deleteRecursively 会遍历目标；卸载或替换后清理 backup 都可能损伤外部文件。根目录同级别名也可能作用于另一技能。改为 NIO walkFileTree 默认不跟随链接，拒绝技能根目录别名。需要已有 app 私有目录链接；当前 ZIP/GitHub 安装不创建符号链接，未声称可由普通 ZIP 直接触发。|Android 合成外部哨兵在替换、卸载后字节保持；同级别名拒绝。`SkillsInstallBoundaryTest.uninstallAndReplacementDoNotFollowDirectoryLinksOrSiblingAliases`。修复前路径由源码确定，未伪称运行过修复前删除负例。|
|CF03 P2|确定测试回归|`MaterialRealModelTest.kt` sample loop；`MaterialSampleFixtures.kt`|上轮 UUID 目录迁移只替换了样例路径，没有写入新文件，真实方法会在导入前失败。补齐五格式合成文件写入，新增无模型设备方法验证文件存在及中文内容。此遗漏来自上轮证据迁移，本轮已纠正。|`WordFormulaAssertionTest.perRunMaterialSamplesExistAndContainSyntheticContent`；远端材料方法本轮 NOT_RUN。|
|CF04 P2|确定测试判断缺陷|`MaterialRealModelTest.kt:22` 及原图片探针段|图片工具返回文本 FAILED 时，原复合方法仅在有 Image 时才断言，因此方法 PASS 可能被误读为图片成功。拆成独立配置/方法/UUID，缺配置明确跳过，有配置要求实际 Image、新 SUCCEEDED PNG、解码与非零尺寸。|本轮缺配置合成负例通过且无新目录/成功图片；真实正例本轮 NOT_RUN，不计成功。|
|CF05 P3|覆盖缺口|`WordFormulaAssertions.kt:39`、`WordFormulaAssertionTest.kt`|仅存在 w:tbl 不能证明内容保留。新增全部表格、行、单元格顺序和明确内容比对，删除/重排/值变更/空表负例；真实 Word 方法也接入源/目标比对。|本轮本地结构正反例通过；真实模型 Word 重建仍 NOT_RUN。|
|CF06 P3|证据覆盖扩展|`ProjectReviewDeliveryTest.kt`|交付测试仍用共享设备证据目录，连续执行会覆盖文件。改为方法/UUID immutable 目录与 finalized manifest，SOURCE 记录合成材料 ID/档案。|按本轮最终 APK 对执行和采集；每次与精确目标的哈希对照单列，不累计为全量回归。|

历史真实教学/材料失败原因仍未知。本轮修复不把后来通过归因于这些历史失败；未知根因不是已关闭代码缺陷。环境错误和本轮新增测试断言错误分别见 report，不作为生产问题混入。
