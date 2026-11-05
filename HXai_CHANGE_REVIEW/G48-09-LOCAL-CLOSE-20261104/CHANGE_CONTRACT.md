# CHANGE_CONTRACT

FeatureID: G48-09-LOCAL-CLOSE-20261104

用户故事: 普通管理入口可以授予、调用、撤销、拒绝旧代次并重新授权；三次含参的区间写法在宿主上仍能完成；圆锥参数交点和一致收敛不能借文本交付变成成功；当前未提交实现有一份可核对的源码清单。

入口: Flutter `extensions.grant` / `extensions.revokeTool` / `extensions.command` → `ExtensionFrontend.handle` → `ExtensionToolGrantStore` → `ExtensionMcpRuntime.invoke`。宿主验收走 `HostAcceptanceRuntimeTest` 的原 Chat。未核验门禁是 `UncertifiedGoal.blocksTextSuccess`，不新增证书。

不改 schema、不调用模型、不改 `HOSTED_KEYS`、不提交、不部署。

成功: 未授权和撤销后的调用不发出 `tools/call`；重新授权后同一工具可调用；安装代次变化后旧 id 和未重授的新代次都不能调用。区间写法的三次讨论在宿主上 `SUCCEEDED`。交点题和一致收敛题保持 `COMPLETED_UNVERIFIED`，且四个现有解析器都返回 null。

失败: 只授权 A 不能调用 B。颠倒区间不能完成。文本里写出“已经证明”不能把无证书题变成成功。

回滚: 删除本批仪器测试和宿主用例，不回滚已有 grant 表或证书解析器。
