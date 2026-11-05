# 对抗复验

同一执行者。

假成功：修复前 `_XMLSIGNATURES/sig.xml` 与 `word/vbaData.xml` 不被拒绝。修复后 `pytest.raises(ValueError)` 通过。

正确输入被拒：`test_package_accepts_real_rich_office_samples_and_checks_digest` 仍通过。未收紧超链接例外。

未证明容器不会执行宏；该结论仍只来自静态 `MacroExecutionMode`。
