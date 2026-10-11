from pathlib import Path
import re
import hashlib
import json
ROOT=Path(__file__).resolve().parents[1]
res=ROOT/'client/app/src/main/res'
qa=ROOT/'qa/warnings-review-20261008'
# East Asian locales use only 'other'; Arabic and Russian require additional categories.
ru={
 '褌芯泻械薪芯胁':('褌芯泻械薪','褌芯泻械薪邪'), '褕邪谐芯胁':('褕邪谐','褕邪谐邪'), '胁芯锌褉芯褋芯胁':('胁芯锌褉芯褋','胁芯锌褉芯褋邪'),
 '褉械蟹褍谢褜褌邪褌芯胁':('褉械蟹褍谢褜褌邪褌','褉械蟹褍谢褜褌邪褌邪'), '褋芯芯斜褖械薪懈泄':('褋芯芯斜褖械薪懈械','褋芯芯斜褖械薪懈褟'),
 '褑懈褌懈褉芯胁邪薪懈泄':('褑懈褌懈褉芯胁邪薪懈械','褑懈褌懈褉芯胁邪薪懈褟'), '蟹邪锌懈褋械泄':('蟹邪锌懈褋褜','蟹邪锌懈褋懈'),
 '褎邪泄谢芯胁':('褎邪泄谢','褎邪泄谢邪'), '屑芯写械谢械泄':('屑芯写械谢褜','屑芯写械谢懈'),
 '褝谢械屑械薪褌芯胁':('褝谢械屑械薪褌','褝谢械屑械薪褌邪'), '写薪械泄':('写薪褟','写薪械泄'),
}
ar={
 'assistant_page_max_tokens_limit':'丕賱丨丿: %d Tokens',
 'backup_page_reminder_interval_days':'毓丿丿 丕賱兀賷丕賲: %1$d',
 'backup_page_reminder_last_days':'丕賱兀賷丕賲 賲賳匕 丌禺乇 賳爻禺 丕丨鬲賷丕胤賷: %1$d',
 'chain_of_thought_show_more_steps':'禺胤賵丕鬲 廿囟丕賮賷丞: %d',
 'chat_message_tool_ask_questions':'丕胤乇丨 兀爻卅賱丞 (丕賱毓丿丿: %d)',
 'chat_message_tool_search_results_count':'毓丿丿 丕賱賳鬲丕卅噩: %1$d',
 'chat_page_message_count':'毓丿丿 丕賱乇爻丕卅賱: %d',
 'citations_count':'毓丿丿 丕賱丕爻鬲卮賴丕丿丕鬲: %1$d',
 'prompt_page_entries_count_format':'毓丿丿 丕賱賲丿禺賱丕鬲: %d',
 'setting_files_page_clean_older_than_days':'丕賱毓賲乇 亘丕賱兀賷丕賲 兀賰亘乇 賲賳 %1$d',
 'setting_page_chat_storage_desc':'毓丿丿 丕賱賲賱賮丕鬲: %1$d貙 %2$.2f 賲賷噩丕亘丕賷鬲',
 'setting_provider_page_model_count':'毓丿丿 丕賱賳賲丕匕噩: %d',
 'setting_update_reminder_pause_days':'毓丿丿 丕賱兀賷丕賲: %1$d',
 'workspace_detail_extracting':'噩丕乇賺 丕爻鬲禺乇丕噩 丕賱毓賳丕氐乇 (丕賱毓丿丿: %1$d)%2$s',
}
for path in res.glob('values*/strings.xml'):
 locale=path.parent.name
 text=path.read_text(encoding='utf-8')
 def transform(match):
  name=match[1];body=match[2]
  other=re.search(r'<item quantity="other">(.*?)</item>',body,re.S)[1]
  if locale in ('values-zh','values-zh-rTW','values-ja','values-ko-rKR'):
   forms={'other':other}
  elif locale=='values-ru':
   one=few=other
   for word,(single,paucal) in ru.items():
    one=re.sub(r'\b'+word+r'\b',single,one)
    few=re.sub(r'\b'+word+r'\b',paucal,few)
   forms={'one':one,'few':few,'many':other,'other':other}
  elif locale=='values-ar' and name in ar:
   # A count label has no numeral-governed noun inflection; show the exact number in every category.
   forms={q:ar[name] for q in ('zero','one','two','few','many','other')}
   if name=='assistant_page_max_tokens_limit':forms['one']=forms['one'].replace('Tokens','Token')
  else:return match[0]
  return '<plurals name="'+name+'">\n'+ '\n'.join(f'    <item quantity="{q}">{v}</item>' for q,v in forms.items())+'\n  </plurals>'
 text=re.sub(r'<plurals name="([^"]+)">(.*?)</plurals>',transform,text,flags=re.S)
 path.write_text(text,encoding='utf-8',newline='')
# Keep both public layer names, sharing byte-identical artwork through an alias.
icons=[]
for path in res.glob('mipmap-*/ic_launcher_monochrome.png'):
 foreground=path.with_name('ic_launcher_foreground.png')
 assert path.read_bytes()==foreground.read_bytes()
 icons.append({'density':path.parent.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
 path.unlink()
(res/'values/icon_aliases.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n  <item type="mipmap" name="ic_launcher_monochrome">@mipmap/ic_launcher_foreground</item>\n</resources>\n',encoding='utf-8')
# All supported devices (minSdk 26) select the adaptive icon; the same resource key remains defined.
for path in res.glob('mipmap-*/ic_launcher.png'):path.unlink()
(qa/'icon-alias-audit.json').write_text(json.dumps(icons,indent=2),encoding='utf-8')

# Complete removal of unreachable non-null response fallbacks found by the fresh compiler.
for module in ('ai','search'):
 for path in (ROOT/f'client/{module}/src/main/java').rglob('*.kt'):
  t=path.read_text(encoding='utf-8')
  t=t.replace('response.body.string() ?: ""','response.body.string()')
  t=re.sub(r'response\.body\.string\(\) \?: error\("(?:empty body|Failed to get response body)"\)', 'response.body.string()',t)
  path.write_text(t,encoding='utf-8',newline='')
p=ROOT/'client/speech/src/main/java/me/rerere/tts/provider/providers/StepTTSProvider.kt'
t=p.read_text(encoding='utf-8').replace('\n            ?: throw Exception("Step TTS returned empty body")','')
p.write_text(t,encoding='utf-8',newline='')
p=ROOT/'client/app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt'
t=p.read_text(encoding='utf-8').replace('} ?: preferences.remove(SELECTED_TTS_PROVIDER)', '}')
p.write_text(t,encoding='utf-8',newline='')
p=ROOT/'client/app/build.gradle.kts'
t=p.read_text(encoding='utf-8').replace('assets.srcDirs("$projectDir/schemas")','assets.directories.add("$projectDir/schemas")')
p.write_text(t,encoding='utf-8',newline='')
