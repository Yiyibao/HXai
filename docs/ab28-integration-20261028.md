# AB28 当前 A/B 线集成基线

## 来源与合并方式

本地集成分支：`codex/ab28-integration-20261028`。共同 Git 起点：`5d826d5d7d8755ed42db834d6cd6dc52ae914c69`。A/B 开发分支多数没有新增提交，成果原在未提交工作树；本轮使用单个本地集成快照提交，不伪造两个相同分支指针的 merge commit，也不推送/部署。

继承来源：

- `A27-SETTINGS-COMBINED-20261027`：配置持久发布/并发修复、schema13 QA 收尾，以及此前 A/B 组合源码。
- `B27-B-LINE-CLOSURE-20261027`：资料库/取消候选/元数据、文件与数学质量领域增量。
- `B27-MATH-OBLIGATION-SCORING-20261027`：原32题分离包的离线 seal/template/score 与逐义务判定。

三个最终源码清单都无缺失。A27 的 2,978 项中 2,974 项与根工作树字节一致，四项差异均为后来文档；B领域 311 项中308项一致，三项差异均为文档；B评分14项中12项一致，两项差异是共享文档后追加 A27 交接。两份共享文档已经验证 B27 完整前缀保留、A27 追加前版本与 B27 候选完全相同，未重复应用补丁或覆盖用户文件。

源码/来源映射与本轮证据：`HXai_CHANGE_REVIEW/AB28-INTEGRATION-20261028/` 的 `SOURCE_COMPARISONS.json`、`SHARED_DOCUMENT_MERGE.json`、`SOURCE_FINAL.json`、`TEST_RESULTS.json`。历史 QA 与原始 Pro 包保留在原位置，不把历史日志重新计作本轮测试。历史源码快照/构建缓存/合成设备数据不装入产品提交。

## 保持的合同

原 Flutter/Pigeon → ChatService/ConversationSession/GenerationLoop → 唯一 Scheduler/TaskStore → 工具/材料/Provider → receipt/DTO/UI 链保持。Room33、TaskStore13、Pigeon、候选schema1/2、数学operation合同1–17与离线评分合同1无新迁移。owner/profile/contextEpoch、授权epoch/inputHash、接受时模型快照、slot/fence、暂停/撤权/UNKNOWN、材料原件/版本/墓碑均不放宽。

离线评分只是本机字节绑定与判定一致性，不是新的模型执行器、授权服务或数学证明器。

## 本轮实际验证

| 层级 | 结果 | 证据范围 |
|---|---|---|
| App / AI / Search JVM | 525 / 214 / 33 PASS，零失败/跳过 | 当前源码、离线 Gradle、QA variant |
| Flutter | 416 PASS，analyze见本轮 TEST_RESULTS | 当前源码、预装SDK、无pub安装 |
| Python脚本 | 123 PASS + 22 subtests PASS | scripts/tests |
| 文档服务 | 154 PASS，保留既有1条弃用警告 | 本机pytest，非生产/容器 |
| Android混合 | 31 PASS，零跳过 | 自有API26，设置发布+材料数学+共享Chat |
| 普通App | 冷启及真实Flutter任务中心/Host查询PASS | 已安装Debug包，SHA与A27冻结包一致 |
| 实际评分CLI | prepare→seal→template→score通过；覆盖拒绝、旧报告保护通过 | 合成输入，32题/81义务全保未知，真实模型调用0 |

Android烟测复用 A27 最终冻结 QA/普通 Debug 包：所有当前 client 输入与该批封印相同后核实际安装SHA；本轮未重新构建 APK/AAR/Release。原生结果不能扩成完整项目、真实模型/OCR、目标真机或正式发行通过。完整新构建命令及发布门禁仍依照 BUILD_AND_TEST；真实账号、云托管、手写/MinerU、WPS、Mate50和独立/完整全项目审查均需各自证据。

## 复验与回滚

```powershell
python -m pytest -q scripts/tests
# 文档服务须在 server/document-service 工作目录执行：python -m pytest -q
.local/gradle-9.6.0/bin/gradle.bat -p client :app:testDebugUnitTest :ai:testDebugUnitTest :search:testDebugUnitTest -PagentNativeTest=true --offline --console=plain --max-workers=2
# 在 client/flutter_ui 工作目录，使用本仓库预装SDK：
../../.local/flutter/bin/flutter.bat analyze --no-pub
../../.local/flutter/bin/flutter.bat test --no-pub
```

Android使用归属封印/匹配QA两包与新run-id，在归档后只清合成nativeqa；不得并发使用同一设备或清普通App。精确本轮选择器、安装SHA和命令取 NATIVE_RESULT / logs。

回滚先停止受影响执行器，再切回原 feature 分支/逆集成提交；保留新库、publicationRevision、授权撤销、材料版本、receipt、费用与UNKNOWN，不reset/clean/stash、不降库、不盲重发。开发与定向反证由同一执行者完成，未开子代理，独立审查 NOT_EVALUATED。
