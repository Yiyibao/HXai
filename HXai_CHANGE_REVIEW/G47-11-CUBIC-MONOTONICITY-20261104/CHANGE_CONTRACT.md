# CHANGE_CONTRACT

FeatureID: G47-11-CUBIC-MONOTONICITY-20261104

用户故事：对所有实参数 a，完整讨论 \(f_a(x)=x^3-3ax\) 的实数单调性。必须区分 a<0、a=0、a>0。只给导数或代入有限个 a 不能完成。

证书形状：对 x 的导数必须是 \(Kx^2+La\)，K、L 为有理常数，K 非零。其他形状 UNSUPPORTED，不判命题为假。

证明：a=0 时用立方差恒等证明孤立驻点仍严格单调；\(sa>0\) 时用 \(x^2-r^2=(x-r)(x+r)\) 的实线序符号表，不采样。s=-L/K。

宿主只识别「完整讨论」且要求处理退化边界的题面。导数是否已完成分类的另一题不走这条路径。已有工具计划或已派发调用不被文本路径擦除。

回滚：删除新文件，恢复 `MultivariateCertificate.verify` 的 action 分发、guidance、AgentPlan、TaskPlanner、AgentTaskStore。
