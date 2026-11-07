#!/usr/bin/env python3
"""Recalcula a luz do ceu (SkyLight) de um mundo Anvil (.mca) - mapas convertidos do PC as vezes vem com luz zero
e ficam escuros no MCPE 0.15 mesmo ao meio-dia (o cliente usa a luz que o servidor manda).

uso: python3 -I reluz.py <pasta_do_mundo> [y_min y_max]
Rode SOMENTE com o mundo descarregado (de preferencia numa copia). Precisa de numpy.
Por regiao: luz 15 acima do primeiro bloco opaco de cada coluna e espalhamento (-1 por bloco) para baixo de
marquises e telhados, entre y_min e y_max (padrao 40-127). Abaixo de y_min a luz original fica.
"""
import os, struct, sys, zlib, gzip
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt2

# Blocos que deixam a luz passar (ar, vidro, folhas, plantas, tochas, placas, cercas, escadas, lajes, agua...).
TRANSP = {0, 6, 8, 9, 10, 11, 18, 20, 26, 27, 28, 30, 31, 32, 37, 38, 39, 40, 44, 50, 51, 53, 54, 55, 59, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72,
          75, 76, 77, 78, 79, 81, 83, 85, 92, 96, 101, 102, 104, 105, 106, 107, 108, 109, 111, 113, 114, 114, 115, 116, 117, 118, 120, 126, 127,
          128, 131, 132, 134, 135, 136, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 156, 158, 160, 161, 163, 164, 167, 171, 175, 180,
          182, 183, 184, 185, 186, 187, 193, 194, 195, 196, 197, 198, 199}
LUT = np.zeros(256, dtype=bool)
for b in TRANSP: LUT[b] = True


def relight_region(path, ymin, ymax):
    chunks = {}
    for idx, name, root in nbt2.chunks(path):
        chunks[idx] = (name, root)
    if not chunks: return 0
    B = np.zeros((256, 512, 512), dtype=np.uint8)
    old = {}
    for idx, (name, root) in chunks.items():
        lv = nbt2.level(root); cx, cz = idx % 32, idx // 32
        for sec in lv['Sections'].v:
            y = sec.v['Y'].v
            if y < 0 or y > 15: continue
            blk = np.frombuffer(bytes(x & 0xFF for x in sec.v['Blocks'].v) if isinstance(sec.v['Blocks'].v, list) else bytes(sec.v['Blocks'].v), dtype=np.uint8).reshape(16, 16, 16)
            B[y * 16:y * 16 + 16, cz * 16:cz * 16 + 16, cx * 16:cx * 16 + 16] = blk
    transp = LUT[B]
    opaque = ~transp
    blocked = np.flip(np.maximum.accumulate(np.flip(opaque, 0), axis=0), 0)  # ha bloco opaco nesta altura ou acima
    L = np.where(blocked, 0, 15).astype(np.int8)
    sub = L[ymin:ymax + 1]; t = transp[ymin:ymax + 1]
    for _ in range(14):
        n = sub.copy()
        n[1:] = np.maximum(n[1:], sub[:-1] - 1); n[:-1] = np.maximum(n[:-1], sub[1:] - 1)
        n[:, 1:] = np.maximum(n[:, 1:], sub[:, :-1] - 1); n[:, :-1] = np.maximum(n[:, :-1], sub[:, 1:] - 1)
        n[:, :, 1:] = np.maximum(n[:, :, 1:], sub[:, :, :-1] - 1); n[:, :, :-1] = np.maximum(n[:, :, :-1], sub[:, :, 1:] - 1)
        n = np.where(t, np.maximum(n, 0), 0).astype(np.int8)
        if np.array_equal(n, sub): break
        sub = n
    L[ymin:ymax + 1] = sub
    changed = 0
    for idx, (name, root) in chunks.items():
        lv = nbt2.level(root); cx, cz = idx % 32, idx // 32; dirty = False
        for sec in lv['Sections'].v:
            y = sec.v['Y'].v
            if y < 0 or y > 15 or y * 16 + 15 < ymin: continue
            part = L[y * 16:y * 16 + 16, cz * 16:cz * 16 + 16, cx * 16:cx * 16 + 16].reshape(-1).astype(np.uint8)
            if y * 16 < ymin:  # secao cortada pelo y_min: mantem a luz original abaixo dele
                orig = nibbles(sec.v['SkyLight'].v)
                keep = (ymin - y * 16) * 256
                part[:keep] = orig[:keep]
            packed = (part[0::2] | (part[1::2] << 4)).astype(np.uint8).tobytes()
            cur = sec.v['SkyLight'].v
            cur_b = bytes(x & 0xFF for x in cur) if isinstance(cur, list) else bytes(cur)
            if cur_b != packed:
                sec.v['SkyLight'].v = type(cur)(packed) if not isinstance(cur, list) else [b if b < 128 else b - 256 for b in packed]
                dirty = True
        if dirty:
            if 'LightPopulated' in lv: lv['LightPopulated'].v = 1
            nbt2.write_chunk(path, idx, name, root); changed += 1
    return changed


def nibbles(v):
    b = np.frombuffer(bytes(x & 0xFF for x in v) if isinstance(v, list) else bytes(v), dtype=np.uint8)
    out = np.empty(b.size * 2, dtype=np.uint8); out[0::2] = b & 15; out[1::2] = b >> 4
    return out


if __name__ == '__main__':
    wd = sys.argv[1]
    ymin = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    ymax = int(sys.argv[3]) if len(sys.argv) > 3 else 127
    total = 0
    for (rx, rz), (path, fmt) in sorted(nbt2.regions(wd).items()):
        if fmt != 'mca': print('pulando (nao e mca):', path); continue
        n = relight_region(path, ymin, ymax); total += n
        print(os.path.basename(path), 'chunks com luz corrigida:', n)
    print('total:', total)
