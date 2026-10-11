"""Inspect each shipped native binary; ELF alignment is not a 16 KB device test."""
from pathlib import Path
import hashlib
import json
import struct
import zipfile
ROOT=Path(__file__).resolve().parents[1]
QA=ROOT/'qa/warnings-review-20261008'
def elf(data):
 if data[:4]!=b'\x7fELF':return {'error':'not ELF'}
 endian='<' if data[5]==1 else '>'
 if data[4]==2:
  off=struct.unpack_from(endian+'Q',data,32)[0];size,num=struct.unpack_from(endian+'HH',data,54)
  headers=[struct.unpack_from(endian+'IIQQQQQQ',data,off+i*size) for i in range(num)]
  segments=[{'offset':h[2],'vaddr':h[3],'align':h[7]} for h in headers if h[0]==1]
 else:
  off=struct.unpack_from(endian+'I',data,28)[0];size,num=struct.unpack_from(endian+'HH',data,42)
  headers=[struct.unpack_from(endian+'IIIIIIII',data,off+i*size) for i in range(num)]
  segments=[{'offset':h[1],'vaddr':h[2],'align':h[7]} for h in headers if h[0]==1]
 return {'load_segments':segments,'elf_16kb_compatible':bool(segments) and all(x['align']>=16384 and (x['vaddr']-x['offset'])%16384==0 for x in segments)}
result=[]
for apk in (ROOT/'client/app/build/outputs/apk/debug').glob('*.apk'):
 apk_digest=hashlib.sha256(apk.read_bytes()).hexdigest()
 with zipfile.ZipFile(apk) as archive:
  for item in archive.infolist():
   if item.filename.startswith('lib/') and item.filename.endswith('.so'):
    data=archive.read(item)
    result.append({'apk':apk.name,'apk_sha256':apk_digest,'entry':item.filename,'sha256':hashlib.sha256(data).hexdigest(),
                   'compressed':item.compress_type!=zipfile.ZIP_STORED,**elf(data)})
(QA/'packaged-native-alignment.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps([x for x in result if x['entry'].endswith('/libtermux.so')],indent=2))
