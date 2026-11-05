# 对抗复审

同一执行者，不是独立审查。

假成功：只写导数或代入 `a=1`、`a=-1` 的回复不被 `quoted` 接受。漏掉 `a=0` 孤立驻点的回复同样拒绝。API26 漏掉零参数格时保持 `COMPLETED_UNVERIFIED`，disposition 计数为 0，`proofKind` 为空。含 `x` 的一次导数项、参数高次、一般含参形状返回 `UNSUPPORTED`，不判命题为假。

正确输入被拒的反证：`x^3-3*a*x` 的三格符号表为 `VERIFIED`，`a=0` 是 `strict_isolated_stationary` 且 `extremum=false`。常数平移不改符号表，首项变号会翻转。宿主题面解析出 `x^3-3*a*x`，三格回复被接受。只问导数是否已完成分类的题不进入这条路径。API26 三格回复完成，证明种类是 `cubic_parameter_derivative_sign_chart`，数学调用数为 0。

残余：证书只覆盖导数恰好为 `Kx^2+La`。代数等价但没写出证书三格措辞的回复会被宿主拒绝。这不是一般含参不等式、极值或图像证明。
