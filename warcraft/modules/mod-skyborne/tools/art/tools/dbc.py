import struct
class Dbc:
    def __init__(self, path):
        b = open(path, 'rb').read()
        _, self.n, self.f, self.rs, self.ss = struct.unpack('<4s4I', b[:20])
        self.raw = [b[20+i*self.rs:20+(i+1)*self.rs] for i in range(self.n)]
        self.text = b[20+self.n*self.rs:]
        self.rows = [list(struct.unpack(f'<{self.f}I', r)) if self.rs == self.f*4 else list(r) for r in self.raw]
    def s(self, off):
        e = self.text.index(b'\0', off); return self.text[off:e].decode('utf-8', 'replace')
    def f32(self, v): return struct.unpack('<f', struct.pack('<I', v))[0]
