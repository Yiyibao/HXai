# 每条剩余 Lint 项目

源文件：本轮 after-lint.xml。Hint 独立标明，不计入 warning。

## 1. DiscouragedPrivateApi (Warning)

Reflective access to sCursorWindowSize, which is not part of the public SDK and therefore likely to change in future Android releases

位置：`client/app/src/main/java/me/rerere/rikkahub/utils/DatabaseUtil.kt:11`

影响：Future Android may reject the reflection; existing try/catch prevents startup crash, but large rows could exceed the platform CursorWindow limit.

保留原因：Startup requests a 32 MiB global cursor window using a private field. A public CursorWindow constructor only sizes an individual cursor; replacing the call would require ownership changes for cursor creation and large-row regression testing.

已做检查：Inspected DatabaseUtil, RikkaHubApp and SQLiteConfiguration; Room uses the separate requery helper and migrations remain unchanged.

解决条件：Measure large legacy database rows on target devices and migrate cursor creation to supported per-cursor sizing before removing the compatibility fallback.

## 2. AndroidGradlePluginVersion (Warning)

A newer version of Gradle than 9.6.0 is available: 9.8.0

位置：`client/gradle/wrapper/gradle-wrapper.properties:4`

影响：A newer Gradle distribution is available; current configured build remains reproducible.

保留原因：Wrapper upgrade requires matching AGP/Kotlin/KSP/NDK compatibility checks; user prohibited whole-stack upgrades for warning count.

已做检查：Current wrapper and bundled Gradle are 9.6.0; build tasks executed with existing versions.

解决条件：A planned build-tool upgrade validated with current plugins, native build and supported host platforms.

## 3. NewerVersionAvailable (Warning)

A newer version of io.github.g00fy2.quickie:quickie-bundled than 1.11.0 is available: 1.12.0

位置：`client/gradle/libs.versions.toml:43`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 4. NewerVersionAvailable (Warning)

A newer version of io.pebbletemplates:pebble than 4.1.1 is available: 4.1.2

位置：`client/gradle/libs.versions.toml:49`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 5. NewerVersionAvailable (Warning)

A newer version of dev.chrisbanes.haze:haze than 2.0.0 is available: 2.0.1

位置：`client/gradle/libs.versions.toml:55`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 6. NewerVersionAvailable (Warning)

A newer version of dev.chrisbanes.haze:haze-blur than 2.0.0 is available: 2.0.1

位置：`client/gradle/libs.versions.toml:55`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 7. NewerVersionAvailable (Warning)

A newer version of dev.chrisbanes.haze:haze-blur-material3 than 2.0.0 is available: 2.0.1

位置：`client/gradle/libs.versions.toml:55`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 8. NewerVersionAvailable (Warning)

A newer version of dev.chrisbanes.haze:haze-glass than 2.0.0 is available: 2.0.1

位置：`client/gradle/libs.versions.toml:55`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 9. NewerVersionAvailable (Warning)

A newer version of dev.chrisbanes.haze:haze-glass-material3 than 2.0.0 is available: 2.0.1

位置：`client/gradle/libs.versions.toml:55`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 10. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-client-content-negotiation than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 11. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-client-core than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 12. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-client-okhttp than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 13. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-serialization-kotlinx-json than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 14. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-auth than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 15. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-auth-jwt than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 16. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-caching-headers than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 17. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-cio than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 18. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-compression than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 19. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-conditional-headers than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 20. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-content-negotiation than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 21. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-core than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 22. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-cors than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 23. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-default-headers than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 24. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-host-common than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 25. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-sse than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 26. NewerVersionAvailable (Warning)

A newer version of io.ktor:ktor-server-status-pages than 3.5.2 is available: 3.6.0

位置：`client/gradle/libs.versions.toml:60`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 27. NewerVersionAvailable (Warning)

A newer version of ai.sqlite:vector than 1.0.0 is available: 1.1.2

位置：`client/gradle/libs.versions.toml:61`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 28. NewerVersionAvailable (Warning)

A newer version of org.slf4j:slf4j-api than 2.0.18 is available: 2.0.20

位置：`client/gradle/libs.versions.toml:63`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 29. NewerVersionAvailable (Warning)

A newer version of uk.uuid.slf4j:slf4j-android than 2.0.17-0 is available: 2.0.20-0

位置：`client/gradle/libs.versions.toml:183`

影响：Installed dependency is behind the version reported by Lint; version availability alone does not prove a defect or vulnerability.

保留原因：A version change affects protocol, persistence or rendering libraries and is outside the requested focused warning repair. No blanket upgrades performed.

已做检查：Recorded exact coordinate and installed/suggested version from Lint; current build and protocol regression results recorded in report.

解决条件：Review release notes and compatibility/security impact for this exact dependency, update individually, and rerun its protocol/device regressions.

## 30. ModifierFactoryExtensionFunction (Warning)

