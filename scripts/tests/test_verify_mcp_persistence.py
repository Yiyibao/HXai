"""Exercise driver admission: wrong identities cannot reach force-stop/install."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import pytest

spec=importlib.util.spec_from_file_location('mcp_persistence',Path(__file__).parents[1]/'verify_mcp_persistence.py')
driver=importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

class AuthorizedMutationReached(Exception):
    pass

@pytest.mark.parametrize('wrong', ['serial','api','abi','avd','not_emulator','not_booted','unowned',None])
def test_actual_identity_checked_before_any_mutation(tmp_path,monkeypatch,wrong):
    root=Path(__file__).parents[2]
    monkeypatch.setattr(driver,'ROOT',root)
    identity=dict(serial='emulator-5584',avd='owned-api26',api=26,abi='x86_64',created_this_batch=True)
    if wrong=='serial': identity['serial']='emulator-5590'
    if wrong=='unowned': identity['created_this_batch']=False
    ownership=tmp_path/'ownership.json'
    ownership.write_text(json.dumps(identity))
    monkeypatch.setattr(sys,'argv',['driver','--serial','emulator-5584','--run-dir',str(tmp_path/'run'),'--ownership',str(ownership)])
    queries=[]
    def fake_run(command,**kwargs):
        queries.append(command)
        if 'force-stop' in command or 'install' in command:
            assert wrong is None, 'Invalid ownership reached a mutating command'
            raise AuthorizedMutationReached()
        answers={'ro.kernel.qemu':'0' if wrong=='not_emulator' else '1',
            'ro.build.version.sdk':'34' if wrong=='api' else '26',
            'ro.product.cpu.abi':'arm64-v8a' if wrong=='abi' else 'x86_64',
            'sys.boot_completed':'0' if wrong=='not_booted' else '1'}
        if command[-3:]==['emu','avd','name']:
            output='foreign-avd' if wrong=='avd' else 'owned-api26'
        else:
            assert command[-1] in answers,command
            output=answers[command[-1]]
        return subprocess.CompletedProcess(command,0,(output+'\n').encode(),b'')
    monkeypatch.setattr(driver.subprocess,'run',fake_run)
    with pytest.raises(AuthorizedMutationReached if wrong is None else SystemExit):
        driver.main()
    if wrong is not None:
        assert not any('force-stop' in c or 'install' in c for c in queries)
