-- WowCompanionSpells / Radial.lua
-- THE CONSUMABLES RADIAL (the user's signed design; Home's D-pad up, 2026-10-06). The add-on's half:
--   1. the `radial` document: the bags' usable consumables in the USER'S groups and order -- 1 food & drink, 2 potions / elixirs /
--      flasks, 3 bandages, 4 other usable (the Hearthstone, scrolls, quest items with a Use) -- by Bags' filters (food 0/5; potion
--      0/1, elixir 0/2, flask 0/3; bandage 0/7; other = a Use and (class 12 Quest, a quest item, or any class but 2 Weapon / 4
--      Armor)); within a group, bag order; ONE entry per item id (its stacks counted together); at most 24 (3 pages of 8, LB / RB),
--      the rest counted in `more`. Each entry: col (its tap cell), id, n, i, c (count), g (group), cd { d, l } while cooling down.
--   2. ONE TAP = ONE USE, IN FIGHTS TOO: the secure pad row "radial" (TapPad's secureRow): each cell a SecureActionButton with
--      type "item" / item "item:<id>". Using an item is a PROTECTED action: only a secure click with a hardware event does it, and
--      the app's tap is one (a real touch). Cells are set OUT of combat only, so the set FREEZES at a fight's start: an item used up
--      in the fight leaves a dead cell (c 0), one looted in it has no cell yet (col nil) until the fight ends.
-- The app (Home) reads the stick itself, draws the pointed slice and taps its cell; the guest "radial" (Quests' generic guests)
-- holds the controller while it shows. Columns are STABLE per item (a freed one waits 30 s) so a tap made from a slightly old
-- document never lands on a different item. No game window, no Blizzard UI call, nothing written but our own cells.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local RV = "r13"                -- bump on every change
local ROW, CELLS = "radial", 24 -- the secure pad row: 24 cells = 3 pages of 8
local PLAYER_BAGS = { 0, 1, 2, 3, 4 }
local QUARANTINE = 30           -- seconds a freed column stays unused (as the pad's own columns)

local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
local function TS(fn) local v = S(fn); if type(v) == "string" and real(v) and v ~= "" and v:byte(1) > 3 then return v end end
local function TN(fn) local v = S(fn); if type(v) == "number" then return v end end
local function inCombat() return S(function() return InCombatLockdown() end) == true end
local function field(t, k)
    if type(t) == "table" then local v = S(function() return t[k] end); if real(v) then return v end end
    return nil                                  -- an explicit nil: type(field(...)) must never get NO value (Lua 5.1 errors)
end

-- the user's groups by Bags' filters (see the header); nil = not on the radial
local function groupOf(cls, sub, use, quest)
    if cls == 0 and sub == 5 then return 1 end
    if cls == 0 and (sub == 1 or sub == 2 or sub == 3) then return 2 end
    if cls == 0 and sub == 7 then return 3 end
    if use and (cls == 12 or quest or (cls ~= 2 and cls ~= 4)) then return 4 end
    return nil
end

local classOf, useOf = {}, {}   -- per item id: { class, subclass } (never changes); true once a Use was seen (an uncached item: asked again)

local function itemClass(id)
    local cs = classOf[id]
    if cs then return cs end
    local ok, _, _, _, _, _, classID, subclassID = CALL(function() return C_Item.GetItemInfoInstant(id) end)
    if not ok then return nil end
    cs = { SN(function() return classID end), SN(function() return subclassID end) }
    if cs[1] ~= nil then classOf[id] = cs end
    return cs
end
local function hasUse(id)
    if useOf[id] then return true end
    local ok, sp = CALL(function() return C_Item.GetItemSpell(id) end)
    if ok and type(sp) == "string" and WC.isPlain(sp) then useOf[id] = true return true end
    return false
end

-- the bags, in bag order: every radial item once (stacks counted together), sorted by group then first place
local function scan()
    local byId, list = {}, {}
    for _, bag in ipairs(PLAYER_BAGS) do
        local n = TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        for slot = 1, n do
            local id = TN(function() return C_Container.GetContainerItemID(bag, slot) end)
            if id then
                local okI, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
                if not okI then info = nil end
                local cnt = field(info, "stackCount")
                cnt = type(cnt) == "number" and cnt or 1
                local e = byId[id]
                if e then
                    e.c = e.c + cnt
                elseif e == nil then
                    local cs = itemClass(id)
                    local quest = false
                    local okQ, qi = CALL(function() return C_Container.GetContainerItemQuestInfo(bag, slot) end)
                    if okQ and type(qi) == "table" and (field(qi, "isQuestItem") == true or type(field(qi, "questID")) == "number") then quest = true end
                    local g = cs and groupOf(cs[1], cs[2], hasUse(id), quest)
                    if g then
                        local icon = field(info, "iconFileID")
                        e = { id = id, g = g, c = cnt, seq = #list + 1, i = type(icon) == "number" and icon or nil }
                        byId[id] = e
                        list[#list + 1] = e
                    else
                        byId[id] = false                          -- not a radial item (asked once per scan)
                    end
                end
            end
        end
    end
    table.sort(list, function(a, b) if a.g ~= b.g then return a.g < b.g end return a.seq < b.seq end)
    return list
end

-- ---------------------------------------------------------------- the stable columns
local colOf, itemAt, freedAt = {}, {}, {}  -- id -> col; col -> id; col -> when it was freed (0-based columns, as the pad's)
local kindOf = {}               -- r7: id -> "item" / "toy" (how its cell uses it)
local function assign(shown)
    if inCombat() then return false end                 -- the cells are frozen in a fight
    local now = GetTime()
    local want, changed = {}, false
    for _, e in ipairs(shown) do want[e.id] = true end
    for id, col in pairs(colOf) do
        if not want[id] then colOf[id] = nil; itemAt[col] = nil; freedAt[col] = now; changed = true end
    end
    for _, e in ipairs(shown) do
        if e.kind and kindOf[e.id] ~= e.kind then kindOf[e.id] = e.kind; changed = true end
        if colOf[e.id] == nil then
            for col = 0, CELLS - 1 do
                if itemAt[col] == nil and (freedAt[col] == nil or now - freedAt[col] >= QUARANTINE) then
                    colOf[e.id], itemAt[col], freedAt[col] = col, e.id, nil
                    changed = true
                    break
                end
            end
        end
    end
    return changed
end

-- ---------------------------------------------------------------- the secure cells
local pad = WC.pad
local secureOk = type(pad) == "table" and type(pad.secureRow) == "function"
local frozen = {}               -- col -> id as the cells were last set (what a tap really uses)
local function cellInit(btn, col)
    local id = itemAt[col]
    if id and kindOf[id] == "toy" then                           -- r7: a pinned toy (the game's own toy use)
        btn:SetAttribute("type", "toy")
        btn:SetAttribute("toy", id)
        btn:SetAttribute("item", nil)
    else
        btn:SetAttribute("type", id and "item" or nil)
        btn:SetAttribute("item", id and ("item:" .. id) or nil)
        btn:SetAttribute("toy", nil)
    end
    frozen[col] = id
end
local function onCell(col)
    -- a tap landed (after the game's own use): said, for the app and for the proof. r5 (Home's latency study: item use -> radialuse
    -- ~1.08 s): flushed AT ONCE, not at the next frame through the 0.2 s throttle -- the player is waiting on it (Sink's own rule), and
    -- a use comes at most about once a second
    WC.send(WC.encode({ type = "radialuse", v = RV, col = col, id = frozen[col], fight = inCombat() or nil }))
    if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
end
-- the cells differ from the columns (a build refused or not run yet): built again at the next chance, out of combat
local function cellsStale()
    for id, col in pairs(colOf) do if frozen[col] ~= id then return true end end
    for col, id in pairs(frozen) do if colOf[id] ~= col then return true end end
    return false
end
-- r2 (the pad owner, 10-06: "NO ROW IS FREE" on pad6c; pad8 adds rows 38-47 that fit WoW Forever's 1080p): the row number the pad gave,
-- nil = no row (then no item has a cell and the document says `nopad`, so the app can say the radial cannot use items yet)
local padRow = nil
local function buildCells()
    if not secureOk or inCombat() then return false end
    local ok, row = pcall(pad.secureRow, ROW, CELLS, cellInit, function(col) onCell(col) end)
    padRow = (ok and type(row) == "number") and row or nil
    if not padRow then for col in pairs(frozen) do frozen[col] = nil end end   -- no row = no cell can be tapped
    return padRow ~= nil
end

local stickOn = false           -- r3: the stick catch's EnableGamePadStick(true) took (declared here: the document reads it)

-- ---------------------------------------------------------------- r7: PINS
-- The user 2026-10-06 (signed off via Home; "First page only"): any usable item -- consumables, the Hearthstone, quest items, worn
-- items with a Use (trinkets), toys -- can be PINNED to a slice of the wheel's FIRST page (slices 0-7; 4 is Home's "+" slice, never a
-- pin); pins stay put, the free slices fill by themselves, a pin that ran out is shown greyed and keeps its slice. The pins live HERE,
-- per character (SavedVariables: they survive an app reinstall), and every pinned item that is in the bags or worn gets a secure use
-- cell FIRST. Set from the app by ONE plain pad row "radialpin" (Quests' yes): the fixed keys s0-s7 (pick a slice, kept 2 s) and
-- "unpin" (columns 0-8, claimed first, kept through every retain), then each candidate item by its id (columns 9-39, the doc's k).
-- The app sends the pair as ONE call (TapBridge.taps). One pick = one action; a tap on an empty / stale column refuses and drops the
-- pick. Answer: `radialpinack` {op, slice, id, ok, why}. SavedVariables only: fine in fights (the new cell waits for the fight's end).
local PIN_NS, PIN_SLICES, PLUS_SLICE, PICK_S = "radialpin", 8, 4, 2
local PIN_FIXED = { "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "unpin" }
local function whoKey()
    local n = TS(function() return UnitName("player") end)
    local r = TS(function() return GetRealmName() end)
    return n and (n .. "-" .. (r or "?")) or nil
end
local function pinsFor(create)
    local k = whoKey()
    if not k then return nil end
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.radialPins) ~= "table" then db.radialPins = {} end
    if type(db.radialPins[k]) ~= "table" then
        if not create then return nil end
        db.radialPins[k] = {}
    end
    return db.radialPins[k]
end

-- every usable item: the bags (any item with a Use), what is worn (with a Use), the toys (when this client has them)
local function toysHere() return type(C_ToyBox) == "table" and type(C_ToyBox.GetNumFilteredToys) == "function"
    and type(C_ToyBox.GetToyFromIndex) == "function" and type(PlayerHasToy) == "function" end
local function scanUsable()
    local list, by = {}, {}
    local function add(id, src, c, icon)
        local e = by[id]
        if e then e.c = e.c + (c or 0) return end
        e = { id = id, src = src, c = c or 0, i = icon }
        by[id] = e
        list[#list + 1] = e
    end
    for _, bag in ipairs(PLAYER_BAGS) do
        local n = TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0
        for slot = 1, n do
            local id = TN(function() return C_Container.GetContainerItemID(bag, slot) end)
            if id and hasUse(id) then
                local okI, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
                local cnt = okI and field(info, "stackCount")
                add(id, "bag", type(cnt) == "number" and cnt or 1, okI and field(info, "iconFileID") or nil)
            end
        end
    end
    for slot = 1, 19 do
        local id = TN(function() return GetInventoryItemID("player", slot) end)
        if id and hasUse(id) and not by[id] then add(id, "worn", 1, TN(function() return GetInventoryItemTexture("player", slot) end)) end
    end
    if toysHere() then
        local n = TN(function() return C_ToyBox.GetNumFilteredToys() end) or 0
        for k = 1, math.min(n, 200) do
            local id = TN(function() return C_ToyBox.GetToyFromIndex(k) end)
            if id and id > 0 and S(function() return PlayerHasToy(id) end) == true and not by[id] then add(id, "toy", 1, nil) end
        end
    end
    return list, by
end

-- the pick and the actions (the plain row's handler)
local pick = nil                -- { slice, t }: the last slice picked, for PICK_S seconds
local pinRow = nil
local soonPin                   -- below: the doc again after a change
local function pinAck(op, ok, why, slice, id)
    WC.send(WC.encode({ type = "radialpinack", v = RV, op = op, ok = ok and true or false, why = why, slice = slice, id = id }))
    if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
end
local function onPinTap(key)
    if key == nil then pick = nil; pinAck("tap", false, "empty column") return end
    if type(key) == "string" and key:match("^s%d$") then
        local sl = tonumber(key:sub(2))
        if sl == PLUS_SLICE then pick = nil; pinAck("pick", false, "plus slice", sl) return end
        pick = { slice = sl, t = GetTime() }
        return                                                       -- a no-action pick: no answer
    end
    local p = pick
    pick = nil                                                       -- one pick = one action
    local op = key == "unpin" and "unpin" or "pin"
    if key ~= "unpin" and type(key) ~= "number" then pinAck("tap", false, "unknown key") return end
    if not p or GetTime() - p.t > PICK_S then pinAck(op, false, "no slice picked", nil, type(key) == "number" and key or nil) return end
    local pins = pinsFor(true)
    if not pins then pinAck(op, false, "no character", p.slice) return end
    if key == "unpin" then
        local was = pins[p.slice]
        pins[p.slice] = nil
        pinAck("unpin", true, nil, p.slice, was)
    else
        for sl, id in pairs(pins) do if id == key then pins[sl] = nil end end   -- one slice per item: a re-pin moves it
        pins[p.slice] = key
        pinAck("pin", true, nil, p.slice, key)
    end
    if soonPin then soonPin() end
end
if type(pad) == "table" and type(pad.op) == "function" and type(pad.slotFor) == "function" then
    local okR, row = pcall(pad.op, PIN_NS, PIN_NS, function(key) pcall(onPinTap, key) end)
    pinRow = okR and row or nil
    for _, k in ipairs(PIN_FIXED) do pcall(pad.slotFor, PIN_NS, k) end   -- claimed FIRST: columns 0-8
end
-- the candidates' columns (pinned first, then the auto list, the other usable items, worn, toys): kept through every retain with the
-- fixed keys; at most 31 have one (9-39); the rest come with k nil (the app shows them as not pinnable)
local function pinColumns(cand)
    if not pinRow then return end
    local keep = {}
    for _, k in ipairs(PIN_FIXED) do keep[k] = true end
    for _, c in ipairs(cand) do keep[c.id] = true end
    pcall(pad.retain, PIN_NS, keep)
    for _, k in ipairs(PIN_FIXED) do pcall(pad.slotFor, PIN_NS, k) end
    for _, c in ipairs(cand) do
        local ok, col = pcall(pad.slotFor, PIN_NS, c.id)
        c.k = (ok and type(col) == "number") and col or nil
    end
end

-- ---------------------------------------------------------------- the document
local lastKey
local function cdOf(id, now)
    local okC, st, dur = CALL(function() return C_Container.GetItemCooldown(id) end)
    st, dur = okC and SN(function() return st end), okC and SN(function() return dur end)
    if st and dur and dur > 0 and st > 0 then
        local left = st + dur - now
        if left > 0 then return { d = math.floor(dur * 10 + 0.5) / 10, l = math.floor(left * 10 + 0.5) / 10 } end
    end
end
local function collect()
    local all = scan()
    local usable, byUse = scanUsable()
    local pins = pinsFor(false) or {}
    local now = GetTime()
    -- r7: the pinned items first (those in the bags or worn get the cells first), then the auto list without them
    local pinned, pinnedSet = {}, {}
    for sl = 0, PIN_SLICES - 1 do
        local id = pins[sl]
        if type(id) == "number" then
            pinnedSet[id] = true
            local u = byUse[id]
            pinned[#pinned + 1] = { s = sl, id = id, c = u and u.c or 0, src = u and u.src or nil, i = u and u.i or nil }
        end
    end
    local auto = {}
    for _, e in ipairs(all) do if not pinnedSet[e.id] then auto[#auto + 1] = e end end
    local shown = {}
    for _, p in ipairs(pinned) do
        if p.c > 0 then shown[#shown + 1] = { id = p.id, kind = p.src == "toy" and "toy" or "item" } end
    end
    local autoShown = {}
    for _, e in ipairs(auto) do
        if #shown >= CELLS then break end
        shown[#shown + 1] = { id = e.id, kind = "item" }
        autoShown[#autoShown + 1] = e
    end
    if (assign(shown) or cellsStale()) and not inCombat() then buildCells() end
    local function cellFor(id)
        local col = colOf[id]
        if col ~= nil and frozen[col] ~= id then col = nil end        -- no cell for it yet (looted / pinned in a fight)
        return col
    end
    local pinDoc = {}
    for _, p in ipairs(pinned) do
        local d = { id = p.id, c = p.c, have = p.c > 0, src = p.src, i = p.i, col = p.c > 0 and cellFor(p.id) or nil, cd = cdOf(p.id, now) }
        d.n = TS(function() return C_Item.GetItemNameByID(p.id) end)
        if not d.i then d.i = TN(function() return C_Item.GetItemIconByID(p.id) end) end
        pinDoc[tostring(p.s)] = d
    end
    -- the candidates: pinned, then the auto list, then every other usable item (bag order), worn, toys
    local cand, seen = {}, {}
    local function addCand(id, src, icon)
        if seen[id] then return end
        seen[id] = true
        cand[#cand + 1] = { id = id, src = src or "bag", i = icon }
    end
    for _, p in ipairs(pinned) do addCand(p.id, p.src, p.i) end
    for _, e in ipairs(all) do addCand(e.id, "bag", e.i) end
    for _, u in ipairs(usable) do addCand(u.id, u.src, u.i) end
    pinColumns(cand)
    for _, c in ipairs(cand) do
        c.n = TS(function() return C_Item.GetItemNameByID(c.id) end)
        if not c.i then c.i = TN(function() return C_Item.GetItemIconByID(c.id) end) end
    end
    local items = {}
    for _, e in ipairs(autoShown) do
        local d = { col = cellFor(e.id), id = e.id, c = e.c, g = e.g, i = e.i, cd = cdOf(e.id, now) }
        d.n = TS(function() return C_Item.GetItemNameByID(e.id) end)
        if not d.i then d.i = TN(function() return C_Item.GetItemIconByID(e.id) end) end
        items[#items + 1] = d
    end
    -- frozen cells whose item is gone (used up in a fight): said as dead, so the app never offers them
    local live = {}
    for _, d in ipairs(items) do live[d.id] = true end
    for _, p in ipairs(pinned) do if p.c > 0 then live[p.id] = true end end
    local dead = {}
    for col = 0, CELLS - 1 do
        local id = frozen[col]
        if id and not live[id] then dead[#dead + 1] = col end
    end
    local noK = 0
    for _, c in ipairs(cand) do if c.k == nil then noK = noK + 1 end end
    return { items = items, more = (#auto > #autoShown) and (#auto - #autoShown) or nil, dead = next(dead) and dead or nil,
             fight = inCombat() or nil, row = ROW, cells = CELLS, pad = padRow ~= nil or nil,
             nopad = (padRow == nil) or nil, sticks = stickOn or nil,
             pins = next(pinDoc) and pinDoc or nil, cand = cand, prow = pinRow and PIN_NS or nil, nok = noK > 0 and noK or nil,
             toys = toysHere() or nil }
end
local function emit(force, now)
    local doc = collect()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "radial", v = RV, r = doc }))
    if now and type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
    return true
end
soonPin = function() pcall(emit, true, true) end
local queued = false
local function soon(force)
    if queued then return end
    queued = true
    C_Timer.After(0.3, function() queued = false; pcall(emit, force) end)
end

-- ---------------------------------------------------------------- r3: THE STICK CATCH
-- v2 (the user: TAP Up / HOLD Up, BOTH sticks aim; MEASURED on the first run: the right stick turned the camera while the wheel was open):
-- an invisible frame of ours with EnableGamePadStick(true) -- while SHOWN it captures BOTH sticks: the character does not walk, the camera
-- does not turn (ControllerForever's wheel and Blizzard's soft cursor do the same; its older CVar route was dropped). Shown while the
-- shared capture's holder is the radial's guest, hidden otherwise. EnableGamePadStick is a protected call: made ONCE, out of combat; a
-- plain frame's Show / Hide works in fights too. NO CVar is changed (nothing to restore after a crash or a reload). The app aims with
-- its own reading of the controller (Android side), which this does not touch.
local GUEST_HOLDER = "pad:radial"
local catch = CreateFrame("Frame", "WowCompanionRadialStickCatch", UIParent)
catch:Hide()
pcall(function() catch:SetAllPoints(UIParent) end)
pcall(function() catch:EnableMouse(false) end)
-- r4, MEASURED on b59 (13:48-13:49): the frame was shown with its sticks enabled, yet "the camera is taking over again on the top".
-- The handler's ANSWER decides: Blizzard's own soft cursor (blizzard_gamepadsoftcursor/softcursor.lua) and stick listener
-- (inputbindingstack/inputaxisbinding.lua) return FALSE for a stick they use ("handled": the game does not get it) and TRUE to pass it
-- on ("unhandled"). r3's handler returned nothing, which passed both sticks on. Now both are kept: false for every stick.
catch:SetScript("OnGamePadStick", function(_, stick, x, y, len)
    catch.lastStick = stick
    return false
end)
local function enableSticks()
    if stickOn or inCombat() then return stickOn end
    local ok = pcall(function() catch:EnableGamePadStick(true) end)
    stickOn = ok and S(function() return catch:IsGamePadStickEnabled() end) == true
    return stickOn
end
-- r5 PRE-ARM (Home's latency study: Up -> the frame shown = 260 ms median, 2.9 s worst, waiting for the guest's hold): the moment the
-- router clicks Home's WowCompanionPadRadial (~24 ms after the press) the frame shows. It stays while (the guest holds) OR (armed less
-- than 1.5 s ago and nobody holds yet); the guest's hold ENDS the arm, so its free hides the frame at once (a quick HOLD and let go).
local ARM_S = 1.5
local armedAt = nil
local hubHooked = false
local stickSync
-- r8: the SPELL RADIAL's button (Hub hub8, SpellRadial.lua) arms it too: the same guest "radial" holds both wheels
-- r11: the PAUSE wheel's button too (Hub hub9, Home's radial pause menu: Start on plain Home): every wheel shares the guest
local EXTRA_ARM = { "WowCompanionPadSpellRadial", "WowCompanionPadPause" }
local extraHooked = {}
local function allHooked() for _, n in ipairs(EXTRA_ARM) do if not extraHooked[n] then return false end end return true end
local function hookHub()
    for _, n in ipairs(EXTRA_ARM) do
        local sb = not extraHooked[n] and _G[n]
        if type(sb) == "table" and type(sb.HookScript) == "function" then
            extraHooked[n] = pcall(function() sb:HookScript("OnClick", function() armedAt = GetTime(); pcall(stickSync) end) end)
        end
    end
    if hubHooked then return true end
    local b = _G.WowCompanionPadRadial
    if type(b) ~= "table" or type(b.HookScript) ~= "function" then return false end
    hubHooked = pcall(function() b:HookScript("OnClick", function() armedAt = GetTime(); pcall(stickSync) end) end)
    return hubHooked
end
local function holderNow()
    local cap = WC.capture
    if type(cap) ~= "table" or type(cap.state) ~= "function" then return nil end
    local ok, st = pcall(cap.state)
    return ok and type(st) == "table" and st.holder or nil
end
stickSync = function()
    if not stickOn then enableSticks() end
    if not hubHooked or not allHooked() then hookHub() end
    local holder = holderNow()
    if holder == GUEST_HOLDER then armedAt = nil end              -- the hold has come: the arm has done its job
    local armed = armedAt ~= nil and holder == nil and (GetTime() - armedAt) < ARM_S
    -- r13 (b-class-takeover, Quests q44): the app's CLASS wheel's guest holds the camera still the same way while it aims
    local want = (holder == GUEST_HOLDER or holder == "pad:class" or armed) and stickOn
    if want ~= (catch:IsShown() and true or false) then
        if want then catch:Show() else catch:Hide() end
        -- r6 (the user, b65: "A sometimes doesn't use it" -- MEASURED: after an app restart every wheel had 0 items, because this doc is
        -- sent only on a change and the app's log replay no longer held the last one): the FULL list again on every opening (the arm or
        -- the hold), flushed at once, so the app always has it before any A
        if want then pcall(emit, true, true) end
        -- r10 (Home: "so the wheel never opens on a stale list"): the SPELL radial's full list too, at the same opening (the two wheels
        -- share the guest, so the edge cannot tell which one opens; its list is small), flushed at once
        if want and type(WC.spellRadial) == "table" and type(WC.spellRadial.emit) == "function" then pcall(WC.spellRadial.emit, true) end
        WC.send(WC.encode({ type = "radialstick", v = RV, on = want or false, ok = stickOn or nil, fight = inCombat() or nil,
                            arm = (want and armed and holder == nil) or nil }))
        WC.push()
    end
end
pcall(enableSticks)
if type(C_Timer) == "table" and type(C_Timer.NewTicker) == "function" then
    pcall(C_Timer.NewTicker, 0.1, function() pcall(stickSync) end)
end

-- ---------------------------------------------------------------- events
local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, e)
    if e == "PLAYER_ENTERING_WORLD" then soon(true); pcall(enableSticks); pcall(hookHub)
    elseif e == "PLAYER_REGEN_ENABLED" then soon(true); pcall(enableSticks)   -- the cells catch up; r3: the stick catch if a fight delayed it
    else soon(false) end
end)
for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "BAG_UPDATE_DELAYED", "BAG_UPDATE_COOLDOWN", "GET_ITEM_INFO_RECEIVED",
                     "PLAYER_EQUIPMENT_CHANGED", "TOYS_UPDATED",
                     "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED" }) do
    pcall(function() f:RegisterEvent(e) end)
end

WC.radial = { version = RV, emit = function(force) return emit(force) end, collect = collect, groupOf = groupOf,
              colOf = colOf, frozen = frozen, catch = catch, stickSync = function() return stickSync() end,
              pinTap = function(key) return onPinTap(key) end }

-- ==================================================================== THE SPELL RADIAL (q1) =====
-- r9 (Home, 2026-10-07: WoW loads a NEW file of a TOC only when the game starts, never on /reload): the spell radial rides this
-- file, so one /reload brings it. Its own function: its own locals, nothing shared with the consumables radial above.
-- THE SPELL RADIAL (the user's picks 2026-10-07, via tracking: "A: You choose" -- up to 8 per page, 3 pages, filled in the Controller room
-- like buttons; "A: Like the consumables radial" -- hold, point, release to cast, or Tap mode, the same Hold / Tap setting). The add-on's
-- half, mirroring the consumables radial (Radial.lua):
--   1. the `sradial` document: the character's chosen spells by PLACE s 1-24 (page = ceil(s / 8); slice = (s - 1) % 8 + 1, slice 1 = up,
--      clockwise, as Home's HomeRadialView draws them), each { s, col, id, n, i, cd }; gaps allowed. Home draws it (its source switch).
--   2. ONE TAP = ONE CAST, IN FIGHTS TOO: the secure pad row "sradial" (the pad owner's YES, 45 of 48): each cell a SecureActionButton
--      type "spell", spell <id>. Casting is a PROTECTED action: only a secure click with a hardware event does it, and the app's tap is one
--      (a real touch). Cells are set OUT of combat only, so the set FREEZES at a fight's start. Columns are STABLE per spell (a freed one
--      waits 30 s), so a tap made from a slightly old document never lands on another spell. onCell only answers `sradialuse`.
--   3. The Controller room's "Spell radial" tab (PadTabCtl) fills it through WC.spellRadial: put / clear / move (out of combat).
-- Opened by Home's Hub button WowCompanionPadSpellRadial (barcmd "sradial", the radial's own guest holds the controller). No game window,
-- no Blizzard UI call, nothing written but our own cells and the character's choice.
local function spellRadial()
    local WC = _G.WowCompanion
    if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
    local S, SN, CALL = WC.S, WC.SN, WC.CALL

    local SRV = "q1"                -- bump on every change
    local ROW, CELLS = "sradial", 24 -- the secure pad row: 24 cells = 3 pages of 8
    local PLACES = 24
    local QUARANTINE = 30           -- seconds a freed column stays unused (as the consumables radial's)

    local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
    local function TS(fn) local v = S(fn); if type(v) == "string" and real(v) and v ~= "" then return v end end
    local function TN(fn) local v = S(fn); if type(v) == "number" then return v end end
    local function inCombat() return S(function() return InCombatLockdown() end) == true end

    -- ---------------------------------------------------------------- the character's choice: place -> spell id
    local function whoKey()
        local n = TS(function() return UnitName("player") end)
        local r = TS(function() return GetRealmName() end)
        return n and (n .. "-" .. (r or "?")) or nil
    end
    local function store()
        if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
        local db = _G.WowCompanionSpellsDB
        if type(db.sradial) ~= "table" then db.sradial = {} end
        local k = whoKey()
        if not k then return {} end
        if type(db.sradial[k]) ~= "table" then db.sradial[k] = {} end
        return db.sradial[k]
    end
    local function at(s) local v = store()[s]; if type(v) == "number" then return v end end

    -- ---------------------------------------------------------------- the stable columns
    local colOf, spellAt, freedAt = {}, {}, {}      -- spell id -> col; col -> spell id; col -> when it was freed
    local function assign()
        local now = GetTime()
        local want, changed = {}, false
        for s = 1, PLACES do local id = at(s); if id then want[id] = true end end
        for id, col in pairs(colOf) do
            if not want[id] then colOf[id] = nil; spellAt[col] = nil; freedAt[col] = now; changed = true end
        end
        for s = 1, PLACES do
            local id = at(s)
            if id and colOf[id] == nil then
                for col = 0, CELLS - 1 do
                    if spellAt[col] == nil and (freedAt[col] == nil or now - freedAt[col] >= QUARANTINE) then
                        colOf[id], spellAt[col], freedAt[col] = col, id, nil
                        changed = true
                        break
                    end
                end
            end
        end
        return changed
    end

    -- ---------------------------------------------------------------- the secure cells
    local pad = WC.pad
    local secureOk = type(pad) == "table" and type(pad.secureRow) == "function"
    local frozen = {}               -- col -> spell id as the cells were last set (what a tap really casts)
    local function cellInit(btn, col)
        local id = spellAt[col]
        btn:SetAttribute("type", id and "spell" or nil)
        btn:SetAttribute("spell", id)
        frozen[col] = id
    end
    local function onCell(col)
        -- a cast landed (after the game's own): said, for the app and for the proof, flushed AT ONCE (the player is waiting on it)
        WC.send(WC.encode({ type = "sradialuse", v = SRV, col = col, id = frozen[col], fight = inCombat() or nil }))
        if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
    end
    local function cellsStale()
        for id, col in pairs(colOf) do if frozen[col] ~= id then return true end end
        for col, id in pairs(frozen) do if colOf[id] ~= col then return true end end
        return false
    end
    local padRow = nil              -- the row number the pad gave; nil = no row (the document says `nopad`)
    local function buildCells()
        if not secureOk or inCombat() then return false end
        local ok, row = pcall(pad.secureRow, ROW, CELLS, cellInit, function(col) onCell(col) end)
        padRow = (ok and type(row) == "number") and row or nil
        if not padRow then for col in pairs(frozen) do frozen[col] = nil end end
        return padRow ~= nil
    end

    -- ---------------------------------------------------------------- the document
    local function cdOf(id, now)
        local ok, cd = CALL(function() return C_Spell.GetSpellCooldown(id) end)
        if not ok or type(cd) ~= "table" then return nil end
        local st, dur = SN(function() return cd.startTime end), SN(function() return cd.duration end)
        if st and dur and dur > 1.5 and st > 0 then                -- the global cooldown is not a cooldown worth a sweep
            local left = st + dur - now
            if left > 0 then return { d = math.floor(dur * 10 + 0.5) / 10, l = math.floor(left * 10 + 0.5) / 10 } end
        end
    end
    local function collect()
        -- the row is built at the first document too (an empty radial still says `pad`: Home's view tells a missing row apart)
        if (assign() or cellsStale() or (padRow == nil and secureOk)) and not inCombat() then buildCells() end
        local now = GetTime()
        local items, live = {}, {}
        for s = 1, PLACES do
            local id = at(s)
            if id then
                local d = { s = s, id = id, cd = cdOf(id, now) }
                local col = colOf[id]
                if col ~= nil and frozen[col] == id then d.col = col end      -- no cell yet (chosen in a fight): none until it ends
                local ok, info = CALL(function() return C_Spell.GetSpellInfo(id) end)
                if ok and type(info) == "table" then
                    d.n = TS(function() return info.name end)
                    d.i = TN(function() return info.iconID end)
                end
                items[#items + 1] = d
                live[id] = true
            end
        end
        local dead = {}
        for col = 0, CELLS - 1 do
            local id = frozen[col]
            if id and not live[id] then dead[#dead + 1] = col end
        end
        return { items = items, row = ROW, cells = CELLS, pad = padRow ~= nil or nil, nopad = (padRow == nil) or nil,
                 dead = next(dead) and dead or nil, fight = inCombat() or nil }
    end
    local lastKey = nil
    local function emit(force, now)
        local doc = collect()
        local key = WC.encode(doc)
        if key == lastKey and not force then return false end
        lastKey = key
        WC.send(WC.encode({ type = "sradial", v = SRV, r = doc }))
        -- `now` (an opening, Radial r10): flushed at once, so the app has the list before any press
        if now and type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end
        return true
    end
    local queued = false
    local function soon(force)
        if queued then return end
        queued = true
        C_Timer.After(0.3, function() queued = false; pcall(emit, force) end)
    end

    -- ---------------------------------------------------------------- the Controller room's tab (PadTabCtl)
    -- each change is out of combat only (the cells must follow it); `why` answers a refusal: "combat", "place", "spell"
    local function okPlace(s) return type(s) == "number" and s >= 1 and s <= PLACES and s == math.floor(s) end
    WC.spellRadial = {
        version = 1,
        places = PLACES,
        get = function(s) return okPlace(s) and at(s) or nil end,
        -- a spell onto place s (the one already there goes; the same spell elsewhere moves here: one place per spell)
        put = function(s, id)
            if inCombat() then return false, "combat" end
            if not okPlace(s) then return false, "place" end
            if type(id) ~= "number" or id <= 0 then return false, "spell" end
            local st = store()
            for p = 1, PLACES do if st[p] == id then st[p] = nil end end
            st[s] = id
            soon(true)
            return true
        end,
        clear = function(s)
            if inCombat() then return false, "combat" end
            if not okPlace(s) then return false, "place" end
            store()[s] = nil
            soon(true)
            return true
        end,
        -- Move: what is on `from` goes to `to`; a taken `to` SWAPS
        move = function(from, to)
            if inCombat() then return false, "combat" end
            if not (okPlace(from) and okPlace(to)) or from == to then return false, "place" end
            local st = store()
            if not st[from] then return false, "nothing" end
            st[from], st[to] = st[to], st[from]
            soon(true)
            return true
        end,
        emit = function(now) return emit(true, now) end,
    }

    -- ---------------------------------------------------------------- events
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "SPELLS_CHANGED", "SPELL_UPDATE_COOLDOWN" }) do
        pcall(function() f:RegisterEvent(e) end)
    end
    f:SetScript("OnEvent", function(_, e)
        if e == "SPELL_UPDATE_COOLDOWN" then
            if next(store()) then soon() end                -- a sweep to draw only when something is on the radial
            return
        end
        soon(e ~= "PLAYER_REGEN_DISABLED")
    end)
    -- the document on login and on a manual snapshot (the app asks after a start)
    if type(WC.snapshot) == "function" then
        hooksecurefunc(WC, "snapshot", function(reason) if reason == "login" or reason == "manual" then pcall(emit, true) end end)
    end
end
spellRadial()
-- ==================================================================== end of the spell radial =====

-- ==================================================================== THE PAUSE ROW (r11) =====
-- Home's RADIAL PAUSE MENU (the user's sign-off 2026-10-08; wow-companion-pulse/RADIAL_PAUSE_MENU.md): Start on plain Home opens OUR rebuild of
-- the game's GamepadRadial (PadMap m37 routes the key). Its one-shot actions ride ONE plain pad row "pause" (the pad owner's count: 46 of 54),
-- fixed columns, fired on the press, fine in fights, each answered at once with { type = "pauseack", col, cmd, ok, why | restricted }:
--    0 sit    the game's ToggleSit (the radial's own D-pad Down). If this client blocks it from add-on code (ADDON_ACTION_BLOCKED), the
--             game's Sit / Stand emotes from then on (remembered), the companion keeping its own sitting flag (moving stands you up)
--    1 pvp    C_PvP.TogglePVP (the radial's PvP slice); the app confirms first
--    2-9      the radial's Emotes page, C_ChatInfo.PerformEmote with the game's own tokens (Dance, Point, Wave, Kneel, Train, Chicken,
--             Angry, Cheer); `restricted` when the game refused it (the call answers false, or throws)
--   10 menu   Start's NEXT press opens the game's own Game Menu (PadMap m37 menuArm: the TOGGLEGAMEMENU binding, the secure way); out of
--             combat only (a binding change)
-- Its own function: its own locals. Nothing of Blizzard's UI Lua is called (the two game functions above are the radial's own actions).
local function pauseRow()
    local WC = _G.WowCompanion
    if type(WC) ~= "table" or type(WC.send) ~= "function" or type(WC.encode) ~= "function" then return end
    local pad = WC.pad
    if type(pad) ~= "table" or type(pad.op) ~= "function" then return end
    local PRV = "pr1"
    local EMOTES = { [2] = "Dance", [3] = "Point", [4] = "Wave", [5] = "Kneel", [6] = "Train", [7] = "Chicken", [8] = "Angry", [9] = "Cheer" }
    local CMDS = { [0] = "sit", [1] = "pvp", [10] = "menu" }
    for c, t in pairs(EMOTES) do CMDS[c] = "emote_" .. t:lower() end
    local function inCombat() local ok, v = pcall(function() return InCombatLockdown() end); return ok and v == true end
    local function db()
        if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
        return _G.WowCompanionSpellsDB
    end
    local function ack(col, t)
        t.type, t.v, t.col, t.cmd = "pauseack", PRV, col, CMDS[col]
        WC.send(WC.encode(t))
        if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end      -- the player is waiting on it
    end
    -- the game says what it blocked (an add-on's call of a protected function): seen during our own call, or right after it
    local blocked, sitting = nil, false
    local watch = CreateFrame("Frame")
    for _, e in ipairs({ "ADDON_ACTION_BLOCKED", "ADDON_ACTION_FORBIDDEN", "PLAYER_STARTED_MOVING" }) do pcall(function() watch:RegisterEvent(e) end) end
    watch:SetScript("OnEvent", function(_, e, _, fn)
        if e == "PLAYER_STARTED_MOVING" then sitting = false return end
        blocked = tostring(fn or "?")
    end)
    local function emote(token)
        local ok, res = pcall(function() return C_ChatInfo.PerformEmote(token) end)
        return ok and res ~= false
    end
    local function sitEmote()
        local token = sitting and "Stand" or "Sit"
        local ok = emote(token)
        if ok then sitting = not sitting end
        return ok, token
    end
    -- a game function from add-on code: answered a beat later (a block may be reported after the call returns)
    local function guarded(col, fn, onBlocked)
        blocked = nil
        local ok = pcall(fn)
        C_Timer.After(0.1, function()
            if blocked then
                if onBlocked then onBlocked() else ack(col, { ok = false, why = "blocked" }) end
            else
                ack(col, ok and { ok = true } or { ok = false, why = "error" })
            end
            blocked = nil
        end)
    end
    local function act(col)
        if col == 0 then
            if db().sitMode == "emote" then
                local ok, token = sitEmote()
                ack(col, { ok = ok, via = token, restricted = (not ok) or nil })
                return
            end
            guarded(col, function() ToggleSit() end, function()
                db().sitMode = "emote"                                   -- this client blocks it: the emotes from now on
                local ok, token = sitEmote()
                ack(col, { ok = ok, via = token, why = "blocked", restricted = (not ok) or nil })
            end)
        elseif col == 1 then
            guarded(col, function() C_PvP.TogglePVP() end)
        elseif EMOTES[col] then
            local ok = emote(EMOTES[col])
            ack(col, { ok = ok, restricted = (not ok) or nil })
        elseif col == 10 then
            if inCombat() then ack(col, { ok = false, why = "combat" }) return end
            local pm = WC.padMap
            if type(pm) ~= "table" or type(pm.menuArm) ~= "function" then ack(col, { ok = false, why = "no router" }) return end
            local okc, ok, why = pcall(pm.menuArm)
            ack(col, { ok = okc and ok == true, why = (not okc and "error") or (ok ~= true and why) or nil })
        else
            ack(col, { ok = false, why = "unknown column" })
        end
    end
    pcall(pad.op, "pause", "pause", function(_, col) if type(col) == "number" then act(col) end end)
    WC.pauseRow = { version = PRV, act = act, cmds = CMDS }
end
pauseRow()
-- ==================================================================== end of the pause row =====

-- ==================================================================== THE GAME MENU ROW (r12) =====
-- Home's bottom-screen GAME MENU (the user's pick 2026-10-09; the routes in wow-companion-pulse/GAME_MENU_ROUTES.md, read from this client's
-- own source): ONE SECURE pad row "gamemenu" (the pad owner's pad6b mix), 6 fixed cells, their attributes set out of combat (static):
--   0 gm_logout    type macro "/logout"    } the game's own SECURE slash commands, run by the hardware tap (Logout() / Quit()).
--   1 gm_quit      type macro "/quit"      } NEVER ForceQuit: the QUIT popup's own "Quit Now" stays the player's
--   2 gm_macro     type macro "/macro"     } the game's slash handlers in the secure macro context (the Macro frame; Edit Mode,
--   3 gm_editmode  type macro "/editmode"  } which says the game's own error line when it cannot open)
--   4 gm_info      no action (on the press): the "gamemenu" document
--   5 gm_cancel    no action (on the press): CancelLogout() (a plain API; it cancels both countdowns)
-- Every tap is answered at once: { type = "gmack", v, col, cmd, ok, why }.
-- THE DOCUMENT { type = "gamemenu", v = "gm1", why, mode (C_GameRules.GetActiveGameMode), kiosk, cells = { name -> col },
--   items = the buttons the GAME would show, in its order (GameMenuFrameMixin:InitButtons), each { key, t = the game's own label,
--   on = false when the game would grey it, col = its cell (Log Out, Exit, Macros, Edit Mode) },
--   binds = the client's binding commands that could open Options / AddOns / Support / Shop / Rewards / What's New / Ratings / the menu
--   (read once, GetNumBindings / GetBinding; none expected: those open through Escape, the game's own menu),
--   count = "logout" | "quit" and left = whole seconds while the CAMP / QUIT popup shows (once a second), and once more when it ends
--   (ended = "cancelled" | "gone") }.
-- Only plain reads and the game's own read-only helpers (StaticPopup_Visible runs through securecallfunction); no Blizzard UI is opened
-- or closed from here.
local function gameMenuRow()
    local WC = _G.WowCompanion
    if type(WC) ~= "table" or type(WC.send) ~= "function" or type(WC.S) ~= "function" then return end
    local pad = WC.pad
    if type(pad) ~= "table" or type(pad.secureRow) ~= "function" then return end
    local S, SN = WC.S, WC.SN
    local GMV = "gm2"
    -- gm2 (the map session, test15's safe close: the Exit cell answered but no QUIT countdown came): after a Log Out / Exit tap the row
    -- reports what the GAME did with it: its own refusal line (UI_ERROR_MESSAGE within EXIT_ERR_S: in combat, falling, ...) as a
    -- "gamemenu" doc why "refused" with err = the game's words, and its quitting / camping event as why "event" with ev = the event
    local EXIT_ERR_S = 5
    local exitAt = -100
    local CELLS = { [0] = { "gm_logout", "/logout" }, [1] = { "gm_quit", "/quit" }, [2] = { "gm_macro", "/macro" },
                    [3] = { "gm_editmode", "/editmode" }, [4] = { "gm_info" }, [5] = { "gm_cancel" } }
    local COL = {}
    for c, x in pairs(CELLS) do COL[x[1]] = c end
    local function TB(fn) return S(fn) == true end
    local function word(g)
        local v = _G[g]
        if type(v) == "string" and (type(WC.isPlain) ~= "function" or WC.isPlain(v)) then return v end
        return g
    end
    local function flush() if type(WC.flush) == "function" then pcall(WC.flush) else WC.push() end end
    local function popup(which)
        local ok, name, dialog = pcall(function() return StaticPopup_Visible(which) end)
        if ok and name then return type(dialog) == "table" and dialog or true end
    end
    local function items()
        local kiosk = TB(function() return Kiosk.IsEnabled() end)
        local out = {}
        local function add(key, label, on, col) out[#out + 1] = { key = key, t = word(label), on = on and true or false, col = col } end
        if TB(function() return C_ExternalEventURL.HasURL() end) then add("external", "GAMEMENU_EXTERNALEVENT", true) end
        add("options", "GAMEMENU_OPTIONS", true)
        if TB(function() return C_StorePublic.IsEnabled() end) or TB(function() return C_CatalogShop.IsShop2Enabled() end) then
            add("shop", "BLIZZARD_STORE", not kiosk)
        end
        local store = S(function() return GameRulesUtil.GetActiveAccountStore() end)
        if store ~= nil and store ~= false and store ~= WC.ABSENT and store ~= WC.THREW then add("rewards", "GAME_MENU_SHOW_REWARDS", true) end
        if TB(function() return GameRulesUtil.ShouldShowAddOns() end) then
            add("addons", "ADDONS", not (kiosk or TB(function() return C_AddOns.GetScriptsDisallowedForBeta() end)))
        end
        if TB(function() return GameRulesUtil.ShouldShowSplashScreen() end) then add("whatsnew", "GAMEMENU_NEW_BUTTON", not kiosk) end
        if TB(function() return EditModeManagerFrame:CanEnterEditMode() end) then add("editmode", "HUD_EDIT_MODE_MENU", true, COL.gm_editmode) end
        if TB(function() return GameMenuFrame:GetRatingsButtonShown() end) then add("ratings", "RATINGS_MENU", true) end
        add("support", "GAMEMENU_SUPPORT", not kiosk)
        if not TB(function() return C_GameRules.IsGameRuleActive(Enum.GameRule.MacrosDisabled) end) then
            add("macros", "MACROS", not kiosk, COL.gm_macro)
        end
        local exitOff = kiosk or popup("CAMP") ~= nil or popup("PLUNDERSTORM_LEAVE") ~= nil or popup("QUIT") ~= nil
        add("logout", "LOG_OUT", not exitOff, COL.gm_logout)
        add("quit", "EXIT_GAME", not exitOff, COL.gm_quit)
        add("return", "RETURN_TO_GAME", true)
        return out, kiosk
    end
    -- the binding commands that could open one of the menu's other windows (read once)
    local binds = nil
    local BIND_HINTS = { "GAMEMENU", "OPTION", "SETTING", "ADDON", "HELP", "SUPPORT", "STORE", "SHOP", "REWARD", "SPLASH", "WHATSNEW",
                         "RATING", "MACRO" }
    local function bindList()
        if binds then return binds end
        local n = SN(function() return GetNumBindings() end) or 0
        if n <= 0 then return {} end                                    -- not readable yet (before login): asked again next time
        binds = {}
        for i = 1, n do
            local cmd = S(function() return (GetBinding(i)) end)
            if type(cmd) == "string" and (type(WC.isPlain) ~= "function" or WC.isPlain(cmd)) then
                for _, h in ipairs(BIND_HINTS) do
                    if cmd:find(h, 1, true) then binds[#binds + 1] = cmd; break end
                end
            end
        end
        return binds
    end
    local function doc(why, extra)
        local list, kiosk = items()
        local d = { type = "gamemenu", v = GMV, why = why, mode = SN(function() return C_GameRules.GetActiveGameMode() end),
                    kiosk = kiosk or nil, items = list, cells = COL, binds = bindList() }
        for k, v in pairs(extra or {}) do d[k] = v end
        WC.send(WC.encode(d))
        flush()
    end
    -- THE COUNTDOWN
    local counting, ticker, endedHow = nil, nil, nil
    local function countNow()
        local d = popup("CAMP")
        if d then return "logout", d end
        d = popup("QUIT")
        if d then return "quit", d end
    end
    local function tick()
        local c, d = countNow()
        if c then
            counting = c
            local left = type(d) == "table" and SN(function() return d.timeleft end) or nil
            doc("count", { count = c, left = left and math.ceil(left) or nil })
        else
            if counting then
                local was = counting
                counting = nil
                doc("count", { ended = endedHow or "gone", was = was })
            end
            endedHow = nil
            if ticker and type(ticker.Cancel) == "function" then ticker:Cancel() end
            ticker = nil
        end
    end
    local function watch()
        if not ticker and type(C_Timer) == "table" and type(C_Timer.NewTicker) == "function" then
            ticker = C_Timer.NewTicker(1, function() pcall(tick) end)
        end
        pcall(tick)
    end
    local function ack(col, t)
        t.type, t.v, t.col, t.cmd = "gmack", GMV, col, CELLS[col] and CELLS[col][1]
        WC.send(WC.encode(t))
        flush()
    end
    local function onCell(col)
        if col == COL.gm_info then
            ack(col, { ok = true })
            doc("info")
        elseif col == COL.gm_cancel then
            local ok = pcall(function() CancelLogout() end)
            endedHow = "cancelled"
            ack(col, { ok = ok, why = (not ok) and "error" or nil })
            C_Timer.After(0.2, function() pcall(tick) end)
        elseif CELLS[col] then
            -- a secure cell: the game ran its own slash command on this tap (Logout / Quit: its countdown popup follows)
            ack(col, { ok = true })
            if col == COL.gm_logout or col == COL.gm_quit then exitAt = GetTime(); C_Timer.After(0.3, watch) end
        end
    end
    local function cellInit(btn, col)
        local c = CELLS[col]
        btn:SetAttribute("type", (c and c[2]) and "macro" or nil)
        btn:SetAttribute("macrotext", c and c[2] or nil)
    end
    pcall(pad.secureRow, "gamemenu", 6, cellInit, function(col) pcall(onCell, col) end, { onPress = true })
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_CAMPING", "PLAYER_QUITING", "LOGOUT_CANCEL", "PLAYER_ENTERING_WORLD", "UI_ERROR_MESSAGE" }) do
        pcall(function() f:RegisterEvent(e) end)
    end
    f:SetScript("OnEvent", function(_, e, a1, a2)
        if e == "UI_ERROR_MESSAGE" then
            -- only right after our own Log Out / Exit tap, and only a plain line of the game's
            if GetTime() - exitAt <= EXIT_ERR_S then
                local msg = (type(a2) == "string" and a2) or (type(a1) == "string" and a1) or nil
                if msg and (type(WC.isPlain) ~= "function" or WC.isPlain(msg)) then pcall(doc, "refused", { err = msg }) end
            end
            return
        end
        if (e == "PLAYER_QUITING" or e == "PLAYER_CAMPING") then pcall(doc, "event", { ev = e }) end
        if e == "LOGOUT_CANCEL" then endedHow = "cancelled"; pcall(tick)
        elseif e == "PLAYER_ENTERING_WORLD" then C_Timer.After(2, function() pcall(doc, "login") end)
        else watch() end
    end)
    WC.gameMenu = { version = GMV, doc = function(why) return doc(why or "manual") end, tick = function() return tick() end, cells = COL }
end
gameMenuRow()
-- ==================================================================== end of the game menu row =====
