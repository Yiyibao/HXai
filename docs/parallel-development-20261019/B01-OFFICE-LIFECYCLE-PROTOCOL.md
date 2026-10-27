# B01 Office worker 生命周期增量 v1.1（2026-10-19）

来源：`HXai_CHANGE_REVIEW/B-OFFICE-LIFECYCLE-20261019/CHANGE_CONTRACT.md`。A01 XLSX 主Chat闭环已由 `A-OFFICE-CHAT-20261019` 接通，本批冻结其工具、Repository、manifest/witness和版本接口，不再叠加旧共享patch。B01仅修改文档服务领域调度/授权停止边界及定向验证。

## 停止不是一条失败的命令

`dispatcher.main.stop_container` 接受两种停止证明：`docker rm -f`成功；或rm失败后，通过成功的精确容器名查询证实不存在。CLI失败、daemon不可用或容器仍存在均抛 `WorkerStopUnconfirmed(worker_stop_unconfirmed)`，不是“任务已停止”。`stop_task_containers` 同时处理worker与validator。

启动恢复先确认所有已知worker/validator停止，再将原RUNNING置FAILED/server_restarted并进入claim循环。停止未确认则原RUNNING/取消状态、源/输出/存储预留保持，dispatcher退出，不继续领取任务。cleanup只有确认停止后才删除源目录和标EXPIRED；执行异常先停止确认再记录失败，不能因错误回包释放实际执行位置。无新DB状态、API字段或schema迁移。

## Claim不是永久dispatch许可

`Store.dispatchable(owner, job_id)` 在事务内重查owner、job、RUNNING、cancel=0及active身份，并围住旧管理员直接SQL撤权。dispatcher在创建worker前、create后真正start前、运行轮询及派发独立validator前复查。取消/撤权已生效的claim不能启动新worker；已发出的命令仍按取消路径确认停止，不承诺瞬时撤销已发出的外部调用。Store.finish原事务撤权/取消围栏保留。

## 证据与适用范围

原版停止失败仍FAILED/EXPIRED的3个反例，以及claim后或create后取消/撤权仍实际启动子进程的6个反例FAIL保留。修复后服务143例PASS，真实本机UNO/生产dispatcher逻辑在显式本地进程适配下完成14个上传/claim/运行/发布/错资产/写后停止恢复场景；正常编辑→重算→日期/多表/图表读回和DOCX/PPT真实渲染另跑原入口通过。

本机适配器替代Docker/mount/flock，只证明执行逻辑与真实UNO子进程，不证明Linux namespace/no-network/资源限额/fcntl锁。`docker info`实际失败，Linux固定镜像NOT_RUN。无生产、真实模型、私有外传或独立审查声明；不授予Release/Mate50/MS Office/WPS/专业版式PASS。

## 重复运行与回滚

```powershell
python scripts/verify_b_office_lifecycle.py --run-dir HXai_CHANGE_REVIEW/B-OFFICE-LIFECYCLE-20261019/runs/next-unused
```

pytest应从 `server/document-service` cwd执行（`python -m pytest -q`）以沿用现有模块路径。脚本只在新目录用合成材料启动/终止其自有Windows UNO树，不启动Docker、安装依赖或连生产。回滚保留所有源/版本/任务/UNKNOWN/撤权记录，仅逆本批精确patch；实际服务不得回到“停止未确认仍清理/继续claim”的危险路径。后续B02 DOCX/OMML、B03 PPT/PDF等按B_TASK逐批执行，不把本批算整条B路线完成。