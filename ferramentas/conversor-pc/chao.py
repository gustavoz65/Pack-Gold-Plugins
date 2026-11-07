import sys,os,math; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); import nbt2, struct, zlib, gzip
class World:
    def __init__(s,wd): s.regs=nbt2.regions(wd); s.cache={}
    def chunk(s,cx,cz):
        k=(cx,cz)
        if k in s.cache: return s.cache[k]
        r=s.regs.get((cx>>5,cz>>5)); res=None
        if r:
            with open(r[0],'rb') as fh:
                fh.seek(4*((cx&31)+(cz&31)*32)); loc=struct.unpack('>I',fh.read(4))[0]
                if loc:
                    fh.seek((loc>>8)*4096); ln,ct=struct.unpack('>IB',fh.read(5)); d=fh.read(ln-1)
                    nm,root=nbt2.load(zlib.decompress(d) if ct==2 else gzip.decompress(d)); lv=nbt2.level(root)
                    if r[1]=='mca':
                        secs={x.v['Y'].v:x.v['Blocks'].v for x in lv['Sections'].v}; res=('mca',secs)
                    else: res=('mcr',lv['Blocks'].v)
        s.cache[k]=res; return res
    def get(s,x,y,z):
        if y<0 or y>255: return 0
        c=s.chunk(x>>4,z>>4)
        if not c: return 0
        if c[0]=='mca':
            b=c[1].get(y>>4); return b[(y&15)*256+(z&15)*16+(x&15)] if b else 0
        if y>127: return 0
        return c[1][((x&15)<<11)|((z&15)<<7)|y]
PASS={0,6,31,32,37,38,39,40,50,51,59,63,68,69,70,72,75,76,77,78,83,104,105,106,111,141,142,143,147,148,171,175,323}
NOSTAND={8,9,10,11,30,51,81,85,101,102,107,113,139,183,184,185,186,187,188,189,190,191,192}
def spots(w,sx,sy,sz,rmin,rmax):
    out=[]
    for dx in range(-rmax,rmax+1):
        for dz in range(-rmax,rmax+1):
            d=math.hypot(dx,dz)
            if d<rmin or d>rmax: continue
            x,z=sx+dx,sz+dz
            for y in range(sy+3,sy-4,-1):
                f=w.get(x,y-1,z)
                if f and f not in PASS and f not in NOSTAND and w.get(x,y,z) in PASS and w.get(x,y+1,z) in PASS and w.get(x,y,z)==0 and w.get(x,y+1,z)==0:
                    out.append((x,y,z,round(d,1),f)); break
    return out
if __name__=='__main__':
    wd,sx,sy,sz=sys.argv[1],int(sys.argv[2]),int(sys.argv[3]),int(sys.argv[4]); rmin=float(sys.argv[5]); rmax=int(sys.argv[6])
    w=World(wd); sp=spots(w,sx,sy,sz,rmin,rmax)
    print(f"{wd}: spawn {sx},{sy},{sz}; chao sob spawn id={w.get(sx,sy-1,sz)}; {len(sp)} pontos livres entre {rmin} e {rmax} blocos")
    # mapa ASCII do entorno: '#' chao livre na altura do spawn (+-1), 'S' spawn, '.' sem chao
    for dz in range(-rmax,rmax+1):
        row=''
        for dx in range(-rmax,rmax+1):
            if dx==0 and dz==0: row+='S'; continue
            m=[p for p in sp if p[0]==sx+dx and p[2]==sz+dz]
            row+= ('#' if m and abs(m[0][1]-sy)<=1 else '+' if m else '.')
        print('   '+row)
