"""G47-01: prove Gradle redirect, build current packages, bind Flutter bytes, run device tests.

Invoked only by scripts/project_storage.py so HXAI_RUN_DIR exists. Writes compact proof
outside the disposable run directory before returning.
"""
import hashlib
import json
import os
import subprocess
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REVIEW = Path(__file__).resolve().parent
SDK = Path(r"D:\Office\Study\Depend\android")
FLUTTER = Path(r"D:\Office\Study\Depend\flutter")
INIT = ROOT / "scripts" / "storage.init.gradle"
AAPT = SDK / "build-tools" / "37.0.0" / "aapt.exe"
EMU = SDK / "emulator" / "emulator.exe"
ADB = SDK / "platform-tools" / "adb.exe"
PROOF = REVIEW / "gradle_bind_proof.json"
QA_MARKERS = (
    "McpFixtureManagementActivity",
    "LibraryFixtureDocumentsProvider",
    "AgentNativeTestRunner",
    "OfficeSafetyTestRunner",
    "org.hxsi.agent.nativeqa",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(cmd, env, cwd=None, timeout=None):
    print("CMD", " ".join(str(part) for part in cmd), flush=True)
    completed = subprocess.run(cmd, cwd=cwd or ROOT, env=env, text=True, capture_output=True, timeout=timeout)
    text = (completed.stdout or "") + (completed.stderr or "")
    print(text[-4000:], flush=True)
    return completed.returncode, text


def gradle(env, init, tasks, timeout):
    cmd = [str(ROOT / "client" / "gradlew.bat"), "-p", "client", "--no-daemon", "--console=plain", "--max-workers=2"]
    if init:
        cmd[3:3] = ["-I", str(INIT.resolve())]
    cmd.extend(tasks)
    return run(cmd, env, timeout=timeout)


def main():
    run_dir = os.environ.get("HXAI_RUN_DIR")
    if not run_dir or not Path(run_dir).is_dir():
        raise SystemExit("HXAI_RUN_DIR missing; refuse to build outside the storage run")
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(SDK)
    env["ANDROID_SDK_ROOT"] = str(SDK)
    proof = {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "run_dir": run_dir,
        "init_script": str(INIT.resolve()),
        "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    old_apk = ROOT / "client" / "app" / "build" / "outputs" / "apk" / "debug" / "app-x86_64-debug.apk"
    proof["old_debug_apk_mtime"] = old_apk.stat().st_mtime if old_apk.exists() else None
    code, text = gradle(env, True, [":app:generateDebugBuildConfig"], 600)
    redirected = Path(run_dir) / "gradle" / "_app"
    generated = sorted(str(path.relative_to(run_dir)) for path in redirected.rglob("*") if path.is_file()) if redirected.exists() else []
    proof["redirect"] = {
        "exit": code,
        "gated_message_absent": "Run through scripts/project_storage.py run first" not in text,
        "output_dir": str(redirected),
        "file_count": len(generated),
        "sample": generated[:12],
        "old_apk_mtime_unchanged": (old_apk.stat().st_mtime if old_apk.exists() else None) == proof["old_debug_apk_mtime"],
    }
    if code != 0 or not generated:
        proof["status"] = "FAIL"
        PROOF.write_text(json.dumps(proof, indent=2), encoding="utf-8")
        raise SystemExit(code or 1)

    code, text = gradle(env, False, [
        ":app:assembleDebug", ":app:assembleDebugAndroidTest",
        ":app:testDebugUnitTest", ":ai:testDebugUnitTest", ":search:testDebugUnitTest", ":app:lintDebug",
    ], 3000)
    proof["package_build_exit"] = code
    proof["package_build_tail"] = text[-2000:]
    if code != 0:
        proof["status"] = "FAIL"
        PROOF.write_text(json.dumps(proof, indent=2), encoding="utf-8")
        raise SystemExit(code)

    debug_apk = ROOT / "client" / "app" / "build" / "outputs" / "apk" / "debug" / "app-x86_64-debug.apk"
    release_apk = ROOT / "client" / "app" / "build" / "outputs" / "apk" / "release" / "app-x86_64-release-unsigned.apk"
    test_apk = ROOT / "client" / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
    debug_aar = ROOT / "client" / "flutter_ui" / "build" / "host" / "outputs" / "repo" / "me" / "rerere" / "rikkahub" / "flutter_ui" / "flutter_debug" / "1.0" / "flutter_debug-1.0.aar"
    release_aar = ROOT / "client" / "flutter_ui" / "build" / "host" / "outputs" / "repo" / "me" / "rerere" / "rikkahub" / "flutter_ui" / "flutter_release" / "1.0" / "flutter_release-1.0.aar"
    bindings = {}
    out = REVIEW / "binding-debug.json"
    bind_code, bind_text = run([
        "python", str(ROOT / "scripts" / "verify_flutter_apk.py"),
        "--output", str(out), "--mode", "debug", "--apk", str(debug_apk), "--aar", str(debug_aar),
        "--flutter-sdk", str(FLUTTER),
    ], env, timeout=300)
    bindings["debug"] = {"exit": bind_code, "tail": bind_text[-500:], "report": str(out)}
    proof["bindings"] = bindings
    proof["release"] = {
        "status": "BLOCKED",
        "reason": "processReleaseGoogleServices found no client for org.hxsi.agent; google-services.json only contains org.hxsi.agent.debug. Unsigned release was not substituted with the debug key.",
        "aar_present": release_aar.exists(),
        "apk_present": release_apk.exists(),
    }
    badging_code, badging_text = run([str(AAPT), "dump", "badging", str(debug_apk)], env, timeout=120)
    xml_code, xml = run([str(AAPT), "dump", "xmltree", str(debug_apk), "AndroidManifest.xml"], env, timeout=120)
    proof["normal_package"] = {
        "badging_exit": badging_code,
        "package_lines": [line for line in badging_text.splitlines() if line.startswith("package:")],
        "xml_exit": xml_code,
        "qa_markers_present": [marker for marker in QA_MARKERS if marker in xml],
    }
    proof["apk_sha256"] = {name: sha256(path) for name, path in {
        "debug_x86_64": debug_apk, "androidTest": test_apk,
    }.items()}
    proof["status"] = "BIND_ONLY"
    PROOF.write_text(json.dumps(proof, indent=2), encoding="utf-8")
    proof["devices"] = run_devices(env, debug_apk, test_apk)
    proof["status"] = "PASS" if bindings["debug"]["exit"] == 0 and not proof["normal_package"]["qa_markers_present"] and all(item.get("instrument_exit") == 0 for item in proof["devices"]) else "FAIL"
    PROOF.write_text(json.dumps(proof, indent=2), encoding="utf-8")
    print("PROOF", proof["status"], flush=True)
    raise SystemExit(0 if proof["status"] == "PASS" else 1)


def run_devices(env, app, test):
    results = []
    for name, port in (("hxai-api26", 5554), ("hxai-api34", 5556)):
        serial = f"emulator-{port}"
        proc = subprocess.Popen([
            str(EMU), "-avd", name, "-port", str(port), "-no-snapshot", "-no-boot-anim",
            "-no-audio", "-no-window", "-gpu", "swiftshader_indirect", "-memory", "2048",
        ], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.time() + 180
            booted = False
            while time.time() < deadline:
                code, text = run([str(ADB), "-s", serial, "shell", "getprop", "sys.boot_completed"], env, timeout=20)
                if code == 0 and text.strip().endswith("1"):
                    booted = True
                    break
                time.sleep(3)
            if not booted:
                results.append({"avd": name, "instrument_exit": 1, "reason": "boot_timeout"})
                continue
            installs = []
            for apk in (app, test):
                code, text = run([str(ADB), "-s", serial, "install", "-r", "-t", str(apk)], env, timeout=180)
                installs.append({"apk": apk.name, "exit": code, "tail": text[-200:]})
            code, text = run([
                str(ADB), "-s", serial, "shell", "am", "instrument", "-w",
                "-e", "class", "me.rerere.rikkahub.service.HostEpochAndDocumentRevokeTest,me.rerere.rikkahub.service.FlutterChatBridgeTest",
                "org.hxsi.agent.debug.test/androidx.test.runner.AndroidJUnitRunner",
            ], env, timeout=900)
            results.append({"avd": name, "installs": installs, "instrument_exit": code, "tail": text[-1500:]})
        finally:
            subprocess.run([str(ADB), "-s", serial, "emu", "kill"], env=env, timeout=30, check=False)
            try:
                proc.wait(timeout=40)
            except subprocess.TimeoutExpired:
                proc.kill()
            snap = Path(r"D:\Office\Study\Depend\avd") / f"{name}.avd" / "snapshots"
            if snap.exists():
                import shutil
                shutil.rmtree(snap, ignore_errors=True)
    return results


if __name__ == "__main__":
    main()
