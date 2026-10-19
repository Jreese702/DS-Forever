# What a flood on the ground reaches (segcheck's step rule: no step at 45 degrees or more), with the way to each target and its
# steepest steps: for checking an exit or a ridge crossing by eye.
#   python reach.py x,y [R=vertices] [--box t,b,l,r] [--path x,y ...]
# prints the region's size, the highest ground it reaches inside the box (when given), and for each --path target the flood's own
# way to it (vertex steps), its height profile every ~50 yd and its steepest steps.
import sys, math, collections
import segcheck as SC
import heights as HT
args = sys.argv[1:]
start = tuple(map(float, args[0].split(',')))
R = int(args[1]) if len(args) > 1 and args[1].isdigit() else 300
box = None; targets = []
if '--box' in args: box = tuple(map(float, args[args.index('--box') + 1].split(',')))
if '--path' in args: targets = [tuple(map(float, a.split(','))) for a in args[args.index('--path') + 1:] if ',' in a and not a.startswith('--') and not a.endswith('.txt')]
s = SC.vertex(*start); par = {s: None}; q = collections.deque([s])
while q:
    c = q.popleft()
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            if di == dj == 0: continue
            n = (c[0] + di, c[1] + dj)
            if n in par or abs(n[0] - s[0]) > R or abs(n[1] - s[1]) > R: continue
            if SC.step_deg(c[0], c[1], di, dj) >= 45: continue
            par[n] = c; q.append(n)
def world(v): return (HT.ORG - v[1] * SC.S, HT.ORG - v[0] * SC.S)
print('region from %s: %d vertices, ~%d sq yd' % (start, len(par), int(len(par) * SC.S * SC.S)))
if box:
    t, b, l, r = box; hi = []
    for v in par:
        x, y = world(v)
        if b <= x <= t and r <= y <= l: hi.append((HT.height(x, y), x, y))
    hi.sort(reverse=True)
    print('in the box: %d vertices; highest %s' % (len(hi), ', '.join('%.0f at %.0f,%.0f' % h for h in hi[:5])))
for tg in targets:
    v = SC.vertex(*tg)
    if v not in par: print(tg, 'NOT reached'); continue
    path = []
    while v is not None: path.append(v); v = par[v]
    path.reverse()
    steps = [(SC.step_deg(a[0], a[1], b[0] - a[0], b[1] - a[1]), world(b)) for a, b in zip(path, path[1:])]
    L = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(path, path[1:])) * SC.S
    print(tg, 'reached: %.0f yd of steps; profile:' % L, ' '.join('%.0f' % HT.height(*world(p)) for p in path[::12]))
    print('   steepest steps:', ', '.join('%.1f at %.0f,%.0f' % (d, w[0], w[1]) for d, w in sorted(steps, reverse=True)[:8]))
    if '--dump' in args:
        with open(args[args.index('--dump') + 1], 'a') as fo:
            for (d, w), pv in zip([(0, world(path[0]))] + steps, path):
                fo.write('%.1f %.1f %.1f %.1f' % (w[0], w[1], HT.height(*w), d) + chr(10))
    print('   steps 40-45 deg: %d; 35-40: %d' % (sum(1 for d, _ in steps if d >= 40), sum(1 for d, _ in steps if 35 <= d < 40)))
