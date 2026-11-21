"""LETTERS OF MORE THAN ONE BYTE REACH THE APP WHOLE (issue #358, 2026-11-15: "Character names: Cyrillic characters are displayed as
empty squares instead of actual letters. Quest descriptions: Some Cyrillic characters are replaced with question marks"). Runs the REAL
Safety.lua (k5) and Sink.lua (sink1) in Lua 5.1 (lupa), with C_Log.LogMessage captured as the log's lines, UNDER A WINDOWS CODE PAGE
(1252, the C library's ctype: what the game's %c follows; on another system Latin-1, which is worse):
  - the measured cause: %c calls bytes 0x81 0x8D 0x8F 0x90 0x9D 0xAD controls there, and the k4 encoder (git 1d1906b2) wrote them as
    \\u0081 ...: "Анна" left it broken (shown only when this system's code page reproduces it)
  - k5: Cyrillic, accented Latin and CJK names come out of WC.encode byte for byte, and parse back to the same text
  - the ASCII controls are escaped exactly as before (\\u0001 \\u001f \\u007f \\n \\" \\\\), the kernel's sentinels too
  - sink1: a long document of Cyrillic text is cut into parts of at most 900 bytes, each of them valid UTF-8, which join to the
    document; an ASCII document is cut where it always was (every 900 bytes); a run of continuation bytes longer than a part is cut
    as before (no endless loop)
  - the kernel's self-test says so on the device: utf8 = true, cc = how many high bytes this client's %c calls controls, sink = sink1
Run: python -B tools/test_utf8_wire.py
"""
import json
import locale
import os
import subprocess
import sys

from lupa import lua51 as lupa

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDON = os.path.join(ROOT, 'companion', 'app', 'src', 'main', 'assets', 'companion', 'addons', 'WowCompanion')
passes = fails = 0
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')       # the names below, on any console
except AttributeError:
    pass


def check(ok, what, got=None):
    global passes, fails
    if ok:
        passes += 1; print('PASS', what)
    else:
        fails += 1; print('FAIL', what, '->', got)


# the game's C library follows a Windows code page: the same for this Lua's %c
code_page = None
for loc in ('English_United States.1252', 'en_US.ISO-8859-1', 'en_US.iso88591'):
    try:
        locale.setlocale(locale.LC_CTYPE, loc); code_page = loc; break
    except locale.Error:
        pass
print('LC_CTYPE:', code_page or locale.setlocale(locale.LC_CTYPE))

STUB = b'''
LINES = {}
C_Log = { LogMessage = function(s) LINES[#LINES + 1] = s end }
function debugprofilestop() return 0 end
function GetTime() return 0 end
C_Timer = { After = function() end }
'''


def boot(safety_src):
    L = lupa.LuaRuntime(unpack_returned_tuples=True, encoding=None)
    L.execute(STUB)
    load = L.eval(b'function(src, name) local f, e = loadstring(src, name); if not f then error(e) end; return f end')
    load(safety_src, b'@Safety.lua')(b'WowCompanion')
    load(open(os.path.join(ADDON, 'Sink.lua'), 'rb').read(), b'@Sink.lua')(b'WowCompanion')
    return L


def lines(L):
    return [L.eval(b'LINES[%d]' % i) for i in range(1, int(L.eval(b'#LINES')) + 1)]


SAFETY = open(os.path.join(ADDON, 'Safety.lua'), 'rb').read()
L = boot(SAFETY)
enc = L.eval(b'WowCompanion.encode')
high = L.eval(b'function() local out = {} for i = 128, 255 do if string.char(i):find("%c") then out[#out + 1] = i end end return table.concat(out, ",") end')()
print('%c matches, bytes 128-255:', high.decode() or '(none)')

NAMES = ['Анна', 'Сяэ Ёлка', 'Эхо Нина', 'María Íñigo', 'Ýmir Ðóra', '李小龍', 'Ünal']

# ------------------------------------------------------------------ the cause, measured (only where this system's code page shows it)
try:
    old = subprocess.run(['git', '-C', ROOT, 'show', '1d1906b2:companion/app/src/main/assets/companion/addons/WowCompanion/Safety.lua'],
                         capture_output=True, check=True).stdout
except Exception:
    old = None
if old and b'144' in high.split(b','):
    Lold = boot(old)
    got = Lold.eval(b'WowCompanion.encode')('Анна'.encode())
    check(got != b'"' + 'Анна'.encode() + b'"' and b'\\u0090' in got,
          'k4 (1.0.32) broke the letter under this code page: А (D0 90) went out as D0 + \\u0090', got)
else:
    print('(the k4 comparison needs a code page whose %c matches 0x90; this system: ' + (high.decode() or 'none') + ')')

# ------------------------------------------------------------------ k5: whole letters
for n in NAMES:
    raw = n.encode('utf-8')
    got = enc(raw)
    check(got == b'"' + raw + b'"', 'k5 encode keeps %s byte for byte' % n, got)
    check(json.loads(got.decode('utf-8')) == n, 'k5 encode parses back to %s' % n, got)
