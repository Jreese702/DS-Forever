# THE CROSS-ZONE ROUTES (cross/routes.json): long routes through several zones and their links, as chains of one-zone legs.
#   python cross.py specs <run folder>     one spec per zone the routes cross (its own spec, the goals the legs), in <run>/specs;
#                                          prints "x_<zone>:<dir>" for each, for gate.sh's loop
#   python cross.py stitch <run folder>    joins the legs (each zone's run, gate_x_<zone>.log and levels_routes_x_<zone>.json),
#                                          checks every join and link, writes <run>/cross.json, prints a CROSS line per route
#   python cross.py draw <run folder>      the continent overviews and a strip per route, into <run>/cross/
# A ride's ends come from transports.json; a gap is a link we don't have: the route stops there and says what is missing.
import os, re, sys, json, math, copy
try: sys.stdout.reconfigure(encoding='utf-8', newline='\n')     # gate.sh reads its lines: no CR on Windows
except Exception: pass
HERE = os.path.dirname(os.path.abspath(__file__))
ZONES = os.path.join(HERE, '..', 'zones')
WORK = os.environ.get('SAFEPATH_WORK', 'E:/safepath-work')
ART = os.environ.get('ART_ROOT', os.path.join(WORK, 'artroot'))
JOIN_YD = 30.0      # a leg's end and the next leg's start: each moved at most 15 yd onto ground one can stand on
RIDE_YD = 60.0      # a leg's end and its ride's end (a dock's edge, a tower's top over the ground under it)
FORD = 2.2          # a narrow stream's limit (the user's ruling, 2026-10-18); wetcheck's runs are the gate's

def routes(): return json.load(open(os.path.join(HERE, '..', 'cross', 'routes.json'), encoding='utf-8'))['routes']
def rides():
    return {r['p']: r for r in json.load(open(os.path.join(ART, 'transports.json'), encoding='utf-8'))['rides']}
def resolve(o, rd):
    """a goal object with a ride's end ({"ride": p, "end": "a"}) made a plain point"""
    if 'ride' in o: r = rd[o['ride']]; return {'xy': r[o['end']][:2], 'within': RIDE_YD}
    return dict(o)
def legs(rts):
    """(route, piece index, key, piece) for each leg; the key ("a1") is its route's id and its number"""
    out = []
    for r in rts:
        n = 0
        for i, p in enumerate(r['pieces']):
            if 'zone' in p: n += 1; out.append((r, i, '%s%d' % (r['id'], n), p))
    return out

