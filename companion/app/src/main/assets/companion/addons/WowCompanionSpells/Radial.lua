-- WowCompanionSpells / Radial.lua
-- THE CONSUMABLES RADIAL (the user's signed design; Home's D-pad up, 2026-10-06). The add-on's half:
--   1. the `radial` document: the bags' usable consumables in the USER'S groups and order -- 1 food & drink, 2 potions / elixirs /
--      flasks, 3 bandages, 4 other usable (the Hearthstone, scrolls, quest items with a Use) -- by Bags' filters (food 0/5; potion
--      0/1, elixir 0/2, flask 0/3; bandage 0/7; other = a Use and (class 12 Quest, a quest item, or any class but 2 Weapon / 4
--      Armor)); within a group, bag order; ONE entry per item id (its stacks counted together); at most 24 (3 pages of 8, LB / RB),
--      the rest counted in `more`. Each entry: col (its tap cell), id, n, i, c (count), g (group), st (r20: it stacks / gets used up: the
--      app shows its count; nil = not known yet). r20: no `cd`: the cooldowns ride the `itemcd` document (below).
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

local RV = "r20a"               -- bump on every change; r20a (review of feat/item-cooldowns; the user 2026-11-13: "honour enable=0 (held potion: dim, no number)", "Persist the hearthstone end time per name-realm"): held only with a length (the game's duration > 0 and enable 0; an item with no cooldown never dims), the itemcd doc says whose it is (`who`, name-realm: the app never keeps one character's Hearthstone under another's name); r20: ITEM COOLDOWNS (feat/item-cooldowns; the user's picks 2026-11-13: "cooldowns on radial slices AND bag slots: dark pie sweep + seconds"; "LEARNED LENGTHS in the style of the existing facecd"; "Counts only on stackable consumables/reagents/ammo/items with charges"): the `itemcd` document (event-driven only, change-only, never collect / pad.retain / buildCells), BAG_UPDATE_COOLDOWN off the radial's rebuild list, the radial doc's items / pins carry `st` (stackable) and no `cd`; r19: SEVERAL SPELL RADIALS back (feat/multi-radial-v2): q3 = 1.0.18's q2 (6 radials x 8, 40 shared cells, the radial buttons 2..6 armed) with r17e's guard (the row rebuilt only on a change) and NO cooldown sweep (r18 / q1b: one sradialcd per cast); r18: the spell radial (q1b) has NO cooldown sweep (no SPELL_UPDATE_COOLDOWN): its cooldowns come once per cast (sradialcd); r17 (1.0.18, reverted): the spell radials (q2); r16: the gamemenu row widened to 40, cols 6-39 Smart buffs' (PadMap m62)
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
-- r14 (the user's ruling on 1.0.9-test18: "Recipes should not be an option to assign to the quick radial"): a recipe (Enum.ItemClass
-- .Recipe, 9) is never on the radial, auto-filled or pinned
local RECIPE = 9
local function groupOf(cls, sub, use, quest)
    if cls == RECIPE then return nil end
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
local useItemOf = {}            -- r20: an item's Use spell id -> the item id (a cast of it = that item used: the learned length starts)
local function hasUse(id)
    if useOf[id] then return true end
    local ok, sp, sid = CALL(function() return C_Item.GetItemSpell(id) end)
    if ok and type(sp) == "string" and WC.isPlain(sp) then
        useOf[id] = true
        if type(sid) == "number" and WC.isPlain(sid) then useItemOf[sid] = id end
        return true
    end
    return false
end

