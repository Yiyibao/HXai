# HANDOFF — B27数学离线逐义务评分

## I. 本轮实际事实

用户“继续开发B线”。复核最新B任务书/B27交接和Pro原文后，推进无需真实账号/外传的B26-R01/R27-07离线评估缺口。分支codex/b27-math-obligation-scoring-20261027；base/HEAD=5d826d5d7d8755ed42db834d6cd6dc52ae914c69；保留完整未提交工作树，无提交/推送/部署、模型调用或设备操作。

新增score_b_math_evaluation.py seal/template/score，复用原32题分离包。73项定向Python测试通过，实际CLI32题全分母/81义务、七种预期拒绝、旧输出保护和重复读回通过。全部回答为明确SYNTHETIC，不能当Provider成绩。裁决PASS_SCOPED仅离线字节绑定/人工判断一致性，参考/数学全文/真实执行独立性不认证。

## II. 改动、来源与接收

FIXES.patch为本批七文件增量，SOURCE_BEFORE保存精确起点，SOURCE_FINAL、SOL_IMPLEMENTATION/和SOURCE_MAP绑定当前源码/方法。PATCH_REPLAY证明专用before副本实际apply、逐字节匹配；接收前按before/candidate/current，已存在改动不再apply，不覆盖后来用户文档。

原prepare包装器、两题库fixture、App数学/材料/服务/共享Agent/Provider/权限/费用/Host/Pigeon/构建源码均未修改。新增离线评估contractVersion1，corpusVersion2、数学operation合同1–17、Room33/TaskStore13与候选schema1/2保持。接口/命令在docs/b27-math-obligation-scoring-contract1.md。

## III. 直接接续

本批交付完整离线入口，没有待实现stub。立即可执行回归：

```powershell
python -m pytest -q scripts/tests/test_score_b_math_evaluation.py scripts/tests/test_verify_b_ocr_corpus.py scripts/tests/test_verify_b_windows_ocr.py
```

下一真实评估先取得32题provider/endpoint/model/reasoning与传输/收费/留存明确范围，在原可信App锁接受快照、串行完成，不发参考/证书/用户历史。封存实际回答/task/物理回执/用量后，去敏感导出RUN_INPUT.json，再按合同seal→template→score；未知收费不盲重发。模板不是已评估成果，人工须核每项义务/异法/条件与参考缺项，正确范围的quote不证明数学结论。

具体可执行seal命令、输入schema及阻碍在SOL_BLOCKERS。B27真实OCR质量FAIL、真实手写/MinerU、独占WPS/目标设备、当前组合包和API26混合失败保留，不能用本CLI关闭这些任务。平台Settings/Scheduler不在本批改动范围。

## IV. 安全、证据与回滚

同一开发者做开发与反证，独立代码/人工及完整全项目审查NOT_EVALUATED。无新授权、外发、日程或子代理。保原题库、原回答/判定、失败/未知、材料版本/元数据/墓碑、TaskStore、receipt与费用；回滚仅移除新增离线入口并逆精确文档增量，不降库、不reset/clean/stash、不恢复撤权或重放未知请求。实际命令/环境/退出码/原logs/封印见COMMANDS、SOURCE_BEFORE、TEST_RESULTS、SMOKE_RESULTS、INPUT_OUTPUT_SHA256。
