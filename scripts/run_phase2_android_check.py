"""Run synthetic instrumentation in the explicitly isolated QA package only."""
import argparse, subprocess, re

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['persistence', 'chat', 'seed', 'verify', 'force-stop'])
parser.add_argument('--fixture')
args = parser.parse_args()
ADB = r'D:\Office\Study\code\MathStudyAgent\.local\android-sdk\platform-tools\adb.exe'
PACKAGE = 'org.hxsi.agent.phase2qa.debug'
base = [ADB, '-s', 'emulator-5554', 'shell']
assert subprocess.check_output(base + ['getprop', 'sys.boot_completed'], text=True).strip() == '1'
assert subprocess.run(base + ['pm', 'path', PACKAGE], capture_output=True, text=True).returncode == 0
if args.mode == 'force-stop':
    subprocess.run(base + ['am', 'force-stop', PACKAGE], check=True)
else:
    command = base + ['am', 'instrument', '-w', '-r', '-e', 'class']
    clazz = 'me.rerere.rikkahub.service.'
    if args.mode == 'chat': clazz += 'AgentChatAdmissionTest'
    else: clazz += 'AgentNativePersistenceTest'
    if args.mode in ['seed', 'verify']:
        assert args.fixture and re.fullmatch(r'[A-Za-z0-9-]{1,80}', args.fixture)
        clazz += '#separateProcessRecovery'
    elif args.mode == 'persistence':
        clazz = ','.join(clazz + '#' + method for method in [
            'atomicFileReopenAndOsLeaseProtectUnknown',
            'actualVaultSnapshotKeepsModelAndReasoningAfterSettingsChange',
            'nativeConcurrentClaimsShareSingleSlot'])
    command.append(clazz)
    if args.mode in ['seed', 'verify']:
        command += ['-e', 'agentFixture', args.fixture, '-e', 'agentPhase', args.mode]
    command.append(PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
    result = subprocess.run(command, capture_output=True, text=True)
    print(result.stdout, flush=True)
    print(result.stderr, flush=True)
    # am instrument can return exit zero even when the test failed.
    assert result.returncode == 0 and re.search(r'OK \(\d+ tests?\)', result.stdout), 'Instrumentation did not report PASS'
    assert 'FAILURES!!!' not in result.stdout and 'INSTRUMENTATION_FAILED' not in result.stdout
