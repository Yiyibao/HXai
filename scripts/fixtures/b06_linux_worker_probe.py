"""Synthetic qualification image entrypoint. Never copied into the production image."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import resource
import socket
import time

OUTPUT = Path('/output')
MODE = os.environ['B06_PROBE_MODE']


def isolation():
    request = json.loads(Path('/input/request.json').read_text())
    source = Path('/input/source.' + request['extension'])
    readonly = {}
    for label, path, flags in [('input', source, os.O_WRONLY),
                               ('root', Path('/app/.b06-readonly-probe'), os.O_WRONLY | os.O_CREAT | os.O_EXCL)]:
        try:
            descriptor = os.open(path, flags, 0o600)
        except OSError as error:
            readonly[label] = error.errno
        else:
            os.close(descriptor)
            raise AssertionError(label + ' unexpectedly writable')
    with socket.socket() as connection:
        connection.settimeout(2)
        network_error = connection.connect_ex(('192.0.2.1', 443))
    status = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
    proof = dict(uid=os.getuid(), gid=os.getgid(), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                 readonly_errors=readonly, network_errno=network_error,
                 interfaces=sorted(os.listdir('/sys/class/net')),
                 no_new_privileges=status['NoNewPrivs'].strip(), effective_capabilities=status['CapEff'].strip(),
                 nofile_limit=list(resource.getrlimit(resource.RLIMIT_NOFILE)),
                 host_docker_socket_present=any(Path(path).exists() for path in
                    ['/var/run/docker.sock', '/run/docker.sock', '/run/hxai-b06/docker.sock']))
    assert proof['uid'] == proof['gid'] == 10010
    assert proof['source_sha256'] == request['sourceSha256']
    assert proof['readonly_errors']['input'] == 30 and proof['readonly_errors']['root'] in (13, 30)
    assert proof['network_errno'] != 0 and proof['interfaces'] == ['lo']
    assert proof['no_new_privileges'] == '1' and int(proof['effective_capabilities'], 16) == 0
    assert proof['nofile_limit'] == [256, 256] and not proof['host_docker_socket_present']
    return proof


def checkpoint(phase, proof):
    staged = OUTPUT / 'b06-checkpoint.pending'
    staged.write_text(json.dumps(dict(phase=phase, isolation=proof)))
    staged.replace(OUTPUT / 'b06-checkpoint.json')
    deadline = time.monotonic() + 180
    while not (OUTPUT / 'b06-release').exists():
        if time.monotonic() >= deadline:
            raise RuntimeError('synthetic_checkpoint_not_released')
        time.sleep(0.05)


def main():
    import office_worker.engine as engine
    import office_worker.run as runner
    proof = isolation()
    if MODE == 'hold-open':
        original = engine.office_document

        @contextmanager
        def held(*args, **kwargs):
            with original(*args, **kwargs) as document:
                checkpoint('real_uno_open', proof)
                yield document
        engine.office_document = held
        runner.office_document = held
    runner.main()
    if MODE == 'hold-saved':
        checkpoint('real_worker_output_saved_before_exit', proof)
    elif MODE in ('witness', 'missing', 'renderer'):
        manifest_path = OUTPUT / 'manifest.json'
        manifest = json.loads(manifest_path.read_text())
        witness_path = OUTPUT / 'calculation.json'
        if MODE == 'witness':
            witness = json.loads(witness_path.read_text())
            witness['cells'][0]['number'] = 999
            witness_path.write_text(json.dumps(witness))
            manifest['assets'][1].update(size=witness_path.stat().st_size,
                sha256=hashlib.sha256(witness_path.read_bytes()).hexdigest())
        elif MODE == 'missing':
            witness_path.unlink()
        else:
            manifest['rendererVersion'] = 'synthetic-forged-renderer'
        manifest_path.write_text(json.dumps(manifest))
    elif MODE != 'hold-open':
        raise ValueError('Unknown qualification fault mode')


if __name__ == '__main__':
    main()
