# 本轮实际验证

- Python：runtime130、document-service158、定向脚本130 PASS，各组分列。
- JVM：App554、AI214、search33 PASS；真实Kotlin领域21 PASS，旧版21中8 FAIL_BASELINE。
- Flutter analyze PASS；首轮420通过/2项缺字体夹具失败；补入同SHA本机字体后相关4项PASS；Debug AAR新建PASS。
- 本轮独立目录完整离线Gradle：QA Debug、AndroidTest、三个JVM模块及lint全部退出0，544任务执行；lint严重度计数{'warning': 30}。未安装/运行Android包，不授普通Release/真机/模型/生产通过。
- 两种旧假成功→新拒绝的对抗反例均实际重放；最初OCR导入夹具错误日志保留。
- 20个原工作树HEAD、1142处候选源字节未变；75个活动源码输入SHA封印保持。

全部命令/cwd/退出码/时长取各RESULTS JSON和logs；jvm-results保留原始XML。APK/AAR仅本机保存，hash见ARTIFACT_SHA256，不将大二进制/缓存入Git。源码overlay变体新增blob约16.4MiB；完整项目和独立审查NOT_EVALUATED。
