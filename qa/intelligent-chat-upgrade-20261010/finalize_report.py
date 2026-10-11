from pathlib import Path
import json, re, hashlib
r=Path.cwd();q=r/'qa/intelligent-chat-upgrade-20261010'
assert '+218: All tests passed!' in (q/'final-flutter-tests.log').read_text(encoding='utf-8-sig')
a=(q/'final-analysis.log').read_text(encoding='utf-8-sig')
assert 'error -' not in a and 'warning -' not in a
arm=json.loads((q/'arm64-apk-verification.json').read_text(encoding='utf-8'))
assert hashlib.sha256((q/'MathStudyAgent-chat-upgrade-arm64-debug.apk').read_bytes()).hexdigest()==arm['apk_sha256']
p=q/'report.md';s=p.read_text(encoding='utf-8')
s=re.sub(r'ARM64 SHA-256：`[a-f0-9]+`', f"ARM64 SHA-256：`{arm['apk_sha256']}`", s)
s=s.replace('(closure-final-flutter-tests.log)','(final-flutter-tests.log)').replace('(closure-analyze.log)','(final-analysis.log)')
s=s.replace('| 摘要超时可能被误归类为主动取消 |','| 失败工具也可能出现“成果修改”建议 | 仅对已完成且包含真实成果定义/版本的结果显示入口；全量 Flutter 218 项复验通过 |\n| 摘要超时可能被误归类为主动取消 |')
s=s.replace('## 交付与可视证据','最终交付包另做一次实际界面保存/返回/续聊复验，见 [最终包 UI 日志](final-package-ui.log)。\n\n## 交付与可视证据')
p.write_text(s,encoding='utf-8')
print('Final package hash and latest verification logs recorded.')
