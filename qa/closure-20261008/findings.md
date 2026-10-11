# 已确认问题关闭记录

全部原 PR 与固定范围中新确认 P1/P2 已关闭；无未关闭的已确认本地阻断项。覆盖缺口及外部条件未升级为代码缺陷。

## PR01 / P1 / CLOSED：旧保存复活删除会话

源码：[ConversationRepository.kt:395](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt:395)。删除终态只在 session 内；淘汰后缺行被当作新建。持久 tombstone + lifecycleRevision 同事务保护新增/更新/恢复、checkpoint、runtime、元数据与服务入口。

修复前直接证据：[pr01-before.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/pr01-before.log)。

最终候选直接证据：IndependentPostfixProbeTest#deletedConversationMustRejectStaleSaveAfterSessionEviction / `1e360ed3-8ca4-4c43-8c01-c88b5413bd25` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/eviction.log)<br>ClosureBoundaryTest#deletionCreationRestoreAndIndependentIncarnation / `6b423f07-04f7-4b54-b78f-932a575e9551` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/lifecycle-seed.log)<br>ClosureBoundaryTest#deletionCreationRestoreAndIndependentIncarnation / `889cea54-faac-49d6-b75b-0378c8ea8929` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/lifecycle-verify.log)<br>ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess / `c067af37-8737-429c-b7ca-c57794207ec3` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/checklist-seed.log)<br>ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess / `da83c9c5-ae25-4fda-9ad1-e90acb62329f` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/checklist-verify.log)。

## PR02 / P2 / CLOSED：Word tab 比较漏洞

源码：[WordFormulaAssertions.kt:19](D:/Office/Study/code/MathStudyAgent/client/app/src/androidTest/java/me/rerere/rikkahub/service/WordFormulaAssertions.kt:19)。旧 helper 只取文本，丢弃 tab。按 XML 顺序读取 t/tab/br/cr，段落换行归一；允许 run 格式拆分/paragraph-break 等价，错误 tab/换行/顺序拒绝；公式/全表/图片断言保留。

修复前直接证据：[pr02-before-host.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/pr02-before-host.log)。

最终候选直接证据：WordFormulaAssertionTest / `552ac11f-7cc0-494f-82fa-0a1d52279d50` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/word.log)。

## PR03 / P2 / CLOSED：早期 manifest PASS 掩盖最终失败

源码：[collect_run_evidence.py:33](D:/Office/Study/code/MathStudyAgent/scripts/collect_run_evidence.py:33)。旧 collector 仅信 manifest。现核对确切 class/test 的唯一 start/terminal、完整 invocation 与崩溃，矛盾拒绝；混合方法按各自终态记录，无法确认不改 PASS。

修复前直接证据：[pr03-before-qa-temp.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/pr03-before-qa-temp.log)。

最终候选直接证据：[final-collector.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-collector.log)。

## CL01 / P1 / CLOSED：COMMIT 失败污染后续事务

源码：[RollbackSafeOpenHelperFactory.kt:10](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/data/db/RollbackSafeOpenHelperFactory.kt:10)。 bundled requery 在 COMMIT 前弹出 Java 栈、失败时释放连接而未 native ROLLBACK。适配 openHelper 保留原异常、在同一池 writer 直接回滚；不关闭 Room 缓存、不重放写入，活动外层事务不被回滚。

修复前直接证据：[transaction-fixture-7-cleanup-retry/focused.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/transaction-fixture-7-cleanup-retry/focused.log)。

最终候选直接证据：ClosureBoundaryTest#approvalEveryTransactionStepRollsBackBeforePublication / `8b0751a9-9ef8-4f9f-9ac2-b68249b82078` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/transaction.log)<br>BackupReliabilityTest / `67f6b2f2-edc2-47ce-99d2-95b972e885e6` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/db-backup-regression.log)<br>ConversationBoundaryFixTest / `5ea745a3-f222-4573-bf5e-44c63eacd1c7` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/db-conversation-regression.log)。

## CL02 / P2 / CLOSED：Skills 替换及 rollback rename 失败后原技能不可读

