from pathlib import Path
r=Path.cwd(); p=r/'docs/intelligent-chat-upgrade-plan-20261010.md'
s=p.read_text(encoding='utf-8').replace('日期：2026-10-10。状态：建议方案，尚未实施。','日期：2026-10-10。状态：四阶段实现已完成。原设计建议保留供对照，具体交付、修复和验证范围见 [验收报告](../qa/intelligent-chat-upgrade-20261010/report.md) 与 [实现/使用说明](intelligent-chat-upgrade-implementation-20261010.md)。',1); p.write_text(s,encoding='utf-8')
