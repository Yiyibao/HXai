# 下一批

每项都停在可执行命令。未把本轮 NOT_RUN 写成待办空话。

## 1. 提交或放弃 F1

文件：`server/document-service/office_worker/package.py`、`server/document-service/tests/test_office.py`。

不要把未提交的 `AGENTS.md` 与 `docs/pro-development-baseline-20261012.md` 混进该提交。

复验：

```powershell
python scripts/project_storage.py run --feature DOC-PREFLIGHT --kind test --disposable --reserve-mib 128 -- python -m pytest -q -p no:cacheprovider server/document-service/tests/test_office.py --tb=line
```

正常：含 `_XMLSIGNATURES` 与 `word/vbaData.xml` 的用例失败于 `ValueError`，富样例仍被接受。中断：工作树保留两文件 diff，回滚即 `git checkout --` 这两文件。风险：未提交前 HEAD 仍接受这两类包。

## 2. 有 SDK 后再跑宿主门禁

前置：项目外 Android SDK、Flutter、`client/local.properties`，且不把 SDK 复制进工作树。先 `python scripts/project_storage.py check`。

```powershell
cd client/flutter_ui
flutter analyze
flutter test
cd ..
python ..\scripts\project_storage.py run --feature NATIVE-UNIT --kind test --disposable --reserve-mib 2048 -- cmd /d /c client\gradlew.bat -p client :app:testDebugUnitTest :app:lintDebug :search:testDebugUnitTest --no-daemon
```

这仍不是 Mate50，也不是 Release 安装。签名缺失时 `assembleRelease` 只能单列 unsigned，不能当发布通过。

## 3. 设备与容器

前置：已核归属的 API26/API34，新的 `--run-dir`，冻结 APK。不要对用户设备或普通包清库。

Office 容器与 MinerU 仍是独立层。ASGI 160 项通过不是容器通过。生产撤权与 `https://hxnf.top` 需要单独授权。

## 4. 不要做的事

不要在原 `HXai_Pro_Delivery` 里跑 `07_CODE/run_verification.py`。不要把 Python 130 项或文档 160 项累加为 App 完成率。不要把本审查写成独立审查通过。
