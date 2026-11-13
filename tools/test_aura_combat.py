"""Issue #181 ("Buffs and Debuffs for target and player disappears in top screen DS frame during combat"), off-device: Trail.lua (the
HUD's aura row, WC.prd prd16) + Hub.lua (the target bar's auras, uf16) in the render rig's runtime, with the game's aura reads in the
shapes this client's API docs allow in a fight (C_UnitAuras.GetAuraDataByIndex / ByAuraInstanceID: SecretWhenUnitAuraRestricted).

    python tools/test_aura_combat.py

A secret here is STRICT: comparing two of them, indexing one, adding, concatenating, its length or tostring - each raises, as the
client refuses them. type() of a secret field says what the client's would ("number" for a duration / icon / count, "string" for a
dispel type): the review of 71b6616d - a table-typed secret never reached num()'s compare. And a self-compare (x == x / x ~= x, the
NaN tests) raises on a secret: Lua never calls __eq for one object against itself, so the rig rewrites those sites in the loaded
Trail.lua / Hub.lua (both builds) into SELFCMP(x, op), plain values unchanged. Modes:
  plain   out of a fight: plain AuraData
  fields  every field secret except auraInstanceID (icons straight to SetTexture, times to the setter, re-read many times)
  throw   the index loop raises; GetUnitAuraInstanceIDs + GetAuraDataByAuraInstanceID answer (the IDs route)
  table   the index loop gives secret TABLES (never indexed); the IDs route answers
  closed  everything secret, IDs route too: nothing can be shown, the probe says so
Checks each mode for both frames (icons shown, nothing raised), the probe "aurasec" (once per kind, capped, plain fields only), and the
1.0.27 code (0953cc36) under "fields" (the HUD row empty, the target bar still shown; num()'s reorder alone brings it back) and
"table" (both empty - the player's report); which the device is in, the probe will say. Needs lupa. Exit code 1 on any failure."""
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_top_party as rt  # noqa: E402
import test_compass as tc  # noqa: E402

AURAS = r'''
local function E() error("a secret refused (compare / index / arithmetic)") end
SMT = { secret = true, __eq = E, __lt = E, __le = E, __index = E, __newindex = E, __concat = E, __len = E, __call = E,
        __add = E, __sub = E, __mul = E, __div = E, __unm = E, __tostring = E }
local rawtype, KIND = type, setmetatable({}, { __mode = "k" })
local function S(kind) local v = setmetatable({}, SMT); KIND[v] = kind; return v end
function type(v) return (rawtype(v) == "table" and rawequal(getmetatable(v), SMT) and KIND[v]) or rawtype(v) end
function issecretvalue(v) local mt = rawtype(v) == "table" and getmetatable(v); return mt and mt.secret == true or false end
WowCompanion.isPlain = function(v) return not issecretvalue(v) end
AUR = { mode = "plain", raised = 0 }
-- the lists: { id, icon, dur, left, n, kind }
AUR.list = {
    player = { HELPFUL = { { 11, 135953, 15, 9 }, { 12, 136081, 12, 6, 2 }, { 13, 132333, 1800, 900 } },
               HARMFUL = { { 21, 136134, 18, 10, nil, "Magic" } } },
    target = { ["HARMFUL|PLAYER"] = { { 101, 136001, 12, 8 }, { 102, 136002, 12, 8, 3, "Magic" } },
               HARMFUL = { { 101, 136001, 12, 8 }, { 102, 136002, 12, 8, 3, "Magic" }, { 103, 136003, 20, 15, nil, "Curse" } },
               HELPFUL = { { 200, 135900, 120, 60 } } },
}
local function find(u, f) local l = AUR.list[u]; return l and l[f] or {} end
local function plainOf(r)
    return { auraInstanceID = r[1], icon = r[2], duration = r[3], expirationTime = GAME.t + r[4], applications = r[5] or 0, dispelName = r[6] }
end
local function fieldsOf(r)
    return { auraInstanceID = r[1], icon = S("number"), duration = S("number"), expirationTime = S("number"), applications = S("number"),
             dispelName = S("string") }
end
C_UnitAuras = {
    GetAuraDataByIndex = function(u, i, f)
        local r = find(u, f)[i]
        local m = AUR.mode
        if m == "throw" then AUR.raised = AUR.raised + 1; error("restricted") end
        if not r then return nil end
        if m == "plain" then return plainOf(r) end
        if m == "fields" then return fieldsOf(r) end
        return S()                                               -- table / closed: the table itself secret
    end,
    GetUnitAuraInstanceIDs = function(u, f, max)
        local out = {}
        for _, r in ipairs(find(u, f)) do out[#out + 1] = r[1] end
        return out
    end,
    GetAuraDataByAuraInstanceID = function(u, id)
        for _, l in pairs(AUR.list[u] or {}) do
            for _, r in ipairs(l) do
                if r[1] == id then
                    if AUR.mode == "closed" then return S() end
                    if AUR.mode == "plain" then return plainOf(r) end
                    return fieldsOf(r)
                end
            end
        end
    end,
}
C_Secrets = { ShouldAurasBeSecret = function() return GAME.fight == true end }
PROBES = {}
WowCompanion.send = function(j) if type(j) == "table" and j.probe == "aurasec" then PROBES[#PROBES + 1] = j end end
WowCompanion.encode = function(t) return t end
function SHOWN(objs)
    local n, sec = 0, 0
    for _, o in ipairs(objs or {}) do
        if o.f.shown then n = n + 1; if issecretvalue(o.tx.tex) then sec = sec + 1 end end
    end
    return n, sec
end
'''

