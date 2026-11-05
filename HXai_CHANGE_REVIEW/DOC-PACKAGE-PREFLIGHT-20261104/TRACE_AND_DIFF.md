# TRACE

Android `OfficePackage.read` 用 `Locale.ROOT` 小写拒绝 `_xmlsignatures/`、`vbaproject.bin`、`vbadata.xml`。

服务端 `inspect_package` 原先只匹配小写 `_xmlsignatures/` 前缀，且不看 `vbaData.xml`。合成包被接受。

修复：条目名 `casefold()` 后比较，并拒绝 `vbadata.xml`。外部链接前缀仍按原大小写 `xl/externalLinks/`。宏执行开关未改，仍是 `engine.py` 的 `NEVER_EXECUTE`。

diff 见 `HXai_FULL_REVIEW/FULL-AUDIT-20261104/FIXES.patch`。
