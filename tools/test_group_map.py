"""THE GROUP ON THE MAP after the group goes (issue #30, the player 2026-11-06: "after leaving a party, the player dot stayed on the
map at their last location"; 2026-11-11). Runs Map.lua's WC.grpRead section alone in Lua 5.1 (lupa) with a stand-in game, and checks
Core.lua's position-ticker key:
  - while grouped: one read a second, the members, the read's seq bumped every time
  - the group goes: grpN = 0 for ZERO_TICKS reads (one a second), then nothing (nil) while ungrouped
  - a new group after that: members again, the zero count reset
  - Core.lua's ticker key carries WC.grp.seq, so a read is SENT while the player stands still (the lost single grpN = 0)
Run: python -B tools/test_group_map.py
"""
import os
import re
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDON = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion')
MAP = open(os.path.join(ADDON, 'Map.lua'), encoding='utf-8').read()
CORE = open(os.path.join(ADDON, 'Core.lua'), encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


start = MAP.index('WC.grp = {')
end = MAP.index('    return out, #out\nend', start) + len('    return out, #out\nend')
SECTION = MAP[start:end]

L = lua51.LuaRuntime(unpack_returned_tuples=True)
L.execute(r'''
NOW = 100
function GetTime() return NOW end
N = 2
function GetNumGroupMembers() return N end
function IsInRaid() return false end
function UnitExists(u) return u == "party1" end
function UnitIsUnit() return false end
function UnitPosition(u) return -10630.04, 1040.51, 0, 0 end
function UnitName() return "Lyra" end
function UnitClass() return "Paladin", "PALADIN" end
RAID_CLASS_COLORS = { PALADIN = { colorStr = "fff58cba" } }
function UnitIsDeadOrGhost() return false end
function UnitIsConnected() return true end
WC = { isPlain = function() return true end }
''')
L.execute('local WC = WC\n' + SECTION)
read = L.eval('function() local g, n = WC.grpRead(); return n, g and #g or -1, WC.grp.seq end')

n, cnt, seq0 = read()
check(n == 1 and cnt == 1, "grouped: the member read", (n, cnt))
n2, _, s = read()
check(n2 is None, "a second read inside the second: nothing (once a second)", n2)
L.execute('NOW = NOW + 1')
n, cnt, seq1 = read()
check(n == 1 and seq1 == seq0 + 1, "the next second: the member again, the seq bumped (Core sends it standing still)", (n, seq0, seq1))

L.execute('N = 0')
zeros = []
for i in range(8):
    L.execute('NOW = NOW + 1')
    zeros.append(read())
got = [z[0] for z in zeros]
ticks = int(L.eval('WC.grp.ZERO_TICKS'))
check(ticks >= 3, "ZERO_TICKS repeats the 'no group' read at least 3 times", ticks)
check(got[:ticks] == [0] * ticks and all(g is None for g in got[ticks:]),
      "the group went: grpN = 0 for ZERO_TICKS reads, then nothing while ungrouped", got)
seqs = [z[2] for z in zeros[:ticks]]
check(all(b == a + 1 for a, b in zip(seqs, seqs[1:])), "each 'no group' read bumps the seq (each one is sent)", seqs)

L.execute('N = 2; NOW = NOW + 1')
n, cnt, _ = read()
check(n == 1, "a new group: members again", n)
L.execute('N = 0; NOW = NOW + 1')
n, _, _ = read()
check(n == 0, "left again: grpN = 0 again (the zero count reset)", n)

# Core.lua's ticker: the key carries the group's read
code = '\n'.join(re.sub(r'--.*$', '', ln) for ln in CORE.splitlines())
m = re.search(r'local key = string\.format\((.*?)\)\n', code, re.S)
check(m is not None and 'WC.grp.seq' in m.group(1), "Core.lua: the position key carries WC.grp.seq", m and m.group(1)[-160:])
# the key itself, tick by tick (review 2026-11-11): standing still while grouped it changes ONLY on the once-a-second read; the old key
# (seq only on the read's tick) went seq -> "" -> same, three sends a second
K = L.eval('function(pos) local WC = WC; return string.format(%s) end' % m.group(1))
st = L.table_from({'x': 0.5, 'y': 0.5, 'facing': 1.0, 'indoors': False})
L.execute('WC.grp.seq = 7')
L.execute('POSG = { x = 0.5, y = 0.5, facing = 1.0, indoors = false, grpN = 1 }')
k_read = K(L.eval('POSG'))
keys = [K(st) for _ in range(9)]
check(all(k == k_read for k in keys), "standing still: the ticks after the read keep the read's key (no extra sends)", (k_read, keys[:2]))
L.execute('WC.grp.seq = 8')
check(K(L.eval('POSG')) != k_read, "the next read (seq bumped) changes the key: that read is sent standing still")

print('ALL PASS (%d checks)' % passes if fails == 0 else '%d FAILED (of %d)' % (fails, fails + passes))
sys.exit(1 if fails else 0)
