"""Check the publisher's complete client with bounded HTTP ranges; never execute clients."""
import hashlib
import io
import json
import struct
import urllib.request
import zipfile

URL = 'http://ashamanecore.com/dicpics/LegionClient.zip'
class Remote(io.RawIOBase):
    def __init__(self):
        self.position = 0
        with urllib.request.urlopen(urllib.request.Request(URL, headers={'Range':'bytes=0-0'}), timeout=60) as r:
            self.size = int(r.headers['Content-Range'].split('/')[-1])
            self.etag = r.headers['ETag']
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.position
    def seek(self, offset, whence=0):
        self.position = offset + (0 if whence == 0 else self.position if whence == 1 else self.size)
        return self.position
    def read(self, count=-1):
        count = self.size-self.position if count < 0 else min(count, self.size-self.position)
        if count == 0: return b''
        if count > 64*1024*1024: raise ValueError('Unbounded inspection read')
        start=self.position; end=start+count-1
        request=urllib.request.Request(URL,headers={'Range':f'bytes={start}-{end}','If-Range':self.etag})
        with urllib.request.urlopen(request,timeout=180) as r:
            if r.status != 206 or r.headers['Content-Range'] != f'bytes {start}-{end}/{self.size}':
                raise ValueError('Source changed or ignored range')
            result=r.read(count+1)
        if len(result)!=count: raise ValueError('Incomplete range')
        self.position+=count
        return result

def emit(name,value): print(name+'='+json.dumps(value),flush=True)
remote=Remote()
with zipfile.ZipFile(remote) as archive:
    infos=archive.infolist()
    emit('FULL_CLIENT_ARCHIVE',{'url':URL,'bytes':remote.size,'etag':remote.etag,'files':len(infos),'expanded':sum(i.file_size for i in infos),'casc_bytes':sum(i.file_size for i in infos if i.filename.lower().startswith('data/data/'))})
    emit('ROOT_FILES',[i.filename for i in infos if '/' not in i.filename.strip('/')])
    for info in infos:
        if info.filename.lower() in {'wow-64.exe','wow.exe','spp_wow-64.exe','.build.info','wtf/config.wtf'}:
            data=archive.read(info) # validates the member's ZIP CRC
            value={'path':info.filename,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
            if info.filename.lower().endswith('.exe'):
                offset=data.find(bytes.fromhex('bd04effe'))
                high,low=struct.unpack_from('<II',data,offset+8)
                value['version']=[high>>16,high&65535,low>>16,low&65535]
            else: value['text']=data.decode(errors='replace')[:4000]
            emit('FULL_CLIENT_MEMBER',value)