HUB_REL = "companion/app/src/main/assets/companion/addons/WowCompanionHub/Hub.lua"
TRAIL_REL = "companion/app/src/main/assets/companion/addons/WowCompanion/Trail.lua"
OLD = "0953cc36"
SELF = re.compile(r'(?<![\w.:])([A-Za-z_]\w*) (==|~=) \1(?![\w.(\[:])')
SELFCMP = ('SELFCMP = SELFCMP or function(v, op) if rawequal(getmetatable(v), rawget(_G, "SMT")) and rawget(_G, "SMT") ~= nil then '
           'error("a secret refused (self-compare)") end if op == "==" then return v == v end return v ~= v end\n')


def strict(src):
    """the self-compares in src as SELFCMP(x, op) (one line on top; line numbers in errors are one off) -> (src, sites)"""
    out, n = SELF.subn(lambda m: 'SELFCMP(%s, "%s")' % (m.group(1), m.group(2)), src)
    return SELFCMP + out, n


def read(rel, ver=None):
    if ver is None:
        with open(os.path.join(rt.ROOT, rel), encoding="utf-8") as fh:
            return fh.read()
    return subprocess.run(["git", "-C", rt.ROOT, "show", ver + ":" + rel], capture_output=True, check=True).stdout.decode("utf-8")


def make(hub_src=None, trail_src=None, mode="plain"):
    hub_src, _ = strict(hub_src if hub_src is not None else read(HUB_REL))
    trail_src, _ = strict(trail_src if trail_src is not None else read(TRAIL_REL))
    old = tc.TRAIL
    tmp = None
    if trail_src is not None:
        tmp = os.path.join(tempfile.mkdtemp(), "trail_old.lua")
        with open(tmp, "w", encoding="utf-8", newline="") as fh:
            fh.write(trail_src)
        tc.TRAIL = tmp
    try:
        rig = rt.Rig(hub_src)
    finally:
        tc.TRAIL = old
        if tmp and os.path.exists(tmp):
            os.remove(tmp)
    rig.L.execute(AURAS)
    rig.G.AUR.mode = mode
    rig.G.GAME.fight = mode != "plain"
    rig.scene(dict(n=1, pet=False, mode=False, pick=None, dead=None, oor=None))
    return rig


def counts(rig):
    G = rig.G
    P, TB = rig.P, rig.UF.tb
    hb, hbs = G.SHOWN(P.buffs)
    hd, hds = G.SHOWN(P.debuffs)
    tb, tbs = (G.SHOWN(TB.f.buf) if TB.f else (0, 0))
    td, tds = (G.SHOWN(TB.f.deb) if TB.f else (0, 0))
    return dict(hb=hb, hd=hd, tb=tb, td=td, sec=hbs + hds + tbs + tds)


def go(rig, mode, fight, secs=1.5):
    G = rig.G
    G.AUR.mode = mode
    G.GAME.fight = fight
    rig.fire("UNIT_AURA", "player")
    rig.fire("UNIT_AURA", "target")
    rig.run(secs)
    return counts(rig)


