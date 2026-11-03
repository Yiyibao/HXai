# STORAGE-LIFECYCLE-20261103

用户故事：阻止多轮开发反复复制源码/SDK/AVD、累积构建与测试数据；有统一盘点、启动门禁、登记回收和收尾记录。
Base: 54696b85beab3b3c7077d4e9becdc80f2b9ac925
Branch: codex/storage-lifecycle-20261103

范围：开发用本机磁盘治理，非App TaskStore/Scheduler，不改变owner/profile/epoch、模型快照、授权、数据库、Pigeon或用户任务语义。不新增Token/费用预算。只管理工具自己创建且明确可丢弃的临时目录；不清现有源码/材料/凭据/SDK/AVD/未知目录，不终止模型/设备任务。

链路：AGENTS/BUILD_AND_TEST与现有构建验证入口→统一Python盘点/check→受控开发命令run及登记→封存短receipt→gc默认dry-run、仅已完成disposable目录可apply；本地pre-commit检查空间与新增大产物。原始规范包不改。

默认约束：项目12GiB/关联工作树合计16GiB、生成物8GiB、单run6GiB、最低空闲10GiB、工作树最多2、登记构建run最多1；SDK/Flutter/Gradle本体和AVD不在项目内复制。具体常量存版本化策略文件，变更须有理由，不能为让FAIL消失而改大。

成功：超限启动不执行子命令；默认run收尾清掉自己明确disposable的生成物并留下有界小receipt；原始失败诊断可显式短期保留，最多1份；gc拒绝ACTIVE/非disposable/损坏标记/路径逃逸/链接/Git跟踪文件/嵌套.git，部分删除或中断不能记为成功；已有未知目录只报告。策略与清理测试含正常输入、边界、并发和故障恢复，原始规范与源数据不受损。

失败/恢复：持久登记与删除意图，未确认结束的run不自动回收；中断/清理失败保持BLOCKED或GC_PENDING，不据超时假设结束。回收只在同一受控目录内隔离/删除，真实读回不存在才记CLEANED。

兼容/回滚：移除入口guard/hook可回退开发门禁；保留登记与未清理目录，不变更App数据；工具不提供任意路径删除或force选项。直接绕过入口的外部命令不受内核级磁盘配额控制，本轮不能声称全系统绝对防膨胀。

验证范围：Python标准库/本机合成Git与临时文件；不重建大App、不安装SDK、不调用模型、不部署。独立审查NOT_EVALUATED。
