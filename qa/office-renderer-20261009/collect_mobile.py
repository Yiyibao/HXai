from pathlib import Path
import subprocess,json
root=Path(__file__).resolve().parents[2]; out=Path(__file__).parent/'mobile-output'; out.mkdir(exist_ok=True)
adb=str(root/'.local/android-sdk/platform-tools/adb.exe')
names=subprocess.run([adb,'exec-out','run-as','org.mathstudy.agent.debug','ls','cache/office-live-output'],capture_output=True,check=True).stdout.decode().split()
for name in ['conversation.json','tasks.json','result.json','docx.pdf','xlsx.pdf','pptx.pdf']:
    if name not in names:
        (out/name).unlink(missing_ok=True); continue
    result=subprocess.run([adb,'exec-out','run-as','org.mathstudy.agent.debug','cat','cache/office-live-output/'+name],capture_output=True)
    if result.returncode==0: (out/name).write_bytes(result.stdout)
if (out/'tasks.json').exists():
    tasks=json.loads((out/'tasks.json').read_text(encoding='utf-8')); print('tasks',[(t['extension'],t.get('state'),t.get('downloaded',False)) for t in tasks])
if (out/'conversation.json').exists():
    conv=json.loads((out/'conversation.json').read_text(encoding='utf-8'))
    for node in conv.get('messageNodes',[])[-3:]:
        for message in node.get('messages',[]):
            print('message',message.get('role'),[(p.get('type'),p.get('toolName'),p.get('text','')[:240]) for p in message.get('parts',[])])
if (out/'result.json').exists(): print((out/'result.json').read_text(encoding='utf-8'))
