"""Read-only proof for an explicitly selected synthetic recovery conversation; no credentials or raw history."""
import argparse
import hashlib
import io
import json
import sqlite3
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from contextlib import closing
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
PACKAGE = 'org.mathstudy.agent.debug'


def adb(*args):
    return subprocess.check_output([str(ADB), '-s', 'emulator-5554', *args], timeout=40)


def inspect(conversation_id):
    data = adb('exec-out', 'run-as', PACKAGE, 'tar', 'cf', '-', 'databases/rikka_hub', 'databases/rikka_hub-wal')
    with tempfile.TemporaryDirectory() as folder:
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for member in archive.getmembers():
                if member.name in ('databases/rikka_hub', 'databases/rikka_hub-wal'):
                    Path(folder, Path(member.name).name).write_bytes(archive.extractfile(member).read())
        with closing(sqlite3.connect(str(Path(folder, 'rikka_hub')))) as conn:
            title = conn.execute('select title from ConversationEntity where id=?', (conversation_id,)).fetchone()
            if not title or title[0] != '[QA] 中断恢复界面验证':
                raise RuntimeError('Refusing evidence extraction from a non-QA conversation')
            rows = conn.execute('select messages, select_index from message_node where conversation_id=? order by node_index', (conversation_id,)).fetchall()
            messages = []
            for raw, selected in rows:
                message = json.loads(raw)[selected]
                text = ''.join(p.get('text', '') for p in message.get('parts', []))
                if not text.startswith('[QA 合成测试]'):
                    raise RuntimeError('Refusing non-synthetic message content')
                messages.append(dict(id=message['id'], role=message['role'], generation_state=message.get('generationState'),
                    characters=len(text), sha256=hashlib.sha256(text.encode()).hexdigest()))
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/s1-network-recovery.xml')
    if b'dumped' not in result:
        raise RuntimeError('Fresh UI hierarchy unavailable')
    tree = ET.fromstring(adb('shell', 'cat', '/sdcard/s1-network-recovery.xml'))
    nodes = list(tree.iter('node'))
    texts = [n.get('text', '') for n in nodes]
    visible = dict(partial_reply=any('[QA 合成测试] 已收到的讲解' in t for t in texts),
        interruption_prompt=any('连接或任务中断，已保留收到的内容' in t for t in texts), retry_button='重新生成' in texts)
    if not all(visible.values()):
        raise RuntimeError('QA recovery page was not fully visible')
    screenshot = ROOT / 'qa/s1/network-recovery-20261006.png'
    screenshot.write_bytes(adb('exec-out', 'screencap', '-p'))
    return dict(conversation_id=conversation_id, source='Synthetic Room restart fixture, inspected after force-stop and cold start',
        selected_messages=messages, ui_visible=visible,
        stop_controls=sum(n.get('content-desc') == 'Stop' for n in nodes),
        progress_bars=sum(n.get('class') == 'android.widget.ProgressBar' for n in nodes),
        screenshot=screenshot.name, screenshot_sha256=hashlib.sha256(screenshot.read_bytes()).hexdigest(),
        limitations=['This fixture cold start is not a process kill during a live HTTP stream'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--conversation-id', required=True)
    args = parser.parse_args()
    output = json.dumps(inspect(args.conversation_id), ensure_ascii=False, indent=2) + '\n'
    (ROOT / 'qa/s1/network-recovery-ui-20261006.json').write_text(output, encoding='utf-8')
    print(output)