def specs(run):
    rd = rides(); os.makedirs(os.path.join(run, 'specs'), exist_ok=True)
    byzone = {}
    for r, i, key, p in legs(routes()):
        fr = resolve(p['from'], rd); fr['name'] = key
        byzone.setdefault(p['zone'], []).append({'name': p['name'], 'from': fr, 'to': resolve(p['to'], rd)})
    for z, goals in byzone.items():
        s = json.load(open(os.path.join(ZONES, z + '.json'), encoding='utf-8'))
        s = copy.deepcopy(s); s['tag'] = 'x_' + z; s['goals'] = goals
        s.pop('closeups', None); s.pop('probes', None)
        json.dump(s, open(os.path.join(run, 'specs', 'x_%s.json' % z), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print('x_%s:%s' % (z, s.get('dir', 'kalimdor')))

def parse(log):
    """a zone run's routes by their start's name (the leg's key): letter, yd, reached, walls, water"""
    by = {}; walls = {}; water = {}; seg = None
    for line in open(log, encoding='utf-8', errors='replace'):
        m = re.match(r'ROUTE2 (\w) (.*?) -> (.*?): (\d+) yd, (.*)$', line.rstrip('\n'))
        if m: by[m.group(2)] = {'letter': m.group(1), 'yd': int(m.group(4)), 'reached': 'unreachable' not in m.group(5),
                                'lifts': int((re.search(r'(\d+) lift ride', m.group(5)) or [0, 0])[1])}
        m = re.match(r'WALLS route (\w) (.*)', line)
        if m: walls[m.group(1)] = m.group(2).strip()
        m = re.match(r'WATERMAX route (\w) ([\d.]+) yd(?: at (.*))?', line)
        if m: water[m.group(1)] = (float(m.group(2)), (m.group(3) or '').strip())
        m = re.match(r'\d+ segments; crossing a 45\+ degree step: (.*)$', line.strip())
        if m: seg = m.group(1)
    for v in by.values():
        v['walls'] = walls.get(v['letter']); v['water'] = water.get(v['letter'], (0.0, ''))
    return by, seg

def stitch(run):
    rd = rides(); out = []; logs = {}; pts = {}
    def zone(z):
        if z not in logs:
            p = os.path.join(run, 'gate_x_%s.log' % z)
            logs[z] = parse(p) if os.path.exists(p) else ({}, None)
            j = os.path.join(run, 'levels_routes_x_%s.json' % z)
            pts[z] = {r['name'].split(' → ')[0]: r['pts'] for r in json.load(open(j, encoding='utf-8'))['routes']} if os.path.exists(j) else {}
        return logs[z]
    for r in routes():
        res = {'id': r['id'], 'name': r['name'], 'pieces': [], 'walkYd': 0, 'rideYd': 0, 'zones': [], 'links': [], 'problems': [], 'gaps': []}
        prev = None; n = 0
        for p in r['pieces']:
            if 'zone' in p:
                n += 1; key = '%s%d' % (r['id'], n); by, seg = zone(p['zone']); leg = by.get(key)
                way = pts[p['zone']].get(key)
                q = {'kind': 'leg', 'key': key, 'zone': p['zone'], 'name': p['name'], 'check': p.get('check')}
                if leg is None or way is None:
                    q['ok'] = False; res['problems'].append('%s (%s): no route in the run' % (key, p['name']))
                else:
                    q.update(yd=leg['yd'], reached=leg['reached'], walls=leg['walls'], water=leg['water'][0], waterAt=leg['water'][1],
                             lifts=leg['lifts'], segcheck=seg, pts=way)
                    if not leg['reached']: res['problems'].append('%s (%s): the goal unreachable' % (key, p['name']))
                    if leg['walls']: res['problems'].append('%s: crosses a wall (%s)' % (key, leg['walls'][:120]))
                    if leg['water'][0] > FORD: res['problems'].append('%s: water %.1f yd deep at %s' % (key, leg['water'][0], leg['water'][1]))
                    if seg not in (None, 'none'): res['problems'].append('%s: segcheck in %s: %s' % (key, p['zone'], seg[:120]))
                    res['walkYd'] += leg['yd']
                    # the join: this leg starts where the last piece ended (a leg's end, or a ride's far end)
                    if prev is not None:
                        d = math.hypot(way[0][0] - prev[0], way[0][1] - prev[1]); q['joinYd'] = round(d, 1)
                        lim = RIDE_YD if prevRide else JOIN_YD
                        if d > lim: res['problems'].append('%s: starts %d yd from where the last piece ended' % (key, d))
                    q['ok'] = leg['reached'] and not leg['walls'] and leg['water'][0] <= FORD and seg in (None, 'none')
                    prev = way[-1]; prevRide = False
                if p['zone'] not in res['zones']: res['zones'].append(p['zone'])
            elif 'ride' in p:
                rr = rd[p['ride']]; a, b = (rr['a'], rr['b']) if p['from'] == 'a' else (rr['b'], rr['a'])
                q = {'kind': 'ride', 'p': p['ride'], 'name': p['name'], 'yd': rr['yd'], 'air': rr['air'], 'a': a, 'b': b,
                     'ca': rr['ca'] if p['from'] == 'a' else rr['cb'], 'cb': rr['cb'] if p['from'] == 'a' else rr['ca']}
                if prev is not None:
                    d = math.hypot(a[0] - prev[0], a[1] - prev[1]); q['boardYd'] = round(d, 1)
                    if d > RIDE_YD: res['problems'].append('%s: the last leg ended %d yd from its %s' % (p['name'], d, 'tower' if rr['air'] else 'pier'))
                res['rideYd'] += rr['yd']; res['links'].append('%s (transports.json path %d, %d yd%s)' % (p['name'], p['ride'], rr['yd'], ', in the air' if rr['air'] else ''))
                prev = b[:2]; prevRide = True; q['ok'] = True
            else:
                q = {'kind': 'gap', 'what': p['gap'], 'ok': False}; res['gaps'].append(p['gap']); prev = None
            res['pieces'].append(q)
        res['complete'] = not res['gaps']
        res['clean'] = not res['problems']
        out.append(res)
        legsN = sum(1 for q in res['pieces'] if q['kind'] == 'leg')
        print('CROSS %s %s: %s; %d yd walked in %d legs%s; zones %s; links %s; %s' % (
            r['id'], r['name'], 'COMPLETE' if res['complete'] else 'STOPS AT %d MISSING LINK(S)' % len(res['gaps']), res['walkYd'], legsN,
            ' + %d yd aboard' % res['rideYd'] if res['rideYd'] else '', ', '.join(res['zones']), '; '.join(res['links']) or 'none',
            'every leg clear of walls and deep water, every join met' if res['clean'] else 'PROBLEMS: ' + ' | '.join(res['problems'])))
        for g in res['gaps']: print('  MISSING %s: %s' % (r['id'], g))
    json.dump(out, open(os.path.join(run, 'cross.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

# ---- the pictures (the client's own map art: they stay in the work folder)
ZMAP = {}
def zmap(z):
    if z not in ZMAP: ZMAP[z] = json.load(open(os.path.join(ZONES, z + '.json'), encoding='utf-8'))['zone']
    return ZMAP[z]
def art(m):
    from PIL import Image
    ix = json.load(open(os.path.join(ART, 'worldmap', str(m), 'index.json'), encoding='utf-8'))
    return Image.open(os.path.join(ART, 'worldmap', str(m), 'base.webp')).convert('RGB'), ix['rect']
def px(rect, w, h, wx, wy):
    c, t, b, l, r = rect
    return ((l - wy) / (l - r) * w, (t - wx) / (t - b) * h)
COL = {'a': (230, 40, 40), 'b': (40, 110, 240), 'c': (150, 60, 200), 'd': (240, 140, 0), 'e': (0, 170, 140), 'f': (220, 30, 160)}
def font(n):
    from PIL import ImageFont
    for f in ('C:/Windows/Fonts/segoeui.ttf', 'C:/Windows/Fonts/arial.ttf'):
        if os.path.exists(f): return ImageFont.truetype(f, n)
    return ImageFont.load_default()
def dashed(d, a, b, col, w=3, on=10, off=7):
    L = math.hypot(b[0] - a[0], b[1] - a[1]) or 1; s = 0.0
    while s < L:
        e = min(s + on, L)
        d.line([(a[0] + (b[0] - a[0]) * s / L, a[1] + (b[1] - a[1]) * s / L), (a[0] + (b[0] - a[0]) * e / L, a[1] + (b[1] - a[1]) * e / L)], fill=col, width=w)
        s = e + off

def draw(run):
    from PIL import Image, ImageDraw
    res = json.load(open(os.path.join(run, 'cross.json'), encoding='utf-8'))
    od = os.path.join(run, 'cross'); os.makedirs(od, exist_ok=True)
    cont = {1: (1414, 'Kalimdor'), 0: (1415, 'Eastern Kingdoms')}
    kz = {'durotar', 'barrens', 'mulgore', 'teldrassil', 'darkshore'}
    def cof(z): return 1 if z in kz else 0
    S = 2.2
    for c, (m, title) in cont.items():
        im, rect = art(m); im = im.resize((int(im.width * S), int(im.height * S)), Image.LANCZOS); d = ImageDraw.Draw(im); w, h = im.size
        f = font(18); fs = font(14); legend = []
        for r in res:
            col = COL.get(r['id'], (255, 255, 255)); shown = False
            for i, q in enumerate(r['pieces']):
                if q['kind'] == 'leg' and 'pts' in q and cof(q['zone']) == c:
                    P = [px(rect, w, h, x, y) for x, y in q['pts']]
                    d.line(P, fill=(0, 0, 0), width=6); d.line(P, fill=col, width=3); shown = True
                    for e in (P[0], P[-1]): d.ellipse([e[0] - 4, e[1] - 4, e[0] + 4, e[1] + 4], fill=col, outline=(0, 0, 0))
                if q['kind'] == 'ride':
                    for end, cc in ((q['a'], q['ca']), (q['b'], q['cb'])):
                        if cc != c: continue
                        e = px(rect, w, h, end[0], end[1]); shown = True
                        other = q['b'] if end is q['a'] else q['a']
                        if q['ca'] == q['cb']: dashed(d, e, px(rect, w, h, other[0], other[1]), col, 4)
                        else:
                            # the far end is on the other continent: a dashed stub off the map edge, named
                            tgt = (e[0] + (60 if c == 1 else -60), e[1] - 70); dashed(d, e, tgt, col, 4)
                            d.text((tgt[0] - 10, tgt[1] - 22), '%s %s %s' % (q['name'].split(',')[0], '→' if end is q['a'] else '←', 'Eastern Kingdoms' if c == 1 else 'Kalimdor'), fill=col, font=fs, stroke_width=2, stroke_fill=(0, 0, 0))
                if q['kind'] == 'gap':
                    near = next((x for x in reversed(r['pieces'][:i]) if x['kind'] == 'leg' and 'pts' in x), None)
                    if near and cof(near['zone']) == c:
                        e = px(rect, w, h, *near['pts'][-1])
                        d.line([(e[0] - 9, e[1] - 9), (e[0] + 9, e[1] + 9)], fill=(255, 0, 0), width=4); d.line([(e[0] - 9, e[1] + 9), (e[0] + 9, e[1] - 9)], fill=(255, 0, 0), width=4)
                        d.text((e[0] + 12, e[1] - 8), 'missing link', fill=(255, 80, 80), font=fs, stroke_width=2, stroke_fill=(0, 0, 0))
            if shown: legend.append((col, '%s  %s  (%s)' % (r['id'], r['name'], 'complete' if r['complete'] else 'stops at a missing link')))
        # crop to the routes, with a margin
        bb = im.getbbox()
        d.rectangle([10, 10, 620, 20 + 26 * (len(legend) + 1)], fill=(0, 0, 0))
        d.text((20, 14), title + ': the cross-zone routes (solid walked, dashed a ride)', fill=(255, 255, 255), font=f)
        for k, (col, s) in enumerate(legend):
            d.rectangle([20, 44 + 26 * k, 36, 58 + 26 * k], fill=col); d.text((44, 40 + 26 * k), s, fill=(255, 255, 255), font=fs)
        p = os.path.join(od, 'overview_%s.png' % title.split()[0].lower()); im.save(p); print('wrote', p)
    # a strip per route: one panel a piece
    PH = 420
    for r in res:
        panels = []
        for q in r['pieces']:
            if q['kind'] == 'leg' and 'pts' in q:
                im, rect = art(zmap(q['zone'])); W, H = im.size
                P = [px(rect, W, H, x, y) for x, y in q['pts']]
                x0 = min(p[0] for p in P); x1 = max(p[0] for p in P); y0 = min(p[1] for p in P); y1 = max(p[1] for p in P)
                pad = max(40, 0.15 * max(x1 - x0, y1 - y0)); bx = [max(0, x0 - pad), max(0, y0 - pad), min(W, x1 + pad), min(H, y1 + pad)]
                if bx[2] - bx[0] < 160: cx = (bx[0] + bx[2]) / 2; bx[0] = max(0, cx - 80); bx[2] = min(W, cx + 80)
                if bx[3] - bx[1] < 160: cy = (bx[1] + bx[3]) / 2; bx[1] = max(0, cy - 80); bx[3] = min(H, cy + 80)
                k = PH / (bx[3] - bx[1]); cw = max(300, int((bx[2] - bx[0]) * k))
                pim = im.crop([int(v) for v in bx]).resize((int((bx[2] - bx[0]) * k), PH), Image.LANCZOS)
                pan = Image.new('RGB', (max(cw, pim.width), PH + 110), (24, 24, 24)); pan.paste(pim, (0, 0)); d = ImageDraw.Draw(pan)
                Q = [((p[0] - bx[0]) * k, (p[1] - bx[1]) * k) for p in P]
                d.line(Q, fill=(0, 0, 0), width=7); d.line(Q, fill=COL.get(r['id'], (255, 0, 0)), width=4)
                d.ellipse([Q[0][0] - 6, Q[0][1] - 6, Q[0][0] + 6, Q[0][1] + 6], fill=(255, 255, 255), outline=(0, 0, 0))
                d.ellipse([Q[-1][0] - 6, Q[-1][1] - 6, Q[-1][0] + 6, Q[-1][1] + 6], fill=(0, 0, 0), outline=(255, 255, 255))
                ok = q['ok']; f = font(15)
                d.text((8, PH + 6), '%s  %s' % (q['key'], q['name']), fill=(255, 255, 255), font=f)
                d.text((8, PH + 30), '%s: %d yd%s' % (q['zone'], q['yd'], ', %d lift ride(s)' % q['lifts'] if q.get('lifts') else ''), fill=(200, 200, 200), font=f)
                d.text((8, PH + 54), 'walls: %s   water max %.1f yd   segcheck: %s' % ('none' if not q['walls'] else 'CROSSES', q['water'], q['segcheck']), fill=(120, 230, 120) if ok else (255, 90, 90), font=f)
                if 'joinYd' in q: d.text((8, PH + 78), 'joins the last piece %.0f yd off' % q['joinYd'], fill=(200, 200, 200), font=f)
                panels.append(pan)
            elif q['kind'] == 'ride':
                pan = Image.new('RGB', (260, PH + 110), (35, 35, 50)); d = ImageDraw.Draw(pan); f = font(15)
                dashed(d, (30, PH / 2), (230, PH / 2), COL.get(r['id'], (255, 255, 255)), 5)
                d.text((20, PH / 2 - 60), q['name'], fill=(255, 255, 255), font=f)
                d.text((20, PH / 2 + 20), '%d yd %s (path %d)' % (q['yd'], 'by air' if q['air'] else 'by sea', q['p']), fill=(200, 200, 200), font=f)
                d.text((20, PH / 2 + 44), 'boards %.0f yd from the leg\'s end' % q.get('boardYd', 0), fill=(200, 200, 200), font=f)
                panels.append(pan)
            elif q['kind'] == 'gap':
                pan = Image.new('RGB', (340, PH + 110), (60, 20, 20)); d = ImageDraw.Draw(pan); f = font(15)
                for k in range(-PH, 340, 28): d.line([(k, 0), (k + PH + 110, PH + 110)], fill=(80, 30, 30), width=8)
                d.text((16, 16), 'MISSING LINK', fill=(255, 120, 120), font=font(22))
                words = q['what'].split(); line = ''; y = 56
                for wd in words:
                    if d.textlength(line + ' ' + wd, font=f) > 305: d.text((16, y), line, fill=(255, 255, 255), font=f); y += 21; line = wd
                    else: line = (line + ' ' + wd).strip()
                d.text((16, y), line, fill=(255, 255, 255), font=f)
                panels.append(pan)
        if not panels: continue
        title = '%s  %s: %d yd walked%s, %s' % (r['id'], r['name'], r['walkYd'], ' + %d yd aboard' % r['rideYd'] if r['rideYd'] else '',
                                                 'complete' if r['complete'] else 'stops at a missing link')
        W = sum(p.width for p in panels) + 8 * (len(panels) + 1)
        strip = Image.new('RGB', (W, PH + 160), (12, 12, 12)); d = ImageDraw.Draw(strip); d.text((10, 10), title, fill=(255, 255, 255), font=font(20))
        x = 8
        for p in panels: strip.paste(p, (x, 44)); x += p.width + 8
        pth = os.path.join(od, 'strip_%s.png' % r['id']); strip.save(pth); print('wrote', pth)

if __name__ == '__main__':
    {'specs': specs, 'stitch': stitch, 'draw': draw}[sys.argv[1]](sys.argv[2])
