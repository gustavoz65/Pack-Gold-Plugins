# Converte um mundo Anvil do Minecraft PC 1.8 para o Nukkit 0.15 (copia em outra pasta).
import sys, os, gzip, json, re, struct, zlib, shutil, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import nbt2
from nbt2 import T
# PC id -> (PE id, meta fixa ou None para manter)
REMAP = {95: (20, 0), 160: (102, 0), 166: (0, 0), 176: (0, 0), 177: (0, 0), 36: (0, 0), 34: (0, 0), 137: (0, 0), 90: (0, 0), 119: (0, 0),
         125: (157, None), 126: (158, None), 157: (126, None), 158: (125, None),
         188: (85, 1), 189: (85, 2), 190: (85, 3), 191: (85, 5), 192: (85, 4), 52: (0, 0), 84: (0, 0)}
POT = {'red_flower': 38, 'yellow_flower': 37, 'sapling': 6, 'cactus': 81, 'brown_mushroom': 39, 'red_mushroom': 40, 'deadbush': 32, 'tallgrass': 31}
DROP_TILES = {'MobSpawner', 'Banner', 'Control', 'RecordPlayer', 'Beacon', 'Airportal', 'EndGateway'}
def sign_text(v):
    v = str(v)
    if v.startswith('{') or v.startswith('"'):
        try:
            j = json.loads(v)
            def flat(o):
                if isinstance(o, str): return o
                if isinstance(o, dict): return o.get('text', '') + ''.join(flat(e) for e in o.get('extra', []))
                if isinstance(o, list): return ''.join(flat(e) for e in o)
                return ''
            v = flat(j)
        except Exception: pass
    return v if v != 'null' else ''
def nib_get(data, i): return (data[i >> 1] >> ((i & 1) * 4)) & 0xF
def nib_set(data, i, val):
    b = data[i >> 1]
    data[i >> 1] = (b & 0xF0) | (val & 0xF) if (i & 1) == 0 else (b & 0x0F) | ((val & 0xF) << 4)
def convert(src, dst, name):
    if os.path.exists(dst): shutil.rmtree(dst)
    os.makedirs(os.path.join(dst, 'region'))
    st = collections.Counter()
    for (rx, rz), (path, fmt) in nbt2.regions(src).items():
        out = os.path.join(dst, 'region', os.path.basename(path))
        shutil.copyfile(path, out)
        for idx, nm, root in list(nbt2.chunks(out)):
            lv = nbt2.level(root)
            for s in (lv['Sections'].v if 'Sections' in lv else []):
                blocks = bytearray(s.v['Blocks'].v); data = bytearray(s.v['Data'].v) if 'Data' in s.v else bytearray(2048)
                if 'Add' in s.v: del s.v['Add']
                for i, b in enumerate(blocks):
                    if b in REMAP:
                        nb, meta = REMAP[b]; blocks[i] = nb; st['bloco %d->%d' % (b, nb)] += 1
                        if meta is not None: nib_set(data, i, meta)
                s.v['Blocks'].v = bytes(blocks); s.v['Data'] = T(7, bytes(data))
            if 'Entities' in lv: st['entidades'] += len(lv['Entities'].v); lv['Entities'].v = []; lv['Entities'].et = 10
            if 'TileTicks' in lv: del lv['TileTicks']
            keep = []
            for te in (lv['TileEntities'].v if 'TileEntities' in lv else []):
                tid = str(te.v['id'].v) if 'id' in te.v else ''
                if tid in DROP_TILES: st['tile removida ' + tid] += 1; continue
                if 'Items' in te.v: te.v['Items'].v = []; te.v['Items'].et = 10; st['itens limpos ' + tid] += 1
                if tid == 'Sign':
                    for k in ('Text1', 'Text2', 'Text3', 'Text4'):
                        if k in te.v: te.v[k] = T(8, sign_text(te.v[k].v))
                if tid == 'FlowerPot' and 'Item' in te.v and te.v['Item'].t == 8:
                    item = te.v['Item'].v.replace('minecraft:', ''); data = te.v['Data'].v if 'Data' in te.v else 0
                    del te.v['Item']; te.v.pop('Data', None); te.v['item'] = T(2, POT.get(item, 0)); te.v['mData'] = T(3, int(data))
                keep.append(te)
            if 'TileEntities' in lv: lv['TileEntities'].v = keep
            nbt2.write_chunk(out, idx, nm, root)
    nm, r = nbt2.load(gzip.open(os.path.join(src, 'level.dat')).read()); d = r.v['Data'].v
    d['LevelName'] = T(8, name); d['generatorName'] = T(8, 'void'); d['generatorOptions'] = T(8, '')
    for k in ('Player',): d.pop(k, None)
    open(os.path.join(dst, 'level.dat'), 'wb').write(gzip.compress(nbt2.dump(nm, r)))
    return st
if __name__ == '__main__':
    src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
    st = convert(src, dst, name)
    print(name, dict(st.most_common(8)))
