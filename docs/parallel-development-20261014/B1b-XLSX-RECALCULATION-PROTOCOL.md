# B1b XLSX 重算协议增量 v1（2026-10-14）

不覆写原生总协议、Pigeon 或 Pro 原文；复用现有文档身份/任务/材料版本契约。实现与证据：`HXai_CHANGE_REVIEW/B-XLSX-SERVICE-20261014/`。

## 入口与授权

- 领域工具 `recalculate_material_xlsx`：输入 `id`、`expected_sha256`、`title`，显式审批与当前档案远程文档开关必需。可信注册沿用 `document.remote` / EXTERNAL / REMOTE_READBACK；模型和映射函数不能授予权限。
- `GET /api/v1/capabilities` 新字段 `officeRecalculation`，仅可信 `office-ready.json` 含 `recalculationContractVersion=1` 才可用。旧 PDF worker 不自动升级能力。
- `POST /api/v1/recalculations?extension=xlsx`，现有 bearer、`Idempotency-Key`、`X-Source-Sha256`，固定 operation `xlsx_recalculate`、options `{scope:recalculate_workbook}`。其他格式/选项拒绝。
- 状态、取消、manifest、资产下载及清理复用现有 jobs 接口；丢回执取消为 `/api/v1/recalculation-requests/{request_key}/cancel`，身份隔离，后到提交不得复活取消。

## 独立读回与版本

Manifest contractVersion 1 区分 operation，绑定 sourceSha256/sourceExtension、rendererVersion、renderOptions、recalculation、warnings 与 `xlsx`/`calculation` 两个固定资产的路径/摘要/大小/MIME。见证 contractVersion 1 绑定 sourceSha256/outputSha256/sheetNames/cells（sheet/cell/number/text），最大4MiB、最多100000公式。服务与 Android 独立核对真实保存文件的公式位置/类型/值/工作表/计数；不能只核对JSON转述。

证明 scope 仅 `saved_formula_caches`，明示 relativeTolerance=1e-12、absoluteTolerance=1e-15；layout=NOT_VERIFIED。不是一般数学证明、MS Office兼容或版式保真证明。

新版本复用 MaterialFiles 的 owner/sourceHash/parent/task output身份；原件不覆盖。材料提交前再次检查来源、授权上下文和取消围栏；缓存重新访问仍验证 witness/真实文件。任务新增 operation/cancelConfirmed 有兼容默认，旧 PDF manifest字段及旧任务恢复不变。取消意图黏性保留，ACK只表示取消交付已确认，不声称运行容器已停止。

## 集成与范围

B领域实现已在主目录；共享7文件最小提案只在B完整脏树隔离副本验证，交Sol三方合并。TaskStore/StudyHostFacade主A树已有后续变更，不盲套、不以隔离副本覆盖。无需Pigeon再生成，无新执行器、模型快照或账本方案。

真实本机HTTP/UNO/Android新版本及Flutter成果表面通过；普通Web重建、Linux/Docker、MS Office、Release/Mate50、真实模型和生产未授予PASS。独立外部审查未评估。
