-- WowCompanionSpells / Talents.lua
-- The TALENTS room's data and actions: the character's class talent tree as the game's own talent window reads it, and the
-- four ways the room may change it (Add Point, Remove Point, Undo, Apply).
--
-- USER RULINGS (2026-09-30, relayed as the user's answers): layout "A, one school at a time"; the first build "See and learn";
-- Legacy is "Its own room, later" (nothing of it here). Earlier: two rooms (Spellbook, Talents), the Talents door in the Spellbook,
-- B goes Home, and nothing drawn from memory: Forever's trees are its own (9 class trees, 3 schools x 4 columns, one point pool).
--
-- WHAT THE GAME DOES, read in this client's source (blizzard_sharedtalentui, blizzard_playerspells/camelot/classtalents):
--   * the class tree is the active spec group's combat config (C_SpecializationInfo.GetCombatConfigIDForSpecGroup); its first tree
--   * the three schools are the tree's display groups (C_Traits.GetGroupDisplayInfoByTreeID), each with the points spent in it
--   * a node shows when isVisible; Add Point = C_Traits.PurchaseRank, Remove = C_Traits.RefundRank (allowed when canRefundRank and
--     ranksPurchased > 0: a point already applied can be taken back too, and that is staged like any change), Undo =
--     C_Traits.RollbackConfig, Apply = C_Traits.CommitConfig. Every change is STAGED until Apply; closing the window keeps it.
--   * the requirement lines under a talent come from its conditions: the best gate, the lowest unmet level, an unmet condition left
--     out when a sufficient one of its type is met (TalentFrameBaseMixin:AddConditionsToTooltip)
-- RESET = the game's "All" (blizzard_classtalentsframe.lua ResetTree): NOT C_Traits.ResetTree (the game avoids it on purpose, "so that
-- Hero Talents are not reset") but C_Traits.ResetTreeByCurrency once per point pool the tree reports (Forever's class tree: one). It is
-- STAGED like Add Point (AttemptConfigOperation): Apply makes it the character's, Undo brings the points back. Proven on the device: not yet.
--
-- TAINT (the bags lesson of 2026-09-28): this file never opens, closes, shows or hides a game window and runs none of the game's UI
-- code that writes shared state. Its changes are C API calls. Two reads may be the game's Lua: once the game's talent code has loaded
-- it REPLACES C_Traits.GetEntryInfo and C_Traits.GetConditionInfo with wrappers (blizzard_sharedtalentutil.lua) that only read and
-- return (a cost table added, a tooltip text formatted). Whether PurchaseRank / CommitConfig from addon code are accepted is reasoned
-- (no restriction in the API documentation), NOT proven: the first device run watches for refused actions.
--
-- Every value goes through S() / SN() (the T* wrappers), so nothing raw is ever concatenated or formatted.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local TV = "t8"                 -- bump on every change; the app shows it, so a stale build is visible
local MAX_NODES = 160           -- nodes read per tree (a class tree has 50-54; a margin for hidden ones)
local MAX_DESC = 600            -- characters of a rank's description
local MAX_GRID = 16             -- a node further than this many steps from its school's corner is odd: listed, not placed
local PET_TREES = { 1187, 1188, 1189 }  -- the pet trees in this client's data (a read-only probe until the device shows them)

local function real(v) return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW end

-- S() that also counts sentinel hits, so a change in what the game keeps secret shows on the wire (`sec`)
local tally = { secret = 0, threw = 0 }
local function T(fn)
    local v = S(fn)
    if v == WC.SECRET then tally.secret = tally.secret + 1
    elseif v == WC.THREW then tally.threw = tally.threw + 1 end
    return v
end
local function TS(fn) local v = T(fn); if type(v) == "string" and real(v) then return v end end
local function TN(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = T(fn); if type(v) == "boolean" then return v end end
local function TT(fn) local ok, v = CALL(fn); if ok and type(v) == "table" then return v end end
local function yes(b) if b then return true end end
local function clip(s, n) if type(s) == "string" and #s > n then return s:sub(1, n) end return s end

-- a plain list of plain numbers out of an API table (node ids, group ids, condition ids), or {}
local function ids(t)
    local out = {}
    if type(t) ~= "table" then return out end
    for i = 1, MAX_NODES do
        local v = TN(function() return t[i] end)
        if v == nil then break end
        out[#out + 1] = v
    end
    return out
end

local function inCombat() return TB(function() return InCombatLockdown() end) == true end

-- ---------------------------------------------------------------- the config

-- the active spec group's class-talent config and its tree (the camelot frame: SetTab -> GetCombatConfigIDForSpecGroup)
local function current()
    local group = TN(function() return C_SpecializationInfo.GetActiveSpecGroup() end)
    local cfg = group and TN(function() return C_SpecializationInfo.GetCombatConfigIDForSpecGroup(group) end)
    if not cfg then cfg = TN(function() return C_ClassTalents.GetActiveConfigID() end) end
    if not cfg then return nil end
    local info = TT(function() return C_Traits.GetConfigInfo(cfg) end)
    local tree = info and TN(function() return info.treeIDs[1] end)
    return cfg, tree, group
end

-- ---------------------------------------------------------------- one node

local function nameAndIcon(cfg, entryID)
    if not entryID then return nil end
    local entry = TT(function() return C_Traits.GetEntryInfo(cfg, entryID) end)
    local defID = entry and TN(function() return entry.definitionID end)
    local def = defID and TT(function() return C_Traits.GetDefinitionInfo(defID) end)
    if not def then return nil end
    local spell = TN(function() return def.spellID end)
    local name = TS(function() return def.overrideName end)
    if name == "" then name = nil end
    if not name and spell then name = TS(function() return C_Spell.GetSpellName(spell) end) end
    local icon = TN(function() return def.overrideIcon end)
    if (not icon or icon == 0) and spell then icon = TN(function() return C_Spell.GetSpellTexture(spell) end) end
    return name, icon, spell
end

-- t8 (the user 2026-10-09 after t7: "I have no points to spend but green still showing"): the node's canPurchaseRank does NOT count
-- the points. The game's own button (blizzard_talentbuttonbase / blizzard_talentbuttonspend: CanPurchaseRank = nodeInfo.canPurchaseRank
-- and CanAfford(); IsSelectable = not maxed, not locked, CanAfford) checks the cost against the tree's currency itself
-- (TalentFrameBaseMixin:CanAfford: every cost's amount <= that currency's quantity). [qty] = currency id -> quantity left.
local function affordable(cfg, nodeID, qty)
    if not qty then return true end
    local cost = TT(function() return C_Traits.GetNodeCost(cfg, nodeID) end)
    if type(cost) ~= "table" then
        -- no cost list readable: the first pool decides (Forever's class tree has one)
        for _, q in pairs(qty) do if q <= 0 then return false end end
        return true
    end
    for i = 1, 8 do
        local c = TT(function() return cost[i] end)
        if not c then break end
        local id, amount = TN(function() return c.ID end), TN(function() return c.amount end) or 0
        if not id or (qty[id] or 0) < amount then return false end
    end
    return true
end

local function readNode(cfg, nodeID, qty)
    local info = TT(function() return C_Traits.GetNodeInfo(cfg, nodeID) end)
    if not info then return nil end
    if TB(function() return info.isVisible end) ~= true then return nil end
    local n = {
        id = nodeID,
        x = TN(function() return info.posX end), y = TN(function() return info.posY end),
        rk = TN(function() return info.currentRank end) or 0,      -- what the window shows, staged changes included
        a = TN(function() return info.activeRank end),             -- the rank in force (to be confirmed on the device)
        p = TN(function() return info.ranksPurchased end),
        max = TN(function() return info.maxRanks end) or 1,
        cp = yes(TB(function() return info.canPurchaseRank end)),     -- t8: and affordable (below)
        cr = yes(TB(function() return info.canRefundRank end)),
        av = yes(TB(function() return info.isAvailable end)),
        me = yes(TB(function() return info.meetsEdgeRequirements end)),
    }
    if n.cp and not affordable(cfg, nodeID, qty) then n.cp = nil end   -- t8: the game's CanPurchaseRank (CanAfford)
    n.e = TN(function() return info.activeEntry.entryID end) or TN(function() return info.entryIDs[1] end)
    n.ne = TN(function() return info.nextEntry.entryID end)          -- the game's tooltip reads an unlearnt talent's text from it
    n.n, n.i, n.s = nameAndIcon(cfg, n.e)
    n.groups = ids(TT(function() return info.groupIDs end))
    n.conds = ids(TT(function() return info.conditionIDs end))
    -- where its arrows go (the talents it opens), and whether each arrow is lit
    local edges = TT(function() return info.visibleEdges end)
    if edges then
        for i = 1, 8 do
            local ed = TT(function() return edges[i] end)
            if not ed then break end
            local to = TN(function() return ed.targetNode end)
            if to then
                n.to = n.to or {}
                n.to[#n.to + 1] = to
                n.lit = n.lit or {}
                n.lit[#n.lit + 1] = TB(function() return ed.isActive end) == true
                n.edgeTypes = n.edgeTypes or {}
                n.edgeTypes[#n.edgeTypes + 1] = TN(function() return ed.type end)
            end
        end
    end
    -- t6 (the user 2026-10-09: "in the Talents menu, they show Green, even when there is not points available to spend"): the game's own
    -- order (blizzard_sharedtalentui TalentButtonBaseMixin:CalculateVisualState): Maxed, then SELECTABLE (green: not maxed, not locked
    -- AND affordable = canPurchaseRank), then progress (partly learnt: the normal look), else grey. An unlocked tier with no point to
    -- spend is NOT green any more (it was "available and its arrows met", which ignored the points).
    -- t7 (UI Art's kit Talent.GREY, the game's "Disabled"): reachable (available, its arrows met) but nothing to spend and nothing learnt
    -- is the game's grey frame, apart from "locked" (row or arrow not met)
    if n.rk >= n.max then n.st = "full"
    elseif n.cp then n.st = "open"
    elseif n.rk > 0 then n.st = "part"
    elseif n.av and n.me then n.st = "grey"
    else n.st = "locked" end
    return n
end

-- ---------------------------------------------------------------- the grid

-- The positions are the game's own units (this client's data: a school's four columns 600 apart, the schools 2200 apart, the rows
-- 600 apart, one node 10 units off its row). Step = the smallest gap between distinct values that is more than a nudge; a column
-- counts from its school's own left edge, a row from the top of the whole tree (the tiers line up across the schools).
local NUDGE = 100
local function stepOf(values)
    table.sort(values)
    local step
    for i = 2, #values do
        local gap = values[i] - values[i - 1]
        if gap > NUDGE and (not step or gap < step) then step = gap end
    end
    return step
end

local function place(nodes, groupOf)
    local xs, ys, minX, minY = {}, {}, {}, nil
    for _, n in ipairs(nodes) do
        if n.x and n.y then
            xs[#xs + 1] = n.x; ys[#ys + 1] = n.y
            local g = groupOf[n] or 0
            if not minX[g] or n.x < minX[g] then minX[g] = n.x end
            if not minY or n.y < minY then minY = n.y end
        end
    end
    local sx, sy = stepOf(xs), stepOf(ys)
    local odd = {}
    for _, n in ipairs(nodes) do
        if n.x and n.y and sx and sy then
            local c = math.floor((n.x - minX[groupOf[n] or 0]) / sx + 0.5)
            local r = math.floor((n.y - minY) / sy + 0.5)
            if c >= 0 and c < MAX_GRID and r >= 0 and r < MAX_GRID then n.c, n.r = c, r
            else odd[#odd + 1] = n.id end
        end
    end
    return sx, sy, odd
end

-- ---------------------------------------------------------------- the requirement lines (the game's own rules)

local function condText(ci, schoolName)
    local fmt = TS(function() return ci.tooltipFormat end)
    if not fmt then return nil end
    local ok, text
    if TB(function() return ci.isAlwaysMet end) then return fmt end
    local level = TN(function() return ci.playerLevel end)
    local spent = TN(function() return ci.spentAmountRequired end)
    if level then ok, text = pcall(string.format, fmt, level)
    elseif spent then ok, text = pcall(string.format, fmt, spent, schoolName or "")
    else return nil end    -- a quest, an achievement or a spec condition: not expected on a class tree; left out, not guessed
    if ok and type(text) == "string" then return text end
end

local RANKS_ALLOWED = 5         -- Enum.TraitConditionType.RanksAllowed (read from the enum when it exists)
local function ranksAllowed()
    local v = TN(function() return Enum.TraitConditionType.RanksAllowed end)
    return v or RANKS_ALLOWED
end

local function requirements(cfg, node, schoolName)
    local infos = {}
    for _, condID in ipairs(node.conds or {}) do
        -- the game's wrapper (when loaded) takes (configID, condID, ignoreFontColor, traitTreeName); the C function the first two
        local ci = TT(function() return C_Traits.GetConditionInfo(cfg, condID) end)
        if ci then infos[#infos + 1] = { id = condID, ci = ci } end
    end
    local metTypes, bestGate, bestGateSpent, bestLevel, minLevel = {}, nil, nil, nil, nil
    local RA = ranksAllowed()
    for _, it in ipairs(infos) do
        local ci = it.ci
        it.met = TB(function() return ci.isMet end) == true
        it.type = TN(function() return ci.type end)
        it.gate = TB(function() return ci.isGate end) == true
        it.level = TN(function() return ci.playerLevel end)
        it.spent = TN(function() return ci.spentAmountRequired end)
        if TB(function() return ci.isSufficient end) and it.met and it.type then metTypes[it.type] = true end
        if it.gate and it.spent and (not bestGate or it.spent > bestGateSpent) then bestGate, bestGateSpent = it.id, it.spent end
        -- RanksAllowed: several level locks on one node, only the lowest UNMET one is shown (met ones are not shown either)
        it.lock = it.level and it.type == RA
        if it.lock and not it.met and (not bestLevel or it.level < minLevel) then bestLevel, minLevel = it.id, it.level end
    end
    local out = {}
    for _, it in ipairs(infos) do
        local show = true
        if not it.met and it.type and metTypes[it.type] then show = false end
        if it.gate and it.id ~= bestGate then show = false end
        if it.lock and it.id ~= bestLevel then show = false end
        if show then
            local text = condText(it.ci, schoolName)
            if text then out[#out + 1] = { t = text, met = it.met or nil } end
        end
    end
    return out
end

-- ---------------------------------------------------------------- the document

local sel = nil                 -- the selected node's id (the controller or a touch picks it)
local school = 1                -- the school on screen (1-based, the game's order): a touch pick is a cell of THIS school
local ROWS = { talents = false, talentn = false }   -- the pad rows, true once registered (false = the pad had no room)
local open = false              -- the room is open (the heartbeat re-sends only then)
local last = nil                -- the last collected tree, for the detail and the actions

-- THE GAME'S OWN GATE (its talent button, blizzard_micromenu PlayerSpellsMicroButtonMixin:UpdateMicroButton): talents can be used once the
-- player HAS EARNED A TALENT POINT; until then the button is disabled and says FEATURE_BECOMES_AVAILABLE_AT_LEVEL with the unlock level, which
-- ranks in the Legacy Adventure tree LOWER (camelot's GetTalentUnlockLevel override: MIN_TALENT_LEVEL minus the talented node's rank, at least 1).
-- The same reads here, never a call into the game's Lua.
local function unlockLevel()
    local base = TN(function() return Constants.LevelConstsExposed.MIN_TALENT_LEVEL end) or 10
    local lt = TN(function() return Constants.LegacyConsts.LEGACY_TREE_ADVENTURE_ID end)
    local ln = TN(function() return Constants.LegacyConsts.LEGACY_TREE_ADVENTURE_TALENTED_NODE_ID end)
    if not (lt and ln) then return base end
    local lcfg = TN(function() return C_Traits.GetConfigIDByTreeID(lt) end)
    if not lcfg then return base end
    local info = TT(function() return C_Traits.GetNodeInfo(lcfg, ln) end)
    local rank = info and TN(function() return info.activeRank end) or 0
    return math.max(1, base - rank)
end

local function talentGate(level)
    local unlock = unlockLevel()
    local earned = TB(function() return C_SpecializationInfo.HasPlayerEarnedATalentPoint() end)
    if earned == nil then earned = (level or 0) >= unlock end     -- the call missing: the level rule
    return earned, unlock
end

function WC.collectTalents()
    local doc = { kind = "class", lvl = TN(function() return UnitLevel("player") end), combat = yes(inCombat()) }
    -- `use` false = the game's talent button is disabled: the room greys its page (the user 2026-09-30: "they should just be Greyed out until
    -- its actually available in the game"), with the game's own line (`lockw`) and the unlock level (`unlock`)
    local use, unlock = talentGate(doc.lvl)
    doc.use, doc.unlock = use and true or false, unlock
    if not use then doc.lockw = TS(function() return string.format(FEATURE_BECOMES_AVAILABLE_AT_LEVEL, unlock) end) end
    -- the class token (the school pictures are talentbg_<class>_<school>); UnitClass returns name, token, id
    local okC, _, token = CALL(function() return UnitClass("player") end)
    if okC and WC.isPlain(token) and type(token) == "string" then doc.cls = token end
    tally.secret, tally.threw = 0, 0
    local cfg, tree, group = current()
    doc.cfg, doc.tree, doc.g = cfg, tree, group
    doc.ng = TN(function() return GetNumSpecGroups() end)
    local byID, groupOf, list = {}, {}, {}
    if cfg and tree then
        local okE, canEdit, why = CALL(function() return C_Traits.CanEditConfig(cfg) end)
        if okE and WC.isPlain(canEdit) and type(canEdit) == "boolean" then
            doc.edit = canEdit
            if not canEdit and WC.isPlain(why) and type(why) == "string" and why ~= "" then doc.why = why end
        end
        doc.staged = yes(TB(function() return C_Traits.ConfigHasStagedChanges(cfg) end))
        -- the pool: what is left to spend (staged changes counted), the most there can be, what is spent
        local cur = TT(function() return C_Traits.GetTreeCurrencyInfo(cfg, tree, false) end)
        local c1 = cur and TT(function() return cur[1] end)
        -- every pool the tree reports (Reset empties each one, as the game's "All" does)
        local pools, qty = {}, nil
        for i = 1, 4 do
            local ci = cur and TT(function() return cur[i] end)
            local id = ci and TN(function() return ci.traitCurrencyID end)
            if not id then break end
            pools[#pools + 1] = id
            qty = qty or {}
            qty[id] = TN(function() return ci.quantity end) or 0           -- t8: what is left of each (staged changes counted)
        end
        doc.pools = pools
        if c1 then
            doc.pool = { have = TN(function() return c1.quantity end), max = TN(function() return c1.maxQuantity end),
                         spent = TN(function() return c1.spent end) }
        end
        -- the schools, in the game's order, with the points spent in each
        doc.groups = {}
        local disp = TT(function() return C_Traits.GetGroupDisplayInfoByTreeID(tree) end)
        local gIndex, gids = {}, {}
        for i = 1, 8 do
            local d = disp and TT(function() return disp[i] end)
            if not d then break end
            local gid = TN(function() return d.groupID end)
            if gid then
                doc.groups[#doc.groups + 1] = { id = gid, n = TS(function() return d.displayName end),
                                                i = TN(function() return d.icon end) }
                gIndex[gid] = #doc.groups
                gids[#gids + 1] = gid
            end
        end
        local gcur = #gids > 0 and TT(function() return C_Traits.GetGroupCurrencyInfo(cfg, gids) end)
        for i = 1, #gids do
            local gc = gcur and TT(function() return gcur[i] end)
            local gid = gc and TN(function() return gc.traitNodeGroupID end)
            local spent = gc and TN(function() return gc.currencyInfos[1].spent end)
            if gid and gIndex[gid] then doc.groups[gIndex[gid]].spent = spent end
        end
        -- what is staged, node by node (the game's own list)
        local okS, bought, refunded = CALL(function() return C_Traits.GetStagedChanges(cfg) end)
        local plus, minus = {}, {}
        if okS then
            for _, id in ipairs(ids(bought)) do plus[id] = true end
            for _, id in ipairs(ids(refunded)) do minus[id] = true end
        end
        -- the nodes
        for _, nodeID in ipairs(ids(TT(function() return C_Traits.GetTreeNodes(tree) end))) do
            local n = readNode(cfg, nodeID, qty)
            if n then
                for _, gid in ipairs(n.groups) do if gIndex[gid] then n.g = gIndex[gid] break end end
                groupOf[n] = n.g
                if plus[nodeID] then n.pend = "+" elseif minus[nodeID] then n.pend = "-" end
                list[#list + 1] = n
                byID[nodeID] = n
            end
        end
        local sx, sy, odd = place(list, groupOf)
        doc.step = (sx or sy) and { x = sx, y = sy } or nil
        doc.odd = #odd > 0 and odd or nil
        doc.nodes = {}
        for _, n in ipairs(list) do
            -- the raw position travels only for a node that could not be placed (`odd`): step + column + row are the check
            local unplaced = n.c == nil or nil
            doc.nodes[#doc.nodes + 1] = { id = n.id, g = n.g, c = n.c, r = n.r, x = unplaced and n.x, y = unplaced and n.y,
                rk = n.rk, a = n.a, p = n.p, max = n.max, st = n.st, cp = n.cp, cr = n.cr, n = n.n, i = n.i, to = n.to,
                lit = n.lit, pend = n.pend }
        end
    end
    -- hunter pets: the pet's tree name, and which of the pet trees have a config (a read-only probe for the first device run)
    local petTree = TS(function() return C_PetInfo.GetPetTalentTree() end)
    local petCfg = {}
    for _, t in ipairs(PET_TREES) do
        local c = TN(function() return C_Traits.GetConfigIDByTreeID(t) end)
        if c then petCfg[#petCfg + 1] = { tree = t, cfg = c } end
    end
    if petTree or #petCfg > 0 then doc.pet = { tree = petTree, cfgs = #petCfg > 0 and petCfg or nil } end
    if type(WC.pad) == "table" and type(WC.pad.describe) == "function" then
        local okd, d = pcall(WC.pad.describe)
        if okd and type(d) == "table" then doc.pad = d end
    end
    doc.rows = { talents = ROWS.talents, talentn = ROWS.talentn }
    if tally.secret > 0 or tally.threw > 0 then doc.sec = { s = tally.secret, t = tally.threw } end
    last = { cfg = cfg, tree = tree, byID = byID, list = list, groups = doc.groups, staged = doc.staged, pools = doc.pools or {},
             spent = doc.pool and doc.pool.spent or 0, use = doc.use }
    doc.pools = nil
    if sel and not byID[sel] then sel = nil end
    return doc
end

-- the selected talent's detail: this rank's text and the next one's, and what it still needs
-- nothing selected travels as { none = true }: an empty table would be encoded as a JSON list, and the app would never see the clear
local function collectDetail()
    if not (last and last.cfg and sel) then return { none = true } end
    local n = last.byID[sel]
    if not n then return { none = true } end
    local d = { id = n.id, n = n.n, i = n.i, rk = n.rk, max = n.max, cp = n.cp, cr = n.cr, pend = n.pend, st = n.st }
    -- A LOCKED TALENT SAYS WHAT IT DOES TOO (b-locked-talents; the game's talent tooltip shows a locked one's text, its unmet
    -- requirements in red under it): the entry's own text; else the node's NEXT entry (what the game's tooltip reads for a talent
    -- with no rank yet, TalentDisplayMixin:AddTooltipDescription); else the talent's spell's own description
    local function said(s) return type(s) == "string" and s ~= "" and s or nil end
    local function textAt(rank)
        local t = n.e and said(TS(function() return C_Traits.GetTraitDescription(n.e, rank) end))
        if not t and n.ne then t = said(TS(function() return C_Traits.GetTraitDescription(n.ne, rank) end)) end
        if not t and n.s then t = said(TS(function() return C_Spell.GetSpellDescription(n.s) end)) end
        return clip(t, MAX_DESC)
    end
    if n.e or n.ne or n.s then
        d.d = textAt(math.max(1, n.rk))
        if n.rk > 0 and n.rk < n.max then d.dn = textAt(n.rk + 1) end
    end
    local school = n.g and last.groups[n.g] and last.groups[n.g].n
    local req = requirements(last.cfg, n, school)
    d.req = #req > 0 and req or nil
    -- the talents whose arrows lead here, and whether each is met (the window's arrows; the words are the room's)
    for _, other in ipairs(last.list) do
        for k, to in ipairs(other.to or {}) do
            if to == n.id then
                d.from = d.from or {}
                d.from[#d.from + 1] = { id = other.id, n = other.n, met = other.lit and other.lit[k] or nil }
            end
        end
    end
    return d
end

-- ---------------------------------------------------------------- emission

local lastKey, lastSelKey = nil, nil
local function emitTree(force)
    local doc = WC.collectTalents()
    local key = WC.encode(doc)
    if key == lastKey and not force then return false end
    lastKey = key
    WC.send(WC.encode({ type = "talents", v = TV, t = doc }))
    return true
end
local function emitDetail(force)
    local d = collectDetail()
    local key = WC.encode(d)
    if key == lastSelKey and not force then return false end
    lastSelKey = key
    -- `sch` = the school the addon counts touch picks in: the app holds Add / Remove until this echoes its pick (pad owner's note)
    WC.send(WC.encode({ type = "talentsel", v = TV, sch = school, sel = d }))
    return true
end

-- trait events come in bursts (one purchase changes several nodes), so coalesce
local queued = false
local function soon()
    if queued then return end
    queued = true
    C_Timer.After(0.3, function()
        queued = false
        local a = emitTree(false)
        local b = emitDetail(false)
        if a or b then WC.push() end
    end)
end

local function ack(op, ok, why, id)
    WC.send(WC.encode({ type = "talentsack", v = TV, op = op, ok = ok and true or false, why = why, id = id }))
    WC.push()
end

-- ---------------------------------------------------------------- the actions (called by the room's controller half and its pad row)

-- The checks every change makes first. The answer names why a change was refused.
local function canChange()
    if inCombat() then
        if type(WC.spellsRefusedCombat) == "function" then WC.spellsRefusedCombat() end    -- capture v8: Home's red pulse
        return nil, "combat"
    end
    if last and last.use == false then return nil, "not yet" end      -- the game's talent button is still disabled
    local cfg = current()
    if not cfg then return nil, "no config" end
    local okE, canEdit = CALL(function() return C_Traits.CanEditConfig(cfg) end)
    if okE and WC.isPlain(canEdit) and canEdit == false then return nil, "locked" end
    return cfg
end

WC.talents = {
    version = 1,
    -- the room is open / closed: while open, the heartbeat re-sends the tree (a new app start sees it at once)
    setOpen = function(on) open = on and true or false; if open then emitTree(true); emitDetail(true); WC.push() end end,
    isOpen = function() return open end,
    selected = function() return sel end,
    -- read-only views for the room's controller half (TalentsCtl.lua)
    nodes = function() if not last then WC.collectTalents() end return last and last.list or {} end,
    school = function() return school end,
    schools = function() return last and #last.groups or 0 end,
    staged = function() return last and last.staged == true or false end,
    spent = function() return last and last.spent or 0 end,
    busy = function() return WC.talents.pending ~= nil end,
    -- the game's talent button is disabled (no talent point earned yet): the page is greyed, nothing on it answers
    locked = function() if not last then WC.collectTalents() end; return last ~= nil and last.use == false end,
    -- select a talent (no game action): its detail is sent
    select = function(nodeID)
        if type(nodeID) ~= "number" then return false, "bad id" end
        if not (last and last.byID[nodeID]) then WC.collectTalents() end
        if not (last and last.byID[nodeID]) then return false, "unknown" end
        sel = nodeID
        local g = last.byID[nodeID].g
        if g then school = g end
        if emitDetail(false) then WC.push() end
        return true
    end,
    -- the school on screen (1-based): touch picks count in it
    showSchool = function(index)
        if type(index) ~= "number" or index < 1 or index > 3 then return false, "bad school" end
        school = index
        emitDetail(true)
        WC.push()
        return true
    end,
    -- a touch pick: the talent at a cell (column + 4 x row) of the school on screen. No game action.
    pick = function(cell)
        if type(cell) ~= "number" or cell < 0 or cell > 35 then ack("pick", false, "bad cell") return false end
        if not last then WC.collectTalents() end
        local c, r = cell % 4, math.floor(cell / 4)
        for _, n in ipairs(last and last.list or {}) do
            if n.g == school and n.c == c and n.r == r then return WC.talents.select(n.id) end
        end
        ack("pick", false, "empty")
        return false
    end,
    -- ONE point onto the selected talent, staged (the game's Add Point)
    add = function()
        local cfg, why = canChange()
        if not cfg then ack("add", false, why, sel) return false, why end
        if not sel then ack("add", false, "none", nil) return false, "none" end
        local n = last and last.byID[sel]
        local okC, can = CALL(function() return C_Traits.CanPurchaseRank(cfg, sel, n and n.e) end)
        if okC and WC.isPlain(can) and can == false then ack("add", false, "cannot", sel) return false, "cannot" end
        local ok, done = CALL(function() return C_Traits.PurchaseRank(cfg, sel) end)
        local good = ok and done == true
        ack("add", good, not good and "refused" or nil, sel)
        soon()
        return good
    end,
    -- ONE point off the selected talent, staged (the game's Remove Point: any bought point, the game says when it may)
    remove = function()
        local cfg, why = canChange()
        if not cfg then ack("remove", false, why, sel) return false, why end
        if not sel then ack("remove", false, "none", nil) return false, "none" end
        local n = last and last.byID[sel]
        if not (n and n.cr and (n.p or 0) > 0) then ack("remove", false, "cannot", sel) return false, "cannot" end
        local ok, done = CALL(function() return C_Traits.RefundRank(cfg, sel) end)
        local good = ok and done == true
        ack("remove", good, not good and "refused" or nil, sel)
        soon()
        return good
    end,
    -- throw every staged change away (the game's Undo)
    undo = function()
        local cfg, why = canChange()
        if not cfg then ack("undo", false, why) return false, why end
        if TB(function() return C_Traits.ConfigHasStagedChanges(cfg) end) ~= true then ack("undo", false, "nothing") return false, "nothing" end
        local ok, done = CALL(function() return C_Traits.RollbackConfig(cfg) end)
        local good = ok and done == true
        ack("undo", good, not good and "refused" or nil)
        soon()
        return good
    end,
    -- take every point out, staged (the game's Reset "All": ResetTreeByCurrency per pool); Apply makes it the character's
    reset = function()
        local cfg, why = canChange()
        if not cfg then ack("reset", false, why) return false, why end
        if not last or last.cfg ~= cfg then WC.collectTalents() end
        if not last or not last.tree or #last.pools == 0 then ack("reset", false, "no tree") return false, "no tree" end
        if (last.spent or 0) <= 0 then ack("reset", false, "nothing") return false, "nothing" end
        local good = true
        for _, currency in ipairs(last.pools) do
            local ok, done = CALL(function() return C_Traits.ResetTreeByCurrency(cfg, last.tree, currency) end)
            if not (ok and done == true) then good = false end
        end
        ack("reset", good, not good and "refused" or nil)
        soon()
        return good
    end,
    -- make the staged changes the character's (the game's Apply). The room asks first, naming the changes; this is called after the yes.
    -- The request is answered at once ("apply"), the game's verdict later ("applied": TRAIT_CONFIG_UPDATED or CONFIG_COMMIT_FAILED).
    apply = function()
        local cfg, why = canChange()
        if not cfg then ack("apply", false, why) return false, why end
        if TB(function() return C_Traits.ConfigHasStagedChanges(cfg) end) ~= true then ack("apply", false, "nothing") return false, "nothing" end
        local ok, done = CALL(function() return C_Traits.CommitConfig(cfg) end)
        local good = ok and done == true
        ack("apply", good, not good and "refused" or nil)
        if good then WC.talents.pending = cfg end
        soon()
        return good
    end,
}

-- ---------------------------------------------------------------- the pad rows (touch)
-- Pad owner's YES (quests, AGENT_LOG 9f7202e): two rows of FIXED columns.
--   `talents`  (namespace "talent"):     0 open, 1 close, 2 Add Point, 3 Remove Point, 4 Undo, 5 Apply (sent only after the room's own
--                                         confirmation), 6-8 show school 1 / 2 / 3, 9 Reset (staged, like the game's),
--                                         10 close the controller's box (a touch on its Cancel; no game action)
--   `talentn`  (namespace "talentcell"): 0-35 pick the talent at cell (column + 4 x row) of the school on screen: a no-action pick tap
-- The namespaces are never keyed (no slotFor): the handler reads the column. A row the pad had no room for is reported in `rows`.
local OPS = {
    -- open / close go through the Spells room's pages (Book.lua): opening takes the controller by touch as the Spellbook's own column 0 does
    [0] = function()
        if type(WC.spellsRoom) == "table" then WC.spellsRoom.open("talents") else WC.talents.setOpen(true) end
    end,
    [1] = function()
        if type(WC.spellsRoom) == "table" and WC.spellsRoom.page() == "talents" then WC.spellsRoom.close()
        else WC.talents.setOpen(false) end
    end,
    [2] = function() WC.talents.add() end,
    [3] = function() WC.talents.remove() end,
    [4] = function() WC.talents.undo() end,
    [5] = function() WC.talents.apply() end,
    [6] = function() WC.talents.showSchool(1) end,
    [7] = function() WC.talents.showSchool(2) end,
    [8] = function() WC.talents.showSchool(3) end,
    [9] = function() WC.talents.reset() end,       -- added with UI Art's SPEC (the foot's Reset); the pad owner told
    -- a touch on Cancel in a box the controller opened (no game action)
    [10] = function() if type(WC.talentsCtl) == "table" then WC.talentsCtl.closeBox() end end,
}
if type(WC.pad) == "table" and type(WC.pad.op) == "function" then
    local okA, rowA = pcall(WC.pad.op, "talents", "talent", function(_, col)
        local fn = type(col) == "number" and OPS[col]
        if fn then fn() return end
        -- columns 11-39: the Spellbook's Controller tab (the pad owner's YES 2026-09-30: one owner sharing its own row)
        if type(col) == "number" and col >= 11 and col <= 39 and type(WC.padTabTouch) == "function" then
            local ok, done = pcall(WC.padTabTouch, col - 11)
            if ok and done then return end
        end
        ack("pad", false, "unknown column")
    end)
    local okB, rowB = pcall(WC.pad.op, "talentn", "talentcell", function(_, col)
        -- columns 36 / 37: the Spellbook's Controller tab learns the app's button layout (plain / Nintendo), a no-action pick; my own row
        if (col == 36 or col == 37) and type(WC.padTabCtl) == "table" and type(WC.padTabCtl.setLayout) == "function" then
            pcall(WC.padTabCtl.setLayout, col == 37)
            return
        end
        -- column 38: the Controller tab's next command code counts from command 12 (a no-action pick before ONE action, the same touch)
        if col == 38 and type(WC.padTabCtl) == "table" and type(WC.padTabCtl.commandsFrom12) == "function" then
            pcall(WC.padTabCtl.commandsFrom12)
            return
        end
        -- column 39: the Controller tab's next spell / clear is for the COMBAT layer (a no-action pick before ONE action, the same touch)
        if col == 39 and type(WC.padTabCtl) == "table" and type(WC.padTabCtl.combatLayer) == "function" then
            pcall(WC.padTabCtl.combatLayer)
            return
        end
        WC.talents.pick(col)
    end)
    ROWS.talents = okA and type(rowA) == "number" or false
    ROWS.talentn = okB and type(rowB) == "number" or false
end

-- ---------------------------------------------------------------- events

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, a1)
    if event == "CONFIG_COMMIT_FAILED" then
        if WC.talents.pending then WC.talents.pending = nil; ack("applied", false, "failed") end
    elseif event == "TRAIT_CONFIG_UPDATED" then
        local cfg = WC.isPlain(a1) and type(a1) == "number" and a1 or nil
        if WC.talents.pending and (cfg == nil or cfg == WC.talents.pending) then WC.talents.pending = nil; ack("applied", true) end
    elseif event == "UNIT_PET" and a1 ~= "player" then
        return
    end
    soon()
end)

local WANT = {
    "PLAYER_ENTERING_WORLD", "PLAYER_LEVEL_UP", "SPELLS_CHANGED",
    "TRAIT_CONFIG_UPDATED", "TRAIT_CONFIG_LIST_UPDATED", "TRAIT_NODE_CHANGED", "TRAIT_NODE_CHANGED_PARTIAL",
    "TRAIT_TREE_CURRENCY_INFO_UPDATED", "CONFIG_COMMIT_FAILED", "TRAIT_COND_INFO_CHANGED",
    "ACTIVE_PLAYER_SPECIALIZATION_CHANGED", "PLAYER_TALENT_UPDATE", "UNIT_PET",
    "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", talents = TV, missingEvents = missing })) end)
end

-- RIDE THE CORE HEARTBEAT only while the room is open: the tree is a few kilobytes and the log's buffer is shared
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] and (open or reason == "login" or reason == "manual") then emitTree(true); emitDetail(true) end
    end)
end