Modifier factory functions should be extensions on Modifier

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ReorderableDrag.kt:27`

影响：API consistency only; callers currently receive a fresh Modifier and chain it with then.

保留原因：These functions are extensions on third-party reorderable scopes needed to access longPressDraggableHandle. Converting to a Modifier receiver requires changing scope propagation across all drag surfaces, beyond a local signature fix.

已做检查：Reviewed both implementations and all caller sites; no state/context retained; existing draggingScale extends Modifier.

解决条件：A focused drag API migration with interaction tests on all list and row consumers.

## 31. ModifierFactoryExtensionFunction (Warning)

Modifier factory functions should be extensions on Modifier

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ReorderableDrag.kt:45`

影响：API consistency only; callers currently receive a fresh Modifier and chain it with then.

保留原因：These functions are extensions on third-party reorderable scopes needed to access longPressDraggableHandle. Converting to a Modifier receiver requires changing scope propagation across all drag surfaces, beyond a local signature fix.

已做检查：Reviewed both implementations and all caller sites; no state/context retained; existing draggingScale extends Modifier.

解决条件：A focused drag API migration with interaction tests on all list and row consumers.

## 32. Aligned16KB (Warning)

The native library `arm64-v8a/libtermux.so` (from `com.termux.termux-app:terminal-emulator:0.118.0`) is not 16 KB aligned

位置：`C:/Users/Hfff/.gradle/caches/9.6.0/transforms/d5752d6441a49c9b57f8f1824c642e4c/transformed/terminal-emulator-0.118.0/jni/arm64-v8a/libtermux.so:binary/resource`

影响：Dependency binaries flagged by Lint are not 16 KB aligned. The actual packaged replacement passes ELF alignment checks; JNI runtime compatibility on a real 16 KB device remains unverified.

保留原因：Dependency AAR has an unaligned ARM64 binary; workspace also builds a replacement with the same soname. Lint analyzes dependency artifacts regardless of which binary the APK packages. Removing terminal code changes retained upstream functionality.

已做检查：Inspected CMake termux_pty.cpp, dependency and pickFirst packaging. All 56 packaged native-library entries in the final Debug APK variants pass ELF 16 KB load-segment alignment checks; libtermux.so uses the local aligned build, not the dependency binary. useLegacyPackaging=true compresses and extracts native libraries. See packaged-native-alignment.json; this is not a 16 KB runtime test.

解决条件：Verify all shipped ABI binaries and JNI behavior on a 16 KB device, then replace the dependency native artifact with a proven build.

## 33. Aligned16KB (Warning)

The native library `arm64-v8a/libtermux.so` (from `com.termux.termux-app:terminal-emulator:0.118.0`) is not 16 KB aligned

位置：`C:/Users/Hfff/.gradle/caches/9.6.0/transforms/d5752d6441a49c9b57f8f1824c642e4c/transformed/terminal-emulator-0.118.0/jni/arm64-v8a/libtermux.so:binary/resource`

影响：Dependency binaries flagged by Lint are not 16 KB aligned. The actual packaged replacement passes ELF alignment checks; JNI runtime compatibility on a real 16 KB device remains unverified.

保留原因：Dependency AAR has an unaligned ARM64 binary; workspace also builds a replacement with the same soname. Lint analyzes dependency artifacts regardless of which binary the APK packages. Removing terminal code changes retained upstream functionality.

已做检查：Inspected CMake termux_pty.cpp, dependency and pickFirst packaging. All 56 packaged native-library entries in the final Debug APK variants pass ELF 16 KB load-segment alignment checks; libtermux.so uses the local aligned build, not the dependency binary. useLegacyPackaging=true compresses and extracts native libraries. See packaged-native-alignment.json; this is not a 16 KB runtime test.

解决条件：Verify all shipped ABI binaries and JNI behavior on a 16 KB device, then replace the dependency native artifact with a proven build.

## 34. Aligned16KB (Warning)

The native library `arm64-v8a/libtermux.so` (from `com.termux.termux-app:terminal-emulator:0.118.0`) is not 16 KB aligned

位置：`C:/Users/Hfff/.gradle/caches/9.6.0/transforms/d5752d6441a49c9b57f8f1824c642e4c/transformed/terminal-emulator-0.118.0/jni/arm64-v8a/libtermux.so:binary/resource`

影响：Dependency binaries flagged by Lint are not 16 KB aligned. The actual packaged replacement passes ELF alignment checks; JNI runtime compatibility on a real 16 KB device remains unverified.

保留原因：Dependency AAR has an unaligned ARM64 binary; workspace also builds a replacement with the same soname. Lint analyzes dependency artifacts regardless of which binary the APK packages. Removing terminal code changes retained upstream functionality.

已做检查：Inspected CMake termux_pty.cpp, dependency and pickFirst packaging. All 56 packaged native-library entries in the final Debug APK variants pass ELF 16 KB load-segment alignment checks; libtermux.so uses the local aligned build, not the dependency binary. useLegacyPackaging=true compresses and extracts native libraries. See packaged-native-alignment.json; this is not a 16 KB runtime test.

解决条件：Verify all shipped ABI binaries and JNI behavior on a 16 KB device, then replace the dependency native artifact with a proven build.

## 35. Typos (Warning)

Did you mean "central!" instead of "central1"?

位置：`client/app/src/main/res/values/strings.xml:963`

