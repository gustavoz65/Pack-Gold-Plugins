# NBT com tipos preservados (leitura e escrita), regiões .mca/.mcr
import struct, zlib, gzip, os, re
class T:  # valor tipado
    __slots__=('t','v','et')
    def __init__(s,t,v,et=None): s.t=t; s.v=v; s.et=et
F={1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}
def _rs(b,i): n=struct.unpack_from('>H',b,i)[0]; return b[i+2:i+2+n].decode('utf-8','surrogateescape'), i+2+n
def _p(b,i,t):
    if t in F: f='>'+F[t]; return T(t,struct.unpack_from(f,b,i)[0]), i+struct.calcsize(f)
    if t==7: n=struct.unpack_from('>i',b,i)[0]; return T(7,b[i+4:i+4+n]), i+4+n
    if t==8: s,i=_rs(b,i); return T(8,s),i
    if t==9:
        et=b[i]; n=struct.unpack_from('>i',b,i+1)[0]; i+=5; o=[]
        for _ in range(n): v,i=_p(b,i,et); o.append(v)
        return T(9,o,et),i
    if t==10:
        d={}
        while True:
            tt=b[i]; i+=1
            if tt==0: return T(10,d),i
            k,i=_rs(b,i); v,i=_p(b,i,tt); d[k]=v
    if t==11: n=struct.unpack_from('>i',b,i)[0]; return T(11,b[i+4:i+4+4*n]), i+4+4*n
    if t==12: n=struct.unpack_from('>i',b,i)[0]; return T(12,b[i+4:i+4+8*n]), i+4+8*n
    raise ValueError(t)
def load(b):
    t=b[0]; name,i=_rs(b,1); v,_=_p(b,i,t); return name,v
def _ws(s): e=s.encode('utf-8','surrogateescape'); return struct.pack('>H',len(e))+e
def _w(x):
    t=x.t
    if t in F: return struct.pack('>'+F[t],x.v)
    if t==7: return struct.pack('>i',len(x.v))+x.v
    if t==8: return _ws(x.v)
    if t==9: return bytes([x.et if x.v==[] or x.et else x.v[0].t])+struct.pack('>i',len(x.v))+b''.join(_w(e) for e in x.v)
    if t==10: return b''.join(bytes([v.t])+_ws(k)+_w(v) for k,v in x.v.items())+b'\0'
    if t==11: return struct.pack('>i',len(x.v)//4)+x.v
    if t==12: return struct.pack('>i',len(x.v)//8)+x.v
def dump(name,v): return bytes([v.t])+_ws(name)+_w(v)
def regions(wd):
    out={}; rd=os.path.join(wd,'region')
    if os.path.isdir(rd):
        for f in os.listdir(rd):
            m=re.match(r'r\.(-?\d+)\.(-?\d+)\.(mca|mcr)$',f)
            if m: out[(int(m[1]),int(m[2]))]=(os.path.join(rd,f),m[3])
    return out
def chunks(path):
    with open(path,'rb') as fh: data=fh.read()
    for idx in range(1024):
        loc=struct.unpack_from('>I',data,4*idx)[0]
        if not loc: continue
        off=(loc>>8)*4096
        if off+5>len(data): continue
        ln,ct=struct.unpack_from('>IB',data,off)
        raw=data[off+5:off+4+ln]
        try: nm,root=load(zlib.decompress(raw) if ct==2 else gzip.decompress(raw))
        except Exception: continue
        yield idx,nm,root
def write_chunk(path,idx,name,root):
    payload=zlib.compress(dump(name,root))
    rec=struct.pack('>IB',len(payload)+1,2)+payload
    sectors=(len(rec)+4095)//4096
    with open(path,'r+b') as fh:
        fh.seek(4*idx); loc=struct.unpack('>I',fh.read(4))[0]
        if loc and (loc&0xff)>=sectors: off=loc>>8
        else:
            fh.seek(0,2); end=fh.tell(); off=(end+4095)//4096
        fh.seek(off*4096); fh.write(rec+b'\0'*(sectors*4096-len(rec)))
        fh.seek(4*idx); fh.write(struct.pack('>I',(off<<8)|sectors))
def level(root): return root.v['Level'].v if 'Level' in root.v else root.v
def signs(lv):
    for te in (lv.get('TileEntities').v if lv.get('TileEntities') else []):
        if te.v.get('id') and te.v['id'].v=='Sign': yield te
def sign_text(te): return [str(te.v[k].v) if k in te.v else '' for k in ('Text1','Text2','Text3','Text4')]
