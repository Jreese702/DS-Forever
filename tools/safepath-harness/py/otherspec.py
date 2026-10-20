# a zone's spec for the other faction (as gate.sh makes them): python otherspec.py <tag> <out folder>
# prints "<tag>_<side>:<spec path>" - the zone's own spec for that side when it has one (zones/arathi_horde.json), else a copy of
# zones/<tag>.json with the faction swapped and its tag <tag>_<side>, written to <out folder>/specs/
import json, os, sys
here = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'zones')
tag, out = sys.argv[1], sys.argv[2]
import re
base = re.sub(r'_(horde|alliance)$', '', tag)
if base != tag and os.path.isfile(os.path.join(here, base + '.json')): sys.exit()     # arathi_horde: arathi's own other side, run with it
s = json.load(open(os.path.join(here, tag + '.json'), encoding='utf-8'))
other = 'horde' if s.get('faction', 'horde') == 'alliance' else 'alliance'
own = os.path.join(here, tag + '_' + other + '.json')
if os.path.isfile(own):
    print('%s_%s:%s' % (tag, other, os.path.abspath(own).replace('\\', '/'))); sys.exit()
s['faction'] = other; s['tag'] = tag + '_' + other; s.pop('closeups', None)
os.makedirs(os.path.join(out, 'specs'), exist_ok=True)
p = os.path.join(out, 'specs', s['tag'] + '.json')
json.dump(s, open(p, 'w', encoding='utf-8'), ensure_ascii=False)
print('%s:%s' % (s['tag'], os.path.abspath(p).replace('\\', '/')))
