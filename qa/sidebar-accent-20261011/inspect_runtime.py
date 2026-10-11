from pathlib import Path
import subprocess, xml.etree.ElementTree as ET, sys, re, time
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[2]
adb=root/'.local/android-sdk/platform-tools/adb.exe'
out=Path(__file__).parent

def run(*args):
    return subprocess.check_output([str(adb), '-s','emulator-5554', *args])

def dump(name):
    for attempt in range(3):
        result = run('shell','uiautomator','dump','/sdcard/sidebar-accent-ui.xml')
        if b'dumped' in result:
            break
        time.sleep(1)
    else:
        raise RuntimeError('No fresh accessibility hierarchy')
    xml=run('shell','cat','/sdcard/sidebar-accent-ui.xml')
    (out/f'{name}.xml').write_bytes(xml)
    return ET.fromstring(xml)

if sys.argv[1]=='snapshot':
    name=sys.argv[2]
    tree=dump(name)
    (out/f'{name}.png').write_bytes(run('exec-out','screencap','-p'))
    for n in tree.iter('node'):
        label=n.get('text') or n.get('content-desc')
        if label: print(label, n.get('bounds'))
elif sys.argv[1]=='tap':
    label=sys.argv[2]
    tree=dump('before-tap')
    nodes=[n for n in tree.iter('node') if label in [n.get('text'),n.get('content-desc')]]
    assert len(nodes)==1, f'Expected unique {label}: found {len(nodes)}'
    bounds=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))))
    run('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
