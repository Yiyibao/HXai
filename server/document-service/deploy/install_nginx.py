"""Install one explicit location include; restore every touched file on failure."""
import datetime
import json
import subprocess
import sys
from pathlib import Path


def install(mode):
    if mode not in {"origin", "entry"}:
        raise ValueError("mode must be origin or entry")
    source = Path("/tmp/mathdoc-nginx.conf").read_bytes()
    config = Path("/etc/nginx/sites-enabled/hxnf.top").resolve()
    snippet = Path("/etc/nginx/snippets/mathdoc-location.conf")
    limits = Path("/etc/nginx/conf.d/mathdoc-limits.conf")
    limits_content = Path("/tmp/mathdoc-limits.conf").read_bytes() if mode == "entry" else None
    targets = [config, snippet] + ([limits] if mode == "entry" else [])
    originals = {path: path.read_bytes() if path.exists() else None for path in targets}
    if originals[config] is None:
        raise FileNotFoundError(config)
    original = originals[config].decode("utf-8")
    line = "    include /etc/nginx/snippets/mathdoc-location.conf;"
    anchor = "    include /etc/nginx/snippets/" + (
        "mathstudy-location.conf;" if mode == "origin" else "spring-willow-location.conf;")
    updated = original
    if line not in original:
        if original.count(anchor) != 1:
            raise ValueError("expected exactly one existing location include")
        updated = original.replace(anchor, anchor + "\n" + line)

    # All preimages stay outside nginx include directories, before the first write.
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = Path("/etc/mathdoc-nginx-backups") / (timestamp + "-" + mode)
    backup.parent.mkdir(mode=0o700, exist_ok=True)
    backup.mkdir(mode=0o700)
    for path, content in originals.items():
        if content is not None:
            saved = backup / path.name
            saved.write_bytes(content)
            saved.chmod(0o600)
    state = backup / "state.json"
    state.write_text(json.dumps([{"path": str(path), "existed": content is not None}
                                 for path, content in originals.items()]), encoding="utf-8")
    state.chmod(0o600)
    try:
        snippet.write_bytes(source)
        if mode == "entry":
            limits.write_bytes(limits_content)
        if updated != original:
            config.write_bytes(updated.encode("utf-8"))
        subprocess.run(["nginx", "-t"], check=True)
        subprocess.run(["systemctl", "reload", "nginx"], check=True)
    except BaseException as error:
        failed = []
        for path, content in originals.items():
            try:
                if content is None:
                    path.unlink(missing_ok=True)
                else:
                    path.write_bytes(content)
            except OSError:
                failed.append(str(path))
        if failed:
            raise RuntimeError(f"Rollback incomplete for {failed}; originals: {backup}") from error
        raise
    print("nginx_location_installed:" + mode)


if __name__ == "__main__":
    install(sys.argv[1])
