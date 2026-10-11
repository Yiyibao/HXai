# MathStudy 文档服务

已实现独立 FastAPI 网关、SQLite 持久任务、可信宿主调度器、无网络的 MinerU 4.0.10 ONNX CPU Basic 工作容器，以及独立 LibreOffice 25.2.3.2 Office→PDF 工作容器。Android 通过版本化合同提交 PDF/图片指定页解析，或提交 DOCX/XLSX/PPTX 排版转换，查询/取消并校验下载结果。

已部署入口：`https://hxnf.top/documents`。香港源站执行任务，腾讯云沿用既有 TLS 与源站双向认证，仅代理入口。运维及实测证据见 [实施记录](../../docs/document-service-execution.md)。服务不承担聊天模型调用，也不保存聊天模型密钥。

## 运行预算

- 原文件最多 30 MiB；PDF 最多 200 页，每次选择最多 5 个物理页。
- 一个解析任务同时运行，CPU 配额 4 核，内存和内存加 Swap 总限额均为 4 GiB，不借用宿主 Swap。
- Office 转换与解析共享单并发队列；转换最多300秒、200页、30 MiB PDF。只接受 DOCX/XLSX/PPTX，遵循原文件打印布局；Excel重新计算公式，错误结果明确失败；不导出隐藏幻灯片或备注页。
- 解析最多 900 秒；临时目录 384 MiB，输出临时卷 192 MiB，发布结果最多 64 MiB。
- 任务队列最多 10 个，每个身份最多 3 个。完成、失败或取消后最多保留 24 小时，绝对寿命最多 48 小时。
- 持久任务数据按原件、请求元数据及最大发布结果预留额度：每身份 512 MiB、全局 2 GiB；超额返回 HTTP 429 / `storage_quota`。成功、失败和取消任务在实际删除文件并标记 `EXPIRED` 前仍占用额度，提前 DELETE 请求不立即释放。重复请求可返回原回执，不新增存储预留；旧任务库启动时补记已有文件的额度。
- 完整下载后 Android 请求提前删除。服务端返回 `EXPIRED` 后才清除客户端待清理标记。
- 重启先终止旧容器，再将中断任务标为 `FAILED/server_restarted`；用户可以发起新任务。

## 布局

`api/` 管鉴权、上传和持久任务；`dispatcher/` 只启动固定镜像、固定选项的容器；`worker/` 管一次解析及独立图片校验；`office_worker/` 管 OOXML 预检、UNO 转换和独立 PDF 校验；`deploy/` 保存镜像、systemd 和 Nginx 配置；`tests/` 保存合同及真实服务器探针。MinerU依赖锁版本和包摘要；Office使用固定基础镜像摘要构建，部署固定结果镜像ID，并保存完整系统包版本清单。版本记录分别在 `qa/document-service/` 和 `qa/office-renderer-20261009/`。

Office 能力由鉴权 `GET /api/v1/capabilities` 的 `officePdf` 返回。上传为 `POST /api/v1/conversions?extension=docx|xlsx|pptx`，必须提供 `Idempotency-Key` 和 `X-Source-Sha256`；结果使用原有 `/api/v1/jobs/{id}` 状态、取消、manifest、`assets/pdf` 下载和 DELETE 清理接口。未收到上传回执时，可通过 `POST /api/v1/conversion-requests/{request_key}/cancel` 持久化取消，同身份后续到达的上传仍为 CANCELLED；取消回执最多保留7天。

转换幂等性绑定身份、原请求键、原件摘要、扩展名与渲染选项；服务端渲染器升级不会改变已有请求身份或覆写原任务的版本。任务回执的 `rendererVersion` 返回实际绑定版本，Android 丢回执恢复沿用原意图和输出 ID，并以回执版本校验 manifest。文件、格式或选项变化仍返回 409。此新增回执字段需要先发布网关，再安装对应客户端；本次本地修改不代表线上已部署。

解析的丢回执取消使用鉴权 `POST /api/v1/parse-requests/{request_key}/cancel`，键为原上传的 `Idempotency-Key`，无需重新上传或等待解析器就绪。不存在任务时返回 `{"taskId":null,"state":"CANCELLED","operation":"parse"}` 并写入身份隔离的取消回执；已接受任务返回原任务 ID 与真实状态。后续同键上传保持取消，晚到成功不能覆盖取消。回执与 Office 共用七天保留策略。Android 永久保留 `cancelRequested`，另以 `cancelConfirmed` 记录确认；断网和重启只重试取消，不恢复上传。批取消在联网前记录所有子任务，聊天恢复检查批取消后才考虑首批或续批上传。新增路由须随网关先行发布，仓库修改不代表线上已部署。

