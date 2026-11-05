# HANDOFF

云托管密钥仍是 `ABSENT`。`CloudFrontend` 的 `cloud.state` 固定返回 `crossAppEnabled=false`。没有新增托管密钥存储，也没有打开跨 App。

JVM `CloudBoundaryTest` 1 通过。跨主机网络、断连、丢 ACK 和生产部署未做。
