"""Independent live container boundary checks with synthetic markers."""
import json
import os
import pwd
import subprocess
from pathlib import Path

root = Path('/var/lib/mathdoc-isolation')
root.mkdir(exist_ok=True)
for name in ('input', 'output', 'neighbor'):
    (root / name).mkdir(exist_ok=True)
(root / 'neighbor' / 'marker').write_text('SYNTHETIC_NEIGHBOR_ONLY')
uid = pwd.getpwnam('mathdoc').pw_uid
os.chown(root / 'output', uid, uid)
code = r'''
import os,socket,json
from pathlib import Path
checks = {'nonRoot':os.getuid()!=0}
status=Path('/proc/self/status').read_text()
checks['noCapabilities']='CapEff:\t0000000000000000' in status
checks['noNewPrivileges']='NoNewPrivs:\t1' in status
for label,path in [('neighbor','/var/lib/mathdoc-isolation/neighbor/marker'),('hostCredentials','/etc/mathdoc/worker.env'),('dockerSocket','/var/run/docker.sock'),('hostTasks','/var/lib/mathdoc/tasks.sqlite3')]:
    checks[label+'Denied']=not Path(path).exists()
for label,path in [('rootReadOnly','/app/unauthorized'),('inputReadOnly','/input/unauthorized'),('modelsReadOnly','/models/unauthorized')]:
    try: Path(path).write_text('bad'); checks[label]=False
    except OSError: checks[label]=True
Path('/output/allowed').write_text('synthetic')
checks['ownOutputWritable']=True
try: socket.create_connection(('1.1.1.1',443),timeout=1); checks['egressDenied']=False
except OSError: checks['egressDenied']=True
print(json.dumps(checks))
assert all(checks.values()),checks
'''
command = ['docker','run','--rm','--network','none','--read-only','--user',f'{uid}:{uid}','--cap-drop','ALL','--security-opt','no-new-privileges',
           '--pids-limit','64','--memory','256m','--memory-swap','256m','--cpus','1','--entrypoint','python',
           '--mount',f'type=bind,src={root / "input"},dst=/input,readonly','--mount',f'type=bind,src={root / "output"},dst=/output',
           '--mount','type=bind,src=/var/lib/mathdoc-models,dst=/models,readonly','mathdoc-worker:4.0.10-1','-c',code]
result = subprocess.run(command,capture_output=True,text=True,timeout=30)
assert result.returncode == 0, result.stderr
Path('/opt/mathdoc/isolation.json').write_text(result.stdout)
print(result.stdout)
