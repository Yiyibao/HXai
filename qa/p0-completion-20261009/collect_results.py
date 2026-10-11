from pathlib import Path
import subprocess,tarfile,io
root=Path(__file__).resolve().parents[2]; target=Path(__file__).parent/'collected'
target.mkdir(exist_ok=True)
adb=str(root/'.local/android-sdk/platform-tools/adb.exe')
for name in ('p0-live-dispatch','p0-live-mixed','p0-routing','p0-rich-output'):
    result=subprocess.run([adb,'exec-out','run-as','org.mathstudy.agent.debug','tar','-C','cache','-cf','-',name],capture_output=True,check=True)
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as archive:
        for member in archive:
            path=(target/member.name).resolve()
            assert path.is_relative_to(target.resolve()) and not member.issym() and not member.islnk()
        archive.extractall(target,filter='data')
    print('Collected synthetic evidence:',name)