def main():
    fails = []

    def chk(name, ok, got=''):
        print(("PASS " if ok else "FAIL ") + name + ((" -> " + str(got)) if got != '' and not ok else ''))
        if not ok:
            fails.append(name)

    rig = make()
    G, P = rig.G, rig.P
    chk("prd17 / uf19 (uf18 the target plate pass + uf19 the snapped spot, uf20 the smooth rim, uf21 its old notched corner): the version tags", P.V == "prd17" and rig.UF.V == "uf21", (P.V, rig.UF.V))
    _, nt = strict(read(TRAIL_REL))
    chk("the rig's strict self-compare: Trail.lua's NaN tests rewritten (%d sites)" % nt, nt >= 4, nt)
    chk("the rig's type(): a secret duration is a number, a secret dispel type a string, a secret table a table",
        rig.L.eval('type(setmetatable({}, SMT))') == "table" and rig.L.eval('issecretvalue(5) == false'))
    # the HUD shows 2 short buffs (the 30 min one is long), 1 debuff; the target bar 3 debuffs (mine first, no repeat), 1 buff
    want = dict(hb=2, hd=1, tb=1, td=3)
    fwant = dict(hb=3, hd=1, tb=1, td=3)                  # in a fight the lengths are secret: no "long buff" left out (prd2's rule c)
    c = go(rig, "plain", False)
    chk("plain (out of a fight): HUD 2 buffs + 1 debuff, target bar 1 buff + 3 debuffs (%s)" % c,
        all(c[k] == v for k, v in want.items()) and c["sec"] == 0, c)
    for mode, label in (("fields", "every field secret"), ("throw", "the index loop raises: the IDs route"),
                        ("table", "secret aura TABLES from the index loop: the IDs route")):
        c = go(rig, mode, True, 2.0)                       # 2 s: many re-reads (the icon cache, the swipe) under strict secrets
        ok = all(c[k] == v for k, v in fwant.items()) and c["sec"] == 8
        chk("%s (a fight): both frames keep every aura (the 30 min buff too: its secret length cannot be judged), each icon the secret "
            "itself (%s)" % (label, c), ok, c)
        # the reads raised nothing of ours: a direct read and a direct draw, unprotected
        r = rig.L.eval('(function() local ok, e = pcall(function() WowCompanion.prd.auras(); WowCompanion.prd.drawAuras(true) end); '
                       'return ok and "ok" or tostring(e) end)()')
        chk("%s: P.auras + drawAuras raise nothing" % label, r == "ok", r)
        ab = P.ab
        chk("%s: the HUD's probe words (via %s, sf %s, st %s, th %s)" % (label, ab.via, ab.sf, ab.st, ab.th),
            ab.sf > 0 and ((mode == "fields" and ab.via == "index/index" and ab.th == 0 and ab.st == 0)
                           or (mode == "throw" and ab.via == "ids/ids" and ab.th >= 2)
                           or (mode == "table" and ab.via == "ids/ids" and ab.st >= 2)), dict(ab))
    # the swipe: secret times handed straight to the setter, plain ones out of a fight; never arithmetic on a secret
    go(rig, "fields", True, 0.5)
    o = P.buffs[1]
    chk("fields: the HUD swipe got the secrets as they came (SetCooldownFromExpirationTime), no seconds",
        G.issecretvalue(o.cd.cdExp) and o.t.text == "")
    o = rig.UF.tb.f.deb[2]
    chk("fields: the target bar's stack count blank (a secret is never compared)", o.n.text == "")
    # closed: nothing can be read - nothing shown, nothing raised, the probe says it
    c = go(rig, "closed", True)
    chk("closed (every read secret): nothing shown, nothing raised (%s)" % c, c["hb"] == c["hd"] == c["tb"] == c["td"] == 0, c)
    c = go(rig, "plain", False)
    chk("after the fight: every aura back (%s)" % c, all(c[k] == v for k, v in want.items()), c)
    # the probe: once per kind and unit, at most PROBE_MAX, plain values only
    pr = [G.PROBES[i] for i in range(1, len(G.PROBES) + 1)]
    kinds = {(p.u, p.via, p.th > 0, p.st > 0, p.sf > 0, p.shown > 0) for p in pr}
    chk("probe aurasec: sent for both units, once per kind (%d sent, %d kinds), at most %d" % (len(pr), len(kinds), P.PROBE_MAX),
        0 < len(pr) <= P.PROBE_MAX and len(kinds) == len(pr) and {p.u for p in pr} == {"player", "target"}, [dict(p) for p in pr])
    plain = all(not G.issecretvalue(v) for p in pr for v in dict(p).values())
    chk("probe aurasec: every field plain, the fight and the oracle's word in it", plain and all(p.fight is True for p in pr)
        and any(p.lock is True for p in pr))
    th = [p for p in pr if p.th > 0]
    chk("probe aurasec: a throw carries its error text (%s)" % (th and th[0].err), th and "restricted" in th[0].err, [dict(p) for p in th])
    n0 = len(pr)
    for _ in range(3):
        go(rig, "throw", True, 0.5)
    chk("probe aurasec: the same kind again sends nothing more", len(G.PROBES) == n0, len(G.PROBES))
    # the 1.0.27 code under "table": no aura on either frame - the player's report
    try:
        hub_old, trail_old = read(HUB_REL, OLD), read(TRAIL_REL, OLD)
    except Exception:
        hub_old = None
    if hub_old:
        # "fields": secret NUMBERS in a plain table - 1.0.27's num() ran v == v before its secret test (Trail.lua:3196)
        oldf = make(hub_old, trail_old, "fields")
        c1 = go(oldf, "fields", True)
        # measured: the HUD row loses every aura, the target bar keeps its own (Hub's v ~= nil never raises in this rig) - so secret
        # numbers alone explain the player's side of the report, not the target's; "table" / "throw" explain both. The probe decides.
        chk("1.0.27 (%s) in a fight with secret numbers in plain aura tables: the HUD row empty, the target bar still shows (%s)"
            % (OLD, c1), c1["hb"] == 0 and c1["hd"] == 0 and c1["tb"] == 1 and c1["td"] == 3, c1)
        c0 = go(oldf, "plain", False)
        chk("1.0.27 (fields) out of the fight shows them again (%s)" % c0, c0["td"] == 3 and c0["hb"] == 2, c0)
        newf = make(None, None, "fields")
        c2 = go(newf, "fields", True)
        chk("this build, the same fight (fields) from the first frame: every aura (%s)" % c2,
            all(c2[k] == v for k, v in fwant.items()) and c2["sec"] == 8, c2)
        # num() is what this proves: 1.0.27 with ONLY the HUD section's num() (its first copy; the second is WC.rings') put in prd16's
        # order shows the HUD row again - P.auras' num(a.duration) ran v == v on a secret number (Trail.lua:3196 at 0953cc36)
        NEW_NUM = ('local function num(v) if type(v) == "number" and not (type(issecretvalue) == "function" and issecretvalue(v)) '
                   'and v == v then return v end end')
        OLD_NUM = ('local function num(v) if type(v) == "number" and v == v and not (type(issecretvalue) == "function" and '
                   'issecretvalue(v)) then return v end end')
        chk("prd16's num() in this Trail.lua, 1.0.27's in the old one", read(TRAIL_REL).count(NEW_NUM) == 1 and trail_old.count(OLD_NUM) >= 1)
        mut = make(hub_old, trail_old.replace(OLD_NUM, NEW_NUM, 1), "fields")
        c3 = go(mut, "fields", True)
        chk("1.0.27 with only num() reordered: the HUD row back (%s) - the reorder is what the fields check sees" % c3,
            all(c3[k] == v for k, v in fwant.items()), c3)
        old = make(hub_old, trail_old, "table")            # a fight from the first frame: secret tables from the index loop
        c1 = counts(old)
        c1 = go(old, "table", True)
        chk("1.0.27 (%s) in a fight with secret aura tables: no aura on either frame (%s) - the report" % (OLD, c1),
            c1 == dict(hb=0, hd=0, tb=0, td=0, sec=0), c1)
        c0 = go(old, "plain", False)
        chk("1.0.27 out of the fight shows them again (%s)" % c0, c0["td"] == 3 and c0["hb"] == 2, c0)
        new = make(None, None, "table")
        c2 = go(new, "table", True)
        chk("this build, the same fight from the first frame: every aura (%s)" % c2, all(c2[k] == v for k, v in fwant.items()), c2)
    print("failed: %d" % len(fails))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
