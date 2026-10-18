import struct, zlib, bz2
def _ct():
    t=[0]*0x500; seed=0x00100001
    for i in range(0x100):
        idx=i
        for j in range(5):
            seed=(seed*125+3)%0x2AAAAB; a=(seed&0xFFFF)<<16
            seed=(seed*125+3)%0x2AAAAB; t[idx]=a|(seed&0xFFFF); idx+=0x100
    return t
T=_ct()
def hs(s,ht):
    s1,s2=0x7FED7FED,0xEEEEEEEE
    for c in s.upper().replace('/','\\').encode('latin1'):
        s1=(T[ht*0x100+c]^((s1+s2)&0xFFFFFFFF))&0xFFFFFFFF; s2=(c+s1+s2+((s2<<5)&0xFFFFFFFF)+3)&0xFFFFFFFF
    return s1
def dec(data,key):
    out=bytearray(); s2=0xEEEEEEEE
    for (v,) in struct.iter_unpack('<I',data[:len(data)//4*4]):
        s2=(s2+T[0x400+(key&0xFF)])&0xFFFFFFFF; d=v^((key+s2)&0xFFFFFFFF); out+=struct.pack('<I',d)
        key=((((~key)<<0x15)+0x11111111)|(key>>0x0B))&0xFFFFFFFF; s2=(d+s2+((s2<<5)&0xFFFFFFFF)+3)&0xFFFFFFFF
    return bytes(out)+data[len(data)//4*4:]
class Mpq:
    def __init__(self,path):
        self.f=open(path,'rb'); b=self.f.read(1<<20); o=b.find(b'MPQ\x1a'); self.base=o
        hs_,self.asz,fv,ss,hto,bto,htn,btn=struct.unpack('<IIHHIIII',b[o+4:o+32])
        self.sector=512<<ss
        self.f.seek(o+hto); self.ht=struct.unpack(f'<{htn*4}I',dec(self.f.read(htn*16),hs('(hash table)',3)))
        self.f.seek(o+bto); self.bt=struct.unpack(f'<{btn*4}I',dec(self.f.read(btn*16),hs('(block table)',3)))
        self.htn=htn
    def find(self,name):
        i=hs(name,0)%self.htn; a,b=hs(name,1),hs(name,2)
        for k in range(self.htn):
            e=self.ht[(i+k)%self.htn*4:(i+k)%self.htn*4+4]
            if e[3]==0xFFFFFFFF: return None
            if e[0]==a and e[1]==b and e[3]!=0xFFFFFFFE: return e[3]
        return None
    def read(self,name):
        bi=self.find(name)
        if bi is None: return None
        off,cs,fs,fl=self.bt[bi*4:bi*4+4]
        if fl&0x10000: raise ValueError('encrypted '+name)
        self.f.seek(self.base+off); raw=self.f.read(cs)
        if fl&0x01000000:
            return self._d(raw,fs) if cs<fs else raw
        if not (fl&0x200): return raw[:fs]
        ns=(fs+self.sector-1)//self.sector; tab=struct.unpack(f'<{ns+1}I',raw[:(ns+1)*4]); out=b''
        for s in range(ns):
            chunk=raw[tab[s]:tab[s+1]]; want=min(self.sector,fs-s*self.sector)
            out+=self._d(chunk,want) if len(chunk)<want else chunk
        return out
    def _d(self,c,n):
        m=c[0]; d=c[1:]
        if m==2: return zlib.decompress(d)
        if m==0x10: return bz2.decompress(d)
        raise ValueError(f'compression {m:#x}')
    def listfile(self):
        d=self.read('(listfile)'); return d.decode('latin1').split() if d else []
