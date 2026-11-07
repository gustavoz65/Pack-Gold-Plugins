# Troca blocos que nao existem no MCPE 0.15 (derrubam o cliente) por equivalentes seguros. Edita no lugar.
import sys,os,collections; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); import nbt2
from pc18 import nib_set
SAFE={84:(5,0),119:(0,0),122:(0,0),130:(49,0),137:(1,0),138:(20,0),160:(102,0),166:(0,0),168:(98,0),169:(89,0),176:(0,0),177:(0,0),188:(85,1),189:(85,2),190:(85,3),191:(85,5),192:(85,4)}
DROP={'EnderChest','Beacon','Control','RecordPlayer','Banner'}
def sanear(wd):
    st=collections.Counter()
    for _,(p,f) in nbt2.regions(wd).items():
        for idx,nm,r in list(nbt2.chunks(p)):
            lv=nbt2.level(r); ch=False
            for s in (lv['Sections'].v if 'Sections' in lv else []):
                if 'Blocks' not in s.v: continue
                bl=bytearray(s.v['Blocks'].v); da=bytearray(s.v['Data'].v) if 'Data' in s.v else bytearray(2048); hit=False
                for i,b in enumerate(bl):
                    if b in SAFE:
                        nb,meta=SAFE[b]; bl[i]=nb; nib_set(da,i,meta); st[b]+=1; hit=True
                if hit: s.v['Blocks'].v=bytes(bl); s.v['Data']=nbt2.T(7,bytes(da)); ch=True
            if 'TileEntities' in lv:
                keep=[t for t in lv['TileEntities'].v if str(t.v['id'].v) not in DROP]
                if len(keep)!=len(lv['TileEntities'].v): st['tiles']+=len(lv['TileEntities'].v)-len(keep); lv['TileEntities'].v=keep; ch=True
            if ch: nbt2.write_chunk(p,idx,nm,r)
    return st
if __name__=='__main__':
    for wd in sys.argv[1:]: print(os.path.basename(os.path.dirname(wd)) + '/' + os.path.basename(wd), dict(sanear(wd)))
