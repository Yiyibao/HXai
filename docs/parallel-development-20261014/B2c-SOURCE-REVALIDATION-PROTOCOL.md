# B2c — 引用事实与正文重新核验 v1

现read_material_text_page保持id/offset/count必填字符串；原无摘要缓存读取不签发source_receipt，原仅expected_sha256模式继续生成v2来源回执。另传expected_receipt（v2 source_receipt对象JSON字符串，<=32768 UTF16）与expected_quote（非空逐字引用，<=24000 UTF16）进入独立重核验；两字段须成对且同时提供expected_sha256。选择已有receipt时id=version_id、offset=locator.start、count=locator.end-start；sourceSHA与引用正文一并提交，不能仅拿VERIFIED当依据。

领域入口MaterialFiles.revalidateSourceTextPage从相同owner版本重新冻结字节、核对实际SHA、重新提取并完成原收尾fence；MaterialSourceReceipt.revalidated对全部来源字段、scope/version/extraction/coverage/locator/source_locations与quote精确比较。notice只校验字符串类型且使用新生成文案，不把警告文字当授权凭据。未知/缺字段、旧v1、错owner/源/父版本/范围/页码、拼接正文、错误摘要与源变化返回FAILED且不交付成功receipt。参数不全不降级cache。

成功只返回真实新page/v2 source_receipt与receipt_revalidation{contractVersion:1,status:VERIFIED,scope:source_receipt_facts_and_quote}。不证明候选JSON曾被签发，不是数字签名/权限/数学证明。明确引用仍完整旧不可变版本可以通过；不能拿旧版本回执认证新子版本。没有DB/Pigeon/共享注册变更，tool名称/capability/read风险不变；Sol仍负责在统一任务验收中消费对应事实证据，不用tool成功绕过完成谓词。

本批证据HXai_CHANGE_REVIEW/B-SOURCE-REVALIDATION-20261014：实际旧工具2反例FAIL，新版本17例PASS（含旧Office/材料/PDF/PPT回归）、真实MD/PDF输出已独立核对quote SHA和UTF16边界。仅B领域E4/PASS_SCOPED，完整B2/B3、全Agent/Release/Mate50/真模型/生产未通过，独立审查NOT_EVALUATED。
