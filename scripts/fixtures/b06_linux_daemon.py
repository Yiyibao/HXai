"""Start only the explicitly owned qualification daemon in a delegated cgroup.

Docker probes swap support in its own cgroup. The cgroup-v2 root omits
memory.swap.max even when child cgroups support it; never accept unlimited swap.
Only delegate available controllers inside the owned Linux environment; never
alter controller limits, global WSL settings, or user daemons.
"""
import argparse
import json
import os
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ownership', type=Path, required=True)
args = parser.parse_args()
owned = json.loads(args.ownership.read_text())
assert owned == json.loads(Path('/etc/hxai-b06-owned-instance.json').read_text())
assert owned['created_this_batch'] and os.geteuid() == 0
socket = Path(owned['daemon_socket'])
assert not socket.exists(), 'Refusing an existing daemon endpoint'
root = Path('/sys/fs/cgroup')
controllers = {'cpu', 'memory', 'pids'}
assert controllers <= set((root / 'cgroup.controllers').read_text().split())
# A cold WSL boot has no Docker-created cgroup tree. Delegate additively rather
# than relying on state left by a previously started daemon.
missing = controllers - set((root / 'cgroup.subtree_control').read_text().split())
if missing:
    (root / 'cgroup.subtree_control').write_text(' '.join('+' + name for name in sorted(missing)))
cgroup = root / ('hxai-b06-daemon-' + owned['owner_nonce'])
cgroup.mkdir(exist_ok=True)
assert not (cgroup / 'cgroup.procs').read_text().strip(), 'Owned cgroup still populated'
assert (cgroup / 'memory.swap.max').exists(), 'Kernel swap controller unavailable'
(cgroup / 'cgroup.procs').write_text(str(os.getpid()))
assert (cgroup / 'memory.swap.max').read_text().strip() == 'max'
print('Owned daemon cgroup: ' + str(cgroup), flush=True)
socket.parent.mkdir(parents=True, exist_ok=True)
os.execv('/usr/bin/dockerd', ['/usr/bin/dockerd',
    '--host=unix://' + str(socket), '--data-root=' + owned['daemon_data_root'],
    '--exec-root=/run/hxai-b06/exec', '--pidfile=/run/hxai-b06/docker.pid',
    '--label=hxai.b06.owner=' + owned['owner_nonce'], '--bridge=none',
    '--iptables=false', '--ip-forward=false', '--ip-masq=false'])
