# SOL_ACCEPTANCE_MATRIX

| 子项 | 需求 | 实现 | HEAD | 证据 | 测试 | 独立审查 | 覆盖 | 遗留 | 下一条 | 回滚 |
|---|---|---|---|---|---|---|---|---|---|---|
| Pigeon | G47-01 | WIRED | fac15db | E1 | PASS | NOT_EVALUATED | 两端生成无 diff | 无 | 无需 | 无代码 |
| Flutter analyze/test | G47-01 | WIRED | fac15db | E2 | PASS | NOT_EVALUATED | 422 项 | 无 | 无需 | 无代码 |
| Gradle 重定向 | G47-01 | WIRED | fac15db | E3 | PASS | NOT_EVALUATED | generateDebugBuildConfig 进入运行目录 | 大包未整体重定向，避免复制 2.9 GiB | 见 BUILD_AND_TEST | 还原注释 |
| Debug/JVM/lint | G47-01 | WIRED | fac15db | E3 | PASS | NOT_EVALUATED | app 554, ai 215, search 33 | Web 未单独新建 | 无需 | 无代码 |
| Release APK | G47-01 | PARTIAL | fac15db | E1 | BLOCKED | NOT_EVALUATED | Release AAR 已有 | 缺 org.hxsi.agent 客户端 | HANDOFF | 不签 debug 密钥 |
| 引擎绑定 | G47-01 | PARTIAL | fac15db | E3 | FAIL | NOT_EVALUATED | 38 资源一致 | 缺 debug flutter.jar | SOL_BLOCKERS | 不放宽校验器 |
| API26/API34 epoch | G47-01 | WIRED | fac15db | E4 | PASS | NOT_EVALUATED | 各 2 项 | 未跑 FlutterChatBridgeTest | 可再跑该类 | 卸测试包 |
