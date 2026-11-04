# TRACE_AND_DIFF

本批没有改产品协议或任务状态机。

代码差异仅：
- `scripts/storage.init.gradle` 增加绝对 `-I` 路径说明。
- `BUILD_AND_TEST.md` 记录 2026-11-04 重定向验证边界。

Pigeon 源 `client/flutter_ui/pigeons/study_host_api.dart` SHA-256 `a6c3ac7b96c63a7a171df5c9d84b29c245c545b66fac85ef1fa9faa4049864a0`。重新生成后 Dart/Kotlin 输出与 HEAD 相同。

Debug APK SHA-256 `c8663c99514fff4d3cce059f6e83b2656a7ddff5ee3d45998da15831730e2bfd`。androidTest SHA-256 `c481c64aaab56fbfcd2afebc71da0efc1dc9dab318798bcf7ec32313632641ea`。包名 `org.hxsi.agent.debug`。普通包 XML 中没有 nativeqa 管理 Activity/Provider。
