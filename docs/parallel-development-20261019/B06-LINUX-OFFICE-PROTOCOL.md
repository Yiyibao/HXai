# B06 Linux Office 资格协议 v1（2026-10-22）

需求：B_TASK B06、Pro FILE-04/05/06/08/09 与部署隔离门禁。交付：`HXai_CHANGE_REVIEW/B06-LINUX-OFFICE-20261021/`；接续合同 `RESUME_CONTRACT_20261022.md`。不是生产部署，不代表所有 Linux 发行版或完整 B06 放行。

## 实际链与来源

`api.main.create_app`（真实非 root HTTP 进程）→`Store.enqueue/claim/dispatchable/finish`→`dispatcher.execute/main`→Docker worker→LibreOffice UNO→独立 Docker validator→宿主摘要/绑定校验→不可变 published→鉴权下载回读。没有替换 Docker/mount/flock 的本地适配器，没有另起产品执行器。

产品组件与本轮开始的 Root 60 文件逐字节一致；未修改 dispatcher/worker/API 产品实现。新增 `scripts/verify_b_linux_office.py` 为资格驱动；`scripts/fixtures/b06_linux_worker_probe.py`、`Dockerfile.b06-office-probe` 为资格专用派生镜像；`b06_linux_daemon.py` 仅启动归属匹配的私有 daemon。probe 不进入产品镜像。最终源码和输入取交付 `FINAL_SOURCE_INPUTS_V2.json`、`FIXTURE_INPUTS_V2.json`、`REGRESSION_FIXTURE_INPUTS.json`；每次运行另有 FROZEN_INPUTS、原始 Docker inspect/events 和命令日志。

本轮环境：Ubuntu 24.04、Python 3.12、Docker 29.1.3、LibreOffice **24.2.7.2**、真实 WSL2 Linux 内核 6.18.33.2。固定 base digest 与 Office 系统包清单均有记录；本机结果不覆盖既有远端不同版本。产品镜像固定 ID：

`sha256:69e69785e7156672be426d8e758cfe1becdcfabd7583ae9ca9a88bc41e3c7433`

## 不能只信 Docker 请求参数

WSL cgroup-v2 根目录不暴露 `memory.swap.max`；直接在根 cgroup 启动本批 Docker 时，Docker 错判 SwapLimit=false，将 `--memory-swap=4g` 退化成 `MemorySwap=-1`。原资格运行因此 FAIL，证据保留。`b06_linux_daemon.py` 将**本批 daemon** 放进专用子 cgroup；冷启动时仅在自有 Linux 内加法委派可用的 cpu/memory/pids 控制器，不改任何控制器限额、全局 `.wslconfig` 或用户 Docker Desktop。早版依赖 `/sys/fs/cgroup/docker` 暂态目录的冷启动失败已保留并修正；修正后从冷环境启动并重跑完整24场景PASS。最终真实 worker 读回：

- `memory.max=4294967296`、`memory.swap.max=0`；4 CPU、192 PID、nofile 256。
- 无网络且仅 lo；非 root 10010、零 effective capability、no-new-privileges。
- 输入只读、根只读，仅 /input 与 /output 两项任务 bind mount；384MiB /tmp、192MiB 输出 tmpfs；无模型/凭据/数据库/宿主 socket。
- validator 为独立进程/容器，768MiB 且无 Swap、1 CPU、64 PID，仅只读 /output。九个正常任务的实际 create inspect 均已保存。

驱动对不能执行 CPU/内存/no-swap/PID 限额的 daemon 提前拒绝，不降低门槛。

## 本轮验收边界

最终单次运行 **24 场景 PASS**，完整服务回归 **149 PASS**（1 项既有 Starlette 弃用提示），另有三个真实拒绝烟测：错源码封印、默认 Docker endpoint、重复 run-dir。计数不与历史累加。

- 复杂 DOCX A/B 原版与编辑版各三页，页眉/页脚/表格/图片/原生 OMML 输入、目标段落与所有页真实渲染；首末非目标页像素一致。人工查看实际页图；不把兼容渲染称为一般 MS Office 保真或 TOC 重算。
- PPT 原版与编辑版各三张可见页，隐藏页不导出；母版/形状/AlternateContent fallback 正确，非目标第2/3页像素一致。
- XLSX 两表七公式，实际 UNO 重算并重新打开；日期、文本001、隐藏表、图表及更新后的图表缓存均核对。除零公式、伪 witness、缺资产、假 renderer 不发布。
- 上传/claim/运行/发布取消与撤权、跨 owner、真实 worker 在打开后及写出后 SIGKILL、dispatcher 写后 SIGKILL、真实 HTTP 上传 ACK 不读取而断连、实际 Docker socket 失联与恢复、真实 fcntl 独占锁。旧请求不重执行，停止未确认保持 RUNNING/原件/tmpfs，恢复先停止旧容器再标失败；显式新请求独立执行。

原件哈希和真实下载摘要核对始终保留；完成不是“文件存在/Office退出0/构建成功”。PDF 原页组装/扫描 OCR 属 B03/独立验收，不由 Office 转 PDF 自动通过。

## 当前已准备环境的重放

本批发行版、镜像、源/输入与原始日志已保留，结束时自有服务及发行版已停止。以下为 **PowerShell**，先在独立终端前台启动归属锁定的 daemon（保持该终端运行）：

```powershell
wsl.exe --distribution HXai-B06-Linux-20261021 --user root --exec /usr/bin/python3 /opt/hxai-b06/source/scripts/fixtures/b06_linux_daemon.py --ownership /opt/hxai-b06/source/OWNERSHIP.json
```

另一个终端运行；`qualification-NEXT` 必须未存在：

```powershell
wsl.exe --distribution HXai-B06-Linux-20261021 --user root --exec /usr/bin/env DOCKER_HOST=unix:///run/hxai-b06/docker.sock /opt/hxai-b06/api-env/bin/python /opt/hxai-b06/source/scripts/verify_b_linux_office.py --ownership /opt/hxai-b06/source/OWNERSHIP.json --images /opt/hxai-b06/source/IMAGE_INPUTS.json --source-inputs /opt/hxai-b06/source/FINAL_SOURCE_INPUTS_V2.json --fixture-inputs /opt/hxai-b06/source/FIXTURE_INPUTS_V2.json --fixtures /opt/hxai-b06/source/b06-fixtures --run-dir /opt/hxai-b06/runs/qualification-NEXT
wsl.exe --distribution HXai-B06-Linux-20261021 --user root --exec /opt/hxai-b06/api-env/bin/python -m pytest -q /opt/hxai-b06/source/server/document-service/tests -c /opt/hxai-b06/source/server/document-service/pytest.ini
```

此命令主动杀死本批任务 worker/dispatcher、暂时隐藏本批 socket，必须独占本批 daemon；不能指向生产/default socket。不安装依赖、不连真实模型。新环境需核原 rootfs/工具链锁、transfer/fixture 清单、构建固定镜像并重新记录 ID，不能复制旧 PASS。Git Bash 另需 `MSYS_NO_PATHCONV=1`；不能让 MSYS 将 `/usr/bin/...` 改成 Windows Git 路径。

裁决 **PASS_SCOPED / WIRED / E3-local / PASS**。WPS/MS Office、最终整合 App API26、Mate50、真实 OCR、Release/生产均非本批验收；独立/完整全项目审查 **NOT_EVALUATED**。回滚只逆本批资格工具与文档增量，保留源/任务库/回执/UNKNOWN/新版本；不得 reset/clean、unregister 或回退既有产品安全修复。
