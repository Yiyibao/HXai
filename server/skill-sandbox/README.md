# 独立 Skill Python 沙箱

使用与权限合同见 [A04 协议](../../docs/a04-general-mcp-skills-protocol-v1.md)。此服务只执行显式批准的脚本和固定镜像，不运行 Agent，也不会下载依赖。

预装 Linux Docker、支持 cgroup 限额的私有 daemon、无秘密的已审核固定镜像后，由服务所有者确认脚本和镜像 SHA。准备 owner-only token 文件，使用专用运行目录：

```sh
python sandbox_service.py --root /srv/hxai-skill-instance/data \
  --docker-host unix:///run/hxai-skill/docker.sock \
  --image sha256:REPLACE_WITH_APPROVED_IMAGE_SHA \
  --script-id sum --script /srv/hxai-skill-instance/sum.py \
  --script-sha256 REPLACE_WITH_APPROVED_SCRIPT_SHA \
  --token-file /srv/hxai-skill-instance/token --port 18891
```

默认只绑定127.0.0.1。远程须由所有者在明确环境配置 HTTPS；App 不接受普通 HTTP。先在前台发现 `run_python`，确认 code/image pin 和参数范围，安装并启用；Chat 实际调用仍需输入绑定审批。模型不提供 run_id，也不会看到 token。网络或 ACK 未知时只查询 read_result，不能重复调用或清库。

自带示例读取 JSON `{"values":[1,2,3]}`，输出 `{"sum":6}`。stdout 必须是一份严格 JSON；图片/Office 等二进制文件不在本版脚本输出合同内。任意外部动作不使用只读复读合同；缺专属 verifier 时保持不开放。

本地协议测试：`python -m unittest -v test_sandbox_service.SandboxContractTest`。实际容器资格使用仓库 `scripts/verify_a04_sandbox.py`，只在已核归属的合成私有 Linux 环境运行。Windows 单测不代表 Linux 资源隔离；Docker参数必须与实际 cgroup/容器 inspect 对照。
