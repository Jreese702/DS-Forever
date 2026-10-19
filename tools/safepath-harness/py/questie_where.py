# where QuestieDB_Forever puts creatures and objects in a zone, in world yards: python questie_where.py "<name regex>" <area id> <uiMap>
#   (the zone's rect from the app's recipe: spawns are percent of the zone's map)
import os, sys, re, json
import questie_npcs as Q
try: sys.stdout.reconfigure(encoding='utf-8')     # the routes' names carry arrows (Windows' console is cp1252)
except Exception: pass
pat = re.compile(sys.argv[1], re.I); area = int(sys.argv[2]); uimap = sys.argv[3]
recipe = os.environ.get('RECIPE', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'companion', 'app', 'src', 'main', 'assets', 'harvest', 'worldmap.json'))
c, t, b, l, r = json.load(open(recipe, encoding='utf-8'))['rects'][uimap]
db = Q.load(('Npc', 'Object'))
for kind, field in (('Npc', '7'), ('Object', '4')):
    for oid, f in sorted(db[kind].items()):
        s = f.get('S') if isinstance(f.get('S'), dict) else {}
        nm = Q.name(s.get(1, ''))
        if not nm or not pat.search(nm): continue
        sp = f.get(field)
        if isinstance(sp, list): sp = {i + 1: v for i, v in enumerate(sp)}
        pts = (sp or {}).get(area) or []
        for p in pts[:6]:
            if isinstance(p, list) and len(p) >= 2 and isinstance(p[0], (int, float)):
                print('%-6s %7d %-36s %6.1f%% %6.1f%%  ->  world %7.1f %7.1f' % (kind, oid, nm, p[0], p[1], t - p[1] / 100 * (t - b), l - p[0] / 100 * (l - r)))
