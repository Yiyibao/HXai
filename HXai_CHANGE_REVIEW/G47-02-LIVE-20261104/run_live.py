"""Run the authorized public quality test. The key is read from HXAI_QUALITY_KEY and never printed."""
import json, os, secrets, shutil, subprocess, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(r"D:\Office\Study\Depend\android")
ADB = SDK / "platform-tools" / "adb.exe"
EMU = SDK / "emulator" / "emulator.exe"
APP = ROOT / "client/app/build/outputs/apk/debug/app-x86_64-debug.apk"
TEST = ROOT / "client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MANIFEST = ROOT / "HXai_CHANGE_REVIEW/G47-02-LIVE-20261104/public-input/LIVE_INPUT.json"
RUN_ID = "g47-live-20261104"
PKG = "org.hxsi.agent.nativeqa"
SERIAL = "emulator-5554"
KEY = os.environ.get("HXAI_QUALITY_KEY", "")
if not (16 <= len(KEY) <= 2048):
    raise SystemExit("missing quality key")

def adb(*args, timeout=180, input_bytes=None):
    print("adb", args[0], flush=True)
    return subprocess.run([str(ADB), "-s", SERIAL, *args], input=input_bytes, capture_output=True, timeout=timeout)

def main():
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(SDK)
    env["ANDROID_SDK_ROOT"] = str(SDK)
    env["ANDROID_AVD_HOME"] = r"D:\Office\Study\Depend\avd"
    log = Path(__file__).with_name("emulator-live.log")
    proc = subprocess.Popen([str(EMU), "-avd", "hxai-api26", "-port", "5554", "-no-snapshot",
        "-no-boot-anim", "-no-audio", "-no-window", "-gpu", "swiftshader_indirect", "-memory", "2048"],
        env=env, stdout=log.open("w", encoding="utf-8"), stderr=subprocess.STDOUT)
    nonce = secrets.token_hex(32)
    served = {"done": False}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if served["done"] or self.path != "/" + nonce:
                self.send_response(404); self.end_headers(); return
            body = json.dumps({"apiKey": KEY}).encode()
            served["done"] = True
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers(); self.wfile.write(body)
        def log_message(self, fmt, *args):
            print("provision_hit", served["done"], flush=True)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        deadline = time.time() + 180
        while time.time() < deadline:
            probe = subprocess.run([str(ADB), "-s", SERIAL, "shell", "getprop", "sys.boot_completed"], capture_output=True, text=True)
            if probe.returncode == 0 and (probe.stdout or "").strip() == "1":
                break
            time.sleep(4)
        else:
            raise SystemExit("boot_timeout")
        for apk in (APP, TEST):
            installed = adb("install", "-r", "-t", str(apk))
            print("install", apk.name, installed.returncode, flush=True)
            if installed.returncode != 0:
                raise SystemExit(installed.returncode)
        script = "mkdir -p no_backup/quality-evaluation && cat > no_backup/quality-evaluation/" + RUN_ID + "-input.json && ls -l no_backup/quality-evaluation"
        pushed = adb("shell", "run-as " + PKG + " sh -c '" + script + "'", input_bytes=MANIFEST.read_bytes())
        detail = ((pushed.stdout or b"") + (pushed.stderr or b"")).decode("utf-8", "replace")[-300:]
        print("manifest_push", pushed.returncode, detail, flush=True)
        if pushed.returncode != 0:
            raise SystemExit(pushed.returncode)
        reversed_ok = adb("reverse", f"tcp:{port}", f"tcp:{port}")
        print("reverse", reversed_ok.returncode, flush=True)
        url = f"http://127.0.0.1:{port}/{nonce}"
        instrument = adb("shell", "am", "instrument", "-w",
            "-e", "qualityLiveConsent", "PUBLIC_INPUTS_PINNED_PROVIDER_MEDIUM",
            "-e", "qualityRunId", RUN_ID,
            "-e", "qualityProvisionUrl", url,
            "-e", "class", "me.rerere.rikkahub.feature.agent.LiveQualityEvaluationTest",
            f"{PKG}.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner", timeout=10800)
        text = (instrument.stdout or b"").decode("utf-8", "replace") + "\n" + (instrument.stderr or b"").decode("utf-8", "replace")
        Path(__file__).with_name("instrument.txt").write_text(text, encoding="utf-8")
        print(text[-4000:], flush=True)
        raise SystemExit(instrument.returncode)
    finally:
        server.shutdown()
        subprocess.run([str(ADB), "-s", SERIAL, "emu", "kill"], timeout=20)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()
        snap = Path(r"D:\Office\Study\Depend\avd\hxai-api26.avd\snapshots")
        if snap.exists():
            shutil.rmtree(snap, ignore_errors=True)

if __name__ == "__main__":
    main()
