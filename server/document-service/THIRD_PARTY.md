# 第三方组件与模型来源

本服务使用 **MinerU** 解析，已在 Android 服务设置及公开项目文档显著标明。MinerU 4.0.10 的代码许可为基于 Apache 2.0、含附加规模与在线服务标识条款的 MinerU Open Source License。固定版本安装包内的许可文本随工作镜像保留；[官方许可](https://github.com/opendatalab/MinerU/blob/master/LICENSE.md)。

ONNX 模型聚合仓库为 `opendatalab/MinerU-4_models_onnx`，固定修订 `358310b4f64b95f9fefc372ad899356e4111f376`。其 README 明确原模型许可与署名继续适用；所有实际模型文件已与该修订的官方 manifest 逐字节摘要核对。

官方 manifest 中的来源仓库及固定修订的模型卡均声明 `apache-2.0`：

- [PP-DocLayoutV2 ONNX](https://huggingface.co/PaddlePaddle/PP-DocLayoutV2_onnx/tree/7d44592493df02e28110d99de8ca4b1fbc7309bd)：PaddlePaddle。
- [PP-OCRv6 Small Rec](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513)：PaddlePaddle。
- [PP-OCRv6 Tiny Det](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c)：PaddlePaddle。
- [PP-FormulaNet plus-M ONNX 配置来源](https://huggingface.co/jinzhenj/PP-FormulaNet_plus-M_onnx/tree/9eb491f62e52f45571fbf88f4d0335e4eb36f9b9)：jinzhenj；公式权重的具体转换依据见 manifest。
- [MinerU 4 Torch 来源模型](https://huggingface.co/opendatalab/MinerU-4_models_torch/tree/2b3afb86f4d23fa623f6b2f4b2279ed4ef93a89d)：OpenDataLab，包含公式原权重及部分表格/印章模型来源。

模型原始路径、转换方式、固定修订及许可元数据证据见 `qa/document-service/model-manifest.json`、`model-provenance.json`、`model-source-licenses.json`。Python 第三方组件的许可随其安装包保留，完整版本及摘要见依赖锁文件；审计结论仅表示在核对时未发现已知漏洞，不代表没有漏洞。
