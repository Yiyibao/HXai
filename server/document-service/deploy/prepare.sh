#!/bin/sh
set -eu
id mathdoc >/dev/null 2>&1 || useradd --system --user-group --home-dir /var/lib/mathdoc --shell /usr/sbin/nologin mathdoc
install -d -o mathdoc -g mathdoc -m 2770 /var/lib/mathdoc
install -d -m 0755 /etc/mathdoc
python3 -m venv /opt/mathdoc/api-env
/opt/mathdoc/preparation/bin/uv pip install --python /opt/mathdoc/api-env/bin/python --require-hashes -r /opt/mathdoc/service/requirements-api.lock
cd /opt/mathdoc/service
docker build --build-arg BASE_IMAGE=python@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258 -f deploy/Dockerfile -t mathdoc-worker:4.0.10-1 .
image_id=$(docker image inspect mathdoc-worker:4.0.10-1 --format '{{.Id}}')
printf 'MATHDOC_WORKER_IMAGE=%s\n' "$image_id" > /etc/mathdoc/worker.env
chmod 0644 /etc/mathdoc/worker.env
install -m 0644 deploy/mathdoc-api.service /etc/systemd/system/mathdoc-api.service
install -m 0644 deploy/mathdoc-dispatcher.service /etc/systemd/system/mathdoc-dispatcher.service
runuser -u mathdoc -- /opt/mathdoc/api-env/bin/python -c 'from api.store import Store; from pathlib import Path; Store(Path("/var/lib/mathdoc"))'
systemctl daemon-reload
# Remains unready until real parsing and isolation checks pass.
systemctl enable --now mathdoc-api mathdoc-dispatcher
