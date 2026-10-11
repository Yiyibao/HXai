import argparse, hashlib, json, re, subprocess, time, uuid
from pathlib import Path
QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
def adb(*args):
    return subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True, check=True, timeout=600).stdout
def sha(data): return hashlib.sha256(data).hexdigest()
def installed(package):
    apk = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
    return adb('shell', 'sha256sum', apk).decode().split()[0]
def main():
    p=argparse.ArgumentParser(); p.add_argument('--batch', required=True); p.add_argument('--install', action='store_true'); p.add_argument('--core', action='store_true'); p.add_argument('--delivery', action='store_true'); p.add_argument('--skills', action='store_true'); p.add_argument('--method'); p.add_argument('--runner',default='me.rerere.rikkahub.service.ClosureTestRunner'); args=p.parse_args()
    out=QA/args.batch; out.mkdir(exist_ok=False)
    if args.install:
        for name in ('debug/app-x86_64-debug.apk', 'androidTest/debug/app-debug-androidTest.apk'):
            apk=ROOT/'client/app/build/outputs/apk'/name
            (out/apk.name).write_bytes(apk.read_bytes()); (out/(apk.name+'.install.log')).write_bytes(adb('install','-r',str(apk)))
    pair=dict(appSha256=installed('org.mathstudy.agent.debug'),testSha256=installed('org.mathstudy.agent.debug.test'))
    (out/'installed-apk.json').write_text(json.dumps(pair,indent=2))
    (out/'device.txt').write_bytes(adb('shell','getprop','ro.build.version.sdk')+adb('shell','getprop','ro.product.cpu.abi')+adb('shell','getconf','PAGESIZE'))
    fixture=str(uuid.uuid4()); checklist=str(uuid.uuid4())
    tasks=[]
    if args.method: tasks=[('focused',args.method,{})]
    if args.core:
        tasks=[('eviction','IndependentPostfixProbeTest#deletedConversationMustRejectStaleSaveAfterSessionEviction',{}),
               ('lifecycle-seed','ClosureBoundaryTest#deletionCreationRestoreAndIndependentIncarnation',dict(fixtureId=fixture,phase='seed')),
               ('lifecycle-verify','ClosureBoundaryTest#deletionCreationRestoreAndIndependentIncarnation',dict(fixtureId=fixture,phase='verify')),
               ('transaction','ClosureBoundaryTest#approvalEveryTransactionStepRollsBackBeforePublication',{}),
               ('attachments','ClosureBoundaryTest#crossConversationReferencesAndDeleteIoRetry',{}),
               ('factory','IndependentPostfixProbeTest#completeServiceFactoryContinuationUsesSyntheticProviderAndFirstDecision',{}),
               ('word','WordFormulaAssertionTest',{}),('diagnostics','DiagnosticsTest',{}),('skills-existing','SkillsInstallBoundaryTest',{}),
               ('skills-install','ChecklistSupplementTest#skillsInstallWorksWhileAuthoringAndEditingAreDisabled',{}),
               ('skills-enabled','ChecklistSupplementTest#installedSkillsCanBeUsedOnlyWhenEnabled',{}),
               ('checklist-seed','ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess',dict(fixtureId=checklist,phase='seed')),
               ('checklist-verify','ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess',dict(fixtureId=checklist,phase='verify'))]
        tasks += [('db-backup-regression','BackupReliabilityTest',{}),
                  ('db-conversation-regression','ConversationBoundaryFixTest',{})]
    if args.skills: tasks += [('skills-links','SkillClosureTest#nestedBrokenLinksAndSiblingReplacement',{}),('skills-rollback','SkillClosureTest#replacementAndRollbackFailureKeepOriginalContentAndEnabledState',{})]
    if args.skills: tasks += [('skills-partial-cleanup','SkillClosureTest#partialUninstallCleanupFailureRestoresEntireSkill',{})]
    if args.delivery: tasks += [('delivery-'+str(i),'ProjectReviewDeliveryTest#everyFormatOpensSavesAndArrivesInOtherUid',{}) for i in (1,2)]
    if args.delivery: tasks += [('delivery-failure','DeliveryClosureTest#cancellationCorruptionAndTargetRereadRejectFalseSuccess',{}),('delivery-ui','DeliveryUiClosureTest',{})]
    results=[]; context=dict(**pair,runs=[])
    for name, cls, extras in tasks:
        if name.endswith('verify'): adb('shell','am','force-stop','org.mathstudy.agent.debug')
        run_id=str(uuid.uuid4()); started=int(time.time()*1000)
        params=['shell','am','instrument','-w','-r','-e','class','me.rerere.rikkahub.service.'+cls,'-e','reviewRunId',run_id]
        for k,v in extras.items(): params += ['-e',k,v]
        params += ['org.mathstudy.agent.debug.test/'+args.runner]
        try: raw=adb(*params)
        except subprocess.TimeoutExpired as error:
            raw=(error.stdout or b'')+b'\nQA_TIMEOUT: no final instrumentation status within 600 seconds\n'
            adb('shell','am','force-stop','org.mathstudy.agent.debug')
        except subprocess.CalledProcessError as error:
            raw=(error.stdout or b'')+b'\nQA_PROCESS_ERROR\n'+(error.stderr or b'')
        ended=int(time.time()*1000); (out/(name+'.log')).write_bytes(raw)
        log=raw.decode('utf-8-sig'); ok=bool(re.search(r'OK \(\d+ tests?\)',log)) and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed',log)
        results.append(dict(name=name,className=cls,runId=run_id,notBefore=started,notAfter=ended,status='PASS' if ok else 'FAIL',logSha256=sha(raw),extras=extras))
        for manifest in re.findall(r'^INSTRUMENTATION_STATUS: qa_evidence_manifest=(.*)$',log,re.M):
            m=json.loads(manifest); context['runs'].append(dict(method=m['method'],runId=m['runId'],log=name+'.log',logSha256=sha(raw),notBefore=started-5000,notAfter=ended+5000))
        (out/'results.json').write_text(json.dumps(results,indent=2)); (out/'run-context.json').write_text(json.dumps(context,indent=2))
        print(name,results[-1]['status'],flush=True)
    return 0 if all(r['status']=='PASS' for r in results) else 1
if __name__=='__main__': raise SystemExit(main())