源码：[SkillManager.kt:244](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt:244)。替换失败后只依赖同一个可失败的 rename；现尝试受限原 backup 的 NIO atomic move，保留唯一副本及启用状态。

修复前直接证据：[candidate-4-android/skills-rollback.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/candidate-4-android/skills-rollback.log)。

最终候选直接证据：SkillClosureTest#replacementAndRollbackFailureKeepOriginalContentAndEnabledState / `daf8ac90-5932-4afb-8590-46ed9e1fe5ec` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/skills-rollback.log)。

## CL03 / P2 / CLOSED：Skills 部分卸载失败损失原内容

源码：[SkillManager.kt:103](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt:103)。删除已消费 SKILL.md 后返回 false，原技能不完整。先不跟随链接复制完整 rollback 树，再原子挪到 trash；删除/设置失败恢复完整文件，成功才更新启用状态。

修复前直接证据：[partial-cleanup-original/focused.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/partial-cleanup-original/focused.log)。

最终候选直接证据：SkillClosureTest#partialUninstallCleanupFailureRestoresEntireSkill / `a4d1e564-d610-46db-ad80-293183dbee84` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/skills-partial-cleanup.log)<br>SkillClosureTest#replacementAndRollbackFailureKeepOriginalContentAndEnabledState / `daf8ac90-5932-4afb-8590-46ed9e1fe5ec` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/skills-rollback.log)<br>SkillClosureTest#nestedBrokenLinksAndSiblingReplacement / `b6fb83e1-aa0d-4886-abd4-f637ff24b670` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/skills-links.log)。

## CL04 / P1 / CLOSED：切档迟到保存导出旧档案材料

源码：[StudyArtifactPreviewPage.kt:97](D:/Office/Study/code/MathStudyAgent/client/app/src/main/java/me/rerere/rikkahub/feature/material/ui/StudyArtifactPreviewPage.kt:97)。Activity 被 DocumentsUI 覆盖时 collectAsStateWithLifecycle 暂停；rememberUpdatedState 仍是旧 owner。回调及复制/重读阶段检查 SettingsStore 的持久选择；拒绝提示跨重组保留；成果预览及列表均验证。

修复前直接证据：[delivery-ui-fixture-16/focused.log](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/delivery-ui-fixture-16/focused.log)。

最终候选直接证据：DeliveryUiClosureTest / `b32c2752-b6d2-4d24-a5a4-30cbdcb067a8` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/delivery-ui.log)<br>DeliveryClosureTest#cancellationCorruptionAndTargetRereadRejectFalseSuccess / `c5bcdd43-573d-4260-a79e-410391104643` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/delivery-failure.log)<br>ProjectReviewDeliveryTest#everyFormatOpensSavesAndArrivesInOtherUid / `8d654e57-df38-4b6d-834f-f32bc6abf97e` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/delivery-1.log)<br>ProjectReviewDeliveryTest#everyFormatOpensSavesAndArrivesInOtherUid / `e345fb95-2eff-4a92-8309-aaee14a2b2ae` [日志](D:/Office/Study/code/MathStudyAgent/qa/closure-20261008/final-independent-android/delivery-2.log)。

## 附加保护与限制

FilesManager.kt:213：一般删除 I/O 失败保留 managed record 供重试；最终设备证据断言 SHA/记录保持和重试删除。缺少同一 metadata 负例的修复前直接证据，不另宣称已确认缺陷关闭。
SQLite 适配依赖本地已构建 bundled driver 的内部 mConnectionPool；源码快照及 sqlite bytecode 保留。Debug 已验证；正式 Release、其他驱动版本及设备未验证。
首次候选运行未隔离 Application 生命周期诊断，可能追加正常启动事件；不声称从批次开始原诊断完全未变。最终 runner 隔离后，整次独立验收前后两份原文件 SHA 完全一致，未导出内容。
早期候选并非全部保存完整源码快照：日志、已保存 APK、安装哈希和首次输入保留；不可将这些候选当成最终可复现源码。最终 snapshot/APK/日志/设备产物完整绑定。
