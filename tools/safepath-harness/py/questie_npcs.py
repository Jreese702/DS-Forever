# QuestieDB_Forever's creatures and quests, read from its .toc (the metadata lines "## X-<Kind>-<id>-<field>: <base64 CBOR>", long values
# split "~N~" into parts "-1".."-N"), for checking who is friend or foe: a creature's friendlyToFaction (field 13: none = attacks both,
# "A" / "H" / "AH"), the quests it starts (10) and ends (11), and the quests whose objectives (quest field 10) ask to kill it.
#   python questie_npcs.py "<name regex>" [area id]
import os, sys, re, base64, struct
try: sys.stdout.reconfigure(encoding='utf-8')     # the routes' names carry arrows (Windows' console is cp1252)
except Exception: pass
TOC = os.environ.get('QDBTOC', 'E:/wow-build/questiedb/unz/QuestieDB/QuestieDB_Forever.toc')

def cbor(b, i=0):
    """a minimal CBOR decoder (maps, arrays, ints, strings, floats, simple values): (value, next index)"""
    ib = b[i]; mt = ib >> 5; ai = ib & 31; i += 1
    def arg(ai, i):
        if ai < 24: return ai, i
        n = {24: 1, 25: 2, 26: 4, 27: 8}[ai]
        return int.from_bytes(b[i:i + n], 'big'), i + n
    if mt == 7:
        if ai == 20: return False, i
        if ai == 21: return True, i
        if ai in (22, 23): return None, i
        if ai == 25:
            h = int.from_bytes(b[i:i + 2], 'big'); s = (h >> 15) & 1; e = (h >> 10) & 31; f = h & 1023
            v = (f / 1024.0) * 2 ** -14 if e == 0 else (1 + f / 1024.0) * 2 ** (e - 15) if e < 31 else float('inf')
            return (-v if s else v), i + 2
        if ai == 26: return struct.unpack('>f', b[i:i + 4])[0], i + 4
        if ai == 27: return struct.unpack('>d', b[i:i + 8])[0], i + 8
        return None, i
    n, i = arg(ai, i)
    if mt == 0: return n, i
    if mt == 1: return -1 - n, i
    if mt == 2: return bytes(b[i:i + n]), i + n
    if mt == 3: return b[i:i + n].decode('utf-8', 'replace'), i + n
    if mt == 4:
        out = []
        for _ in range(n): v, i = cbor(b, i); out.append(v)
        return out, i
    if mt == 5:
        out = {}
        for _ in range(n): k, i = cbor(b, i); v, i = cbor(b, i); out[k] = v
        return out, i
    if mt == 6: return cbor(b, i)
    raise ValueError('cbor major type %d' % mt)

def load(kinds=('Npc', 'Quest')):
    """{kind: {id: {field: value}}} for the wanted kinds' fields"""
    raw = {}; parts = {}
    pat = re.compile(r'^## X-(%s)-(\d+)-([A-Za-z0-9]+)(?:-(\d+))?: (.*)$' % '|'.join(kinds))
    for line in open(TOC, encoding='utf-8', errors='replace'):
        m = pat.match(line.rstrip('\n'))
        if not m: continue
        kind, id_, field, part, val = m.group(1), int(m.group(2)), m.group(3), m.group(4), m.group(5)
        key = (kind, id_, field)
        if part is None:
            mm = re.match(r'^~(\d+)~$', val)
            if mm: parts[key] = [None] * int(mm.group(1))
            else: raw[key] = val
        else:
            arr = parts.get(key)
            if arr is None: continue
            arr[int(part) - 1] = val
            if all(v is not None for v in arr): raw[key] = ''.join(arr); del parts[key]
    out = {k: {} for k in kinds}
    for (kind, id_, field), val in raw.items():
        try: v, _ = cbor(base64.b64decode(val))
        except Exception: continue
        out[kind].setdefault(id_, {})[field] = v
    return out

def name(v): return v.decode('utf-8', 'replace') if isinstance(v, bytes) else v

if __name__ == '__main__':
    pat = re.compile(sys.argv[1], re.I); area = int(sys.argv[2]) if len(sys.argv) > 2 else None
    db = load()
    npcs = db['Npc']; quests = db['Quest']
    # the quests that ask to kill each creature (objectives: field 10, its first list the creatures [[id, text], ...])
    kills = {}
    for qid, q in quests.items():
        obj = q.get('10')
        if not isinstance(obj, list) or not obj or not isinstance(obj[0], list): continue
        for c in obj[0]:
            if isinstance(c, list) and c and isinstance(c[0], int): kills.setdefault(c[0], []).append(qid)
    def qname(qid): return name(quests.get(qid, {}).get('S', {}).get(1, '?')) if isinstance(quests.get(qid, {}).get('S'), dict) else '?'
    for nid, f in sorted(npcs.items()):
        s = f.get('S') if isinstance(f.get('S'), dict) else {}
        nm = name(s.get(1, ''))
        if not nm or not pat.search(nm): continue
        sp = f.get('7') if isinstance(f.get('7'), dict) else {}
        if area is not None and area not in sp: continue
        fr = name(s.get(13)); starts = f.get('10') or []; ends = f.get('11') or []
        print('%6d %-32s lv %s-%s  friendlyTo %-4s  spawns %4d  starts %s  ends %s  killed by %s' % (
            nid, nm, s.get(4), s.get(5), fr or '-', sum(len(v) for v in sp.values() if isinstance(v, list)),
            [(q, qname(q)) for q in starts][:4], [(q, qname(q)) for q in ends][:4], [(q, qname(q)) for q in kills.get(nid, [])][:4]))
