import sys, math, collections
import segcheck as SC
def flood(a, R=260):
    s = SC.vertex(*a); seen = {s}; q = collections.deque([s])
    while q:
        c = q.popleft()
        for di in (-1,0,1):
            for dj in (-1,0,1):
                if di == dj == 0: continue
                n = (c[0]+di, c[1]+dj)
                if n in seen or abs(n[0]-s[0]) > R or abs(n[1]-s[1]) > R: continue
                if SC.step_deg(c[0], c[1], di, dj) >= 45: continue
                seen.add(n); q.append(n)
    return seen
pts = [tuple(map(float, p.split(','))) for p in sys.argv[1:]]
reg = flood(pts[0])
print('region from', pts[0], 'size', len(reg), 'yd^2 ~', int(len(reg)*SC.S*SC.S))
for p in pts[1:]: print(p, 'reachable' if SC.vertex(*p) in reg else 'NOT reachable')
