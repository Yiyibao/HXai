from pathlib import Path
p=Path('client/app/src/test/java/me/rerere/rikkahub/feature/studychat/ChatContextBudgetTest.kt')
s=p.read_text(encoding="utf-8").replace('repeat(20000)','repeat(30000)');p.write_text(s,encoding='utf-8')

