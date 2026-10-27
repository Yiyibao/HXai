"""Freeze this batch's incremental bytes and replay its patch in fresh local copies."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    originals={p.relative_to(OUT/'before').as_posix():p for p in (OUT/'before').rglob('*') if p.is_file()}
    integrated={p.relative_to(OUT/'integration_preimages').as_posix():p for p in (OUT/'integration_preimages').rglob('*') if p.is_file()}
    files=set(originals)
    for prefix in ['client/app/src/main/java/me/rerere/rikkahub/feature/agent/Extension',
                   'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/Extension']:
        parent,name=prefix.rsplit('/',1)
        files.update(p.relative_to(ROOT).as_posix() for p in (ROOT/parent).glob(name+'*.kt'))
    files.update(p.relative_to(ROOT).as_posix() for p in (ROOT/'server/skill-sandbox').rglob('*') if p.is_file() and p.suffix in {'.py','.md'})
    files.update([
        'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/GeneralMcpChatStoryTest.kt',
        'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/GeneralMcpPersistenceProbe.kt',
        'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/SandboxMcpChatStoryTest.kt',
        'client/app/src/test/java/me/rerere/rikkahub/feature/agent/ExtensionPolicyTest.kt',
        'client/app/src/test/java/me/rerere/rikkahub/data/ai/tools/SkillsPermissionTest.kt',
        'client/flutter_ui/lib/pages/extensions_page.dart','client/flutter_ui/test/extensions_page_test.dart',
        'scripts/a04_worklog.py','scripts/verify_a04_baseline.py','scripts/verify_a04_sandbox.py',
        'scripts/verify_a04_sandbox_chat.py','scripts/verify_general_mcp_persistence.py',
        'scripts/tests/test_a04_instrumentation_discovery.py','scripts/finalize_a04_delivery.py',
        'docs/a04-general-mcp-skills-protocol-v1.md',
    ])
    files=sorted(files)
    before=json.loads((OUT/'SOURCE_BEFORE.json').read_text())
    for name,path in originals.items():
        if name in before and sha(path)!=before[name]:raise ValueError('Wrong preimage: '+name)
    originals.update(integrated)
    after={name:sha(ROOT/name) for name in files}
    (OUT/'SOURCE_FINAL.json').write_text(json.dumps(after,indent=2),encoding='utf-8')
    for name in files:
        target=OUT/'SOL_IMPLEMENTATION'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
    work=ROOT/'.local'/('a04-patch-'+uuid.uuid4().hex);work.mkdir(parents=True)
    replay=ROOT/'.local'/('a04-replay-'+uuid.uuid4().hex);replay.mkdir(parents=True)
    def git(folder,*args):return subprocess.run(['git','-c','core.autocrlf=false','-c','core.eol=lf','-c','core.safecrlf=false','-C',str(folder),*args],capture_output=True,check=True).stdout
    for folder in [work,replay]:
        git(folder,'init')
        for name,source in originals.items():
            target=folder/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    git(work,'add','.')
    git(work,'-c','user.name=Codex local evidence','-c','user.email=local-evidence@example.invalid','-c','commit.gpgsign=false','commit','-m','Exact inherited preimages for incremental replay')
    for name in files:
        target=work/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
    git(work,'add','-N','.')
    patch=git(work,'diff','--no-ext-diff','--no-textconv','--binary')
    (OUT/'FIXES.patch').write_bytes(patch)
    git(replay,'apply','--check',str(OUT/'FIXES.patch'))
    git(replay,'apply',str(OUT/'FIXES.patch'))
    mismatches=[name for name in files if sha(replay/name)!=after[name]]
    if mismatches:raise ValueError(mismatches)
    (OUT/'PATCH_REPLAY.json').write_text(json.dumps(dict(status='PASS',cwd=str(ROOT),patch_sha256=sha(OUT/'FIXES.patch'),files=len(files),work=str(work),replay=str(replay)),indent=2))
    xml_counts={}
    for module in ['app','ai']:
        suites=[ET.parse(p).getroot() for p in (ROOT/f'client/{module}/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
        xml_counts[module]={key:sum(int(s.attrib.get(key,0)) for s in suites) for key in ['tests','failures','errors','skipped']}
        dest=OUT/'junit'/module;dest.mkdir(parents=True,exist_ok=True)
        for p in (ROOT/f'client/{module}/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):shutil.copyfile(p,dest/p.name)
    (OUT/'JVM_COUNTS.json').write_text(json.dumps(xml_counts,indent=2))
    records=[json.loads(p.read_text(encoding='utf-8')) for p in sorted((OUT/'logs').glob('*.json'))]
    native=ROOT/'HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/runs/a04-general-integration-20261022-11'
    built={k.replace('\\','/'):v for k,v in json.loads((native/'SOURCE_INPUTS.json').read_text()).items()}
    products=[n for n in files if n.startswith('client/app/src/main/')]
    binding={n:dict(current=after[n],tested=built.get(n),matches=after[n]==built.get(n)) for n in products}
    if not all(row['matches'] for row in binding.values()):raise ValueError('Product source differs from native suite inputs')
    server=json.loads((OUT/'linux-final-05/SOURCE_INPUTS.json').read_text())
    if server['sandbox_service.py']!=sha(ROOT/'server/skill-sandbox/sandbox_service.py'):raise ValueError('Untested server bytes')
    chat=json.loads((OUT/'sandbox-chat-final-07/SOURCE_INPUTS.json').read_text())
    if chat['sandbox_service.py']!=server['sandbox_service.py']:raise ValueError('Chat used another server')
    flutter={n:after[n] for n in files if n.startswith('client/flutter_ui/lib/')}
    old_flutter=json.loads((OUT/'historical_candidate/initial-replay/SOURCE_FINAL.json').read_text())
    if any(old_flutter.get(n)!=digest for n,digest in flutter.items()):raise ValueError('Flutter changed after frozen AAR qualification')
    (OUT/'SOURCE_EVIDENCE_BINDING.json').write_text(json.dumps(dict(status='PASS',native=str(native),product=binding,
        flutter_current=flutter,flutter_note='Native runner hashes Kotlin sources only; Flutter hashes frozen here, AAR build and app UI tests recorded separately',
        server_linux=server,server_android=chat,
        external_integration='Four shared math wiring files preserved through exact three-way preimages; see EXTERNAL_INTEGRATION.json'),indent=2))
    final_groups=[dict(id=i,status='PASS',evidence=e) for i,e in [
        ('flutter408','logs/flutter-stable-all-11.json'),('analyze','logs/flutter-analyze-stable-10.json'),
        ('native43',str(native.relative_to(ROOT)/'TEST_RUNS.json')),('jvm','JVM_COUNTS.json'),
        ('linux15','linux-final-05/COMMAND.json'),('windows8','logs/sandbox-contract-final-04.json'),
        ('android_actual_sandbox','sandbox-chat-integration-08/RESULT.json'),('ordinary_debug','logs/ordinary-debug-integration-08.json'),
        ('apk_binary_reconciliation','APK_RECONCILIATION.json'),('frozen_runner_regression','logs/discovery-freeze-regression-02.json'),
        ('frozen_runner_actual_chat','logs/frozen-runner-smoke-12.json'),
        *[('process_'+mode,'process-integration2-'+mode+'/TEST_RUNS.json') for mode in ['installation','revoked','old_approval','unknown']]]]
    for group in final_groups:
        location=(ROOT if group['evidence'].startswith('HXai_CHANGE_REVIEW') else OUT)/group['evidence']
        value=json.loads(location.read_text())
        if isinstance(value,list):
            if not value or any(r.get('status')!='PASS' for r in value):raise ValueError('Non-passing final group: '+group['id'])
        elif 'exit_code' in value and value['exit_code']!=0:raise ValueError('Failed final command: '+group['id'])
        elif 'status' in value and value['status']!='PASS':raise ValueError('Failed final result: '+group['id'])
        group['sha256']=sha(location)
    (OUT/'TEST_RESULTS.json').write_text(json.dumps(dict(feature='A04-GENERAL-MCP-SKILLS-20261022',
        branch=subprocess.check_output(['git','branch','--show-current'],cwd=ROOT).decode().strip(),
        head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
        independent_review='NOT_EVALUATED',full_project_review='NOT_EVALUATED',
        patch_replay='PASS',verdict='PASS_SCOPED',final_groups=final_groups,jvm=xml_counts,runs=records),indent=2),encoding='utf-8')
    print(json.dumps(dict(files=len(files),patch_bytes=len(patch),patch_replay='PASS',jvm=xml_counts)))

if __name__=='__main__':main()
