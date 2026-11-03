# B线：专业文件、教材与数学领域开发

这是可交给 OMP/独立B开发会话的执行任务书；本轮未派发或启动子代理。先读本目录PLAN、根/client AGENTS及Pro指定原文；保护当前全部未提交和untracked成果。A负责统一任务、授权、模型、共享桥接及最终集成，B不得另造执行器或改A共享真源。

## B01 首批：让Office领域交付可供A稳定集成

用户故事：合成复杂XLSX经实际引擎重算，原件不变，新版公式/值/多sheet/日期/图表可独立核对；失败、取消、撤权、重启不发布假成功。A同期接B1b主Chat，不重复实现已有重算接口。

首先读取：

```powershell
Get-Content -Raw docs/parallel-development-20261014/B1b-XLSX-RECALCULATION-PROTOCOL.md
Get-Content -Raw HXai_CHANGE_REVIEW/B-XLSX-SERVICE-20261014/SOL_IMPLEMENTATION.md
Get-Content -Raw HXai_CHANGE_REVIEW/B-XLSX-SERVICE-20261014/SOL_TEST_EVIDENCE.md
Get-Content -Raw server/document-service/README.md
```

1. 冻结供A接入的 `XlsxRecalculationTool`、OfficeConversionRepository、witness/manifest及版本接口SHA，交完整合成fixture和精确启动/结束方法。现有七共享文件候选基于旧A树，只交差异，由A三方集成。
2. 立新B合同后核引擎实际环境。只读探测 `docker info`；不可用写明确NOT_RUN，继续本机UNO/结构读回/失败夹具。可用时先审dispatcher/worker/container脚本，再在独立合成目录按固定镜像、最小挂载/no-network/资源限制运行，禁止默认连生产。
3. 补实际公式值/类型/日期/跨sheet引用/图表和无关结构对照；见证只能证明其明确scope，不能宣称MS Office/版式/一般数学证明。缺字体/不支持公式需可观测失败或限制。
4. 取消/撤权分别在上传、claim、运行、发布前注入；真实worker终止/重开、下载/提交ACK丢失、错误manifest和witness保留原日志。cancelConfirmed只表示交付取消，不能伪造容器已停止。
5. 版本生成、独立回读、预览/下载失败和cleanup保持owner/sourceHash/expectedText/locator/parent；原件及已验证局部成果不清空。A发现领域缺陷时先保存双方基线和反例，再交精确修复，不在A集成测试中静默换源。

已读本机验证脚本 `scripts/verify_b_xlsx_service.py`：它会创建全新run-dir、loopback HTTP服务与合成身份，实际启动本机LibreOffice并写新输出；是持续前台gateway，不是一条执行完即可判PASS的测试。核安装路径/依赖/空闲端口后，可在B独占终端按新目录运行：

```powershell
python scripts/verify_b_xlsx_service.py --run-dir .local/b-office-gateway-20261019-01 --port 18864
```

必须沿原B证据中的fixture/Android配置启动相应测试，并记录独立终止；端口仅在确认未被A或其他服务使用后选用。新目录不能复用；该本机命令不证明Linux/Docker隔离。其他专属脚本执行前仍须读原文。

## 后续领域批次

| ID | 实施入口/范围 | 可验收成果与A交接 |
|---|---|---|
| B02 DOCX/OMML | MaterialEditor/WordContent/EquationXml，现B1c协议与夹具 | 精准改公式/段落输出可编辑新版本；页眉/图片/表格/样式/目录保全；实际渲染修改处和代表页。给A提供两讲义Word故事所需接口/真实证据 |
| B03 PPTX/PDF | PresentationSlideOrder、现B1d/B1e、PDF assembly/Office worker | PPT逻辑/隐藏/可见/导出页分清，母版/形状关系保全，修改后全页核对；PDF原页操作与扫描转可编辑文件分开验收，标覆盖/符号不确定性 |
| B04 资料库产品闭环 | MaterialFiles/Reader、章节/来源/库检索、领域Flutter页面 | 核已授权目录导入、跨会话版本引用、文件夹/标签/索引、长文覆盖率、删除hook、保存/下载/恢复；已有字面搜索/章节工具不重做，不能宣称扫描全书已读完 |
| B05 数学质量后再扩能力 | MathVerification/math_verify、现合同1–15、教材引用与OCR输入 | 先代表题型/非标准正确解/孔洞原域/首错前缀/歧义确认和实际出处；有真实参考才报质量。之后可单批做Q(x)有限Taylor系数/余项恒等，明确不推出收敛/一般定理 |
| B06 跨环境领域验收 | API26、目标设备领域故事、MS Office/WPS、Linux worker | 确认实际可用环境并分项取证；配合A最终App，不以API34/本机UNO替代低API/容器/目标设备或跨软件保真 |

数学新合同需改变共享schema/verifier/planner时，先交给A明确增量和反例。当前B3o历史累计共享补丁已由A分批接入数学/章节/来源，不能再次叠加回退。P0/P1可交付闭环优先，新数学算法不占用Office首批的依赖修复；数学现有能力回归继续保留。

## 独占边界和交付

直接改领域material/math、document-service、专属测试/脚本和领域页面。A独占Factory/Registry/Dispatch/Store/Planner/Verifier、ChatService/GenerationLoop/Host、Pigeon、主Chat/providers、全局交互组件、构建/DI/路由；需要它们时交最小候选patch与before/candidate SHA、接口/schema影响、独立核验方法和合流验收，不整文件覆盖。

