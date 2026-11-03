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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--classes', default=DEFAULT_CLASSES)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--isolate-tests', action='store_true',
        help='Run each discovered method in a fresh synthetic QA process; archive UNKNOWN state before clearing')
    args = parser.parse_args()
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
    (folder / 'APK_INPUTS.json').write_text(json.dumps({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks}, indent=2))
    for index, apk in enumerate(apks):
        run('install-' + str(index), adb + ['install', '-r', apk], lambda t: 'Success' in t, 120)
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
        methods = list(dict.fromkeys(
            cls.strip() + '#' + method.strip() for cls, method in re.findall(
                r'INSTRUMENTATION_STATUS: class=([^\r\n]+).*?INSTRUMENTATION_STATUS: test=([^\r\n]+)',
                listing, re.DOTALL)))
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
