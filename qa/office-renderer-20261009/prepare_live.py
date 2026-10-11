from pathlib import Path
import subprocess,json,urllib.request
root=Path(__file__).resolve().parents[2]; evidence=Path(__file__).parent
key=str(Path.home()/'.ssh/id_ed25519_tencent_blog'); host='ubuntu@154.222.25.150'
subprocess.run(['ssh','-i',key,host,'cd /opt/mathdoc/service && sudo /opt/mathdoc/api-env/bin/python -m api.admin issue --output /var/lib/mathdoc-admin/credentials/office-test-20261009.json && sudo install -m 600 -o ubuntu -g ubuntu /var/lib/mathdoc-admin/credentials/office-test-20261009.json /home/ubuntu/office-test-20261009.json'],check=True)
secret=root/'.local/document-service/credentials/office-test-20261009.json'; secret.parent.mkdir(parents=True,exist_ok=True)
subprocess.run(['scp','-i',key,host+':/home/ubuntu/office-test-20261009.json',str(secret)],check=True)
subprocess.run(['icacls',str(secret),'/inheritance:r','/grant:r','Hfff:F'],check=True,stdout=subprocess.DEVNULL)
credential=json.loads(secret.read_text())
request=urllib.request.Request(credential['baseUrl']+'/api/v1/capabilities',headers={'Authorization':'Bearer '+credential['token']})
capability=json.load(urllib.request.urlopen(request,timeout=30)); assert capability['ready'] and capability['officePdf']['ready']
(evidence/'live-capabilities.json').write_text(json.dumps(capability,indent=2))
adb=str(root/'.local/android-sdk/platform-tools/adb.exe')
subprocess.run([adb,'shell','run-as','org.mathstudy.agent.debug','sh','-c','"umask 077; cat > cache/office-service-test-credentials.json"'],input=secret.read_bytes(),check=True)
print('Isolated Office test identity prepared; MinerU and LibreOffice ready')
