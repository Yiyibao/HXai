import os, shutil, subprocess, sys, time
from pathlib import Path

sdk = Path(r"D:\Office\Study\Depend\android")
adb = sdk / "platform-tools" / "adb.exe"
emu = sdk / "emulator" / "emulator.exe"
avd = sys.argv[1]
port = sys.argv[2]
serial = f"emulator-{port}"
env = os.environ.copy()
env["ANDROID_HOME"] = str(sdk)
env["ANDROID_SDK_ROOT"] = str(sdk)
env["ANDROID_AVD_HOME"] = r"D:\Office\Study\Depend\avd"
log_path = Path(__file__).with_name(f"emulator-{avd}.log")
proc = subprocess.Popen(
    [str(emu), "-avd", avd, "-port", port, "-no-snapshot", "-no-boot-anim",
     "-no-audio", "-no-window", "-gpu", "swiftshader_indirect", "-memory", "2048"],
    env=env, stdout=log_path.open("w", encoding="utf-8"), stderr=subprocess.STDOUT,
)
booted = False
try:
    deadline = time.time() + 180
    while time.time() < deadline:
        probe = subprocess.run([str(adb), "-s", serial, "shell", "getprop", "sys.boot_completed"], capture_output=True, text=True)
        print("boot", probe.returncode, (probe.stdout or "").strip(), (probe.stderr or "")[-160:], flush=True)
        if probe.returncode == 0 and (probe.stdout or "").strip() == "1":
            booted = True
            break
        time.sleep(4)
    print("BOOTED", avd, booted, flush=True)
    if not booted:
        raise SystemExit(1)
    app = r"client\app\build\outputs\apk\debug\app-x86_64-debug.apk"
    test = r"client\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    for apk in (app, test):
        installed = subprocess.run([str(adb), "-s", serial, "install", "-r", "-t", apk], text=True)
        print("install", apk, installed.returncode, flush=True)
        if installed.returncode != 0:
            raise SystemExit(installed.returncode)
    instrument = subprocess.run([
        str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-e", "class",
        "me.rerere.rikkahub.service.HostEpochAndDocumentRevokeTest",
        "org.hxsi.agent.debug.test/androidx.test.runner.AndroidJUnitRunner",
    ], text=True)
    print("instrument", instrument.returncode, flush=True)
    raise SystemExit(instrument.returncode)
finally:
    subprocess.run([str(adb), "-s", serial, "emu", "kill"], timeout=20)
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
    snap = Path(r"D:\Office\Study\Depend\avd") / f"{avd}.avd" / "snapshots"
    if snap.exists():
        shutil.rmtree(snap, ignore_errors=True)
