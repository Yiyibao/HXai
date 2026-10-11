#!/bin/sh
set -eu
install -d -m 0777 /var/lib/mathdoc-synthetic/input /var/lib/mathdoc-synthetic/output
cp /tmp/math-scan.png /var/lib/mathdoc-synthetic/input/source.png
python3 - <<'PY'
import hashlib,json
from pathlib import Path
p=Path('/var/lib/mathdoc-synthetic/input')
(p/'request.json').write_text(json.dumps({'extension':'png','sourceSha256':hashlib.sha256((p/'source.png').read_bytes()).hexdigest(),'pages':[1,1],'tier':'basic'}))
PY
docker run --rm --name mathdoc-synthetic-debug --network none --read-only --cap-drop ALL --security-opt no-new-privileges --memory 4g --memory-swap 4g --cpus 4 --pids-limit 192 --tmpfs /tmp:rw,noexec,nosuid,size=384m,mode=1777 --tmpfs /models/.locks:rw,noexec,nosuid,size=1m,mode=1777 --mount type=bind,src=/var/lib/mathdoc-synthetic/input,dst=/input,readonly --mount type=bind,src=/var/lib/mathdoc-synthetic/output,dst=/output --mount type=bind,src=/var/lib/mathdoc-models,dst=/models,readonly --entrypoint python mathdoc-worker:4.0.10-1 -c 'from worker.run import run; run()'
