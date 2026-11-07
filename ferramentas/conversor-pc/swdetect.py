import sys, os, math, collections, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import nbt2
GLASS = {20, 102}
def scan(wd):
    chests, furn, glass, top = [], [], set(), {}
    for (rx, rz), (path, fmt) in nbt2.regions(wd).items():
        for idx, nm, root in nbt2.chunks(path):
            lv = nbt2.level(root); cx, cz = lv['xPos'].v, lv['zPos'].v
            for te in (lv['TileEntities'].v if 'TileEntities' in lv else []):
                t = str(te.v['id'].v); p = (te.v['x'].v, te.v['y'].v, te.v['z'].v)
                if t == 'Chest': chests.append(p)
                if t == 'Furnace': furn.append(p)
            for s in (lv['Sections'].v if 'Sections' in lv else []):
                Y = s.v['Y'].v; bl = s.v['Blocks'].v
                for i, b in enumerate(bl):
                    if not b: continue
                    x = cx * 16 + (i & 15); z = cz * 16 + ((i >> 4) & 15); y = Y * 16 + (i >> 8)
                    if b in GLASS: glass.add((x, y, z))
                    k = (x, z)
                    if b not in GLASS and b not in (18, 161, 31, 32, 37, 38, 106, 175, 50) and (k not in top or y > top[k]): top[k] = y
    return chests, furn, glass, top
def clusters(points, dist):
    pts = list(points); seen = set(); out = []
    for i, p in enumerate(pts):
        if i in seen: continue
        comp = [p]; seen.add(i); stack = [p]
        while stack:
            q = stack.pop()
            for j, r in enumerate(pts):
                if j not in seen and abs(q[0]-r[0]) <= dist and abs(q[1]-r[1]) <= dist and abs(q[2]-r[2]) <= dist: seen.add(j); comp.append(r); stack.append(r)
        out.append(comp)
    return out
def cages(glass):
    out = []
    for comp in clusters(glass, 1):
        xs = [p[0] for p in comp]; ys = [p[1] for p in comp]; zs = [p[2] for p in comp]
        w, h, d = max(xs)-min(xs)+1, max(ys)-min(ys)+1, max(zs)-min(zs)+1
        if 3 <= w <= 5 and 3 <= d <= 5 and 3 <= h <= 6 and len(comp) >= 8:
            cx, cz = (min(xs)+max(xs)) / 2.0 + 0.5, (min(zs)+max(zs)) / 2.0 + 0.5
            out.append((cx, min(ys) + 1, cz))
    return out
def surface(top, x, z, near=4):
    best = None
    for r in range(0, near + 1):
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                k = (x + dx, z + dz)
                if k in top and (best is None or top[k] > best[1]): best = (k, top[k])
        if best: return best[0][0] + 0.5, best[1] + 1, best[0][1] + 0.5
    return None
def detect(wd):
    chests, furn, glass, top = scan(wd)
    groups = clusters(chests, 12)
    groups.sort(key=len, reverse=True)
    allc = [sum(p[i] for p in chests) / len(chests) for i in range(3)]
    center_g = min(groups, key=lambda g: math.hypot(sum(p[0] for p in g)/len(g)-allc[0], sum(p[2] for p in g)/len(g)-allc[2]))
    mx = sum(p[0] for p in center_g) / len(center_g); mz = sum(p[2] for p in center_g) / len(center_g)
    islands = [g for g in groups if g is not center_g]
    cg = cages(glass)
    cg = [c for c in cg if math.hypot(c[0]-mx, c[2]-mz) > 12]
    mode = 'jaulas'
    spawns = cg
    if len(cg) < 4:
        mode = 'ilhas'; spawns = []
        for g in islands:
            ix = sum(p[0] for p in g) / len(g); iz = sum(p[2] for p in g) / len(g)
            # um pouco para o lado do centro, na superficie mais alta perto dali
            dx, dz = mx - ix, mz - iz; L = math.hypot(dx, dz) or 1
            s = surface(top, int(math.floor(ix + dx / L * 2)), int(math.floor(iz + dz / L * 2)))
            if s: spawns.append(s)
    spawns.sort(key=lambda s: math.atan2(s[2]-mz, s[0]-mx))
    meio = surface(top, int(math.floor(mx)), int(math.floor(mz)), 6)
    hi = max(top.values()) if top else 100
    return dict(modo=mode, jaulas=spawns, meio=meio, espectador=(round(mx, 1) + 0.5, min(hi + 8, 250), round(mz, 1) + 0.5), ilhas=len(islands), baus=len(chests), grupos=[len(g) for g in groups])
if __name__ == '__main__':
    r = detect(sys.argv[1])
    print(json.dumps(r))
