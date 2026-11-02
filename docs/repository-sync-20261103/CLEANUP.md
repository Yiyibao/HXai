# 本地清理清单（2026-11-03）

根工作区源码已封存为 8775c04e68085d23d52fe62d190752f31464994f，分支 codex/current-code-snapshot-20261103。远端 SHA 已读回一致；main 未修改。未执行删除。

已核实的可重建目录 39 个，共 5.99 GiB；冗余安装 ZIP 5 个，共 1.11 GiB，合计约 7.10 GiB。数值为文件逻辑大小，硬链接、稀疏文件、压缩和仍被占用文件会影响实际释放空间。全部绝对路径、字节数及前置条件见 CLEANUP_CANDIDATES.csv。

## 可清理内容与条件

先停止对应构建、测试和开发服务。build 目录内若存在尚未归档的唯一安装包、测试报告或历史失败证据，先移至受保护的证据归档。Flutter AAR 删除后需先重新生成再构建宿主；node_modules 删除后需依锁文件恢复依赖，离线环境可能无法恢复。

| 路径 | GiB |
|---|---:|
| `D:\Office\Study\code\MathStudyAgent\client\app\build` | 3.585 |
| `D:\Office\Study\code\MathStudyAgent\client\flutter_ui\.android\Flutter\build` | 0.574 |
| `D:\Office\Study\code\MathStudyAgent\client\flutter_ui\.dart_tool` | 0.570 |
| `D:\Office\Study\code\MathStudyAgent\client\web-ui\node_modules` | 0.321 |
| `D:\Office\Study\code\MathStudyAgent\client\.gradle` | 0.230 |
| `D:\Office\Study\code\MathStudyAgent\client\flutter_ui\build` | 0.193 |
| `D:\Office\Study\code\MathStudyAgent\client\build` | 0.121 |
| `D:\Office\Study\code\MathStudyAgent\client\document\build` | 0.114 |
| `D:\Office\Study\code\MathStudyAgent\client\web\build` | 0.067 |
| `D:\Office\Study\code\MathStudyAgent\client\workspace\build` | 0.047 |
| `D:\Office\Study\code\MathStudyAgent\client\ai\build` | 0.043 |
| `D:\Office\Study\code\MathStudyAgent\client\search\build` | 0.031 |
| `D:\Office\Study\code\MathStudyAgent\client\flutter_ui\.android\.gradle` | 0.019 |
| `D:\Office\Study\code\MathStudyAgent\client\speech\build` | 0.017 |
| `D:\Office\Study\code\MathStudyAgent\client\highlight\build` | 0.012 |
| `D:\Office\Study\code\MathStudyAgent\client\web-ui\build` | 0.012 |
| `D:\Office\Study\code\MathStudyAgent\client\material3\build` | 0.011 |
| `D:\Office\Study\code\MathStudyAgent\client\videogen\build` | 0.007 |
| `D:\Office\Study\code\MathStudyAgent\client\common\build` | 0.007 |
| `D:\Office\Study\code\MathStudyAgent\client\oauth\build` | 0.007 |
| `D:\Office\Study\code\MathStudyAgent\scripts\__pycache__` | 0.001 |
| `D:\Office\Study\code\MathStudyAgent\server\agent-runtime\hxai_agent\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\tests\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\agent-runtime\tests\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\scripts\tests\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\client\.kotlin` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\client\flutter_ui\.android\build` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\office_worker\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\client\build-logic\.gradle` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\api\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\skill-sandbox\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\.pytest_cache` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\client\build-logic\build` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\dispatcher\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\agent-runtime\.pytest_cache` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\.pytest_cache` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\worker\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\server\document-service\deploy\__pycache__` | 0.000 |
| `D:\Office\Study\code\MathStudyAgent\client\build-logic\.kotlin` | 0.000 |

## 已安装工具的冗余安装压缩包

对应已存在的 .local/android-sdk/ndk/28.2.13676358、cmdline-tools/19.0 与 latest、platform-tools/adb.exe 和 .local/gradle-9.6.0/bin/gradle.bat。删除 ZIP 不删除已安装目录。

- `D:\Office\Study\code\MathStudyAgent\.local\android-ndk-r28c-windows.zip`：713.5 MiB。
- `D:\Office\Study\code\MathStudyAgent\.local\commandlinetools-win-13114758_latest.zip`：136.4 MiB。
- `D:\Office\Study\code\MathStudyAgent\.local\commandlinetools-win-16111833_latest.zip`：147.8 MiB。
- `D:\Office\Study\code\MathStudyAgent\.local\gradle-9.6.0-bin.zip`：134.2 MiB。
- `D:\Office\Study\code\MathStudyAgent\.local\platform-tools_r37.0.1-win.zip`：7.7 MiB。

## 需要保留或先归档后再判断

- `.git/`：本地提交、分支和全部工作树的共享 Git 数据，保留。
- `client/`、`server/`、`scripts/`、`docs/` 及根目录规范/构建文件：源码已同步，不建议以清理方式删除；本次只列出其明确生成目录。
- `HXai_Pro_Delivery/`：原始权威交付与历史反例；本次没有上传，按项目规则保留原件。
- `HXai_CHANGE_REVIEW/`、`HXai_FULL_REVIEW/`、`qa/`、`artifacts/`、`docs/**/evidence/`、异常命名 OfficeStudycodeMathStudyAgentHXai_CHANGE_REVIEWB04-B05-COMPLETE-20261020logs/：含原始证据/夹具/截图，不能据目录名称删除。本次除最小合成 Office 测试夹具外，没有将这些原始证据作为整体备份上传。若需移出本机，先做保持哈希和路径映射的外部归档。
- `.local/`：混有真实服务数据、私密配置/密钥、SDK、AVD、源快照、安装包和多个 Git 工作树，禁止整目录删除。`.local/document-service/`、配置辅助工具、数据库、设备状态、签名材料尤其不能当缓存。
- `.local/android-sdk/`、`.local/flutter/`、`.local/gradle-9.6.0/`：继续开发所需工具，保留；如决定卸载开发环境，可单独移除并准备重新安装，不能把它们当重复 ZIP。
- `.local/*avd*` 与模拟器相关目录：可能包含 App 数据和设备状态。是否注销设备需另行决定，不能当普通缓存。
- `.local/*patch*`、`*replay*`、`*frozen*`、`*readback*`、源快照与 tmp/：候选副本内可能有唯一改动或证据，不以名称判定冗余。先比较 SHA、保存独有差异与必要历史证据后再清理。
- `.env*`、local.properties、签名文件、加密配置和用户材料：没有作为源码上传；按用途与备份状态保留。
- 其他下载 ZIP、零散日志/截图/HTML/数据库：未证实为冗余；不纳入可直接删除清单。

## 登记的其他工作树

以下均在本轮检查时存在未提交状态；计数仅表示 Git 状态项，不等于独有源码数量。根工作区同步不等于这些工作树的全部代码或证据已备份。不要使用强制 worktree remove。完成差异核对与归档后，再按 Git 工作树方式注销。

| 工作树 | 状态项 | 其中已跟踪变更 |
|---|---:|---:|
| `C:/Users/Hfff/.codex/worktrees/2d08/MathStudyAgent` | 44 | 28 |
| `C:/Users/Hfff/.codex/worktrees/e5dc/MathStudyAgent` | 1 | 1 |
| `C:/Users/Hfff/.codex/worktrees/pro-agent-model-phase2/MathStudyAgent` | 271 | 2 |
| `C:/Users/Hfff/.codex/worktrees/r27-quality-06-09/MathStudyAgent` | 1197 | 20 |
| `C:/Users/Hfff/.codex/worktrees/r30-quality-completion/MathStudyAgent` | 1633 | 72 |
| `D:/Office/Study/code/MathStudyAgent/.local/a02-a03-verified-20261021` | 10431 | 7631 |
| `D:/Office/Study/code/MathStudyAgent/.local/a07-release` | 621 | 300 |
| `D:/Office/Study/code/MathStudyAgent/.local/a24-01-release-20261024` | 690 | 304 |
| `D:/Office/Study/code/MathStudyAgent/.local/a24-02-handoff-20261024` | 694 | 304 |
| `D:/Office/Study/code/MathStudyAgent/.local/a26-account-transport-20261026` | 844 | 339 |
| `D:/Office/Study/code/MathStudyAgent/.local/a26-r01-combined-build-20261026` | 925 | 351 |
| `D:/Office/Study/code/MathStudyAgent/.local/a27-settings-combined-20261027` | 940 | 352 |
| `D:/Office/Study/code/MathStudyAgent/.local/ab28-patch-replay-20261028` | 714 | 714 |
| `D:/Office/Study/code/MathStudyAgent/.local/b04-b05-complete-20261020` | 1929 | 344 |
| `D:/Office/Study/code/MathStudyAgent/.local/b06-api26-compat-20261021` | 769 | 344 |
| `D:/Office/Study/code/MathStudyAgent/.local/b06-linux-office-20261021` | 28 | 12 |
| `D:/Office/Study/code/MathStudyAgent/.local/b3b-rational-worktree` | 758 | 322 |
| `D:/Office/Study/code/MathStudyAgent/.local/omp-b1b-worktree` | 3632 | 3307 |
| `D:/Office/Study/code/MathStudyAgent/.local/omp-b1d-worktree` | 3678 | 3307 |
| `D:/Office/Study/code/MathStudyAgent/.local/p2-professional-math-20261022` | 794 | 348 |

重点保留 `C:/Users/Hfff/.codex/worktrees/r30-quality-completion/MathStudyAgent` 和 `r27-quality-06-09/MathStudyAgent`：各有尚未单独同步的质量验证工作区状态。本次未合并这些工作树。

## 同步与验证边界

Agent runtime 130 PASS；文档服务 156 PASS（1条依赖弃用警告）；定向评测/OCR/Office 脚本 110 PASS。原始日志及命令在同目录。常见秘密格式扫描无匹配；126 个所选文件的 Git 规范化 blob 一致。完整 App/Flutter/Android/Release 本轮 NOT_RUN；独立审查与完整项目审查 NOT_EVALUATED。源码快照不是发布资格。
