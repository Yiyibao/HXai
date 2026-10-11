# Office 排版转 PDF：部署与验收

2026-10-09。已按用户授权在现有文档服务部署独立 LibreOffice 渲染器，并接入 Android/Flutter 聊天文件交付。支持 DOCX、XLSX、PPTX；使用原件打印布局，而非抽取文字后重建 PDF。未提交或推送 Git，保留原工作区改动。

## 结果

- Word、Excel、PPT 经公网 HTTPS、真实队列、离线渲染器转换成功，原件 SHA-256 未改变。
- 修改后的 Excel 原公式缓存为空，服务器重新计算 `4×3=12`，手机实际下载的 PDF 显示12。
- 真实 `gpt-6.1-sol` 聊天收到三个附件后分别调用转换工具；三个 PDF 校验后自动导入并交付真实文件卡片。重新进入会话没有重复续聊，实测93.355秒。
- Word 中文两页、显式分页、重复页眉页脚和表格通过；Excel 中文、A4横向、打印区域、隐藏工作表通过；PPT实际调整后的顺序、隐藏幻灯片排除及备注排除通过。
- Excel `=1/0` 返回 `FAILED/formula_recalculation_failed`，不交付错误公式 PDF。先按请求键取消、后到达上传仍是 CANCELLED。
- 原有 MinerU真实单页解析仍成功。本轮独立测试身份的12个任务最终均为 EXPIRED，无残留任务容器。

## 实现与运行限制

服务入口仍为 `https://hxnf.top/documents`。LibreOffice为25.2.3.2，部署镜像ID `sha256:eded0fd5c86acab9e38840128f24b6fb22af6724dd7568c90caad51681d07e2c`。基础镜像为 `debian@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f`，系统包版本记录在 [packages.txt](packages.txt)。部署固定结果镜像ID；构建脚本的APT来源仍可更新，不能把同脚本未来重建当作相同镜像。

转换和 MinerU共享单并发队列，原有身份隔离、队列容量、取消和24小时文件保留规则继续适用。Office最大30 MiB原件、30 MiB PDF、200页、300秒；CPU4核，内存及内存+Swap均4 GiB。容器无网络、非 root、只读根目录、禁止权限提升，不挂载模型、凭据、任务数据库或 Docker socket。

OOXML预检限制解压大小、条目数和XML大小，拒绝宏、外部资源、签名、嵌入对象等不支持内容；UNO明确禁止宏执行和外部更新。PDF由新的隔离容器检查，宿主只验证固定合同、路径、版本、页数及摘要后原子发布。允许LibreOffice产生的被动首页跳转，拒绝脚本、启动动作、附件及表单等主动内容。

API增加 `officePdf` capability、`POST /api/v1/conversions?extension=...`、`POST /api/v1/conversion-requests/{key}/cancel`。客户端单独保存转换意图和取消意图；提交或取消回执丢失后可以恢复取消，不会以取消为由重新上传。下载校验来源、服务配置、渲染器版本、选项、摘要和MuPDF实际页数；自动交付使用稳定请求ID，远端删除失败不影响已校验本地成果。

## 验收证据