doc = enc(L.eval(b'function(n) return { name = n, t = "full" } end')('Анна'.encode()))
check(json.loads(doc.decode('utf-8')) == {'name': 'Анна', 't': 'full'}, 'a document with a Cyrillic name parses whole', doc)

# ------------------------------------------------------------------ the ASCII controls as before
ctl = enc(b'a\x01b\x1fc\x7fd\ne\tf"g\\h\x00i')
check(ctl == b'"a\\u0001b\\u001fc\\u007fd\\ne\\tf\\"g\\\\h\\u0000i"', 'ASCII controls, quote and backslash escaped as before', ctl)
sent = L.eval(b'WowCompanion.SECRET')
check(enc(sent) == b'"' + b''.join(b'\\u%04x' % c for c in sent) + b'"', 'the kernel sentinel still goes out escaped', enc(sent))
for c in range(0, 128):
    if c in (8, 9, 10, 12, 13, 34, 92):
        continue
    ch = bytes([c])
    want = (b'"\\u%04x"' % c) if (c < 32 or c == 127) else (b'"' + ch + b'"')
    if enc(ch) != want:
        check(False, 'byte %d encodes as before' % c, enc(ch))
        break
else:
    check(True, 'every other ASCII byte encodes as before (controls \\u00XX, the rest as itself)')

# ------------------------------------------------------------------ sink1: parts on letter boundaries
send = L.eval(b'WowCompanion.send')


def parts_since(n0):
    out = []
    for ln in lines(L)[n0:]:
        if not ln.startswith(b'WCB1|') or ln.startswith(b'WCB1|pad|'):
            continue
        _, seq, spec, payload = ln.split(b'|', 3)
        i, n = spec.split(b'/')
        out.append((int(i), int(n), payload))
    return out


text = ('Сяэ Анна Нина Эхо Ёлка. ' * 200)
d = json.dumps({'type': 'quest', 'text': text}, ensure_ascii=False).encode('utf-8')
n0 = len(lines(L))
send(d)
ps = parts_since(n0)
check(len(ps) > 3 and all(n == len(ps) for _, n, _ in ps), 'a %d-byte Cyrillic document goes out in %d parts' % (len(d), len(ps)), len(ps))
bad = []
for i, _, payload in ps:
    try:
        payload.decode('utf-8', errors='strict')
    except UnicodeDecodeError:
        bad.append(i)
check(not bad, 'every part is valid UTF-8 on its own', bad)
old_cut = [d[k:k + 900] for k in range(0, len(d), 900)]
old_bad = 0
for p in old_cut:
    try:
        p.decode('utf-8', errors='strict')
    except UnicodeDecodeError:
        old_bad += 1
check(old_bad > 0, 'the 1.0.32 cut (every 900 bytes) split letters in %d of %d parts of this document' % (old_bad, len(old_cut)), old_bad)
check(all(len(p) <= 900 for _, _, p in ps), 'no part over 900 bytes', [len(p) for _, _, p in ps])
check(b''.join(p for _, _, p in ps) == d, 'the parts join to the document', None)
check(json.loads(b''.join(p for _, _, p in ps).decode('utf-8'))['text'] == text, 'the joined document parses to the same text')

a = json.dumps({'type': 'full', 'x': 'y' * 4000}).encode()
n0 = len(lines(L))
send(a)
ps = parts_since(n0)
check([p for _, _, p in ps] == [a[k:k + 900] for k in range(0, len(a), 900)], 'an ASCII document is cut every 900 bytes, as before',
      [len(p) for _, _, p in ps])

cuts = L.eval(b'WowCompanion.cuts')
odd = b'a' + b'\x80' * 10 + b'b'
got = cuts(odd, 4)
got = [got[i] for i in range(1, len(got) + 1)]
check(b''.join(got) == odd and all(len(p) <= 4 for p in got), 'a run of continuation bytes longer than a part: cut as before', got)
mixed = ('ab' + 'я' * 3 + 'cd').encode()
got = cuts(mixed, 3)
got = [got[i] for i in range(1, len(got) + 1)]
check(b''.join(got) == mixed and all(p.decode('utf-8', errors='strict') is not None for p in got), 'small parts never split a letter', got)

# ------------------------------------------------------------------ the device's own word on it
r = L.eval(b'WowCompanion.kernelSelfTest')()
check(r[b'v'] == b'k12' and r[b'utf8'] is True and r[b'sink'] == b'sink1', 'the self-test: k12, utf8 true, sink1', dict(r))
check(isinstance(r[b'cc'], (int, float)) and int(r[b'cc']) == len([x for x in high.split(b',') if x]),
      'the self-test counts the high bytes this client\'s %c calls controls', r[b'cc'])
capdoc = enc(L.eval(b'function(k) return { type = "cap", kernel = k } end')(r))
check(json.loads(capdoc.decode('utf-8'))['kernel']['utf8'] is True, 'the cap line carries it', capdoc)

print('\n%d passed, %d failed' % (passes, fails))
sys.exit(1 if fails else 0)
