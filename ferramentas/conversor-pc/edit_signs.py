import sys,os,re; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); import nbt2
from nbt2 import T
# uso: edit_signs.py <pasta_mundo> <regex_trocar|-> <regex_apagar|->
wd,rep,dele=sys.argv[1],sys.argv[2],sys.argv[3]
HY=re.compile(r'(?i)hytale((?:§.)*)\s*mc')
for (rx,rz),(path,fmt) in nbt2.regions(wd).items():
    for idx,nm,root in list(nbt2.chunks(path)):
        lv=nbt2.level(root); changed=False
        tes=lv.get('TileEntities')
        if not tes: continue
        keep=[]
        for te in tes.v:
            if not (te.v.get('id') and te.v['id'].v=='Sign'): keep.append(te); continue
            txt=' | '.join(nbt2.sign_text(te)); x,y,z=te.v['x'].v,te.v['y'].v,te.v['z'].v
            if dele!='-' and re.search(dele,txt,re.I):
                # apaga o bloco da placa e a tile entity
                if fmt=='mca':
                    for s in lv['Sections'].v:
                        if s.v['Y'].v==(y>>4):
                            b=bytearray(s.v['Blocks'].v); b[(y&15)*256+(z&15)*16+(x&15)]=0; s.v['Blocks'].v=bytes(b)
                else:
                    b=bytearray(lv['Blocks'].v); b[((x&15)<<11)|((z&15)<<7)|y]=0; lv['Blocks'].v=bytes(b)
                print(f"APAGADA {x},{y},{z}: {re.sub('§.','',txt)}"); changed=True; continue
            if rep!='-' and HY.search(txt):
                for k in ('Text1','Text2','Text3','Text4'):
                    if k in te.v: te.v[k].v=HY.sub(r'Rush\1 MC',te.v[k].v)
                print(f"TROCADA {x},{y},{z}: {re.sub('§.','',' | '.join(nbt2.sign_text(te)))}"); changed=True
            keep.append(te)
        if changed:
            tes.v=keep
            nbt2.write_chunk(path,idx,nm,root)
