# CHANGE_CONTRACT

FeatureID: G47-05-INDUCTION-20261104

用户故事：用户给出 `a_0`、`a_(n+1)` 和“证明 `a_n=` 闭式”，并写明 n 为非负整数时，宿主按题面重算归纳。模型散文不是证明。

base: `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`，其上还有未提交的反例交付改动。

入口：原 Chat 无工具回复结束时，`TaskPlanner.acceptRecognizedInduction` 先于文本交付。

持久层：`acceptInduction` 写 `induction-delivery-v1`，`mathematics_status=VERIFIED`，`proof_kind=mathematical_induction_exact_polynomial_base_and_step`。没有工具调用时才能写。暂停、撤权、缺输入、设备确认仍阻断。

成功：题面可解析为 start=0 的多项式递推，闭式只含 n，回复写出该闭式，宿主重算为 VERIFIED。

失败：同上、圆锥、单调、是否、核验、讨论、分类、缺少“非负整数”、错误闭式、回复没写闭式，都不完成。

同时收紧反例判定：`disproved is not success` 不再被当成核验请求。