转换 manifest 绑定原件摘要、扩展名、渲染器版本、固定打印选项、实际页数和 PDF 摘要。客户端保留提交意图和取消意图，校验下载后导入材料库并自动交付聊天文件卡片；确认清理前保留待清理标记。字体替换明确写入结果提示，LibreOffice兼容排版不承诺与 Microsoft Office 像素一致。

Office 容器不挂载模型、任务库、凭据或 Docker socket；禁止宏执行与链接更新，拒绝含宏、外部资源、签名或嵌入对象的包。提供 Noto CJK、Carlito、Caladea、Liberation 等字体。使用非 root、无网络、只读根目录和300秒硬截止；独立容器检查导出的 PDF，再由宿主校验绑定和摘要后发布。

Android 每个档案默认关闭服务。配置与凭据引用存放在 `noBackupFilesDir`，凭据由现有 CredentialVault 加密；普通备份没有这些数据。解析缓存同样暂不参与普通备份，需要时可从原文件重新解析。

## 运维

网关与 SQLite：`/opt/mathdoc/service`、`/opt/mathdoc/api-env`、`/var/lib/mathdoc`。模型：`/var/lib/mathdoc-models`。固定工作镜像 ID：`/etc/mathdoc/worker.env`。网关运行用户为 `mathdoc`，不属于 Docker 组；只有宿主调度服务可以访问 Docker socket。

查看运行状态：`systemctl status mathdoc-api mathdoc-dispatcher`。网关只监听 `127.0.0.1:8766`。工作容器不保存 stdout/stderr 日志，Nginx 文档路径和 API 访问日志关闭，失败仅返回受限错误码。不得把任务原件、结果、SQLite 或凭据目录加入常规备份。

管理员在服务目录执行：

```sh
sudo mkdir -p /var/lib/mathdoc-admin/credentials
sudo chmod 700 /var/lib/mathdoc-admin /var/lib/mathdoc-admin/credentials
sudo /opt/mathdoc/api-env/bin/python -m api.admin issue --output /var/lib/mathdoc-admin/credentials/learner.json
sudo /opt/mathdoc/api-env/bin/python -m api.admin revoke --identity <服务器身份UUID>
```

输出文件必须尚不存在；凭据只写入权限为 0600 的文件，不打印到终端。每个学习档案使用独立凭据，撤销后旧凭据不能访问任何接口。

部署修改前逐一保存站点配置、location snippet，以及 entry 模式的 rate-limit 配置；原内容和原存在性记录在 `/etc/mathdoc-nginx-backups/<UTC时间戳>-<模式>/`，文件权限为 0600。预检在写入前完成，`nginx -t` 成功后才 reload。任一写入、校验或 reload 失败时恢复全部受影响文件；原先不存在的文件会删除，回滚失败则明确报告路径和备份位置。暂停服务可先停 `mathdoc-dispatcher` 和 `mathdoc-api`，移除本项目 Nginx include 并验证后 reload；保留原件和版本记录，以便恢复。回滚前先确认任务容器已经停止，不覆盖既有 MathStudy 或博客配置。

## 验证

在服务根目录运行 `python -m pytest -q`。`tests/capacity_probe.py`、`isolation_probe.py` 和 `lifecycle_probe.py` 仅用于已知合成材料的受控服务器验证；不是生产自检入口。真实模型测试与合同测试的证据分开保存。

2026-10-11 修复复验：63 项 pytest 通过，另有 1 项 Starlette 弃用提示。真实本地 HTTP 验证了渲染器 A→B 的原回执恢复、17 次取消后的 429 拒绝和物理清理后的额度释放；Nginx 安装 CLI 在受控校验失败下恢复原 CRLF 字节并删除原先不存在的配置。该冒烟未运行转换容器、付费模型或真实 Nginx 部署，不替代生产验收。

本批不提供 Office 增强解析、Standard/Advanced、服务端 Office 生成、Microsoft Office完全一致的排版或精确 bbox。Office→PDF 属于兼容渲染，复杂布局仍需核对。Basic 识别结果可能有错误，表格跨度未承诺保留；原页和公式/表格图片用于复核。Mate 50 与正式签名发布仍待验收。
