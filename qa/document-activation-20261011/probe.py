import hashlib, json, time, urllib.request, uuid
from pathlib import Path

root = Path(__file__).resolve().parents[2]
credential = json.loads((root / '.local/document-service/credentials/learner-1.json').read_text(encoding='utf-8-sig'))
base = credential['baseUrl'].rstrip('/')
auth = {'Authorization': 'Bearer ' + credential['token']}

def request(path, method='GET', body=None, headers=None):
    req = urllib.request.Request(base + path, data=body, method=method, headers={**auth, **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)

reports = []
for filename, end in [('math-scan.png', 1), ('math-scan-five.pdf', 5)]:
    data = (root / 'qa/document-service/fixtures' / filename).read_bytes()
    ext = filename.rsplit('.', 1)[1]
    receipt = request(f'/api/v1/jobs?extension={ext}&start_page=1&end_page={end}', 'POST', data,
                      {'Idempotency-Key': str(uuid.uuid4()), 'X-Source-Sha256': hashlib.sha256(data).hexdigest(), 'Content-Type': 'application/octet-stream'})
    task = receipt['taskId']
    began = time.monotonic()
    try:
        while time.monotonic() - began < 240:
            status = request('/api/v1/jobs/' + task)
            if status['state'] not in ('QUEUED', 'RUNNING'):
                break
            time.sleep(2)
        report = {'fixture': filename, 'status': status, 'elapsedSeconds': round(time.monotonic() - began, 1)}
        reports.append(report)
        print(json.dumps(report), flush=True)
    finally:
        request('/api/v1/jobs/' + task, 'DELETE')
(Path(__file__).parent / 'server-probe.json').write_text(json.dumps(reports, indent=2), encoding='utf-8')