影响：No user-facing spelling error in the retained location example.

保留原因：us-central1 is an actual provider location identifier, not the English word central followed by punctuation. Altering it makes the configuration example invalid.

已做检查：Inspected provider location resource and retained identifier exactly.

解决条件：None unless provider location identifiers change; this is a documented analyzer false positive.

## 36. UsableSpace (Warning)

Consider also using `StorageManager#getAllocatableBytes` and `allocateBytes` which will consider clearable cached data

位置：`client/app/src/main/java/me/rerere/rikkahub/feature/diagnostics/Diagnostics.kt:158`

影响：Reported free space excludes potentially reclaimable cache; may understate storage capacity, but the report never reserves storage.

保留原因：Diagnostics exports the observed freeStorageBytes metric. getAllocatableBytes estimates capacity and allocateBytes reserves storage; neither is the same measurement. Replacing it changes exported semantics and reservation behavior.

已做检查：Inspected export JSON and bounded export files; no preflight allocation decision uses this metric.

解决条件：If needed, add a distinct allocatableStorageBytes field with error handling and a schema contract; keep freeStorageBytes semantics.

## 37. UseKtx (Warning)

Use the KTX extension function `SharedPreferences.edit` instead?

位置：`client/app/src/main/java/me/rerere/rikkahub/feature/studychat/OneShotFeedback.kt:38`

影响：Style suggestion; synchronous feedback persistence must report a failed commit.

保留原因：SharedPreferences.edit(commit=true) returns Unit, whereas FeedbackStorage.record must return the actual commit Boolean and clear must check it. The suggested extension would hide write failures.

已做检查：Inspected FeedbackStorage, FeedbackLedger and durable feedback process regression; keeps commit failure contract.

解决条件：An extension/API returning the actual commit result, or a separately tested equivalent wrapper that preserves failure reporting.

## 38. UseKtx (Warning)

Use the KTX extension function `SharedPreferences.edit` instead?

位置：`client/app/src/main/java/me/rerere/rikkahub/feature/studychat/OneShotFeedback.kt:39`

影响：Style suggestion; synchronous feedback persistence must report a failed commit.

保留原因：SharedPreferences.edit(commit=true) returns Unit, whereas FeedbackStorage.record must return the actual commit Boolean and clear must check it. The suggested extension would hide write failures.

已做检查：Inspected FeedbackStorage, FeedbackLedger and durable feedback process regression; keeps commit failure contract.

解决条件：An extension/API returning the actual commit result, or a separately tested equivalent wrapper that preserves failure reporting.

## 39. RtlHardcoded (Warning)

Use "`Gravity.START`" instead of "`Gravity.LEFT`" to ensure correct behavior in right-to-left locales

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ViewText.kt:157`

影响：Explicit physical TextAlign.Left/Right stay physical in RTL locales.

保留原因：ViewText maps explicit Compose Left/Right to physical gravity; Start/End have separate logical branches. Changing these branches to Start/End would alter the requested text alignment.

已做检查：Inspected all six TextAlign branches in setComposeTextStyle; resource direction support is preserved.

解决条件：Only change if the product contract removes physical alignment; then verify RTL and explicit left/right text.

## 40. RtlHardcoded (Warning)

Use "`Gravity.START`" instead of "`Gravity.LEFT`" to ensure correct behavior in right-to-left locales

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ViewText.kt:157`

影响：Explicit physical TextAlign.Left/Right stay physical in RTL locales.

保留原因：ViewText maps explicit Compose Left/Right to physical gravity; Start/End have separate logical branches. Changing these branches to Start/End would alter the requested text alignment.

已做检查：Inspected all six TextAlign branches in setComposeTextStyle; resource direction support is preserved.

解决条件：Only change if the product contract removes physical alignment; then verify RTL and explicit left/right text.

## 41. RtlHardcoded (Warning)

Use "`Gravity.END`" instead of "`Gravity.RIGHT`" to ensure correct behavior in right-to-left locales

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ViewText.kt:158`

影响：Explicit physical TextAlign.Left/Right stay physical in RTL locales.

保留原因：ViewText maps explicit Compose Left/Right to physical gravity; Start/End have separate logical branches. Changing these branches to Start/End would alter the requested text alignment.

已做检查：Inspected all six TextAlign branches in setComposeTextStyle; resource direction support is preserved.

解决条件：Only change if the product contract removes physical alignment; then verify RTL and explicit left/right text.

## 42. RtlHardcoded (Warning)

Use "`Gravity.END`" instead of "`Gravity.RIGHT`" to ensure correct behavior in right-to-left locales

位置：`client/app/src/main/java/me/rerere/rikkahub/ui/components/ui/ViewText.kt:158`

影响：Explicit physical TextAlign.Left/Right stay physical in RTL locales.

保留原因：ViewText maps explicit Compose Left/Right to physical gravity; Start/End have separate logical branches. Changing these branches to Start/End would alter the requested text alignment.

已做检查：Inspected all six TextAlign branches in setComposeTextStyle; resource direction support is preserved.

解决条件：Only change if the product contract removes physical alignment; then verify RTL and explicit left/right text.
