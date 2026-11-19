"""THE CAST BAR'S NAME = THE CAST'S DISPLAY TEXT (fix/cast-display-text, the user 2026-11-19: a quest container's "Opening - No Text"
showed its internal name on the bar; the game's own bar shows UnitCastingInfo's 2nd return, the display text, and that is "" there).
Runs the REAL noteCast block of WowCompanion/ActionBar.lua (from `local castNow, castEnd` to the end of noteCast) with the REAL
Safety.lua's S / SN (S answers a one-byte sentinel for a nil or a throw, never nil) and ActionBar's own realStr, Lua 5.1 as the game's:
  - display text "" (Opening - No Text): cs.nm stays nil (the bar blank), the icon kept
  - display text nil (no 2nd return at all): the name is the fallback
  - display text given: it is shown, not the internal name
  - a channel reads UnitChannelInfo's 2nd return the same way
  - the 2nd return throwing: the name is the fallback
Run: python -B tools/test_cast_display_text.py   (needs lupa; exit code 1 on any failure)
"""
import os
import re
import sys
from lupa import lua51

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADD = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion')
AB = open(os.path.join(ADD, 'ActionBar.lua'), encoding='utf-8').read()
SAFETY = open(os.path.join(ADD, 'Safety.lua'), encoding='utf-8').read()
passes = fails = 0


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


sent = re.search(r'^local SENTINEL = [^\n]*\n', AB, re.M).group(0)
real = re.search(r'^local function realStr\(v\)\n.*?^end\n', AB, re.M | re.S).group(0)
block = re.search(r'^local castNow, castEnd = nil, nil\n.*?^local function noteCast\(event\)\n.*?^end\n', AB, re.M | re.S).group(0)
check('select(2, fn("player"))' in block, "noteCast reads the 2nd return (the display text)")

L = lua51.LuaRuntime(unpack_returned_tuples=True)
L.execute('NOW = 100; function GetTime() return NOW end; WowCompanion = { emit = function() end }')
L.eval('function(src) local f, e = loadstring(src, "@Safety.lua"); if not f then error(e) end; return f end')(SAFETY)('WowCompanion')
src = ('local WC = _G.WowCompanion\nlocal S, SN = WC.S, WC.SN\n' + sent + real + block +
       '\nreturn function(ev) castNow = nil; noteCast(ev); return castNow end\n')
note = L.eval('function(src) local f, e = loadstring(src, "@ActionBar.lua#noteCast"); if not f then error(e) end; return f end')(src)()

# UnitCastingInfo: name, displayText, texture, startMS, endMS, isTradeSkill, castID, notInterruptible, spellID
L.execute('''
CAST = nil
function UnitCastingInfo(u) if CAST then return CAST.name, CAST.text, 136058, 100000, 101500, false, 7, false, 6247 end end
function UnitChannelInfo(u) if CHAN then return CHAN.name, CHAN.text, 136059, 100000, 103000, false, false, 5143 end end
''')


def cast(name, text, nil_text=False, ev='UNIT_SPELLCAST_START'):
    if ev.find('CHANNEL') >= 0:
        L.execute('CAST = nil; CHAN = { name = %r }' % name)
        if not nil_text:
            L.globals().CHAN.text = text
    else:
        L.execute('CHAN = nil; CAST = { name = %r }' % name)
        if not nil_text:
            L.globals().CAST.text = text
    return note(ev)


cs = cast('Opening', '')
check(cs is not None and cs.nm is None, 'display text "" (Opening - No Text): no name, the bar blank', cs and cs.nm)
check(cs is not None and cs.i == 136058, "... the cast itself and its icon still there", cs and cs.i)
cs = cast('Opening', None, nil_text=True)
check(cs is not None and cs.nm == 'Opening', 'display text nil: the name is the fallback', cs and cs.nm)
cs = cast('Hearthstone', 'Hearthstone')
check(cs is not None and cs.nm == 'Hearthstone', 'a normal cast: its display text', cs and cs.nm)
cs = cast('InternalName', 'Shown Text')
check(cs is not None and cs.nm == 'Shown Text', 'display text wins over the internal name', cs and cs.nm)
cs = cast('Arcane Missiles', '', ev='UNIT_SPELLCAST_CHANNEL_START')
check(cs is not None and cs.k == 'h' and cs.nm is None, 'a channel with display text "": blank too', cs and cs.nm)
cs = cast('Arcane Missiles', None, nil_text=True, ev='UNIT_SPELLCAST_CHANNEL_START')
check(cs is not None and cs.nm == 'Arcane Missiles', 'a channel with nil display text: the name', cs and cs.nm)
# the 2nd return throwing (a select past a broken return): S's THREW sentinel -> the name fallback
L.execute('''
REAL_UCI = UnitCastingInfo
local n = 0
UnitCastingInfo = function(u)
    n = n + 1
    if n == 1 then return REAL_UCI(u) end      -- castTimes' start
    if n == 2 then return REAL_UCI(u) end      -- castTimes' end
    if n == 4 then error("boom") end           -- the display text read (after the not-interruptible read)
    return REAL_UCI(u)
end
CHAN = nil; CAST = { name = "Fishing", text = "Fishing Text" }
''')
cs = note('UNIT_SPELLCAST_START')
check(cs is not None and cs.nm == 'Fishing', 'the display text read throwing: the name is the fallback', cs and cs.nm)

print('passes: %d  failed: %d' % (passes, fails))
sys.exit(1 if fails else 0)