使用与A同一完整基线的独立目录/分支和独立build/cache/端口/设备；B5584使用前核归属，不碰A5554。A集成冻结版本期间，可继续夹具/独立下一批，不直接变更被冻结接口。新领域状态不自报authorized或taskSucceeded；由可信宿主控制owner、权限、任务和验收。

每批至少两类反证：假成果/假重算/改源/数学未知不得成功；正确文档/正常UTF16/合法旧版本/正确异法仍可用。保留XML/ZIP安全回归、原件字节、进程终止/丢ACK适用验证。按PLAN输出B-*完整合同、日志/hash/diff、六类SOL、精确下一命令与回滚；独立审查NOT_EVALUATED，缺环境NOT_RUN。回滚只停新操作/逆本批增量，保原件/版本/receipt/UNKNOWN和既有删除约束。

## B02 本轮交付定位（2026-10-19）

`B02-DOCX-PRECISION-20261019` 已完成本机领域范围：跨段TOC/锁定控件/删除修订统一保护、不同样式普通运行的UTF-16局部编辑、有界嵌套OMML、原件/部件/无关DOM保全；两讲义真实Android版本链及本机LibreOffice/PDFium实际修改页和代表页证据见 [B02领域协议v2](B02-DOCX-PRECISION-PROTOCOL.md)、[交付目录](../../HXai_CHANGE_REVIEW/B02-DOCX-PRECISION-20261019/HANDOFF.md)。完整Chat两讲义自主规划/协作故事仍属A03且NOT_RUN，不把领域通过等同G2全部通过。后续B03按上表另立合同，本轮未顺带启动。

## B03 本轮交付定位（2026-10-19）

`B03-PRESENTATION-PDF-20261019` 完成本机领域增量：PPT 逻辑/隐藏/可见序号、数值形状身份与兼容分支保护；原页 PDF 几何/扫描图/空白/重复与多源组装独立读回；解析正文/manifest/真实源页数一致性；已校验离线缓存生成带原页图与出处的可编辑 OCR 候选 Word。生产 UNO FilterData 显式类型化，真实 PDF 图片无损像素已验证。协议与源码映射见 [B03领域协议v1](B03-PRESENTATION-PDF-PROTOCOL.md)，原始反例、完整日志与精确增量见 [B03交付](../../HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/HANDOFF.md)。

整体 CONDITIONAL：真实 MinerU 未安装且 Docker daemon 不可用，实际 OCR 识别/正确率与完整扫描故事没有通过；合成协议缓存与真 Word 渲染不能冒充真实识别。主 Chat 完整目标、跨 Office、容器、低 API/目标设备/Release/生产另验，独立/全项目审查 NOT_EVALUATED。下批 B04 按原表另立资料库产品闭环合同，不重做既有检索/章节工具，不把候选 Word 当整书已读。

## B04a 本轮交付定位（2026-10-20）

`B04A-LIBRARY-READ-LIFECYCLE-20261020` 完成 B04 首个子批：普通读取/分页/附件输入统一核验真实源，不再信任未绑定源的 read-v6 磁盘正文缓存；实例内最多一份源身份绑定提取结果，热读仍验真实字节。迟到成功/错误/取消不复活已删元数据、不覆盖替换源或并发符号校对。原版伪正文FAIL、修复与完整精确增量见 [交付](../../HXai_CHANGE_REVIEW/B04A-LIBRARY-READ-LIFECYCLE-20261020/HANDOFF.md)，接口与来源映射见 [B04a协议v1](B04A-LIBRARY-READ-PROTOCOL.md)。

本子批 PASS_SCOPED：501 App JVM、53 native方法及普通Debug/lint通过；真实宿主向两个Chat发送同一材料，从实际loopback HTTP请求独立读回正确源ID/hash/正文/coverage。首轮把辅助title计入固定请求数的错误断言已删除并保留FAIL，不将其当产品缺陷。没有A共享/schema/权限/模型变更。完整B04仍PARTIAL：下一子批B04b继续系统授权目录、文件夹/标签、用户删除hook/备份约束和覆盖率产品呈现，先核现有材料/版本/备份消费，不重造字面检索或索引真源。真实Provider/OCR、提取中进程kill、跨环境/Release/目标设备/生产及独立/全项目审查未由本批通过。

## B04/B05 本轮完整实现定位（2026-10-20）

用户要求“完整实现B04与B05”。`B04-B05-COMPLETE-20261020` 已将系统目录授权复制、目录/标签/筛选、固定源覆盖率/章节/字面检索入口、版本/跨会话引用、删除缓存hook/回收/新版本恢复、另存读回与历史备份删除约束接入Root；B05在现合同1–15补真实出处、完整转录与单字符校对区分、代表语料/正确异法/原域孔洞/首错前缀质量证据。代码入口/持久层/消费者见 [领域协议v1](B04-B05-LIBRARY-MATH-PROTOCOL.md)，实际运行与范围裁决见 [本轮交付](../../HXai_CHANGE_REVIEW/B04-B05-COMPLETE-20261020/HANDOFF.md)。

本段取代B04a所列“完整B04仍PARTIAL”的实施进度，不抹去旧批次范围与失败。实际PASS/FAIL、进程退出/丢ACK及合流构建只取本轮最终TEST_RESULTS/验收矩阵，不从接线或旧计数推导。Room33及来源/恢复保护继承A，shared最小差异已按当前Root锚点核对，不回退A；metadata2/library1，sidecar9/Pigeon164/模型/授权不迁移。真实Provider/OCR、TalkBack语音、B06/Release/生产与独立/全项目审查未由本机工程夹具自动通过；可选下一Taylor合同未启动。
