from pathlib import Path
import subprocess
root=Path.cwd(); adb=root/'.local/android-sdk/platform-tools/adb.exe'; qa=root/'qa/intelligent-chat-upgrade-20261010'
for name in ['smart-ui-expanded.png','smart-ui-inline.png','reasoning-collapsed.png','reasoning-expanded.png','chat-upgrade-live-context.json']:
 result=subprocess.run([str(adb),'exec-out','run-as','org.hxsi.agent.debug','cat','cache/'+name],capture_output=True,check=True)
 (qa/name).write_bytes(result.stdout)
print('Captured 4 native Flutter screenshots and real-model result.')
