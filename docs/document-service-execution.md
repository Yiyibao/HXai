# 自建文档服务实施记录

日期：2026-10-07。用户已授权按方案实施。当前完成首批 CPU Basic 服务部署、Android 功能贯通与受控合成样例验证；完整安全矩阵、目标手机和后续格式交付仍未全部验收。

## 实际部署

服务代码放在本仓库 `server/document-service/`，与 `client/` 分开。HTTPS 地址为 `https://hxnf.top/documents`。腾讯云 4 vCPU / 3.6 GiB 机器负责 TLS 入口，香港 8 vCPU / 7.8 GiB 机器承担任务；现有 MathStudy、博客和数据库服务继续运行。

香港新增 `mathdoc-api` 与 `mathdoc-dispatcher` systemd 服务，网关监听本机 8766；既有 Nginx 配置只增加独立文档路径，入口机继续校验香港 TLS 与客户端证书。两端配置修改均先备份、执行 `nginx -t` 后 reload。未新增域名、开放解析器管理 API 或把手机聊天密钥放到服务器。

MinerU 版本固定为 4.0.10，ONNX CPU Basic；Python 网关使用宿主 3.12.3，工作镜像使用固定摘要的 Python 3.12.15 slim-bookworm。两份 Python 依赖锁均包含精确版本与下载摘要，系统库显式锁定。模型约 819 MiB，官方固定修订的模型清单与实际文件摘要一致，来源模型许可元数据均为 Apache 2.0。版本证据另存 `qa/document-service/`。

## 已实现

网关提供鉴权 capabilities、流式上传、持久任务状态、manifest、登记资产下载、幂等取消和异步删除。每个服务器身份独立，上传参数不能指定 owner。SQLite 事务保证同身份同请求键复用任务、不同请求冲突、取消不能被晚到成功覆盖。恢复会先停止旧容器，再明确报告失败。

解析任务最多一并发、最多4核、4 GiB内存且禁止借用宿主 Swap，15分钟超时。工作容器使用非 root、无网络、只读根目录、capabilities 清空和禁止权限提升；每次仅挂载该任务输入/输出及只读模型。锁文件使用独立临时挂载。输出在限额 tmpfs 内生成，解析结束后由新的隔离容器完整解码检查图片，宿主再做合同、路径、范围和摘要校验及原子发布。网关没有 Docker socket。

Android 在材料页配置独立 HTTPS 服务和加密凭据，默认关闭。增加增强解析、任务状态/取消、分块读取、块图片及原页工具。提交前持久化幂等意图，下载验证摘要和范围后原子导入；缓存损坏、源文件变化和服务配置变化会拒绝使用。结果保留原物理页、稳定结果内 blockId、实际 Basic 级别、空白/失败页和局部范围。结果页可以核对原页。WorkManager 在联网条件下恢复取消、查询、下载和清理任务；未确认上传仅由当前聊天分支检查最新权限后恢复，后台不会重新上传，仍受 Android 后台限制。

完整下载后请求删除服务器副本，收到 EXPIRED 才清除待清理标记。未主动清理时，终止任务的文件最多24小时保留，绝对任务寿命48小时；过期元数据保留短期回执后清理。客户端配置、凭据引用和解析缓存均暂不进入普通备份。原材料仍按已有材料库保存。

## 验证证据

