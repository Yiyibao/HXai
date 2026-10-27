# SOL_BLOCKERS

本轮本地A/B源码集成无未解决冲突。以下不属于合并阻碍，不擅自实现或授予通过：

1. 新APK/AAR/Release本轮NOT_RUN：client源码与A27最终封印相同，已复用精确冻结包烟测；将来变更源码后，在预装环境按BUILD_AND_TEST运行Flutter AAR双variant、`:app:assembleDebug :app:assembleRelease -PagentNativeTest=false --offline --console=plain`并独立核包/冷启。正式发行仍需用户安全提供签名；不得用debug签名替代。回滚保数据与新schema。
2. A27 R27-03独立网络权威/真实双进程云续作：沿原Authority/Transport/StopProof/Store接线；精确入口/下一命令/依赖与验收见A27原SOL_BLOCKERS。当前未授权云托管/生产，不部署、不将本地共享SQLite冒充独立云。立即可运行的当前回归：本目录logs/jvm-regression.json；不得放宽fence/撤权/UNKNOWN。
3. B真实模型质量：沿原32题分离包/评分CLI；需要provider/endpoint/model/reasoning、公开题输入、传输/收费/留存明确授权后从原App锁配置执行，先封实际回答/receipt/用量再评价。立即可运行本机命令：`python -m pytest -q scripts/tests`；完整prepare/seal/template/score实跑命令见本目录logs/cli-*.json。回滚不重发未知计费请求。
4. 真实OCR/手写/MinerU、WPS/Mate50与TalkBack语音：保原B27失败/未验收边界，前置为合法标注/固定引擎、专属Office/目标设备与授权。原B27 SOL_BLOCKERS保精确adapter命令；本次不启用额外入口、不读用户Office实例、不伪造独立裁决。
5. 独立/完整全项目审查NOT_EVALUATED：当前执行职责不启动子代理或独立审查，不以开发烟测代授通过。

历史全证据/SDK/设备数据留本机原位，提交只包括产品源码和本轮精选证据；不要用git add -A把历史dump或私有材料装入提交。逆集成提交前停执行器，保材料/任务/授权/receipt/费用与UNKNOWN，不reset/clean/stash、不降库。
