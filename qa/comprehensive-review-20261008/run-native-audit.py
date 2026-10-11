from pathlib import Path
p=Path('scripts/audit_review_native.py').resolve()
s=p.read_text(encoding='utf-8').replace("QA=ROOT/'qa/warnings-review-20261008'", "QA=ROOT/'qa/comprehensive-review-20261008'")
exec(compile(s,str(p),'exec'),{'__file__':str(p)})
