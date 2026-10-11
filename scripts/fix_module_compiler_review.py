"""Remove only operations proven redundant by the freshly executed compiler."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
log=ROOT/'qa/warnings-review-20261008/full-build-tests-lint.log'
files={}
for line in log.read_text(encoding='utf-8-sig').splitlines():
 m=re.match(r'w: file:///(.*?):(\d+):(\d+) (.*)',line)
 if not m:continue
 path=Path(m[1]);number=int(m[2])-1;column=int(m[3])-1;message=m[4]
 if '/app/' in m[1]:continue
 if path not in files:files[path]=path.read_text(encoding='utf-8').splitlines(keepends=True)
 text=files[path][number]
 if message.startswith('Unnecessary safe call'):
  options=[x.start() for x in re.finditer(r'\?\.',text)]
  assert options,(path,text)
  pos=min(options,key=lambda p:abs(p-column));text=text[:pos]+'.'+text[pos+2:]
 elif message.startswith('Unnecessary non-null assertion'):
  options=[x.start() for x in re.finditer(r'!!',text)]
  pos=min(options,key=lambda p:abs(p-column));text=text[:pos]+text[pos+2:]
 elif message=='Expression is unused.' and text.strip()=='Unit':
  text='\n'
 elif "'when' is exhaustive" in message and text.strip().startswith('else ->'):
  text='\n'
 files[path][number]=text
for path,lines in files.items():path.write_text(''.join(lines),encoding='utf-8',newline='')
p=ROOT/'client/common/src/main/java/me/rerere/common/http/AcceptLang.kt'
t=p.read_text(encoding='utf-8')
t=t.replace('''            return if (android.os.Build.VERSION.SDK_INT >= 24) {
                val list = cfg.locales
                (0 until list.size()).map { idx -> list[idx] }
            } else {
                listOf(cfg.locale)
            }''','''            val list = cfg.locales
            return (0 until list.size()).map { idx -> list[idx] }''')
p.write_text(t,encoding='utf-8',newline='')
