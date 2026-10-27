"""Package only this batch's incremental bytes and prove patch replay. No Git root mutation."""
from pathlib import Path
import hashlib, json, shutil, subprocess, sys, tempfile, zipfile
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def freeze():
    target=OUT/'tested-apks'
    target.mkdir(exist_ok=False)
    paths=['client/app/build/outputs/apk/debug/app-x86_64-debug.apk','client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    hashes={}
    for path in paths:
        p=ROOT/path;q=target/p.name
        shutil.copyfile(p,q)
        assert sha(p)==sha(q)
        with zipfile.ZipFile(q) as z:assert len(z.namelist())==len(set(z.namelist())) and z.testzip() is None
        hashes[str(q.relative_to(ROOT))]=sha(q)
    (OUT/'QA_APKS.json').write_text(json.dumps(hashes,indent=2),encoding='utf-8')
def finalize():
    old=json.loads((OUT/'SOURCE_BEFORE.json').read_text(encoding='utf-8'))
    prefixes=['client/app/src','client/ai/src','client/flutter_ui/lib','client/flutter_ui/pigeons','client/flutter_ui/test','server/skill-sandbox']
    files={p.relative_to(ROOT).as_posix() for prefix in prefixes for p in (ROOT/prefix).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    extras=['README.md','BUILD_AND_TEST.md','docs/pro-development-baseline-20261012.md','docs/a04-oauth-a05-trusted-cost-v1.md',
            'scripts/a045_worklog.py','scripts/wire_a05_cost.py','scripts/verify_a045_persistence.py','scripts/verify_pro_linux_copy_a045.py','scripts/finalize_a045_delivery.py']
    files.update(extras)
    for path in extras:
        before=OUT/'before'/path
        if before.exists():old[path]=sha(before)
    # Other user-owned math work shares this checkout. Never attribute or package its changes.
    own_agent=set('AgentCostLedger AgentScheduler AgentTaskStore AgentUsageExport AgentUsageGroups AgentUsageLedger AgentUsageSnapshot ExtensionFrontend ExtensionInstallationStore ExtensionMcpRuntime ExtensionMcpTransport ExtensionOAuth ExtensionOAuthCallbackActivity ExtensionPolicy TrustedCost'.split())
    own_tests=set('AgentAuthorizationPlanTest AgentNewFeatureAdversarialTest AgentSchema6MigrationTest AgentTaskRuntimeTest AgentUsageNotificationScaleTest ExtensionOAuthRuntimeTest ExtensionRuntimeTest OAuthCostPersistenceProbe TaskResumeReceiptRuntimeTest TrustedCostBaselineTest TrustedCostRuntimeTest TrustedCostTest'.split())
    allowed=set(extras)|{
        'client/app/src/main/AndroidManifest.xml',
        'client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
        'client/app/src/main/java/me/rerere/rikkahub/flutter/generated/StudyHostApi.g.kt',
        'client/ai/src/main/java/me/rerere/ai/core/Usage.kt',
        'client/ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt',
        'client/ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsStreamDecoder.kt',
        'client/ai/src/test/java/me/rerere/ai/core/CostUsageTest.kt',
        'client/flutter_ui/lib/bridge/study_host_api.g.dart',
        'client/flutter_ui/lib/pages/extensions_page.dart',
        'client/flutter_ui/lib/widgets/agent_usage_view.dart',
        'client/flutter_ui/pigeons/study_host_api.dart',
        'client/flutter_ui/test/agent_cost_view_test.dart',
        'client/flutter_ui/test/extensions_page_test.dart',
    }
    allowed.update(p for p in files if (p.startswith('client/app/src/main/java/me/rerere/rikkahub/feature/agent/') and Path(p).stem in own_agent) or
        (p.startswith('client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/') and Path(p).stem in own_tests) or
        (p=='client/app/src/test/java/me/rerere/rikkahub/feature/agent/TrustedCostTest.kt'))
    all_changed=[p for p in sorted(files|old.keys()) if ((sha(ROOT/p) if (ROOT/p).exists() else None)!=old.get(p))]
    changed=[p for p in all_changed if p in allowed]
    (OUT/'EXTERNAL_DELTAS.json').write_text(json.dumps({p:{'before':old.get(p),'current':sha(ROOT/p) if (ROOT/p).exists() else None} for p in all_changed if p not in allowed},indent=2),encoding='utf-8')
    final={p:sha(ROOT/p) for p in sorted(files) if (ROOT/p).exists()}
    (OUT/'SOURCE_FINAL.json').write_text(json.dumps(final,indent=2),encoding='utf-8')
    (OUT/'CHANGED_FILES.json').write_text(json.dumps({p:{'before':old.get(p),'after':final.get(p)} for p in changed},indent=2),encoding='utf-8')
    with tempfile.TemporaryDirectory(prefix='hxai-a045-replay-') as temp:
        temp=Path(temp);before=temp/'before';after=temp/'after';replay=temp/'replay'
        assert temp.resolve().parent==Path(tempfile.gettempdir()).resolve() and temp.name.startswith('hxai-a045-replay-')
        before.mkdir();after.mkdir();replay.mkdir()
        for path in changed:
            if old.get(path):
                original=OUT/'before'/path
                assert original.exists() and sha(original)==old[path],path
                for base in [before,replay]:
                    target=base/path;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(original,target)
            if path in final:
                target=after/path;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,target)
                delivery=OUT/'SOL_IMPLEMENTATION'/path;delivery.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,delivery)
        diff=subprocess.run(['git','-c','core.autocrlf=false','diff','--no-index','--binary','--no-textconv','--no-ext-diff','--','before','after'],cwd=temp,capture_output=True)
        assert diff.returncode==1,diff.stderr
        # Normalize path metadata only. Replacing globally corrupts literal paths
        # inside the patch producer's own added source (caught by byte replay).
        lines=[];in_hunk=False
        for line in diff.stdout.splitlines(keepends=True):
            if line.startswith(b'diff --git '):in_hunk=False
            if line.startswith(b'@@'):in_hunk=True
            if not in_hunk and line.startswith((b'diff --git ',b'--- ',b'+++ ',b'Binary files ')):
                line=line.replace(b'a/before/',b'a/').replace(b'b/after/',b'b/').replace(b'a/after/',b'a/').replace(b'b/before/',b'b/')
            lines.append(line)
        patch=b''.join(lines)
        (OUT/'FIXES.patch').write_bytes(patch)
        (OUT/'logs'/'patch-diff-stderr.log').write_bytes(diff.stderr)
        for check in [True,False]:
            command=['git','-c','core.autocrlf=false','apply']+(['--check'] if check else [])+[str(OUT/'FIXES.patch')]
            r=subprocess.run(command,cwd=replay,capture_output=True)
            (OUT/'logs'/('patch-check.log' if check else 'patch-apply.log')).write_bytes(r.stdout+r.stderr)
            assert r.returncode==0,r.stderr
        actual={p.relative_to(replay).as_posix():sha(p) for p in replay.rglob('*') if p.is_file()}
        expected={p:final[p] for p in changed if p in final}
        mismatches={p:{'expected':expected.get(p),'actual':actual.get(p)} for p in expected.keys()|actual.keys() if expected.get(p)!=actual.get(p)}
        (OUT/'logs'/'patch-mismatches.json').write_text(json.dumps(mismatches,indent=2),encoding='utf-8')
        assert actual==expected,'Byte replay differs'
        (OUT/'PATCH_REPLAY.json').write_text(json.dumps(dict(status='PASS',files=len(changed),patch_sha256=sha(OUT/'FIXES.patch'),target='exact inherited dirty-tree preimages, not old Git HEAD',after=actual),indent=2),encoding='utf-8')
    results=[]
    for p in sorted((OUT/'logs').glob('*.json')):
        if p.name.endswith('.SOURCE.json'):continue
        data=json.loads(p.read_text(encoding='utf-8'))
        if 'exit_code' in data:results.append(data)
    counts={}
    for module in ['app','ai']:
        paths=list((ROOT/f'client/{module}/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        roots=[ET.parse(p).getroot() for p in paths]
        counts[module]={k:sum(int(r.attrib.get(k,0)) for r in roots) for k in ['tests','failures','errors','skipped']}
        archive=OUT/'jvm-results'/module;archive.mkdir(parents=True,exist_ok=True)
        for p in paths:shutil.copyfile(p,archive/p.name)
    (OUT/'TEST_RESULTS.json').write_text(json.dumps(dict(branch=subprocess.check_output(['git','branch','--show-current'],cwd=ROOT).decode().strip(),head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),scope='local synthetic OAuth + exact request costs; independent/full review not evaluated',jvm=counts,runs=results),indent=2),encoding='utf-8')
    for name,args in [('branch',['branch','--show-current']),('head',['rev-parse','HEAD']),('status',['status','--porcelain=v1']),('diff',['diff','--binary','--no-textconv','--no-ext-diff'])]:
        (OUT/'logs'/f'final-{name}.log').write_bytes(subprocess.check_output(['git',*args],cwd=ROOT))
    print(json.dumps(dict(changed_files=len(changed),patch_sha256=sha(OUT/'FIXES.patch'),jvm=counts)))
if __name__=='__main__':
    if sys.argv[1]=='freeze':freeze()
    else:finalize()
