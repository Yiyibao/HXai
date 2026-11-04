# HANDOFF

G47-01 可构建的 Debug 基线已经复验。下一项是 G47-02。没有付费模型授权时不跑真实质量 cohort，只做不依赖外发的本地合同，其余记 NOT_RUN。

Release 下一命令，在同一 Firebase 项目加入包名 `org.hxsi.agent` 并重新下载 `client/app/google-services.json` 之后：

`python scripts/project_storage.py run --feature G47-01-RELEASE --kind build --disposable --reserve-mib 2048 -- cmd /c "cd /d client && gradlew.bat :app:assembleRelease --no-daemon --console=plain"`

期望未签名 APK：`client/app/build/outputs/apk/release/app-x86_64-release-unsigned.apk`。仍不要用 debug 密钥签名。

设备命令已用 `ANDROID_AVD_HOME=D:\Office\Study\Depend\avd`。缺这个变量时模拟器立即退出，端口拒绝连接，不能记成产品失败。
