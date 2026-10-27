# B24-02 OCR评估合同1：固定扫描真值与离线评分

适用分支`codex/b24-02-ocr-corpus-20261024`，HEAD/base `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`及继承的未提交工作树。本批精确输入、源码、原失败、日志、评分和裁决见`HXai_CHANGE_REVIEW/B24-02-OCR-CORPUS-20261024/`。这是B24-02可独立交付的本机真值/评估能力，不是完整真实OCR故事通过。

## 入口与不变接口

- `scripts/fixtures/b24_ocr_corpus_v1.json`：原创合成排印/笔画定义，CC0-1.0，仅适用于原创内容；不分发系统字体，不含学生/教材/私人资料。真值由作者在任何识别前固定，不是假称人工标注真实手写或权威数学参考。
- `scripts/verify_b_ocr_corpus.py build`：校验实际字体cmap覆盖，使用显式已安装符号字体补缺；缺任何字形即拒绝，不能把缺字方框当扫描真值。生成五页PNG、只含无损光栅的PDF、truth.json和CORPUS_SEAL.json。seal绑定spec/script/字体/库版本和全部产物SHA；未运行OCR。
- `score`：独立读真实源字节、核SHA与实际PDF物理页数，比较现有document-service contractVersion1的document.json与truth。先核来源、页覆盖分区和每个块，再输出新评分文件，不覆盖已有报告或源文件。读取/评分有界，不执行输入代码。
- 消费者兼容：复用实际`worker.run.normalize_pages`及`api.output.validate_output`完成合成解析输出的发布/摘要读回烟测；未修改两者。不新增服务、Agent、模型、授权或App接线。

Corpus/Truth/评分合同1与解析合同1是独立协议。Room33、TaskStore10、Pigeon164、数学合同17、确认schema1/2均不变，无数据库迁移或备份变化。

## 固定语料与计分语义

| 物理页 | 内容 | 真值/计分 |
|---|---|---|
| 1 | 中文题干与排印公式 | 作者指定逐Unicode码点文本 |
| 2 | 数学式、减号、上下标、1/l、x/× | 不折叠符号，保留字面表示 |
| 3 | 数值表格 | 单元格文本序列；不评分表格结构/版式 |
| 4 | x = 后不可辨直笔画 | 明确1/l两义；整页不计字符准确率，永远保留用户确认义务 |
| 5 | 真正空白 | 幻觉文本计插入，不设零分母为“100%” |

最终有效源为`corpus-final/source.pdf`，SHA256 `a2c4bf5c1491020b040eff25ecd7fda68980fab0493b76202c8798c39f0b51b0`。旧`corpus/`有实际下标缺字，`VISUAL_FAILURE_01.json`标为FAIL，仅作反例，禁止用作真值。最终PDF与五PNG逐像素一致，PDFium另渲染五物理页；没有隐藏文本层。

评分只做Unicode NFC与去空白的逐码点编辑距离；大小写、正负号、上下标、1/l、x/×不折叠。不把`x²`和`x^2`当同一转录表示，也不因此宣判数学错误。LaTeX表示规范化、公式语义、题目解答、首错、版式都不属于本计分器。不同表示须另立事先冻结的真值合同，禁止看引擎结果后修改真值来提高成绩。

- 以请求物理页为单位、按原块序拼接text；不按块ID对齐，避免合法分块不同被拒。跨页内容不能全局重新匹配。
- processed/failed必须无重复、无重叠、恰好覆盖requestedRange；blank只能来自processed且不能含非空内容。complete必须符合“整源范围且无failed”的既有语义。
- 未处理/失败的所选非歧义页按整页删除计入固定分母；合法部分请求只报告该范围，并另列unrequestedPages，不能宣称全源覆盖。
- 每页最多2500个规范化字符、最多5页；动态规划用两距离行和每单元一字节回溯，有界内存。超限明确拒绝，无静默截断。
- 输出S/I/D、距离、CER、符号混淆类别与规范化字符索引；空白页CER为null，插入仍进入总误差。总CER允许大于1，不将负“准确率”包装为通过。
- 报告不带原文或引擎文本，不在终端打印正文；原始私有输入不能纳入交付或技术日志。报告SHA是绑定，不是引擎运行或真值权威签名。

`evaluationStatus=PASS`只表示结构/来源合法并完成字面比较；即使误差为0，recognitionExecution仍NOT_ATTESTED、mathematicsStatus/taskCompletion/layoutStatus仍NOT_EVALUATED。`--result-origin synthetic-control`明确标记控制；external-unattested同样不授予“真实引擎已执行”。真实运行另需引擎/模型角色版本、授权范围、请求/任务/资产SHA与原始回执，A保管Provider调用与费用。

## 实际复验

仅预装Python/Pillow/fontTools/pypdf/PDFium/pytest，无下载或安装。以下输出必须不存在：

```powershell
python scripts/verify_b_ocr_corpus.py build --spec scripts/fixtures/b24_ocr_corpus_v1.json --font C:/Windows/Fonts/msyh.ttc --symbol-font C:/Windows/Fonts/seguisym.ttf --out .local/b24-ocr-NEW
python scripts/verify_b_ocr_corpus.py score --truth .local/b24-ocr-NEW/truth.json --source .local/b24-ocr-NEW/source.pdf --document PATH_TO_DOWNLOADED_DOCUMENT_JSON --output .local/b24-ocr-NEW/score.json --result-origin external-unattested
python -m pytest -q scripts/tests/test_verify_b_ocr_corpus.py
```

下载结果必须来自同SHA源、真实授权任务；score没有网络/提交/下载/重试能力。临时正确控制从固定真值构造，只能测评估器，不能计OCR成绩。实际适配器/输出发布烟测代码和所有CLI参数保存在本批COMMANDS.json，不在产品或自动发现测试目录留下设备驱动。

本批27项评分回归、22项既有解析合同、实际CLI正反控制、独立PDF读回与字节重建分别取证；不同层不累计完成率。错误控制为2替换/4插入/17删除，缺页保留93字符分母，页覆盖4/5。合法部分范围[2,3]单列未请求[1,4,5]。假complete/错SHA/空白冲突/源字节篡改均拒绝且不发布报告。

## 未验、共享边界与回滚

当前解释器无mineru，本机PATH和标准安装路径无tesseract；未因测试安装引擎或下载模型。没有本轮真实服务传输/账号授权，未探测远端或读取秘密。真实MinerU、真实手写样本标注/质量、识别→前台歧义确认→原统一Chat批改→Word来源故事仍NOT_RUN。既有`DocumentServiceLiveTest#actualHttpsBasicParseDownloadPersistenceIsolationAndSource`要求原专用五页夹具（空白页3、含16的表格、frac公式及图像块），**不能用本批空白页5的新语料冒充它的原夹具**。

A无需合入共享源码补丁；B只交领域脚本/定义/输出合同。取得真实服务授权后，用本语料从现有资料库前台显式提交1–5物理页，保存下载document及绑定receipt，先独立score，再按真实错例推进候选/确认/Chat/Word故事。不要修改A共享调度/Provider来绕过授权。

回滚只撤本批领域CLI/定义/测试/协议或停其调用，不重建库、不删除原材料/已确认转录/任务/费用/receipt，不复活已撤权来源或清空UNKNOWN。PASS_SCOPED仅本机语料与比较器；独立及完整项目审查NOT_EVALUATED，未授真实OCR、完整P2、App、Release、真机或生产通过。
