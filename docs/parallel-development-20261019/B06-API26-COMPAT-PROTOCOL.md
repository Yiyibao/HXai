# B06 低 API 与 Office 兼容增量 v1（2026-10-21）

来源与原始失败：`HXai_CHANGE_REVIEW/B06-API26-COMPAT-20261021/`。本批不把 API34、本机 UNO、合成缓存或测试发现数量当成低 API、Linux 容器、跨 Office 或真实 OCR 证明。

## 可重放的真实低 API 执行

`scripts/verify_b_domain_compatibility.py` 要求显式 serial/API、专属 AVD 归属记录、主包/测试包 SHA 和源码 manifest。实际设备必须是该记录的 emulator、API、ABI、AVD；两 APK 必须分别为 `org.hxsi.agent.nativeqa` 与 `.test`。错误 API/包/字节/源在安装和清除前阻断。Windows 本机 SDK 路径沿用仓库既有约定，不声明其他宿主系统适配。

Android8 的不存在包 `pm path` 可返回1且输出为空；只把这一确切情形视作未安装，不吞掉其他查询失败。instrumentation 字段在每个 STATUS_CODE 包内绑定，不依赖 class/test 的输出顺序；发现必须有方法且唯一数量相符。每个方法单独实际运行，必须有该方法最终成功状态及 `OK (1 test)`；发现、ADB0、ignored/assumption 均不授予 PASS。

已有专属 QA 状态在安装/清空前停止并归档，逐方法保留 UNKNOWN、receipt、源和结果，末尾再次归档；归档或停止失败则不清空。不得将此脚本用于生产包、用户设备或 A 的模拟器。

## API26 资源接口与复杂 PPT 原样本

冻结 B04/B05 APK 的真实 Chat→math_verify 故事，在 `AgentTaskStore(...).use` 的数据库验收读回处发现 `ClassCastException`。隔离候选显式实现 `java.io.Closeable`，复用已有 SQLiteOpenHelper.close，不增加适配器、执行器、数据库迁移、授权或模型变化。Store及其任务测试归 A 所有：Root 的 A04 源保持不动；本批交 `A_SHARED_API26_V2.patch`、当前 Root before/candidate SHA，以及冻结源候选的隔离树实际运行证据。该候选通过不等于 Root A04 已通过低 API。

`PptShapeRuntimeTest` 过去依赖前一轮 cache 文件。现把 B-PPTX-SHAPES 已验收的 synthetic 原样本放入测试 APK 资产 `ppt_shapes/complex-original.pptx`，测试自行复制；来源及 SHA 见同目录 SOURCE.json。保留重复文字、组内 shapeId17、隐藏逻辑页、无关 DOM/部件/原件和错误请求拒绝的全部断言，不换简单样本。

扩展运行另发现两项旧字段迁移夹具在 API26 使用不支持的 DROP COLUMN。候选测试用事务表重建，保留原行/主键/唯一约束并恢复外键，生产 onUpgrade 不改；原 UNKNOWN/NULL历史时间/reported用量/fence/暂停恢复断言不降门槛。它们是原v1/v4字段边界合成夹具，不冒充完整真实旧库；另有既存真实Android schema6资产的全表原行保全测试。第一版87 PASS/2 FAIL保留，第二版与实际结果单独封印。

## Office 关闭确认不是转换结果

`office_worker.engine._shutdown_office` 始终尝试关闭文档、终止 Desktop、等待同一个自有 Office 进程。只有清理阶段的 DisposedException，且独立 wait 证实正常退出0、没有强杀，才可认定为丢失关闭 ACK。非零退出、超时强停或其他关闭错误均失败；操作正文异常仍保留，绝不回放操作。后续原件 hash、保存公式缓存、PDF 结构/页数及独立渲染核验继续执行，进程0或文件存在都不能直接发布成功。

原先真实 Word 导出失败、8次带自有进程退出日志的有界诊断、旧版5个失败回归，以及修复后实际 Word/PPT/XLSX/取消撤权恢复日志均保留。Windows 本机 dispatcher 进程适配验证不证明 Docker/mount/flock/no-network 或 Linux 隔离。

## 版本、交付与限制

无持久 schema、Pigeon、ToolPolicy、模型快照、权限、成功验收谓词或数学合同扩展。Room33、sidecar9、metadata2/library1、数学合同1–15仅描述被封印的 B04/B05 测试基线，不冒充 A04 当前全部版本。新增源码映射和各组件实际运行版本分别记录；原始 Pro 规范与历史失败不覆写。

原始 manifest 额外包含165个 `.cxx` 生成描述文件；新候选 manifest 明确排除该派生子树，原清单保留。冻结 Flutter AAR 和373个网页资源均有输入绑定，离线 native 重建跳过网页构建，不宣称重新构建网页或 Flutter。

具体 PASS/FAIL/BLOCKED/NOT_RUN、源码/输入/产物 SHA 和精确重放命令取本批 `SOL_TEST_EVIDENCE.md`、`SOL_ACCEPTANCE_MATRIX.md`、`HANDOFF_STATUS.md`。完整 B06 仍取决于 A04 合流、目标设备、WPS/MS Office 与 Linux 实际资格验证；独立/全项目审查 NOT_EVALUATED，不授予 Release/生产 PASS。回滚仅逆本批精确增量，保留原件、新版、receipt、UNKNOWN、撤权和删除约束，禁止 reset/clean/stash。
