"""Build acceptance evidence from redacted snapshots and allowlisted instrumentation fields."""
import hashlib, json, re
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).parent
def read(name): return json.loads((HERE/name).read_text(encoding='utf-8-sig'))
def phase(name):
    raw=(ROOT/'.local'/name).read_text(encoding='utf-8-sig')
    fields=dict(re.findall(r'^INSTRUMENTATION_STATUS: (qa_\w+)=(.*)$',raw,re.M))
    return dict(fields=fields,junit_pass=bool(re.search(r'OK \(1 test\)',raw)), host_killed='Process crashed' in raw)
stopped=read('real-tools-fixed-stopped-20261006.json')
recovered=read('real-tools-fixed-recovered-20261006.json')
resumed=read('real-tools-fixed-resumed-20261006.json')
reopened=read('real-tools-fixed-reopened-20261006.json')
assert [m['id'] for m in stopped['messages']]==[m['id'] for m in resumed['messages']]
a,b=stopped['messages'][-1]['tools'],resumed['messages'][-1]['tools']
assert a[0]['output_sha256']==b[0]['output_sha256']
assert [t['call_id'] for t in a]==[t['call_id'] for t in b]
assert recovered['messages'][-1]['tools'][1]['execution_state']=='INTERRUPTED'
assert b[1]['execution_state']=='SKIPPED_UNKNOWN' and b[1]['unknown']
assert resumed==reopened
counts=read('real-tools-fixed-log-counts-20261006.json')
assert counts==dict(tool_execution_events=0,responses_requests=1,fatal_exceptions=0)
kill=read('real-kill-recovered-20261006.json'); sent=read('real-kill-ui-resumed-20261006.json')
assert kill['queue']['paused'] and len(kill['queue']['inputs'])==1
assert kill['messages']==sent['messages'][:2]
assert kill['queue']['inputs'][0]['id']==sent['messages'][2]['id']
assert not sent['queue']['inputs'] and sent['messages'][-1]['state']=='COMPLETED'
approval=read('real-approval-completed-20261006.json')
assert len(approval['messages'])==2 and approval['messages'][-1]['state']=='COMPLETED'
jvm={}
for module in ('app','ai'):
    reports=[ET.parse(p).getroot().attrib for p in (ROOT/'client'/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    jvm[module]={key:sum(int(r[key]) for r in reports) for key in ('tests','failures','errors')}
    assert jvm[module]['failures']==jvm[module]['errors']==0
phases={key:phase(file) for key,file in dict(airplane='s1-real-network.log',switch='s1-real-switch.log',kill='s1-real-kill.log',
    verify='s1-real-verify.log',approval='s1-real-approval.log',tools_before_fix='s1-real-tools.log',tools_after_fix='s1-real-tools-fixed.log').items()}
assert all(phases[p]['junit_pass'] for p in ('airplane','switch','verify','approval'))
assert phases['kill']['fields']['qa_pid']!=phases['verify']['fields']['qa_pid']
result=dict(date='2026-10-06',environment='AOSP API 34 x86_64 emulator; existing Responses provider; gpt-6.1-sol / medium',
    status='PASS for listed subcases; complete S1 remains incomplete',
    pre_fix_installed_apk_sha256='cb997b80e57c8701dc6f487a128f675dda726600912f50ddbf84b4ecb100bc68',
    final_apk_sha256=hashlib.sha256((ROOT/'client/app/build/outputs/apk/debug/app-x86_64-debug.apk').read_bytes()).hexdigest(),
    phases=phases,jvm=jvm,android_fault_tests=16,process_recovery_phases=2,post_fix_skip=counts,
    assertions=dict(stable_message_and_call_ids=True,completed_tool_result_preserved=True,queue_input_submitted_once=True,
        skipped_unknown_state_survives_cold_restart=True,approval_answer_continues_real_model=True),
    repaired_failure='Unknown skipped tool was incorrectly marked COMPLETED; now SKIPPED_UNKNOWN with visible label',
    limitations=['Emulator Wi-Fi/cellular routes share host egress; not independent public links',
        'Network no-replay observation window is 3 seconds plus paused queue and no generation job',
        'No external write effects; second no-side-effect tool held at execution entry',
        'Not exhaustive kill timing/background/branch races or real generic approval deny/late-response combinations',
        'Production ten-minute timeout and full credential/log/backup gate not covered'])
artifacts=[p for p in HERE.glob('real-*.json') if p.name!='real-responses-recovery-20261006.json']+list(HERE.glob('real-*.png'))
result['artifact_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts}
(HERE/'real-responses-recovery-20261006.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(status=result['status'],jvm=jvm,android_fault_tests=16,process_recovery_phases=2,post_fix_skip=counts),ensure_ascii=False))
