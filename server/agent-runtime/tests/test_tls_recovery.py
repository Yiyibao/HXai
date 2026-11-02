"""Consumer-visible recovery with a real orphaned child, not a heartbeat fake."""
import subprocess
import sys
import time

from hxai_agent.worker import ProcessSupervisor, process_birth
from test_cloud_device import host, request, worker


def test_restarted_supervisor_observes_preexisting_worker_exit_and_releases_safe_slot(host, tmp_path):
    cloud, phone, _ = host
    task = cloud.create(phone, 'create', request())['taskId']
    token, lease = worker(host, 'old-host-instance')
    gate = tmp_path / 'old-worker-may-exit'
    child = subprocess.Popen([sys.executable, '-c',
        'import pathlib,sys,time\np=pathlib.Path(sys.argv[1])\nwhile not p.exists(): time.sleep(.02)', str(gate)])
    supervisor = ProcessSupervisor(cloud, tmp_path, tmp_path/'vault.key', 'https://localhost:1')
    try:
        with cloud.store.transaction() as db:
            db.execute('UPDATE net_processes SET pid=?,birth=? WHERE instance=?',
                (child.pid, process_birth(child.pid), lease['instance']))
        cloud.wait(token, dict(lease, state='WAITING_INPUT', reason='synthetic_input_checkpoint'))
        supervisor.start()  # A restarted authority does not own this child's Popen handle.
        with cloud.store.transaction() as db:
            assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] == task
            assert db.execute('SELECT state FROM net_processes').fetchone()[0] == 'RUNNING'
        gate.write_text('exit')
        child.wait(timeout=10)
        deadline = time.monotonic()+5
        while time.monotonic() < deadline:
            with cloud.store.transaction() as db:
                slot = db.execute('SELECT task_id FROM execution_slot').fetchone()[0]
            if slot is None: break
            time.sleep(.05)
        assert slot is None, 'OS-confirmed old-worker exit must be observed after supervisor startup too'
        state = cloud.store.get('owner', task)
        assert state['state'] == 'WAITING_INPUT' and state['blocked_on'] == 'WAITING_INPUT'
        with cloud.store.transaction() as db:
            assert db.execute('SELECT state FROM net_processes').fetchone()[0] == 'STOPPED'
    finally:
        gate.touch(exist_ok=True)
        child.wait(timeout=10)
        supervisor.close()
