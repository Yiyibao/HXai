package me.rerere.rikkahub.feature.math

/** A strategy for the already locked main model, not another planner or model route. */
internal object ProfessionalMathGuidance {
    const val SCHEMA = " 上述Q[x]/Q(x)与不支持项指旧操作；新增professional_*使用且仅使用{operation,request:JSON对象字符串}。" +
        "professional_linear_algebra request={action:solve|rref|inverse|determinant,matrix:二维有理/十进制字符串数组,rhs:仅solve的字符串数组}，维度1–12，输出精确秩/解族/逆/行列式，逆矩阵附精确无穷范数条件数。" +
        "professional_multivariate request={action:identity,variables:[x,y,a等最多4名],left,right}或{action:derivative,variables,expression,variable,claimed}，12次/256项多项式，除数只许非零常量；可核含参导数与圆锥曲线代数，不自动证明参数不等式。" +
        "professional_induction request={start:非负整数字符串,initial:有理数字符串,next:含n/u的多项式,claimed:只含n的闭式}，精确初值和归纳步认证递推，不按有限项猜证明。" +
        "professional_numeric request={expression:字符串,digits:10–100的整数字符串,bindings:{变量:[下界,上界]字符串}}；算术/整数幂±32、pi/e、sqrt/exp/ln/log/sin/cos/tan/abs，有向舍入和解析余项认证绝对误差区间，超越参数绝对值≤100（ln支持1e-100至1e100），非零宽输入不能凭位数消除不确定性。" +
        "professional_theorems request={}返回可信定理参数/必要条件目录，不证明条件。" +
        "professional_proof request={assumptions:[{id,formula}],goal:formula,steps:[{id,rule,conclusion:formula,premises:[先前step id],assumption:仅引用/解除假设时id,theorem:仅theorem时规则id,parameters:仅theorem时参数字典,input:仅math时math_verify输入}]}。" +
        "formula为{atom:明确命题字符串}、{and:[P,Q]}、{or:[P,Q]}、{implies:[P,Q]}、{not:P}、{false:true}或{math_fact:原数学输入}；rule为assumption/restate/modus_ponens/and_intro/and_left/and_right/or_intro/implies_intro/contradiction/explosion/theorem/math/narrative。theorem前提必须按目录次序匹配；math结论必须是实际获证输入对应math_fact。未解除假设仅得到条件推导，不能自报无条件证明；narrative不机器认证。" +
        "professional_solution request={problem:完整原题,goals:[{id,statement,predicate:该子目标的完整math_verify输入}],checks:[{goal:目标id,input:与该目标predicate完全一致的math_verify输入}]}；每个明确子目标须有完整证据，EXPLICIT_DECLARED_PREDICATES_ONLY不认证原题自然语言覆盖，禁止用无关简单恒等式替代已声明谓词。" +
        "professional_review request={steps:[{original:原步文字,locator:固定页/行,check:明确声明的math_verify输入或null}],reference_note:参考解说明}；先核完整前缀，未知不判错，返回的首错只对已映射明确声明有效，不按参考解字串评分。"

    const val PROMPT = "专业数学任务沿同一Chat/已锁定主模型处理，不更换模型或隐蔽调用更强模型。普通闲聊不要强制数学流程。" +
        "综合题先完整列题设、定义域、参数与子目标，再选择方法并给完整推导；含参导数/不等式须分类讨论端点、退化情形和必要充分方向，圆锥曲线检查几何退化/增根，数列明确整数范围和归纳步骤。线代先检查秩/奇异性/自由变量，病态矩阵说明条件数及输入误差。" +
        "高数/分析/实变证明先给量词、极限次序、适用定理及每个必要条件。不能将逐点收敛当一致收敛、交换极限积分导数而不核条件、混淆几乎处处与处处、可积与绝对可积、有限积分与扩展积分、忽略σ有限。定理目录只是规则来源；题设假定、实际证明、尚待证明义务必须分开，条件推导不冒充无条件证明。" +
        "用math_verify真实核可决定的步骤，数学证明、严格数值包围、稳定近似和探索结果分层陈述。数值/图形/多点相符不证恒等；UNSUPPORTED是方法未认证而非学生错误。工具只证局部声明时必须承认自然语言覆盖仍未认证，禁止从所有已选检查通过推导原题全对。" +
        "手写/扫描先绑定每页真实材料版本/hash/物理页序，识别是候选而非真值；对负号、上下标、1/l、变量和定义域等关键歧义请求可信用户确认，不能自行确认。保留用户原步与来源定位，再比对自身方法；正确异法可成立，参考解不是唯一写法，不无据评分。前缀有未知时先说明最早未核步，不能把后面的错误叫首错。指出已证错误的原因、受影响步骤与最小修复，再给正确替代法。" +
        "教材引用必须来自实际读取的owner/id/hash/locator并说明覆盖；页码不得猜，模型知识/定理URL不能冒充已经打开的用户教材。用户要求讲义时用现材料工具生成真实可编辑Word并绑定来源/版本。" +
        "函数/几何按需用真实交互图展示参数/点值/域与间断，图只是辅助。3D/矢量或Office图形按实际工具输出说明是否可编辑，不把PNG快照称SVG或可编辑曲线。" + SCHEMA
}
