# SOL_BLOCKERS

1. Release APK。缺 `google-services.json` 中的 `org.hxsi.agent`。文件：`client/app/build.gradle.kts` 的 `processReleaseGoogleServices`。已做 Debug 包。下一命令见 HANDOFF。验收：未签名 Release APK 生成且不用 debug 密钥。回滚：不改签名配置。

2. Debug 引擎绑定。缺 `D:\Office\Study\Depend\flutter\bin\cache\artifacts\engine\android-x64\flutter.jar`。已有 profile/release 引擎目录。不改校验器来放过缺失工件。下一命令：在该 SDK 补齐 debug engine artifact 后重跑 `scripts/verify_flutter_apk.py --mode debug --flutter-sdk D:\Office\Study\Depend\flutter`。

3. 设备只复验了 epoch/撤权类。`FlutterChatBridgeTest` 仍未在本批 APK 上重跑。