-- r20 THE COUNT RULE (the user's pick 2026-11-13: "Counts only on stackable consumables/reagents/ammo/items with charges"; rule 1d):
-- an item shows its count when the game calls it consumable (C_Item.IsConsumableItem, or the old global), or it stacks (GetItemInfo's
-- 8th return above 1), or its class is 0 Consumable / 5 Reagent / 6 Projectile. Gear, the Hearthstone, toys and single quest tools: none.
-- true / false once known (kept per item id: never changes); nil = not known yet (the item not cached): the count shows, asked again
local stackOf = {}
local function consumableFn()
    if type(C_Item) == "table" and type(C_Item.IsConsumableItem) == "function" then return C_Item.IsConsumableItem end
    if type(_G.IsConsumableItem) == "function" then return _G.IsConsumableItem end
end
local function itemInfoFn()
    if type(C_Item) == "table" and type(C_Item.GetItemInfo) == "function" then return C_Item.GetItemInfo end
    if type(_G.GetItemInfo) == "function" then return _G.GetItemInfo end
end
local function stackable(id)
    local v = stackOf[id]
    if v ~= nil then return v end
    local cs = itemClass(id)
    if cs and (cs[1] == 0 or cs[1] == 5 or cs[1] == 6) then stackOf[id] = true return true end
    local cf = consumableFn()
    if cf and S(function() return cf(id) end) == true then stackOf[id] = true return true end
    local gi = itemInfoFn()
    if gi then
        local ok, _, _, _, _, _, _, _, maxStack = CALL(function() return gi(id) end)
        local ms = ok and SN(function() return maxStack end) or nil
        if ms then stackOf[id] = ms > 1 return ms > 1 end
    end
    return nil
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
local usedAt = {}               -- r20: item id -> when it was used (our cell's tap, or its Use spell's cast): a secret read then starts its learned length
local function onCell(col)
    -- a tap landed (after the game's own use): said, for the app and for the proof. r5 (Home's latency study: item use -> radialuse
    -- ~1.08 s): flushed AT ONCE, not at the next frame through the 0.2 s throttle -- the player is waiting on it (Sink's own rule), and
    -- a use comes at most about once a second
    if frozen[col] then usedAt[frozen[col]] = GetTime() end
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
        local cs = itemClass(key)
        if cs and cs[1] == RECIPE then pinAck("pin", false, "a recipe", p.slice, key) if soonPin then soonPin() end return end   -- r14
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
local HEARTH = 6948             -- r20: the Hearthstone (Home's clock extra; always watched)
local cdWatch = { [HEARTH] = true } -- r20: the item ids the `itemcd` document reads: set by collect() (usable bag items, worn, toys, pins)
-- r20: ONE item's cooldown read (C_Container.GetItemCooldown through CALL / S: a secret value is never compared or used):
-- { t0, d } running | "held" (enable = 0: a potion held until the fight ends) | "secret" | nil (ready / none / unreadable)
local function itemRead(id)
    local okC, st, dur, en = CALL(function() return C_Container.GetItemCooldown(id) end)
    if not okC then return nil end
    local sv, dv, ev = S(function() return st end), S(function() return dur end), S(function() return en end)
    -- r20a (review; the user's pick "honour enable=0 (held potion: dim, no number)"): HELD the game's own way (ContainerFrame: duration > 0
    -- and enable 0): an item with no cooldown can say enable 0 too, and was dimmed for good; a secret length with enable 0 = "heldq"
    -- (held only when we know it was used / is counting: cdScan)
    if ev == false or ev == 0 then
        if type(dv) == "number" and dv > 0 then return "held" end
        if dv == WC.SECRET then return "heldq" end
    end
    if sv == WC.SECRET or dv == WC.SECRET then return "secret" end
    if type(sv) == "number" and type(dv) == "number" and dv > 1.5 and sv > 0 and sv + dv > GetTime() then return { t0 = sv, d = dv } end
    return nil
end
local function collect()
    local all = scan()
    local usable, byUse = scanUsable()
    local pins = pinsFor(false) or {}
    -- r20: the ids the itemcd document reads (it never calls this function: it reads only this set)
    for id in pairs(cdWatch) do if id ~= HEARTH then cdWatch[id] = nil end end
    for _, u in ipairs(usable) do cdWatch[u.id] = true end
    for _, id in pairs(pins) do if type(id) == "number" then cdWatch[id] = true end end
    -- r7: the pinned items first (those in the bags or worn get the cells first), then the auto list without them
    local pinned, pinnedSet = {}, {}
    for sl = 0, PIN_SLICES - 1 do
        local id = pins[sl]
        -- r14: a recipe pinned before the ruling is cleared quietly (no dead slice)
        if type(id) == "number" then local cs = itemClass(id); if cs and cs[1] == RECIPE then pins[sl] = nil; id = nil end end
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
        local d = { id = p.id, c = p.c, have = p.c > 0, src = p.src, i = p.i, col = p.c > 0 and cellFor(p.id) or nil, st = stackable(p.id) }
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
        local d = { col = cellFor(e.id), id = e.id, c = e.c, g = e.g, i = e.i, st = stackable(e.id) }
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

-- ---------------------------------------------------------------- r20: THE ITEM COOLDOWNS (`itemcd`)
-- The user's picks 2026-11-13 (feat/item-cooldowns): "cooldowns on radial slices AND bag slots: dark pie sweep + seconds"; the clock
-- extras on Home ("Hearthstone, class teleport (Astral Recall etc., only once known), gold, free bag slots"); in combat "LEARNED LENGTHS
-- in the style of the existing facecd ... if never learned, show the never-secret isActive dim only. Also: a countdown that started before
-- the fight keeps running"; "itemcd document per 1a: event-driven only ... 0.3 s merge, change-only, NEVER calls collect()/pad.retain/
-- buildCells"; "honour enable=0 (held potion: dim, no number)".
--   { type = "itemcd", v, t = { ["<item id>"] = { d, l, e? } | { h = true } (held) | { x = true } (running, length unknown) },
--     hs = { have, i } (the Hearthstone), tp = { id, n, i, cd = { d, l, e? } | x } (the class teleport the player knows), fight }
-- An id that is not in `t` is ready (out of a fight; in a fight the app keeps what it counts). It reads ONLY the ids collect() left in
-- cdWatch (and the Hearthstone): no bag walk, no cell, no pad call. Sent on BAG_UPDATE_COOLDOWN / PLAYER_REGEN_ENABLED / _DISABLED /
-- PLAYER_ENTERING_WORLD, and on the player's cast of an item's Use spell or the teleport (the read that starts a learned length), 0.3 s
-- merged, and only when an (id, start, length, state) changed. Nothing ticks.
-- LEARNED LENGTHS (facecd's way, PadMap m81): every plain read learns the cooldown's length, per character (name-realm), in
-- WowCompanionSpellsDB.cdLearn[who]: ["i<item id>"] / ["s<spell name>"] = seconds. A secret read right after a use starts the learned
-- length (e = true, estimated); never learned = { x = true } (dimmed, no number); a countdown already running is kept.
local function learnDb()
    local k = whoKey()
    if not k then return nil end
    if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
    local db = _G.WowCompanionSpellsDB
    if type(db.cdLearn) ~= "table" then db.cdLearn = {} end
    if type(db.cdLearn[k]) ~= "table" then db.cdLearn[k] = {} end
    return db.cdLearn[k]
end
local TELEPORTS = { 556, 18960, 193753, 50977, 126892 }   -- Astral Recall, Teleport: Moonglade, Dreamwalk, Death Gate, Zen Pilgrimage
local function knows(id)
    return S(function() return IsPlayerSpell(id) end) == true or S(function() return IsSpellKnown(id) end) == true
end
local function teleportNow()
    for _, id in ipairs(TELEPORTS) do if knows(id) then return id end end
    return nil
end
local itemRun = {}              -- item id -> { t0, d, e? } | { held = true } | { dim = true }
local tpRun = nil               -- the teleport's: { id, t0, d, e? } | { id, dim = true }
local tpCastAt = nil            -- when the teleport was last cast (a secret read then starts its learned length)
local function r1(v) return math.floor(v * 10 + 0.5) / 10 end
local function cdScan(now)
    local fight = inCombat()
    local learn = learnDb()
    for id in pairs(cdWatch) do
        local cur = itemRun[id]
        if cur and cur.d and now >= cur.t0 + cur.d then itemRun[id] = nil; cur = nil end
        local r = itemRead(id)
        if type(r) == "table" then
            itemRun[id] = { t0 = r.t0, d = r.d }
            if learn then learn["i" .. id] = r1(r.d) end
        elseif r == "held" or (r == "heldq" and ((cur and cur.held) or (usedAt[id] and now - usedAt[id] < 3))) then
            itemRun[id] = { held = true }
        elseif r == "secret" or r == "heldq" then
            -- the game keeps it secret (a fight): a running countdown stays; a use just now starts its learned length, else the dim
            local used = usedAt[id]
            if used and now - used < 3 and not (cur and cur.d and cur.t0 >= used - 0.5) then
                local d = learn and learn["i" .. id]
                itemRun[id] = (type(d) == "number" and d > 0) and { t0 = used, d = d, e = true } or { dim = true }
            end
        elseif not (fight and cur) then
            itemRun[id] = nil                                       -- a plain "ready" (in a fight a missing read keeps what runs)
        end
    end
    for id in pairs(itemRun) do if not cdWatch[id] then itemRun[id] = nil end end
    -- the class teleport (a spell): its own read; the same learned / secret rule by its name
    local tp = teleportNow()
    if not tp then tpRun = nil
    else
        if tpRun and (tpRun.id ~= tp or (tpRun.d and now >= tpRun.t0 + tpRun.d)) then tpRun = nil end
        local ok, cd = CALL(function() return C_Spell.GetSpellCooldown(tp) end)
        local st = ok and type(cd) == "table" and S(function() return cd.startTime end) or nil
        local du = ok and type(cd) == "table" and S(function() return cd.duration end) or nil
        local nm = TS(function() return C_Spell.GetSpellName(tp) end)
        if st == WC.SECRET or du == WC.SECRET then
            if tpCastAt and now - tpCastAt < 3 and not (tpRun and tpRun.d and tpRun.t0 >= tpCastAt - 0.5) then
                local d = learn and nm and learn["s" .. nm]
                local active = S(function() return cd.isActive end) == true                 -- isActive is never secret
                if type(d) == "number" and d > 0 then tpRun = { id = tp, t0 = tpCastAt, d = d, e = true }
                elseif active then tpRun = { id = tp, dim = true } end
            end
        elseif type(st) == "number" and type(du) == "number" and du > 1.5 and st > 0 and st + du > now then
            tpRun = { id = tp, t0 = st, d = du }
            if learn and nm then learn["s" .. nm] = r1(du) end
        elseif not (fight and tpRun) then
            tpRun = nil
        end
    end
    return tp, fight
end
local cdLastKey = nil
local function cdEmit(force)
    local now = GetTime()
    local tp, fight = cdScan(now)
    local t, sig = {}, {}
    for id, e in pairs(itemRun) do
        local k = tostring(id)
        if e.held then t[k] = { h = true }; sig[#sig + 1] = k .. "h"
        elseif e.dim then t[k] = { x = true }; sig[#sig + 1] = k .. "x"
        else
            local left = e.t0 + e.d - now
            if left > 0 then
                t[k] = { d = r1(e.d), l = r1(left), e = e.e or nil }
                sig[#sig + 1] = k .. ":" .. r1(e.t0) .. ":" .. r1(e.d)
            end
        end
    end
    table.sort(sig)
    local tpDoc = nil
    if tp then
        tpDoc = { id = tp, n = TS(function() return C_Spell.GetSpellName(tp) end), i = TN(function() return C_Spell.GetSpellTexture(tp) end) }
        if tpRun and tpRun.dim then tpDoc.x = true; sig[#sig + 1] = "tpx"
        elseif tpRun and tpRun.d and tpRun.t0 + tpRun.d > now then
            tpDoc.cd = { d = r1(tpRun.d), l = r1(tpRun.t0 + tpRun.d - now), e = tpRun.e or nil }
            sig[#sig + 1] = "tp:" .. r1(tpRun.t0) .. ":" .. r1(tpRun.d)
        end
        sig[#sig + 1] = "tp" .. tp
    end
    local have = TN(function()
        if type(C_Item) == "table" and type(C_Item.GetItemCount) == "function" then return C_Item.GetItemCount(HEARTH) end
        return GetItemCount(HEARTH)
    end)
    local hs = { i = TN(function() return C_Item.GetItemIconByID(HEARTH) end) }
    if have ~= nil then hs.have = have > 0 end                       -- nil = not readable now (the app shows it)
    sig[#sig + 1] = "hs" .. tostring(hs.have) .. (fight and "f" or "")
    local key = table.concat(sig, ",")
    if key == cdLastKey and not force then return false end
    cdLastKey = key
    WC.send(WC.encode({ type = "itemcd", v = RV, who = whoKey(), t = next(t) and t or nil, hs = hs, tp = tpDoc, fight = fight or nil }))
    WC.push()
    return true
end
local cdQueued, cdForce = false, false
local function cdSoon(force)
    if force then cdForce = true end
    if cdQueued then return end
    cdQueued = true
    C_Timer.After(0.3, function() cdQueued = false; local fo = cdForce; cdForce = false; pcall(cdEmit, fo) end)
end
local cdFrame = CreateFrame("Frame")
for _, e in ipairs({ "BAG_UPDATE_COOLDOWN", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "PLAYER_ENTERING_WORLD" }) do
    pcall(function() cdFrame:RegisterEvent(e) end)
end
pcall(function() cdFrame:RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player") end)
cdFrame:SetScript("OnEvent", function(_, e, unit, _, spellID)
    if e == "UNIT_SPELLCAST_SUCCEEDED" then
        if unit ~= "player" then return end
        local sid = SN(function() return spellID end)
        if not sid then return end
        local item = useItemOf[sid]
        if item then usedAt[item] = GetTime(); cdSoon(false)
        else
            for _, tp in ipairs(TELEPORTS) do if tp == sid then tpCastAt = GetTime(); cdSoon(false) end end
        end
        return
    end
    cdSoon(e ~= "BAG_UPDATE_COOLDOWN")
end)
if type(WC.snapshot) == "function" and type(hooksecurefunc) == "function" then
    pcall(hooksecurefunc, WC, "snapshot", function(reason) if reason == "login" or reason == "manual" then cdSoon(true) end end)
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
-- r15 (the user on 1.0.9-test20: "The control sticks are not locking on the dpad up radial"): the catch sits over every window's strata (a
-- frame of ours higher in the stick's order than a window's own), and counts what it caught while shown (the radialstick doc's n / pass)
pcall(function() catch:SetFrameStrata("TOOLTIP") end)
catch.n, catch.pass, catch.mode = 0, 0, nil
-- r4, MEASURED on b59 (13:48-13:49): the frame was shown with its sticks enabled, yet "the camera is taking over again on the top".
-- The handler's ANSWER decides: Blizzard's own soft cursor (blizzard_gamepadsoftcursor/softcursor.lua) and stick listener
-- (inputbindingstack/inputaxisbinding.lua) return FALSE for a stick they use ("handled": the game does not get it) and TRUE to pass it
-- on ("unhandled"). r3's handler returned nothing, which passed both sticks on. Now both are kept: false for every stick.
-- r15 (the user: "On the class radial only the right control stick should be locked"): the CLASS wheel's catch keeps only the right stick
-- (its aim): the left one goes on to the game (the character walks), as Blizzard's own radial does (blizzard_radialwheel.lua: "Only allow
-- this to consume Right Stick input", Left / Movement passed on). The item / spell / pause wheels aim with both: both kept
catch:SetScript("OnGamePadStick", function(_, stick, x, y, len)
    catch.lastStick = stick
    if catch.mode == "class" and (stick == "Left" or stick == "Movement") then catch.pass = catch.pass + 1 return true end
    catch.n = catch.n + 1
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
-- r17 / r19: the other spell radials' buttons too (Hub hub47, q3: WowCompanionPadSpellRadial2..6)
local EXTRA_ARM = { "WowCompanionPadSpellRadial", "WowCompanionPadPause", "WowCompanionPadSpellRadial2", "WowCompanionPadSpellRadial3",
                    "WowCompanionPadSpellRadial4", "WowCompanionPadSpellRadial5", "WowCompanionPadSpellRadial6" }
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
    catch.mode = holder == "pad:class" and "class" or nil
    if want ~= (catch:IsShown() and true or false) then
        local caught, passed = catch.n, catch.pass
        catch.n, catch.pass = 0, 0
        if want then catch:Show() else catch:Hide() end
        -- r6 (the user, b65: "A sometimes doesn't use it" -- MEASURED: after an app restart every wheel had 0 items, because this doc is
        -- sent only on a change and the app's log replay no longer held the last one): the FULL list again on every opening (the arm or
        -- the hold), flushed at once, so the app always has it before any A
        if want then pcall(emit, true, true) end
        -- r10 (Home: "so the wheel never opens on a stale list"): the SPELL radial's full list too, at the same opening (the two wheels
        -- share the guest, so the edge cannot tell which one opens; its list is small), flushed at once
        if want and type(WC.spellRadial) == "table" and type(WC.spellRadial.emit) == "function" then pcall(WC.spellRadial.emit, true) end
        WC.send(WC.encode({ type = "radialstick", v = RV, on = want or false, ok = stickOn or nil, fight = inCombat() or nil,
                            arm = (want and armed and holder == nil) or nil, holder = holder,
                            mode = catch.mode, n = (not want) and caught or nil, pass = (not want and passed > 0) and passed or nil }))
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
-- r20: NO BAG_UPDATE_COOLDOWN here (the user's pick 2026-11-13, rule 1a: a cooldown event never rebuilds the list or the cells, the
-- class of v2's ADDON_ACTION_FORBIDDEN): the cooldowns ride the `itemcd` document above
for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "BAG_UPDATE_DELAYED", "GET_ITEM_INFO_RECEIVED",
                     "PLAYER_EQUIPMENT_CHANGED", "TOYS_UPDATED",
                     "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED" }) do
    pcall(function() f:RegisterEvent(e) end)
end

WC.radial = { version = RV, emit = function(force) return emit(force) end, collect = collect, groupOf = groupOf,
              colOf = colOf, frozen = frozen, catch = catch, stickSync = function() return stickSync() end,
              pinTap = function(key) return onPinTap(key) end,
              -- r20: the item cooldowns (Home's clock extras, the item wheel, the bags) and the count rule
              itemcd = function(force) return cdEmit(force) end, cdWatch = cdWatch, stackable = function(id) return stackable(id) end }

-- ==================================================================== THE SPELL RADIALS (q2) =====
-- r9 (Home, 2026-10-07: WoW loads a NEW file of a TOC only when the game starts, never on /reload): the spell radial rides this
-- file, so one /reload brings it. Its own function: its own locals, nothing shared with the consumables radial above.
-- q2 SEVERAL SPELL RADIALS (the user's picks 2026-11-08: "create new radial on that tab of the controller page and then be able to select
-- them from a list to edit each"; "Each its own button"; "Up to 8, no pages"; "The radials should not have empty slots, but instead have
-- things evenly distributed by how many items are in it"): up to MAX (6) radials per character, in FIXED SLOTS 1..6 (slot n = the bar
-- command DSSRADIAL / DSSRADIAL<n>, Hub's WowCompanionPadSpellRadial / ...<n>), each an ORDERED list of at most PER (8) spells, no gaps:
-- Home spreads them evenly. A radial's name, icon and Hold / Tap are the app's (per character, per slot); the spells are here because
-- each needs a secure cell. q1's single radial (24 places, 3 pages) moves over once: each page with spells becomes a radial, in order.
--   1. the `sradial` document: r = { list = [{ u = slot, items = [{ id, col, n, i, cd }] }], row, cells, pad / nopad, dead, fight }.
--   2. ONE TAP = ONE CAST, IN FIGHTS TOO: the secure pad row "sradial", CELLS (40) cells shared by every radial (a spell on two radials
--      has one cell): each a SecureActionButton type "spell", spell <id>. Cells are set OUT of combat only (the set FREEZES at a fight's
--      start). Columns are STABLE per spell (a freed one waits 30 s). A 41st different spell is refused ("full").
--   3. The Controller room's "Spell radial" tab (PadTabCtl) fills them through WC.spellRadial (out of combat).
-- No game window, no Blizzard UI call, nothing written but our own cells and the character's choice.
local function spellRadial()
    local WC = _G.WowCompanion
    if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
    local S, SN, CALL = WC.S, WC.SN, WC.CALL

    local SRV = "q4"                -- bump on every change; q4 (feat/item-cooldowns; the user 2026-11-13: "LEARNED LENGTHS in the style of the existing facecd ... if never learned, show the never-secret isActive dim only"): every plain read learns the spell's length (per character, Radial r20's cdLearn), a secret read after a cast starts it (cd.e), never learned = act (dim); q3: q2 back with r17e's row guard and no SPELL_UPDATE_COOLDOWN sweep (q1b's sradialcd)
    local ROW, CELLS = "sradial", 40 -- the secure pad row: 40 cells, shared by every radial
    local MAX, PER = 6, 8
    local QUARANTINE = 30           -- seconds a freed column stays unused (as the consumables radial's)

    local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end
    local function TS(fn) local v = S(fn); if type(v) == "string" and real(v) and v ~= "" then return v end end
    local function TN(fn) local v = S(fn); if type(v) == "number" then return v end end
    local function inCombat() return S(function() return InCombatLockdown() end) == true end

    -- ---------------------------------------------------------------- the character's radials: slot -> { ids = { spell id, ... } }
    local function whoKey()
        local n = TS(function() return UnitName("player") end)
        local r = TS(function() return GetRealmName() end)
        return n and (n .. "-" .. (r or "?")) or nil
    end
    local function store()
        if type(_G.WowCompanionSpellsDB) ~= "table" then _G.WowCompanionSpellsDB = {} end
        local db = _G.WowCompanionSpellsDB
        if type(db.sradials) ~= "table" then db.sradials = {} end
        local k = whoKey()
        if not k then return {} end
        if type(db.sradials[k]) ~= "table" then
            -- q1 -> q2, once: each of the old radial's pages that holds spells becomes a radial (its places in order); none = one empty
            local out, n = {}, 0
            local old = type(db.sradial) == "table" and type(db.sradial[k]) == "table" and db.sradial[k] or {}
            for page = 1, 3 do
                local ids = {}
                for p = 1, 8 do
                    local id = old[(page - 1) * 8 + p]
                    if type(id) == "number" and id > 0 then ids[#ids + 1] = id end
                end
                if #ids > 0 and n < MAX then n = n + 1; out[n] = { ids = ids } end
            end
            if n == 0 then out[1] = { ids = {} } end
            db.sradials[k] = out
        end
        return db.sradials[k]
    end
    local function okSlot(r) return type(r) == "number" and r >= 1 and r <= MAX and r == math.floor(r) end
    local function rad(r) local st = store(); local v = okSlot(r) and st[r]; if type(v) == "table" then if type(v.ids) ~= "table" then v.ids = {} end return v end end
    local function slots() local out = {}; for r = 1, MAX do if rad(r) then out[#out + 1] = r end end return out end
    -- every spell on any radial, once
    local function wanted()
        local want, n = {}, 0
        for r = 1, MAX do
            local v = rad(r)
            if v then for _, id in ipairs(v.ids) do if type(id) == "number" and not want[id] then want[id] = true; n = n + 1 end end end
        end
        return want, n
    end

    -- ---------------------------------------------------------------- the stable columns
    local colOf, spellAt, freedAt = {}, {}, {}      -- spell id -> col; col -> spell id; col -> when it was freed
    local function assign()
        local now = GetTime()
        local want = wanted()
        local changed = false
        for id, col in pairs(colOf) do
            if not want[id] then colOf[id] = nil; spellAt[col] = nil; freedAt[col] = now; changed = true end
        end
        for r = 1, MAX do
            local v = rad(r)
            if v then
                for _, id in ipairs(v.ids) do
                    if colOf[id] == nil then
                        for col = 0, CELLS - 1 do
                            if spellAt[col] == nil and (freedAt[col] == nil or now - freedAt[col] >= QUARANTINE) then
                                colOf[id], spellAt[col], freedAt[col] = col, id, nil
                                changed = true
                                break
                            end
                        end
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
    -- q4: the spell's name (the learned length's key: every rank shares it, as facecd's)
    local function nameOf(id) return TS(function() return C_Spell.GetSpellName(id) end) end
    -- -> cd { d, l } or nil; secret (the game keeps the value from us now); active (isActive, never secret)
    local function cdOf(id, now)
        local ok, cd = CALL(function() return C_Spell.GetSpellCooldown(id) end)
        if not ok or type(cd) ~= "table" then return nil end
        local sv, dv = S(function() return cd.startTime end), S(function() return cd.duration end)
        if sv == WC.SECRET or dv == WC.SECRET then return nil, true, S(function() return cd.isActive end) == true end
        local st, dur = type(sv) == "number" and sv or nil, type(dv) == "number" and dv or nil
        if st and dur and dur > 1.5 and st > 0 then                -- the global cooldown is not a cooldown worth a sweep
            local left = st + dur - now
            if left > 0 then
                -- q4: a plain read LEARNS the length (per character; the facecd way)
                local n, learn = nameOf(id), learnDb()
                if n and learn then learn["s" .. n] = math.floor(dur * 10 + 0.5) / 10 end
                return { d = math.floor(dur * 10 + 0.5) / 10, l = math.floor(left * 10 + 0.5) / 10 }
            end
        end
    end
    -- r17e (the loot-close taint hunt, 1.0.19-test11): the row is rebuilt ONLY when its spells / cells change; a row the pad has no place
    -- for is tried ONCE per change, not at every document (TapPad's buildSecure re-places EVERY secure row of every add-on as our code)
    local triedNoRow = false
    local function collect()
        -- the row is built at the first document too (an empty radial still says `pad`: Home's view tells a missing row apart)
        local changed = assign() or cellsStale()
        if changed then triedNoRow = false end
        if (changed or (padRow == nil and secureOk and not triedNoRow)) and not inCombat() then
            if padRow == nil then triedNoRow = true end
            buildCells()
        end
        local now = GetTime()
        local list, live, info = {}, {}, {}
        local function one(id)
            if info[id] == nil then
                local d = { id = id, cd = cdOf(id, now) }
                local col = colOf[id]
                if col ~= nil and frozen[col] == id then d.col = col end      -- no cell yet (chosen in a fight): none until it ends
                local ok, si = CALL(function() return C_Spell.GetSpellInfo(id) end)
                if ok and type(si) == "table" then
                    d.n = TS(function() return si.name end)
                    d.i = TN(function() return si.iconID end)
                end
                info[id] = d
            end
            return info[id]
        end
        for r = 1, MAX do
            local v = rad(r)
            if v then
                local items = {}
                for _, id in ipairs(v.ids) do
                    if type(id) == "number" then items[#items + 1] = one(id); live[id] = true end
                end
                list[#list + 1] = { u = r, items = items }
            end
        end
        local dead = {}
        for col = 0, CELLS - 1 do
            local id = frozen[col]
            if id and not live[id] then dead[#dead + 1] = col end
        end
        return { list = list, max = MAX, per = PER, row = ROW, cells = CELLS, pad = padRow ~= nil or nil, nopad = (padRow == nil) or nil,
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
    -- a radial made or deleted: the padmap's commands list follows (each radial's own command shows only while it exists)
    local function commandsChanged()
        if type(WC.padMap) == "table" and type(WC.padMap.emit) == "function" then pcall(WC.padMap.emit, true) end
    end

    -- ---------------------------------------------------------------- the Controller room's tab (PadTabCtl)
    -- each change is out of combat only (the cells must follow it); `why` answers a refusal: "combat", "radial", "place", "spell",
    -- "full" (a 41st different spell), "max" (a 7th radial)
    WC.spellRadial = {
        version = 2,
        max = MAX, per = PER,
        has = function(r) return rad(r) ~= nil end,
        slots = slots,
        count = function(r) local v = rad(r); return v and #v.ids or 0 end,
        ids = function(r) local v = rad(r); local out = {}; if v then for i, id in ipairs(v.ids) do out[i] = id end end return out end,
        get = function(r, i) local v = rad(r); return v and v.ids[i] or nil end,
        -- a spell onto place i of radial r: i = count + 1 adds it at the end; the same spell elsewhere on THIS radial moves here
        put = function(r, i, id)
            if inCombat() then return false, "combat" end
            local v = rad(r)
            if not v then return false, "radial" end
            if type(id) ~= "number" or id <= 0 then return false, "spell" end
            if type(i) ~= "number" or i < 1 or i > #v.ids + 1 or i > PER or i ~= math.floor(i) then return false, "place" end
            local want, n = wanted()
            if not want[id] and n >= CELLS then return false, "full" end
            local at = nil
            for p, x in ipairs(v.ids) do if x == id then at = p end end
            if at == i then return true end
            if at then
                -- already on this radial: the two places swap (one place per spell, no gap left)
                if i <= #v.ids then v.ids[at], v.ids[i] = v.ids[i], v.ids[at] end
            elseif i == #v.ids + 1 then v.ids[i] = id
            else v.ids[i] = id end
            soon(true)
            return true
        end,
        -- take place i off radial r: the ones after it close up (no empty slots)
        clear = function(r, i)
            if inCombat() then return false, "combat" end
            local v = rad(r)
            if not v then return false, "radial" end
            if type(i) ~= "number" or not v.ids[i] then return false, "place" end
            table.remove(v.ids, i)
            soon(true)
            return true
        end,
        -- Move: place `from` to `to` on radial r; a taken `to` SWAPS
        move = function(r, from, to)
            if inCombat() then return false, "combat" end
            local v = rad(r)
            if not v then return false, "radial" end
            if not (v.ids[from] and v.ids[to]) or from == to then return false, "place" end
            v.ids[from], v.ids[to] = v.ids[to], v.ids[from]
            soon(true)
            return true
        end,
        -- a new, empty radial in the first free slot: its slot, or nil + why
        new = function()
            if inCombat() then return nil, "combat" end
            local st = store()
            for r = 1, MAX do
                if type(st[r]) ~= "table" then
                    st[r] = { ids = {} }
                    soon(true); commandsChanged()
                    return r
                end
            end
            return nil, "max"
        end,
        -- delete radial r: its spells and its slot; its own command comes off every button (PadMap)
        drop = function(r)
            if inCombat() then return false, "combat" end
            if not rad(r) then return false, "radial" end
            store()[r] = nil
            if type(WC.padMapDropCommand) == "function" then pcall(WC.padMapDropCommand, r == 1 and "DSSRADIAL" or ("DSSRADIAL" .. r)) end
            soon(true); commandsChanged()
            return true
        end,
        emit = function(now) return emit(true, now) end,
    }

    -- ---------------------------------------------------------------- one cooldown per cast (q1b)
    -- q1b NO COOLDOWN SWEEP (the user's pick 2026-11-13, after the 1.0.19 bisect: q2's re-send at every SPELL_UPDATE_COOLDOWN was the
    -- line behind the loot window's ADDON_ACTION_FORBIDDEN SetPreferredGamepadInteractTarget): the list is sent on EVENTS only (login,
    -- an edit, a spell learned, a fight's start / end). A cooldown is sent ONCE per cast of a spell on the radial: the player's
    -- UNIT_SPELLCAST_SUCCEEDED, a moment later, ONE read of that spell's cooldown -> { type = "sradialcd", id, cd = { d, l } }; the app
    -- counts it down by itself. A value the game keeps secret (a fight) is never used: no `cd`, the app shows no countdown.
    -- q4 LEARNED LENGTHS (the user's pick 2026-11-13, facecd's way): a secret read after a cast starts the length learned from the
    -- spell's last plain read (cd.e = true, estimated, counted from the cast); never learned: `act` = true when the never-secret isActive
    -- says one runs, read once more past the global cooldown (GCD_S) so the GCD alone never dims a spell (the app dims it, no number)
    local GCD_S = 1.6
    local cdQ = {}                  -- spell id -> when it was cast: casts waiting for their one read
    local cdQueued = false
    local actQ = {}                 -- spell id -> when it was cast: secret, never learned, waiting for the read past the GCD
    local function actSend()
        local now = GetTime()
        for id, at in pairs(actQ) do
            if now - at >= GCD_S - 0.05 then
                actQ[id] = nil
                local cd, secret, active = cdOf(id, now)
                WC.send(WC.encode({ type = "sradialcd", v = SRV, id = id, cd = cd, act = (not cd and secret and active) or nil, fight = inCombat() or nil }))
            end
        end
        WC.push()
    end
    local function cdSend()
        cdQueued = false
        local now = GetTime()
        for id, at in pairs(cdQ) do
            cdQ[id] = nil
            local cd, secret = cdOf(id, now)
            if not cd and secret then
                local n, learn = nameOf(id), learnDb()
                local d = n and learn and learn["s" .. n]
                if type(d) == "number" and d > 1.5 then
                    local left = d - (now - (type(at) == "number" and at or now))
                    if left > 0 then cd = { d = d, l = math.floor(left * 10 + 0.5) / 10, e = true } end
                else
                    actQ[id] = type(at) == "number" and at or now            -- never learned: the dim's read comes past the GCD
                    C_Timer.After(GCD_S, function() pcall(actSend) end)
                end
            end
            if cd or not actQ[id] then
                WC.send(WC.encode({ type = "sradialcd", v = SRV, id = id, cd = cd, fight = inCombat() or nil }))
            end
        end
        WC.push()
    end
    local function onCast(id)
        if type(id) ~= "number" or not (WC.isPlain == nil or WC.isPlain(id)) then return end
        if not wanted()[id] then return end
        cdQ[id] = GetTime()
        if cdQueued then return end
        cdQueued = true
        C_Timer.After(0.2, function() pcall(cdSend) end)
    end
    WC.spellRadialCdQueued = function() local n = 0; for _ in pairs(cdQ) do n = n + 1 end return n end     -- for the tests

    -- ---------------------------------------------------------------- events
    local f = CreateFrame("Frame")
    for _, e in ipairs({ "PLAYER_ENTERING_WORLD", "PLAYER_REGEN_ENABLED", "PLAYER_REGEN_DISABLED", "SPELLS_CHANGED" }) do
        pcall(function() f:RegisterEvent(e) end)
    end
    pcall(function() f:RegisterUnitEvent("UNIT_SPELLCAST_SUCCEEDED", "player") end)
    f:SetScript("OnEvent", function(_, e, unit, _, spellID)
        if e == "UNIT_SPELLCAST_SUCCEEDED" then
            if unit == "player" then pcall(onCast, spellID) end
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
-- ==================================================================== end of the spell radials =====

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
    local PRV = "pr2"
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
            -- pr2 (the user, streaming 2026-11-10: "I got a lua error when logging out" = ADDON_ACTION_FORBIDDEN ToggleSit() from this
            -- cell): this client forbids ToggleSit from add-on code, and a fresh saved file (a new PC, a new character) tried it once
            -- before falling back. The game's Sit / Stand emotes always; ToggleSit is never called
            local ok, token = sitEmote()
            ack(col, { ok = ok, via = token, restricted = (not ok) or nil })
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
    local GMV = "gm3"
    -- gm3 (the user on 1.0.9-test18: "The options and addons button in the game menu don't actually open the options or the addons
    -- menus", "Same with the support"; the user's pick A): those three have no slash command, only the GAME's own menu buttons (a pool
    -- GameMenuFrame:InitButtons fills when IT opens, Blizzard_GameMenu GameMenuFrame.lua). So the APP clicks the game's own buttons, as a
    -- finger would: every document carries `micro`, the game's Menu micro button's box in game px (top-left, the pad's px) while it
    -- shows; the app taps it (a real click: MainMenuMicroButton's OnClick wants the mouse over it) and the game opens its menu; its
    -- OnShow (a post-hook: nothing of ours runs inside it) sends a "shown" document with `targets` = the boxes of its Options / AddOns /
    -- Support buttons, and the app taps the one it wants. Nothing here opens, closes or clicks a game window; only reads.
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
    -- gm3: a shown frame's box in game px (the pad's: physical px, top-left origin), or nil (hidden, see-through, unreadable)
    local function rectOf(fr)
        if type(fr) ~= "table" then return nil end
        local ok, r = pcall(function()
            if not fr:IsVisible() or fr:GetEffectiveAlpha() < 0.05 then return nil end
            local pw, ph = GetPhysicalScreenSize()
            local uw, uh = UIParent:GetWidth(), UIParent:GetHeight()
            local l, t, w, h = fr:GetLeft(), fr:GetTop(), fr:GetWidth(), fr:GetHeight()
            if not (pw and ph and uw and uh and l and t and w and h) or uw <= 0 or uh <= 0 or w <= 0 or h <= 0 then return nil end
            local es = fr:GetEffectiveScale() / UIParent:GetEffectiveScale()
            local sx, sy = pw / uw, ph / uh
            local function r1(v) return math.floor(v * 10 + 0.5) / 10 end
            return { x = r1(l * es * sx), y = r1((uh - t * es) * sy), w = r1(w * es * sx), h = r1(h * es * sy) }
        end)
        return ok and r or nil
    end
    -- gm3: the game's own menu buttons the app clicks, by their own words (GAMEMENU_OPTIONS / ADDONS / GAMEMENU_SUPPORT)
    local OPEN_KEYS = { options = "GAMEMENU_OPTIONS", addons = "ADDONS", support = "GAMEMENU_SUPPORT" }
    local function targets()
        local out = {}
        local ok, kids = pcall(function() return { GameMenuFrame:GetChildren() } end)
        if not ok or type(kids) ~= "table" then return out end
        for _, b in ipairs(kids) do
            local okT, txt = pcall(function() return b:GetText() end)
            local okE, en = pcall(function() return b:IsEnabled() end)
            if okT and type(txt) == "string" then
                for key, g in pairs(OPEN_KEYS) do
                    if txt == _G[g] and okE and en then out[key] = rectOf(b) end
                end
            end
        end
        return out
    end
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
                    kiosk = kiosk or nil, items = list, cells = COL, binds = bindList(), micro = rectOf(_G.MainMenuMicroButton),
                    fight = (S(function() return InCombatLockdown() end) == true) or nil }
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
        -- r16: the row's cells past the Game Menu's six are Smart buffs' (PadMap m62; the pad is full: 54 of 54)
        if col >= 6 then
            if type(WC.smartyCellInit) == "function" then pcall(WC.smartyCellInit, btn, col)
            else btn:SetAttribute("type", nil) end
            return
        end
        local c = CELLS[col]
        btn:SetAttribute("type", (c and c[2]) and "macro" or nil)
        btn:SetAttribute("macrotext", c and c[2] or nil)
    end
    local function onAny(col)
        if col >= 6 then if type(WC.smartyCell) == "function" then pcall(WC.smartyCell, col) end return end
        pcall(onCell, col)
    end
    -- the Game Menu's cells 0-5 exactly as before; Smart buffs rebuilds the row (out of combat) when its cast cells change
    WC.gameMenuRowBuild = function()
        local ok, row = pcall(pad.secureRow, "gamemenu", 40, cellInit, onAny, { onPress = true })
        return ok and row or nil
    end
    WC.gameMenuRowBuild()
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
    -- gm3: the game's menu opened (by the app's tap on its Menu button, or by the player): where its buttons are, once laid out
    local hookedShow = false
    local function hookShow()
        if hookedShow or type(GameMenuFrame) ~= "table" or type(GameMenuFrame.HookScript) ~= "function" then return end
        hookedShow = pcall(function()
            GameMenuFrame:HookScript("OnShow", function()
                C_Timer.After(0.05, function() pcall(doc, "shown", { targets = targets() }) end)
            end)
        end)
    end
    pcall(hookShow)
    if not hookedShow and type(C_Timer) == "table" then C_Timer.After(2, function() pcall(hookShow) end) end
    WC.gameMenu = { version = GMV, doc = function(why) return doc(why or "manual") end, tick = function() return tick() end, cells = COL }
end
gameMenuRow()
-- ==================================================================== end of the game menu row =====
