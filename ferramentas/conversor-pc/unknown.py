import sys,os,collections; sys.path.insert(0,os.path.dirname(os.path.abspath(__file__))); import nbt2
UNKNOWN={23,29,33,34,36,75,77,84,93,94,97,115,119,122,125,130,131,132,137,138,143,146,149,150,154,160,166,168,169,176,177,179,180,181,182,188,189,190,191,192}|set(range(200,243))|set(range(247,256))
def scan(wd):
    c=collections.Counter()
    for _,(p,f) in nbt2.regions(wd).items():
        for i,nm,r in nbt2.chunks(p):
            for s in (nbt2.level(r)['Sections'].v if 'Sections' in nbt2.level(r) else []):
                if 'Blocks' not in s.v: continue
                for b in s.v['Blocks'].v:
                    if b in UNKNOWN: c[b]+=1
    return c
if __name__=='__main__':
    for wd in sys.argv[1:]:
        c=scan(wd); print(f"{os.path.basename(wd):16} {'OK' if not c else dict(c)}")
