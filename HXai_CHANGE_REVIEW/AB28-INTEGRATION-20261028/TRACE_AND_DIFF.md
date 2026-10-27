# TRACE_AND_DIFF — AB28

## 版本与来源

初始 HEAD/base：5d826d5d7d8755ed42db834d6cd6dc52ae914c69，初始分支 codex/b27-math-obligation-scoring-20261027。原 staged 空；原 unstaged/untracked 全量状态与二进制差异另存 SNAPSHOT_*，不覆盖原记录。远端只读 ls-remote 本轮确认 default main/base不变，安全分支仍为 d7e319be42b2d5c1d3adf52ea9d09cc881f4cf59；未读当前PR元数据，不复述历史PR合并状态。

A/B 的分支多数都指向共同base，不能对同一指针 git merge 就声称集成。当前工作树已包含三份最终来源；本轮只固化组合源码、追加集成文档和当前验证证据，不改生产算法/执行器。

- A27 SOURCE_FINAL：2,978项，2,974项字节一致；4项为后续文档增量，无缺失。
- B27领域 SOURCE_FINAL：311项，308项字节一致；3项为后续文档增量，无缺失。
- B27评分 SOURCE_FINAL：14项，12项字节一致；2项为共享文档增量，无缺失。
- 两共享文档：A27追加前字节=B27最终候选；当前字节保留完整B27前缀，追加594/585字节A交接。本轮在其后/前按已有结构追加AB28章节，不回套旧全文。

细节见 SOURCE_COMPARISONS / SHARED_DOCUMENT_MERGE。原来源的HANDOFF/源码封印/测试结果另存 inherited，仅用于来源追溯，不算本轮实跑。

## 真实链与消费者

App真实入口、共享权限/模型/执行位、材料与数学领域使用原Chat链；未加并行Scheduler。离线CLI prepare→seal→template→score为单独本机工具，不连接Provider。Room33/TaskStore13/Pigeon/候选schema1/2/数学合同1–17均保留。SOURCE_MAP定位实际目录/协议；调用者/消费者字节保全，App31方法烟测覆盖设置发布、材料数学与共享Chat消费。

## 提交边界

本地 feature 分支 codex/ab28-integration-20261028。将当前 client/server/scripts/docs 产品增量和根AGENTS纳入源码快照；保留实际源测试夹具。历史docs/**/evidence、原HXai交付目录、QA状态/截图、构建缓存/密钥/签名/设备数据不批量纳入。四个原tracked QA截图及其他历史/无关untracked保持原位，禁止 git add -A。

实际完整产品差异由本地集成提交及 FIXES.patch 提供；它包含此前未提交成果，不误称全由本轮编写。源码封印 SOURCE_FINAL 与本轮日志/输入/产物hash分开记录。没有把source_base或历史负对照接入产品构建。

## Git字节绑定

仓库原`.gitattributes`固定`* text=auto eol=lf`。2,924项最终源码逐项与暂存Git blob核对：2,683项原字节一致，241项仅按既有规则CRLF→LF，零缺失/额外内容差异；未改用户磁盘字节或attributes。SOURCE_FINAL绑定原工作树SHA256，GIT_BLOB_SOURCE同时绑定Git blob/SHA256与换行标志，不能混用两种hash。FIXES.patch为共同base起的完整产品差异，使用binary/no-textconv/no-ext-diff，独立基线重放见PATCH_REPLAY。
