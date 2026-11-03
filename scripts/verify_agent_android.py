"""Isolated synthetic Android service verification; no production app data or paid API.

Each run has a new directory, raw logs, commands, source/APK hashes and explicit
instrumentation verdict (adb exit zero alone never means tests passed).
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import io
import tarfile
import shutil
import zipfile

try:
    from project_storage import enforce
except ModuleNotFoundError as error:
    if error.name != "project_storage":
        raise
    from scripts.project_storage import enforce

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012'
PACKAGE = 'org.hxsi.agent.nativeqa'
RUNNER = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
FAULT_CLASSES = (
    'me.rerere.rikkahub.service.ChatNetworkFaultTest',
    'me.rerere.rikkahub.service.FlutterChatBridgeTest',
)
DEFAULT_CLASSES = ','.join([
    'me.rerere.rikkahub.feature.agent.AgentTaskRuntimeTest',
    'me.rerere.rikkahub.feature.agent.ProviderReplayBoundaryTest',
    'me.rerere.rikkahub.feature.agent.ProviderPhysicalAccountingTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageLedgerRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageAlertsRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageNotificationsRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageNotificationSurfaceTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageGroupsRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageGroupsSurfaceTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageExportRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageRecoveryRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageHistoryRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentUsageExportSurfaceTest',
    'me.rerere.rikkahub.feature.agent.AgentTaskCatalogRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentTaskCenterSurfaceTest',
    'me.rerere.rikkahub.service.AgentChatAcceptanceTest',
    'me.rerere.rikkahub.service.CompressionConcurrencyTest',
    'me.rerere.rikkahub.feature.agent.AgentNewFeatureAdversarialTest',
    'me.rerere.rikkahub.feature.material.OfficeSafetyRuntimeTest',
    'me.rerere.rikkahub.data.ai.tools.OwnedChatImagesTest',
    'me.rerere.rikkahub.feature.agent.AllRoleAdmissionTest',
    'me.rerere.rikkahub.feature.agent.MemoryImageAdmissionTest',
    'me.rerere.rikkahub.feature.agent.AgentAuthorizationPlanTest',
    'me.rerere.rikkahub.feature.agent.TaskPlanningRuntimeTest',
    'me.rerere.rikkahub.feature.agent.PlanDispatchCoverageTest',
    'me.rerere.rikkahub.feature.agent.TaskSupplementCoverageTest',
    'me.rerere.rikkahub.feature.agent.ToolProjectionRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentCredentialRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentApprovalBoundaryTest',
    'me.rerere.rikkahub.feature.agent.AgentSchema6MigrationTest',
    'me.rerere.rikkahub.feature.agent.MemoryGoalRuntimeTest',
    'me.rerere.rikkahub.feature.agent.AgentTaskSurfaceTest',
    'me.rerere.rikkahub.feature.agent.RemoteToolContinuationTest',
    'me.rerere.rikkahub.feature.agent.QueuedTaskCommandRuntimeTest',
] + list(FAULT_CLASSES))


def freeze_apk(source, target):
    """Freeze before hashing/installing; reject a concurrent packaging rewrite."""
    shutil.copyfile(source, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
        raise RuntimeError('APK changed during snapshot; preserved, do not install')
    with zipfile.ZipFile(target) as apk:
        if len(apk.namelist()) != len(set(apk.namelist())) or apk.testzip() is not None:
            raise RuntimeError('Ambiguous or corrupt APK; do not install')
    return digest


def instrumentation_methods(listing):
    """Android Bundle field order is unspecified; never pair across status packets."""
    methods = []
    for packet in re.split(r'INSTRUMENTATION_STATUS_CODE:[^\r\n]*', listing):
        fields = dict(re.findall(r'INSTRUMENTATION_STATUS: (class|test)=([^\r\n]+)', packet))
        if not fields:
            continue
        if set(fields) != {'class', 'test'}:
            raise ValueError('Incomplete instrumentation status packet')
        method = fields['class'].strip() + '#' + fields['test'].strip()
        if method not in methods:
            methods.append(method)
    return methods


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--classes', default=DEFAULT_CLASSES)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--apk-dir', type=Path, help='Use explicit frozen QA main/test APKs instead of mutable build outputs')
    parser.add_argument('--isolate-tests', action='store_true',
        help='Run each discovered method in a fresh synthetic QA process; archive UNKNOWN state before clearing')
    args = parser.parse_args()
    enforce(ROOT)
    if args.build and args.apk_dir:
        parser.error('Choose build outputs or a frozen APK directory')
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.run_id):
        parser.error('run-id must be a plain identifier')
    folder = OUT / 'runs' / args.run_id
    folder.mkdir(parents=True, exist_ok=False)
    records = []
    adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', args.serial]

    def run(name, command, required=None, timeout=2400):
        started = time.time()
        try:
            result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=timeout)
            output = result.stdout + result.stderr
            exit_code = result.returncode
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b'') + (error.stderr or b'') + b'\nVERIFIER_TIMEOUT\n'
            exit_code = 124
        text = output.decode('utf-8', errors='replace')
        log = folder / (name + '.log')
        log.write_text('$ ' + subprocess.list2cmdline(list(map(str, command))) + '\nCWD: ' + str(ROOT) + '\n' + text + '\nexit_code=' + str(exit_code), encoding='utf-8')
        passed = exit_code == 0 and (required is None or required(text))
        records.append(dict(name=name, command=list(map(str, command)), cwd=str(ROOT), seconds=round(time.time()-started, 3), exit_code=exit_code, status='PASS' if passed else 'FAIL', log=log.name, log_sha256=hashlib.sha256(log.read_bytes()).hexdigest()))
        (folder / 'TEST_RUNS.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        print(name, records[-1]['status'], text[-1800:], flush=True)
        if not passed:
            raise SystemExit(1)
        return text

    sources = {}
    for module in ['app', 'ai', 'search']:
        for file in (ROOT / 'client' / module / 'src').rglob('*'):
            if file.is_file():
                sources[str(file.relative_to(ROOT))] = hashlib.sha256(file.read_bytes()).hexdigest()
    for file in (ROOT / 'client/app/schemas').rglob('*.json'):
        sources[str(file.relative_to(ROOT))] = hashlib.sha256(file.read_bytes()).hexdigest()
    (folder / 'SOURCE_INPUTS.json').write_text(json.dumps(sources, indent=2))
    runner_source = Path(__file__).read_bytes()
    (folder / 'RUNNER_INPUT.py').write_bytes(runner_source)
    (folder / 'RUNNER_SHA256.json').write_text(json.dumps({
        'path': str(Path(__file__).relative_to(ROOT)),
        'sha256': hashlib.sha256(runner_source).hexdigest(),
    }, indent=2))
    run('emulator-only', adb + ['shell', 'getprop', 'ro.kernel.qemu'], lambda t: t.strip() == '1', 30)
    if args.build:
        run('build', [ROOT / '.local/gradle-9.6.0/bin/gradle.bat', '-p', ROOT / 'client',
            ':app:assembleDebug', ':app:assembleDebugAndroidTest', '-PagentNativeTest=true', '--offline', '--console=plain'])
    # Fault fixtures intentionally retain UNKNOWN and the global slot. Isolation
    # archives those records instead of inventing a receipt or weakening dispatch.
    def reset_qa(label, clear=True):
        installed = run(label + '-qa-package-query', adb + ['shell', 'pm', 'list', 'packages', PACKAGE],
            lambda text: all(line.startswith('package:') for line in text.splitlines() if line.strip()), 30)
        if 'package:' + PACKAGE not in installed.splitlines():
            return
        run(label + '-stop-qa-fixtures', adb + ['shell', 'am', 'force-stop', PACKAGE], timeout=30)
        # Preserve QA data and screenshots, not runtime code regenerated from the
        # installed APK (Flutter's kernel cache can exceed 80 MB).
        archive_command = adb + ['exec-out', 'run-as', PACKAGE, 'tar',
            '--exclude=./app_flutter', '--exclude=./app_dxmaker_cache', '-cf', '-', '.']
        archive = subprocess.run(archive_command, cwd=ROOT, capture_output=True, timeout=30)
        target = folder / (label + '-SYNTHETIC_STATE.tar')
        target.write_bytes(archive.stdout)
        records.append(dict(name=label + '-archive-synthetic-state', command=archive_command, cwd=str(ROOT),
            exit_code=archive.returncode, status='PASS' if archive.returncode == 0 else 'FAIL',
            artifact=target.name, sha256=hashlib.sha256(archive.stdout).hexdigest(),
            error=archive.stderr.decode('utf-8', errors='replace')))
        (folder / 'TEST_RUNS.json').write_text(json.dumps(records, indent=2))
        if archive.returncode != 0:
            raise SystemExit('QA fixture archive failed; existing state preserved')
        if clear:
            run(label + '-clear-only-synthetic-qa-package', adb + ['shell', 'pm', 'clear', PACKAGE], lambda t: 'Success' in t, 30)

    reset_qa('previous')
    apks = [ROOT / 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk',
            ROOT / 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    if args.apk_dir:
        apks=[args.apk_dir.resolve()/p.name for p in apks]
    frozen = folder / 'selected-apks'; frozen.mkdir()
    hashes = {str(p.relative_to(ROOT)): freeze_apk(p, frozen / p.name) for p in apks}
    (folder / 'APK_INPUTS.json').write_text(json.dumps(hashes, indent=2))
    aapt=sorted((ROOT/'.local/android-sdk/build-tools').glob('*/aapt2.exe'))[-1]
    for index, (source, package) in enumerate(zip(apks, (PACKAGE, PACKAGE+'.test'))):
        apk = frozen / source.name
        run('package-identity-'+str(index), [aapt,'dump','badging',apk],
            lambda t, expected=package: "package: name='"+expected+"'" in t)
        run('install-' + str(index), adb + ['install', '-r', apk], lambda t: 'Success' in t, 120)
        remote = run('installed-path-'+str(index), adb+['shell','pm','path',package], lambda t: t.strip().startswith('package:'))
        run('installed-hash-'+str(index), adb+['shell','sha256sum',remote.strip().removeprefix('package:')],
            lambda t: t.split()[0] == hashes[str(source.relative_to(ROOT))])
    run('registry', adb + ['shell', 'pm', 'list', 'instrumentation'], lambda t: RUNNER in t, 30)
    passed_instrumentation = lambda t: re.search(r'OK \(\d+ tests?\)', t) is not None and 'FAILURES!!!' not in t and 'INSTRUMENTATION_FAILED' not in t
    targets = args.classes.split(',')
    isolated = targets if args.isolate_tests else [target for target in targets if target.split('#', 1)[0] in FAULT_CLASSES]
    regular = [] if args.isolate_tests else [target for target in targets if target.split('#', 1)[0] not in FAULT_CLASSES]
    if regular:
        run('instrumentation', adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(regular), RUNNER],
            passed_instrumentation, 600)
    if isolated:
        reset_qa('before-discovery')
        listing = run('discover-tests', adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'log', 'true',
            '-e', 'class', ','.join(isolated), RUNNER], passed_instrumentation, 60)
        methods = instrumentation_methods(listing)
        if not methods or any(not re.fullmatch(r'[\w.$]+#[\w]+', method) for method in methods):
            raise SystemExit('No unambiguous test methods discovered; QA state preserved')
        count = re.search(r'OK \((\d+) tests?\)', listing)
        if count is None or len(methods) != int(count.group(1)):
            raise SystemExit('Discovered method count does not match instrumentation; QA state preserved')
        (folder / 'DISCOVERED_TESTS.json').write_text(json.dumps(methods, indent=2))
        failed = []
        for index, method in enumerate(methods):
            label = 'method-' + str(index)
            reset_qa(label)
            if method.endswith('#pinnedCommunityInstructionsAndReferencesLoadAndOfficeDependenciesAreExplicit'):
                manifest = json.loads((ROOT/'qa/core-v1/community-sources.json').read_text(encoding='utf-8'))
                archive = io.BytesIO()
                fixtures = {}
                fixture_uid = int(run(label+'-fixture-uid',adb+['exec-out','run-as',PACKAGE,'id','-u']).strip())
                fixture_gid = int(run(label+'-fixture-gid',adb+['exec-out','run-as',PACKAGE,'id','-g']).strip())
                with tarfile.open(fileobj=archive, mode='w') as bundle:
                    for item in manifest['files']:
                        relative = item['path'].removeprefix('skills/')
                        if not re.fullmatch(r'[A-Za-z0-9_./-]+', relative) or '..' in relative.split('/'):
                            raise ValueError('Unsafe community fixture path')
                        data = (ROOT/'.local/core-v1-community'/relative).read_bytes()
                        digest = hashlib.sha256(data).hexdigest()
                        if digest != item['sha256']:
                            raise ValueError('Community fixture hash differs')
                        fixtures[relative] = digest
                        entry = tarfile.TarInfo('cache/core-v1-community/' + relative)
                        entry.size = len(data); entry.mode = 0o600; entry.uid = fixture_uid; entry.gid = fixture_gid
                        bundle.addfile(entry, io.BytesIO(data))
                (folder/'COMMUNITY_FIXTURES.json').write_text(json.dumps(fixtures, indent=2))
                prepared = subprocess.run(adb + ['shell', '-T', 'run-as', PACKAGE, 'tar', '-xf', '-', '-C', '/data/user/0/' + PACKAGE],
                    input=archive.getvalue(), capture_output=True, timeout=30)
                (folder/(label+'-prepare-community.log')).write_bytes(prepared.stdout+prepared.stderr)
                if prepared.returncode != 0:
                    raise SystemExit('Community fixture preparation failed; QA state preserved')
                for relative, expected in fixtures.items():
                    run(label+'-fixture-'+str(len(records)), adb+['exec-out','run-as',PACKAGE,'sha256sum',
                        'cache/core-v1-community/'+relative], lambda text, digest=expected: text.split()[0] == digest, 30)
            # Only test invocations may fail without stopping; environment and
            # state-archive failures still stop before any subsequent clear.
            try:
                run(label + '-instrumentation', adb + ['shell', 'am', 'instrument', '-w', '-r',
                    '-e', 'class', method, RUNNER], passed_instrumentation, 180)
            except SystemExit:
                failed.append(method)
                if records[-1]['exit_code'] != 0:
                    raise
        reset_qa('final', clear=False)
        if failed:
            raise SystemExit('Failed isolated methods: ' + ', '.join(failed))


if __name__ == '__main__':
    main()