| 检查 | 最终结果 | 证据 |
|---|---|---|
| 服务合同和调度回归 | 46通过 | [server-office-tests.log](server-office-tests.log) |
| Android构建、343项JVM测试、Lint | 成功，0测试失败、0Lint错误，46个Lint警告保留 | [full-client-build-final.log](full-client-build-final.log) |
| Flutter测试与分析 | 78通过，分析无问题 | [flutter-tests.log](flutter-tests.log)、[flutter-analyze.log](flutter-analyze.log) |
| 真实镜像DOCX/XLSX/PPTX预部署探针 | 1/1/2页，独立PDF校验通过 | [real-worker-final-probe.log](real-worker-final-probe.log) |
| 公网真实修改后Office转换 | 三个成功，Excel计算12 | [live-api.log](live-api.log)、[live-api-output/results.json](live-api-output/results.json) |
| 中文打印边界、错误公式、取消、MinerU回归 | 全部通过 | [extended-live.log](extended-live.log)、[extended-output/results.json](extended-output/results.json) |
| 设备转换仓库 | 3通过：损坏/摘要/身份/配置/原件拒绝、丢失回执取消持久化、本地权限禁止上传 | [device-office-repository.log](device-office-repository.log) |
| 真实模型三附件自动交付 | 1通过，重新打开无重复续聊 | [device-office-live.log](device-office-live.log)、[mobile-output/result.json](mobile-output/result.json) |
| 既有文件、聊天、权限、备份等设备回归 | 30通过 | [device-regression.log](device-regression.log) |
| APK与Flutter AAR | 两个架构各32项资产完全匹配 | [artifact-verification.json](artifact-verification.json) |
| 部署与清理 | 两个服务active，代码摘要与本地一致，12测试任务EXPIRED，无任务容器 | [deployment-final.json](deployment-final.json) |
| 凭据清理 | 本轮身份撤销、401确认、服务器/本机/设备明文副本删除，其他身份未改 | [credential-cleanup.json](credential-cleanup.json) |

已用Poppler渲染并逐页视觉检查原始富内容样例4页、补充中文打印样例5页及手机实际成果4页。手机成果保留Word标题样式、图片、表格、Office Math；Excel图片、常量及重算值；PPT图片、文字及两页顺序。默认Excel窄列的相邻数值较紧，未擅自改变源列宽或打印布局；用户复杂工作簿应核对实际打印效果。

首次部署脚本和设备测试构造参数曾有错误，修正后以最终日志为准。初始PDF检查曾误拒绝LibreOffice的被动首页定位，已限定放行无动作的页内目标并重新构建、实测；失败日志保留，不计作通过。

## 安装与运维

arm64 Debug APK：`client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，SHA-256 `c14e266b00e263f0367c40c31e1edc9e29a89934ff2c57e6e858135888e85ba3`。

x86_64 Debug APK：`client/app/build/outputs/apk/debug/app-x86_64-debug.apk`，SHA-256 `09c43cd9a91fb118e52c0ac0a385eab3f1ddb9dfa85e6dbfc1aabbd1b900973e`，已安装在模拟器执行上述测试。两个APK包含同一轮最新Flutter资产。

使用当前档案已有的独立文档服务配置与凭据启用服务，在聊天附上Office文件并要求转换PDF。测试凭据已撤销，不应作为正式学习凭据使用；未自动更改真实档案的服务配置。真实聊天测试使用独立档案，并从加密快照恢复原设置。

部署备份在服务器 `/opt/mathdoc/office-backups/20261009-1`，源代码在 `/opt/mathdoc/service`，固定Office镜像配置在 `/etc/mathdoc/worker.env`。部署时先确认空队列，仅暂停两个文档服务；未修改Nginx、博客或其他业务配置。回滚步骤见 [实施记录](../../docs/document-service-execution.md)，完整API使用说明见 [服务README](../../server/document-service/README.md)。构建用、与示例摘要一致的google-services临时配置已删除。

## 范围与未验收部分

这是LibreOffice兼容渲染，字体缺失会提示替换；已安装Noto CJK、Carlito、Caladea、Liberation等字体。未提供Microsoft Office原生基准，未承诺复杂Office版式像素一致。未验证所有图表、SmartArt、特殊函数、分页组合、200页边界、恶意文件全集或负载压力，也未完成本轮Office镜像漏洞审计。隐藏幻灯片与备注明确不导出；含宏、签名、外部资源或嵌入对象的材料需先处理不支持内容。

Mate 50真机、arm64实际安装和正式签名发布仍待验收；本轮自动交付使用模拟器和真实公网服务，不能替代真机验证。服务端Office增强解析及生成新Office文档仍为独立功能，不等同于本轮转换。
