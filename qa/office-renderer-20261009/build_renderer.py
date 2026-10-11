"""Build only the isolated new worker image; this does not restart production services."""
from pathlib import Path
import subprocess,tarfile
root=Path(__file__).resolve().parents[2]; service=root/'server/document-service'; evidence=Path(__file__).parent
archive=evidence/'office-worker-build.tar.gz'
with tarfile.open(archive,'w:gz') as tar:
    for path in sorted((service/'office_worker').rglob('*.py')):
        tar.add(path,arcname=str(path.relative_to(service)).replace('\\','/'))
    tar.add(service/'deploy/Dockerfile.office',arcname='deploy/Dockerfile.office')
    for path in sorted((root/'qa/p0-completion-20261009/samples').glob('rich-source.*')):
        tar.add(path,arcname='samples/'+path.name)
key=str(Path.home()/'.ssh/id_ed25519_tencent_blog'); host='ubuntu@154.222.25.150'
subprocess.run(['scp','-i',key,str(archive),host+':/home/ubuntu/mathdoc-office-build-20261009.tar.gz'],check=True)
script='''set -eu
stage=/opt/mathdoc/office-staging-20261009
sudo install -d -m 0755 "$stage"
sudo tar -xzf /home/ubuntu/mathdoc-office-build-20261009.tar.gz -C "$stage"
sudo docker pull debian:trixie-slim
base=$(sudo docker image inspect debian:trixie-slim --format '{{index .RepoDigests 0}}')
printf '%s\n' "$base" | sudo tee "$stage/base-image.txt"
sudo docker build --build-arg BASE_IMAGE="$base" -f "$stage/deploy/Dockerfile.office" -t mathdoc-office:20261009-1 "$stage"
sudo docker image inspect mathdoc-office:20261009-1 --format '{{.Id}}' | sudo tee "$stage/image-id.txt"
sudo docker run --rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=384m --entrypoint /usr/bin/python3 mathdoc-office:20261009-1 -c 'from office_worker.run import version; print(version())' | sudo tee "$stage/renderer-version.txt"
sudo docker run --rm --network none --read-only --entrypoint cat mathdoc-office:20261009-1 /office-packages.txt | sudo tee "$stage/packages.txt" >/dev/null
'''
subprocess.run(['ssh','-i',key,host,'sh','-s'],input=script.encode('utf-8'),check=True)