| 检查 | 结果与证据 |
|---|---|
| 服务合同与结果校验 | 20 项通过，`qa/document-service/server-tests.log` |
| Android 合同、构建 | 4 项 JVM 测试通过，arm64/x86_64/universal Debug APK 构建成功，`android-build.log` |
| 单页数学 PNG | 约28.75秒，采样内存最高1.769 GiB，CPU高峰约4核；`capacity-png.json` |
| 五页扫描 PDF | 最终版本约35.90秒，采样内存最高2.524 GiB；五页全部保留，第3页空白；`capacity-pdf-final.json` |
| JPEG / WebP | 均成功；JPEG约24.49秒，WebP约48.21秒（包括等待前一任务）；`capacity-jpg.json`、`capacity-webp.json` |
| 真实隔离 | 无外网、无邻任务/宿主任务库/凭据/socket，根目录/输入/模型只读，输出可写；`isolation.json` |
| 真实生命周期 | 同键复用、跨身份拒绝、取消终止、实际删除、重启停止旧容器并报告失败；`lifecycle.json` |
| 模拟器真实 HTTPS | 最终版本41.772秒，上传→Basic→校验下载→重读→原页渲染，跨档案和损坏缓存拒绝，实际清理确认；`android-live-test.log`、`android-live-evidence.json` |
| 页面与来源入口 | 原页核对、结果范围、独立配置页面测试通过；`android-page-test-final.log`、`android-preview.png`、`android-config.png` |
| 依赖核对 | API及worker锁定依赖未发现已知漏洞，`api-audit.json`、`worker-audit.json`；不等于完整安全审计 |
| 部署收尾 | 服务全部active、匿名请求401、任务均EXPIRED、无残留容器或输出挂载；`deployment.json` |

资源数据是合成数学扫描件的周期采样，不能当作所有文件的峰值或耗时保证。五页样例包含4个相同内容页与1个空白页，不代表已覆盖高密度、旋转、双栏或困难手写材料。压力测试期间既有 MathStudy 和博客服务保持 active，HTTPS ready返回200；尚未测量多人并发时的端到端网站延迟。

首次隔离运行因 MinerU 需要写模型锁文件而失败，保留失败诊断后用独立临时锁挂载修复。模拟器测试脚本的 JUnit 返回类型及档案测试设置方法曾修正，最终以通过日志为准。没有把这些失败当作成功证据。

## 使用与边界

安装最新 Debug APK 后，在“材料 → 设置自建增强解析”填入上述地址与当前学习档案的独立服务凭据，启用后选择 PDF 或图片增强处理。每次最多5页，原文件最多30 MiB。可以通过材料页刷新状态、查看下载结果和核对原页；聊天中明确要求增强解析时 Agent 可使用对应受限工具。

本机私有凭据放在 `.local/document-service/credentials/`，不进入 Git 或普通备份，不写入本报告。管理员可按服务器身份撤销。真机仍需要实际安装、联网、文件打开与来源展示验证；当前交付为 Debug 构建，未制作正式签名版本。

已生成 `learner-1.json`、`learner-2.json` 两个独立凭据，本机目录 ACL 限当前用户。服务器原件在 `/var/lib/mathdoc-admin/credentials/`（目录0700、文件0600）；现有业务备份的明确目录清单及配置备份的 `/etc` 范围均不包括该目录与文档任务数据。最终合成测试凭据已撤销，临时明文副本已删除。客户端默认仍关闭，需要为两个真实学习档案各配置一个凭据。

上述首批验收中，Office 增强解析、Standard/Advanced、服务端复杂生成、复杂表格跨度和完整排版往返属于后续批次。当前只定位物理页，不承诺精确 bbox。后续 P0 与 Office 转换验收另见下文，不以首批测试替代完整竞态、恶意文件或真机验收。

## 2026-10-09 Office 排版转 PDF

新增隔离 LibreOffice 25.2.3.2 工作镜像，固定部署 ID `sha256:eded0fd5c86acab9e38840128f24b6fb22af6724dd7568c90caad51681d07e2c`。基础 Debian 镜像摘要和系统包清单保存在服务器 `/opt/mathdoc/office-staging-20261009/`；本轮证据和验收报告在 `qa/office-renderer-20261009/`。只接受 DOCX/XLSX/PPTX，复用原入口和独立身份，不新增公开管理端口或聊天密钥。

转换遵循原件打印布局，Excel 重新计算公式并拒绝公式错误；PPT 默认排除隐藏幻灯片和备注。最大30 MiB输入、30 MiB PDF、200页、300秒，与 MinerU 共享单并发队列。工作容器无网络、非 root、只读根目录、无模型/凭据/任务库挂载。OOXML 包在容器内预检；禁用宏执行和外部更新；导出 PDF 经第二个隔离容器校验后发布。字体缺失会明确提示替换；这是 LibreOffice兼容渲染，未与 Microsoft Office基准逐页比较，不能承诺像素一致。

