# CHANGE_CONTRACT

FeatureID: DOC-PACKAGE-PREFLIGHT-20261104

用户故事：送进文档服务的 OOXML 不能靠大小写绕过签名目录拒绝，也不能只放 `vbaData.xml` 就通过预检。

base/head: `54b832613c1b4ab3eefbecedca5367c274b60135`。修复未提交。

入口：`office_worker.package.inspect_package`。不改 App、Room、Pigeon、任务槽或授权。

成功：`_XMLSIGNATURES/` 与 `word/vbaData.xml` 抛 `ValueError`。失败：修复前这两项被接受。恢复：还原两文件。

不在范围：超链接外部关系、CRC、容器、生产部署。
