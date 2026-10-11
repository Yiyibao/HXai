"""Capture official source metadata; missing declarations remain explicit."""
import concurrent.futures
import json
import urllib.request
from pathlib import Path

root=Path(__file__).resolve().parents[1]/'qa/document-service'
manifest=json.loads((root/'model-manifest.json').read_text())
sources=sorted({(item['source']['repo'],item['source']['revision']) for item in manifest['files']})


def inspect(source):
    repo,revision=source
    with urllib.request.urlopen(f'https://huggingface.co/api/models/{repo}/revision/{revision}',timeout=30) as response:
        metadata=json.load(response)
    declaration=metadata.get('cardData',{}).get('license')
    try:
        with urllib.request.urlopen(f'https://huggingface.co/{repo}/raw/{revision}/README.md',timeout=30) as response:
            readme=response.read().decode()
        relevant=[line for line in readme.splitlines() if 'licen' in line.lower() or '许可' in line][:8]
    except Exception:
        relevant=[]
    return {'repository':repo,'revision':revision,'licenseMetadata':declaration,'readmeLicenseLines':relevant,
            'url':f'https://huggingface.co/{repo}/tree/{revision}',
            'status':'declared' if declaration else 'needs_upstream_attribution_review'}


with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
    results=list(executor.map(inspect,sources))
(root/'model-source-licenses.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
for result in results:
    print(result['repository'],result['licenseMetadata'],result['status'])