API 增加 `officePdf` capability、`POST /api/v1/conversions` 和按幂等键取消的 `/api/v1/conversion-requests/{key}/cancel`。状态、结果下载、取消和清理复用原任务接口。Android 为转换单独持久化提交/取消意图，校验原件版本、服务配置、渲染器版本、输出摘要和实际页数，然后导入材料库并自动交付聊天文件卡片。重新进入会话的交付使用稳定请求ID。

部署仅在队列空闲时暂停两个文档服务，备份目录为 `/opt/mathdoc/office-backups/20261009-1`，其他业务配置未改。若需回滚，先暂停网关并等待正在运行的任务结束，再停调度器；恢复备份的 `api/main.py`、`api/store.py`、`dispatcher/main.py` 和 `worker.env`，移除本次新增的 `api/office.py`、`api/office_output.py` 和 `office-ready.json`（该备份没有这些文件），随后启动两个服务并验证 health。保留新增 SQLite 表与历史回执，避免破坏原任务数据库。回滚不得覆盖其他服务配置或正在运行的任务。

## 2026-10-10 解析取消意图修复

解析任务新增持久化 `cancelRequested` / `cancelConfirmed`：前者记录永久取消意图，后者记录服务已经确认，区分离线等待与已收到回执。取消先落盘，再读取服务配置并联网；断网、停用服务或进程重建均不丢意图。缺失 `remoteId` 时调用鉴权 `POST /api/v1/parse-requests/{request_key}/cancel`，以原本地任务 ID 取消，不重传原文件。无已接受任务时返回 `taskId=null,state=CANCELLED,operation=parse`；已存在任务时返回原任务状态与 ID。身份隔离、取消事务与七天取消回执保留复用现有 Store；晚到上传不会入队，晚到成功不能覆盖取消。

批次在每次上传前持久化子任务与批次绑定。批取消先保存批次及所有已绑定子任务的意图，再逐项联网，任意一个失败不遗漏其余子任务；首批恢复、后续续批、下载和聊天自动续聊均检查取消。已经完成的远端状态可保持真实终态，但客户端仍保留取消意图，不恢复上传或自动交付；用户主动重新发起是新任务。

本轮服务端完整 pytest 48 项通过；模拟器 `DocumentCancellationRecoveryTest` 六项、`DocumentSubmissionRecoveryTest` 和 `P0DocumentBatchTest` 通过，实际覆盖接收 PDF 后丢回执、离线取消、仓储重建、批子任务取消和材料页点击取消。另启动真实本地 HTTP API，按 request key 取消已接受任务，重建 Store 后仍为 CANCELLED，匿名取消返回 401。受控测试没有运行真实 OCR；新增路由未部署到线上，服务端应先发布新增解析 request-key 路由，再发布客户端。回归入口：

- 服务合同：在 `server/document-service` 执行 `python -m pytest tests/test_service.py -q`，包含丢回执、取消先到、重启、鉴权及身份隔离回归。
- Android 持久化及实际表面 smoke：在 `client` 执行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.service.DocumentCancellationRecoveryTest,me.rerere.rikkahub.service.DocumentSubmissionRecoveryTest,me.rerere.rikkahub.service.P0DocumentBatchTest`。新增类使用模拟器内真实 loopback HTTP ServerSocket（接收并校验实际 PDF 字节后断开 TCP 回执）与真实私有文件、凭据、PDF 和仓储重建，覆盖丢回执按键取消、离线/停用服务后恢复、所有批子任务和晚到上传回执；其材料页 Compose smoke 实际点击“取消 / 重试取消”，断言取消后“重试提交”入口消失且上传次数不增加。仅将测试客户端请求地址改写为 loopback，不伪造 OkHttp 响应；服务器回复为受控合同数据，不宣称使用真实 OCR。
- Flutter/真机 smoke：解析七页 PDF，在服务已接受但客户端未收到上传回执时切断连接，点击聊天批任务“取消”，杀进程后重新进入原聊天并恢复网络；服务端任务应停止，不新增 `/api/v1/jobs` 文件上传，不续批或自动续聊。材料详情的等待状态显示“取消已保存，等待服务确认”。断网只能确认本地意图保存，不能声称远端已停止。

