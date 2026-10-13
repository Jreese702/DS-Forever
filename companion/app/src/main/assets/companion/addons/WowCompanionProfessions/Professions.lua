-- WowCompanionProfessions / Professions.lua
-- The PROFESSIONS room: your professions, the open profession's recipes, how many of each can be
-- made now, and the actions the user picked on 2026-09-28: OPEN a profession, CREATE (a count is
-- chosen first) / CREATE ALL, and TRACK a recipe. Casting the book's spells and Unlearn were NOT
-- picked and are not here.
--
-- THE GAME'S WINDOW in this client is the modern ProfessionsFrame with camelot overrides (AGENT_LOG,
-- "Professions: SURVEY done"). NOTHING HERE TOUCHES THAT FRAME: every read and every action goes
-- through C_TradeSkillUI, so no Blizzard frame is ever tainted by this file.
--
-- A SEPARATE ADDON FOLDER, like Quests: it never touches the shared .toc or Core.lua, and it uses the
-- core addon's safety kernel, sink and tap pad (## Dependencies guarantees the load order).
--
-- WHAT IT SENDS (each document only when it changed; encode() is the dedupe key):
--   {"type":"prof"}     always: the professions with their skill bars, which one is open in the
--                       game, the recipe the addon has selected, crafting progress, the pad keys.
--                       Small. Rides the core heartbeat like Quests.
--   {"type":"profrec"}  only while a profession is OPEN in the game: its learned recipes with
--                       reagents, requirements, cooldowns and how many can be made now. The game
--                       hands recipes to add-ons only while the profession is open (its own list code
--                       reads "the open profession"), so the APP keeps the last one per profession.
--   {"type":"profack"}  the answer to every pad tap.
--   {"type":"profmade"} one line per result the game reports crafted.
--
-- THE PAD (two rows on the shared tap pad, TapPad.lua; the pad owner is asked for them in AGENT_LOG):
--   "prof"    CONTROL keys, one column each, fixed for the session and published as `ctl`:
--             make:1 .. make:26, all, track, untrack, and the controller's book / page / free.
--   "profopen" a SECURE row (user, 2026-09-28 23:12: "The game's own way, clicked securely"): one cell per profession with
--             a crafting page, each casting that profession's own spell, the way the game's book opens it. Published per
--             profession as `oslot`. The game opens its window in its own code; this file calls no open function.
--   "profsel" SELECTS a recipe of the open profession. No game action. Its 40 columns are mapped by
--             this file and published per recipe as `slot`. A stale tap can only select a different
--             recipe; the ack names it, and the app enables Create only once `sel` in the prof doc is
--             the recipe the player picked. The confirm the player reads is the addon's own
--             understanding of the target, as with Quests' abandon.
-- ONE TAP = AT MOST ONE GAME ACTION: select = none; make / all = one CraftRecipe; track / untrack =
-- one SetRecipeTracked; an open cell = one cast of the profession's spell, by the secure template (not by this file).
-- NOTHING HERE OPENS OR CLOSES A GAME WINDOW BY A FUNCTION CALL (user's answer 23:12, after Bags' taint flood): open = the
-- secure cell above; close = the game's own close button, clicked by the capture (version 2 `spec.back`) when B is pressed
-- on the room's book.
-- In combat nothing is refused here: the user's lock allows in-combat use where the game allows the
-- action, so the game decides and its red error text reaches the app through the chat route.
--
-- Every value goes through S()/SN() or the V* checks below, so nothing raw is ever concatenated.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, SN, CALL = WC.S, WC.SN, WC.CALL

local PV = "p22"          -- bump on every change; the app shows it, so a stale build is visible
local MAKE_MAX = 26      -- make:1 .. make:26 on the control row; more than that is Create All
local SEL_COLS = 40      -- columns of the "profsel" row (TapPad's COLS)
local BASIC = 1          -- Enum.CraftingReagentType.Basic (profession constants documentation)

-- ---------------------------------------------------------------- safe reads

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that also counts sentinel hits, so a shift in the secrecy map shows on the wire (`sec`).
local tally = { secret = 0, threw = 0 }
local function T(fn)
    local v = S(fn)
    if v == WC.SECRET then tally.secret = tally.secret + 1
    elseif v == WC.THREW then tally.threw = tally.threw + 1 end
    return v
end
local function TS(fn) local v = T(fn); if type(v) == "string" and real(v) and v ~= "" then return v end end
local function TN(fn) local v = T(fn); if type(v) == "number" then return v end end
local function TB(fn) local v = T(fn); if type(v) == "boolean" then return v end end

-- The same for a value already in hand (a table field or a multi-return): plain, or nil.
local function V(v)
    if type(v) == "nil" then return nil end
    if not WC.isPlain(v) then tally.secret = tally.secret + 1 return nil end
    return v
end
local function VN(v) v = V(v); if type(v) == "number" then return v end end
local function VS(v) v = V(v); if type(v) == "string" and v ~= "" then return v end end
local function VB(v) v = V(v); if type(v) == "boolean" then return v end end

-- THE CHARACTER (b-recipes-per-char): name-realm, the key every per-character store uses (Bags' BB.charKey). The app keeps each
-- character's books under it, so an alt never sees another character's recipes
local function who()
    local n, r = TS(function() return UnitName("player") end), TS(function() return GetRealmName() end)
    return n and (n .. "-" .. (r or "?")) or nil
end

-- The game's text markup (colours, links, textures) out of prose; |n is a line break.
local function clean(s)
    if type(s) ~= "string" then return nil end
    local ok, t = pcall(function()
        local x = s:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|cn[%w_]+:", ""):gsub("|r", "")
        x = x:gsub("|H.-|h(.-)|h", "%1"):gsub("|T.-|t", ""):gsub("|A.-|a", ""):gsub("|n", "\n")
        return x
    end)
    if ok and type(t) == "string" and t ~= "" then return t end
end

-- ---------------------------------------------------------------- state

local padFull = nil     -- the pad rows that did not register (the pad was full), set at load
-- the controller (see "the controller" below): held = we are the capture's guest now; want = the room asked for it
local ctlr = { held = false, want = false, n = 0, why = nil, mode = "book", backOn = false, stopOn = false, waiting = false,
               awaitWin = false }
local retake            -- defined with the controller half; the event handler calls it after a fight
local padWindow         -- the controller half's answer to the game's window opening / closing (defined there)
local fightRefused      -- capture v8: the room refused a protected action because of the fight (defined there)
local diagNote          -- p8's look-only diagnostics (end of file): a note when the room takes the controller
local stopNote          -- the same: a note when B becomes the craft's cancel, or the close again

local st = {
    open = false,       -- between TRADE_SKILL_SHOW and TRADE_SKILL_CLOSE
    win = false,        -- the game's professions window is shown (its book or a page), read on a timer
    openID = nil,       -- the open profession's skill line
    sel = nil,          -- the recipe the addon has selected (profsel)
    busy = nil,         -- { id = recipe, n = count asked } while the game crafts
    cast = nil,         -- { s, e } the player's current cast (ms, the game's clock) while crafting: the room's bar fills with it
    castEnd = nil,      -- GetTime() when the player's last cast ended (between two items of one Create there is no cast)
    made = 0,           -- results reported since the last make
    known = {},         -- recipeID -> true for the open profession's learned recipes
    selMap = {},        -- profsel column -> recipeID
    itemsWaiting = false,
}

-- ---------------------------------------------------------------- the pad keys

local CTL_STATIC = {}
for n = 1, MAKE_MAX do CTL_STATIC[#CTL_STATIC + 1] = "make:" .. n end
for _, k in ipairs({ "all", "track", "untrack", "book", "page", "free", "enter" }) do CTL_STATIC[#CTL_STATIC + 1] = k end

-- the secure "profopen" row: a stable column per profession for the session, and the spell each column casts
local openCols, openSpell, openLine, openDirty = {}, {}, {}, false
-- column 0 of that row is the CLOSE cell (user ruling 2026-09-30: leaving the room by touch closes the profession): a secure
-- "click" on the game's own close button, set once the game has loaded its window. The professions take columns 1 and up.
local CLOSE_COL = 0
openLine[CLOSE_COL] = "close"
local builtClose = false
-- column 1 is the BOOK cell (2026-09-30, the fix for the game's TabIndicators error that popped its Lua error window on every
-- room open): a secure CLICK on the game's own Professions button (ProfessionMicroButton → ToggleProfessionsBook). Opened that
-- way the game's window shows its BOOK first, so its controller tab list gets filled (FocusGamepad) before any recipe page is
-- chosen. The app sends it only while the window is not shown (the game's button toggles). The professions take columns 2 and up.
local BOOK_COL = 1
openLine[BOOK_COL] = "book"
local builtBook = false
local MICRO_BUTTON = "ProfessionMicroButton"
-- the game's professions window, READ only (is it shown?): never shown, hidden, hooked or changed from here
local WIN = "ProfessionsFrame"
local function gameWin()
    local f = _G[WIN]
    if type(f) == "table" then return f end
end
local function winShown()
    local f = gameWin()
    return f ~= nil and (TB(function() return f:IsShown() end) or false)
end
local closeSlot                -- below, with the document
local castNow                  -- below, with the document
-- what each cell REALLY carries (set when TapPad runs our init, out of combat): the doc gives a profession its `oslot` only when
-- its cell casts its spell, so a tap can never open another profession while a rebuild waits for the end of a fight (pad owner)
local builtSpell = {}

-- The "profsel" row's columns are mapped here, not by TapPad's slot namespace: key c = column c,
-- claimed once at load so the namespace never frees or quarantines anything.
local function claimSelColumns()
    if not WC.pad then return end
    for c = 0, SEL_COLS - 1 do WC.pad.slotFor("profsel", c) end
end

local function ctlSlot(key)
    return WC.pad and WC.pad.slotFor("profctl", key) or nil
end

-- ---------------------------------------------------------------- professions (the Overview)

local cardButtons              -- below: the card's buttons, as the game's book draws them
local function spellIDAt(offset)
    local ok, info = CALL(function()
        return C_SpellBook.GetSpellBookItemInfo(offset + 1, Enum.SpellBookSpellBank.Player)
    end)
    if ok and type(info) == "table" then return SN(function() return info.spellID end) end
end

-- THE CARD BUTTONS (user ruling 2026-09-30, "Copy the game"): the game's book draws each profession's spells as square buttons
-- on its card (camelot ProfessionsBookFrameMixin:FormatProfession: 1..numSpells, at most 2). A button whose spell has a recipe
-- window opens it (ProfessionSpellButtonMixin:OnClick casts it); the others do their own thing in the game (Fishing, Find
-- Minerals) and stay inert in the room (the user's "no spell casting" ruling). `o` marks the one the secure open cell casts.
local BTN_MAX = 2
cardButtons = function(off, n, openSpellID)
    local out = {}
    for k = 1, math.min(n, BTN_MAX) do
        local sp = spellIDAt(off + k - 1)
        local b = { n = TS(function() return C_SpellBook.GetSpellBookItemName(off + k, Enum.SpellBookSpellBank.Player) end),
                    icon = TN(function() return C_SpellBook.GetSpellBookItemTexture(off + k, Enum.SpellBookSpellBank.Player) end) }
        b.rec = (sp and TB(function() return C_TradeSkillUI.CanTradeSkillShowCraftingUI(sp) end)) or false
        b.o = (openSpellID ~= nil and sp == openSpellID) or nil
        out[#out + 1] = b
    end
    return out
end

-- The game's order (GetProfessions): 1-2 the main professions, then the secondary ones. The
-- camelot book reads them as prof1, prof2, firstAid, fishing, cooking; its tabs take up to 7.
function WC.collectProfessions()
    local list = {}
    local ok, p1, p2, p3, p4, p5, p6, p7 = CALL(GetProfessions)
    if not ok then return list end
    local idx = { p1, p2, p3, p4, p5, p6, p7 }
    for pos = 1, 7 do
        local i = VN(idx[pos])
        if i then
            local okI, name, icon, rank, maxRank, numSpells, spellOffset, skillLine, rankModifier =
                CALL(GetProfessionInfo, i)
            local id = okI and VN(skillLine) or nil
            if id then
                local e = {
                    pos = pos, main = pos <= 2, id = id,
                    name = VS(name), icon = VN(icon) or VS(icon),
                    rank = VN(rank), max = VN(maxRank), mod = VN(rankModifier),
                }
                if e.mod == 0 then e.mod = nil end
                local off = VN(spellOffset)
                local spell = off and spellIDAt(off)
                -- the game's own test for "this profession has a crafting page" (its tab code)
                e.craft = (spell and TB(function()
                    return C_TradeSkillUI.CanTradeSkillShowCraftingUI(spell) end)) or false
                if e.craft and spell then
                    local col = openCols[id]
                    if not col then
                        col = 0
                        while openLine[col] ~= nil do col = col + 1 end
                        openCols[id] = col
                    end
                    if openSpell[col] ~= spell or openLine[col] ~= id then openSpell[col], openLine[col], openDirty = spell, id, true end
                    e.oslot, e.ospell = col, spell
                end
                e.btn = off and cardButtons(off, VN(numSpells) or 0, e.craft and spell or nil) or nil
                list[#list + 1] = e
            end
        end
    end
    -- an unlearned profession's cell casts nothing any more (its column is not given to another for the session)
    local listed = {}
    for _, e in ipairs(list) do if e.oslot then listed[e.oslot] = true end end
    for col in pairs(openSpell) do if not listed[col] and openSpell[col] ~= false then openSpell[col], openDirty = false, true end end
    if openDirty and WC.professionsOpenRow then WC.professionsOpenRow() end
    for _, e in ipairs(list) do
        if e.oslot ~= nil and builtSpell[e.oslot] ~= e.ospell then e.oslot = nil end
    end
    return list
end

-- ---------------------------------------------------------------- the open profession

local function foreign()
    -- a linked profession (from chat), a guild view or an NPC crafting window is not the player's
    -- own list: the app must not store it as "last seen", and no action applies to it
    return TB(function() return C_TradeSkillUI.IsTradeSkillLinked() end)
        or TB(function() return C_TradeSkillUI.IsTradeSkillGuild() end)
        or TB(function() return C_TradeSkillUI.IsNPCCrafting() end)
        or false
end

local function baseInfo()
    local ok, info = CALL(function() return C_TradeSkillUI.GetBaseProfessionInfo() end)
    if ok and type(info) == "table" then return info end
end

-- The line the game's own list and rank bar use (Professions.GetProfessionInfo): the CHILD
-- profession, or the base one when there is no child (professionID 0, some NPC windows).
local function listInfo(base)
    local ok, info = CALL(function() return C_TradeSkillUI.GetChildProfessionInfo() end)
    local cid = ok and type(info) == "table" and SN(function() return info.professionID end) or nil
    if cid and cid ~= 0 then return info, cid end
    return base, base and SN(function() return base.professionID end) or nil
end

local function itemBits(itemID)
    local name = TS(function() return C_Item.GetItemNameByID(itemID) end)
    if not name then
        -- not cached yet: ask for it; GET_ITEM_INFO_RECEIVED brings a new document
        st.itemsWaiting = true
        CALL(function() C_Item.RequestLoadItemDataByID(itemID) end)
    end
    return name,
        TN(function() return C_Item.GetItemIconByID(itemID) end),
        TN(function() return C_Item.GetItemQualityByID(itemID) end)
end

local function schematic(rid, r)
    local ok, sc = CALL(C_TradeSkillUI.GetRecipeSchematic, rid, false)
    if not ok or type(sc) ~= "table" then return end
    local out = SN(function() return sc.outputItemID end)
    if out then
        local name, icon, q = itemBits(out)
        r.out = { item = out, name = name, icon = icon, q = q,
                  min = SN(function() return sc.quantityMin end),
                  max = SN(function() return sc.quantityMax end) }
    end
    local reag = {}
    local okS, slots = CALL(function() return sc.reagentSlotSchematics end)
    if okS and type(slots) == "table" then
        for _, slot in ipairs(slots) do
            local kind = SN(function() return slot.reagentType end)
            local required = S(function() return slot.required end)
            -- basic reagents only: camelot has no optional / finishing reagents in its window
            if kind == BASIC and required ~= false then
                local need = SN(function() return slot.quantityRequired end)
                local item = SN(function() return slot.reagents[1].itemID end)
                local cur = SN(function() return slot.reagents[1].currencyID end)
                if item then
                    local name, icon, q = itemBits(item)
                    reag[#reag + 1] = { item = item, need = need, name = name, icon = icon, q = q }
                elseif cur then
                    reag[#reag + 1] = { cur = cur, need = need }
                end
            end
        end
    end
    r.reag = reag
end

local function requirements(rid)
    local out = {}
    local ok, list = CALL(C_TradeSkillUI.GetRecipeRequirements, rid)
    if ok and type(list) == "table" then
        for _, q in ipairs(list) do
            local name = TS(function() return q.name end)
            if name then out[#out + 1] = { name = name, met = TB(function() return q.met end) or false } end
        end
    end
    if #out > 0 then return out end
end

-- Seconds left, only while the recipe cannot be crafted because of it (the game's own test:
-- a cooldown with no charge left).
local function cooldown(rid)
    local ok, cd, _, charges = CALL(C_TradeSkillUI.GetRecipeCooldown, rid)
    if not ok then return nil end
    cd, charges = VN(cd), VN(charges)
    if cd and cd > 0 and not (charges and charges > 0) then return math.floor(cd + 0.5) end
end

local function categories(recipes)
    local cats, seen = {}, {}
    local function add(cid)
        if not cid or seen[cid] then return end
        seen[cid] = true
        local ok, info = CALL(C_TradeSkillUI.GetCategoryInfo, cid)
        if not ok or type(info) ~= "table" then return end
        local parent = SN(function() return info.parentCategoryID end)
        cats[#cats + 1] = { id = cid, name = TS(function() return info.name end), parent = parent,
                            ord = SN(function() return info.uiOrder end) }
        add(parent)
    end
    for _, r in ipairs(recipes) do add(r.cat) end
    return cats
end

-- Which recipes get a "profsel" column: every one while they fit. When there are more than 40,
-- the ones Create and Track need come first (can be made now, tracked, selected); a recipe keeps its
-- column while it is present, and one without a column is shown by the app as not selectable.
local function mapSelColumns(recipes)
    local present, rank = {}, {}
    for i, r in ipairs(recipes) do
        present[r.id] = true
        local p = 3
        if r.id == st.sel then p = 0 elseif (r.n or 0) > 0 then p = 1 elseif r.track then p = 2 end
        rank[r.id] = p * 10000 + i
    end
    local byRecipe = {}
    for c = 0, SEL_COLS - 1 do
        local rid = st.selMap[c]
        if rid and not present[rid] then st.selMap[c] = nil; rid = nil end
        if rid then byRecipe[rid] = c end
    end
    local order = {}
    for _, r in ipairs(recipes) do order[#order + 1] = r.id end
    table.sort(order, function(a, b) return rank[a] < rank[b] end)
    for _, rid in ipairs(order) do
        if not byRecipe[rid] then
            local free
            for c = 0, SEL_COLS - 1 do if not st.selMap[c] then free = c break end end
            if not free then
                -- full: take the column of the lowest-ranked holder, if it ranks below this one
                local worst, worstRank
                for c = 0, SEL_COLS - 1 do
                    local holder = st.selMap[c]
                    if not worstRank or rank[holder] > worstRank then worst, worstRank = c, rank[holder] end
                end
                if worst and worstRank > rank[rid] and math.floor(worstRank / 10000) > math.floor(rank[rid] / 10000) then
                    byRecipe[st.selMap[worst]] = nil
                    free = worst
                end
            end
            if free == nil then break end
            st.selMap[free] = rid
            byRecipe[rid] = free
        end
    end
    for _, r in ipairs(recipes) do r.slot = byRecipe[r.id] end
end

-- THE CRAFTED ITEM'S OWN TOOLTIP (the user, 2026-09-30: "for half the Items in professions, I have no idea what I am crafting
-- because theirs no description on them either- which honestly could just be above the reagents"): the game's tooltip lines for
-- what a recipe makes, the same the game's crafting page shows on its output icon (GameTooltip:SetRecipeResultItem), READ through
-- C_TooltipInfo (else the output item's own, by id). Read once per recipe and kept for the session, a few per tick while a
-- profession is open (a whole book at once would be a hitch). Same line format as Bags / Interact: t / c = left text and its
-- colour "rrggbb", r / rc = right text and colour. A secret or unreadable line is left out, never guessed; an item the client
-- has not loaded yet ("Retrieving item information") is asked again when the game loads it, at most TIP_TRIES times.
local TIP_MAX, TIP_PER_TICK, TIP_TRIES = 16, 6, 5
local tipCache = {}                 -- recipe id -> lines, or false (the game has no tooltip for it)
local tipQueue, tipQueued, tipTries = {}, {}, {}
local function hexOf(col)
    if type(col) ~= "table" then return nil end
    local r = TN(function() return col.r end); local g = TN(function() return col.g end); local b = TN(function() return col.b end)
    if not (r and g and b) then return nil end
    local function h(v) return math.max(0, math.min(255, math.floor(v * 255 + 0.5))) end
    return string.format("%02x%02x%02x", h(r), h(g), h(b))
end
local function tipText(v)
    local t = TS(function() return v end)
    if t and t:byte(1) and t:byte(1) > 3 then return t end
    return nil
end
-- lines, false (none), or nil (not loaded yet: ask again later)
local function linesOf(fn, wait)
    local ok, data = CALL(fn)
    if not ok or type(data) ~= "table" then return false end
    local n = SN(function() return #data.lines end) or 0
    local out, pending = {}, false
    for i = 1, math.min(n, TIP_MAX) do
        local okL, ln = CALL(function() return data.lines[i] end)
        if okL and type(ln) == "table" then
            local t = tipText(ln.leftText)
            if t then
                if wait and t == wait then pending = true end
                local row = { t = t, c = hexOf(ln.leftColor) }
                local rt = tipText(ln.rightText)
                if rt then row.r = rt; row.rc = hexOf(ln.rightColor) end
                out[#out + 1] = row
            end
        end
    end
    if pending then return nil end
    return #out > 0 and out or false
end
local function readTip(q)
    local api = C_TooltipInfo
    if type(api) ~= "table" then return false end
    local wait = TS(function() return RETRIEVING_ITEM_INFO end)
    local lines = false
    if type(api.GetRecipeResultItem) == "function" then
        lines = linesOf(function() return api.GetRecipeResultItem(q.id) end, wait)
    end
    if lines == false and q.item and type(api.GetItemByID) == "function" then
        lines = linesOf(function() return api.GetItemByID(q.item) end, wait)
    end
    return lines
end
local function queueTip(rid, item)
    if tipQueued[rid] or (tipTries[rid] or 0) >= TIP_TRIES then return end
    tipQueued[rid] = true
    tipQueue[#tipQueue + 1] = { id = rid, item = item }
end
-- The open profession's recipe book. nil while nothing is open or the data is not ready.
function WC.collectRecipes()
    if not st.open then return nil end
    if TB(function() return C_TradeSkillUI.IsTradeSkillReady() end) == false then return nil end
    local base = baseInfo()
    local id = base and SN(function() return base.professionID end)
    if not id then return nil end
    local info, lineID = listInfo(base)
    tally = { secret = 0, threw = 0 }
    st.itemsWaiting, st.tipsWaiting = false, false

    local recipes = {}
    local ok, ids = CALL(function() return C_TradeSkillUI.GetAllRecipeIDs() end)
    if ok and type(ids) == "table" then
        for _, raw in ipairs(ids) do
            local rid = VN(raw)
            local okR, ri = false, nil
            if rid then okR, ri = CALL(C_TradeSkillUI.GetRecipeInfo, rid) end
            if okR and type(ri) == "table"
               and TB(function() return ri.learned end) == true
               -- a higher rank of a recipe the list already has (the game shows the first rank)
               and not SN(function() return ri.previousRecipeID end)
               and TB(function() return C_TradeSkillUI.IsRecipeInSkillLine(rid, lineID) end) ~= false then
                local r = {
                    id = rid,
                    name = TS(function() return ri.name end),
                    cat = SN(function() return ri.categoryID end),
                    icon = SN(function() return ri.icon end),
                    diff = SN(function() return ri.relativeDifficulty end),
                    ups = SN(function() return ri.numSkillUps end),
                    -- the game sorts a category's recipes by these (its SortCategoryData): the
                    -- level at which it turns grey (higher first), the item level, then the name
                    triv = SN(function() return ri.maxTrivialLevel end),
                    ilvl = SN(function() return ri.itemLevel end),
                    multi = TB(function() return ri.canCreateMultiple end),
                    ench = TB(function() return ri.isEnchantingRecipe end) or nil,
                    dis = TB(function() return ri.disabled end) or nil,
                    why = TS(function() return ri.disabledReason end),
                    n = SN(function() return C_TradeSkillUI.GetCraftableCount(rid) end),
                    track = TB(function() return C_TradeSkillUI.IsRecipeTracked(rid, false) end) or nil,
                    req = requirements(rid),
                    cd = cooldown(rid),
                    desc = clean(TS(function() return C_TradeSkillUI.GetRecipeDescription(rid, {}, nil) end)),
                }
                if r.ups and r.ups <= 1 then r.ups = nil end
                schematic(rid, r)
                local tip = tipCache[rid]
                if tip then r.tip = tip elseif tip == nil then queueTip(rid, r.out and r.out.item) end
                recipes[#recipes + 1] = r
            end
        end
    end

    st.known = {}
    for _, r in ipairs(recipes) do st.known[r.id] = r end
    if st.sel and not st.known[st.sel] then st.sel = nil end
    mapSelColumns(recipes)

    local mod = SN(function() return info.skillModifier end)
    return {
        v = PV,
        who = who(),                -- whose book this is: the app keeps it per character
        id = id,                    -- the base skill line: the Overview's `id`, what open:<id> opens
        line = (lineID ~= id) and lineID or nil,
        name = TS(function() return base.professionName end),
        rank = SN(function() return info.skillLevel end),
        max = SN(function() return info.maxSkillLevel end),
        mod = (mod and mod ~= 0) and mod or nil,
        foreign = foreign() or nil,
        n = #recipes,
        cats = categories(recipes),
        r = recipes,
        waiting = st.itemsWaiting or nil,
        sec = { secret = tally.secret, threw = tally.threw },
    }
end

-- THE GAME'S OWN WORDS for the room (UI Art's SPEC: the title, the search hint, the buttons, the filter
-- lines, the book's empty places), read from the client's global strings so they are in the player's
-- language. Names read from Blizzard's professions source (camelot); a missing one is simply left out.
local WORDS = {
    title = "TRADE_SKILLS", search = "SEARCH", create = "CREATE_PROFESSION", createAll = "PROFESSIONS_CREATE_ALL",
    createAllFmt = "PROFESSIONS_CREATE_ALL_FORMAT", track = "PROFESSIONS_TRACK_RECIPE", reagents = "REAGENTS", cancel = "CANCEL",
    requires = "PROFESSIONS_REQUIRED_TOOLS", cooldown = "COOLDOWN_REMAINING", skillUp = "TRADESKILL_FILTER_HAS_SKILL_UP",
    makeable = "CRAFT_IS_MAKEABLE", slots = "TRADESKILL_FILTER_SLOTS", first = "PROFESSIONS_FIRST_PROFESSION",
    second = "PROFESSIONS_SECOND_PROFESSION", missing = "PROFESSIONS_MISSING_PROFESSION", combat = "ERR_NOT_IN_COMBAT",
    -- the book's EMPTY small places, as the game fills them (camelot book templates: missingHeader / missingText)
    cookName = "PROFESSIONS_COOKING", cookMissing = "PROFESSIONS_COOKING_MISSING",
    fishName = "PROFESSIONS_FISHING", fishMissing = "PROFESSIONS_FISHING_MISSING",
    faidName = "PROFESSIONS_FIRST_AID", faidMissing = "PROFESSIONS_FIRST_AID_MISSING",
}
local function words()
    local out = {}
    -- plain text: the game's colour markup out ("|cnNORMAL_FONT_COLOR:Requires:|r %s" drew raw in a room: Home, 2026-09-30)
    for k, g in pairs(WORDS) do out[k] = clean(TS(function() return _G[g] end)) end
    return out
end

-- the close cell's column, only while it really clicks the game's button and the pad publishes the row
closeSlot = function()
    local d = builtClose and WC.pad and WC.pad.describe() or nil
    if d and d.rows and d.rows.profopen then return CLOSE_COL end
end
-- the book cell's column, the same way
local function bookSlot()
    local d = builtBook and WC.pad and WC.pad.describe() or nil
    if d and d.rows and d.rows.profopen then return BOOK_COL end
end

-- The cast the game is running for the craft (the user, 2026-09-30: "the actual crafting timer did not fill at all"): its
-- length and how far in it is, in ms, when this document is written. The app starts its bar at (now - el) and fills it over d.
castNow = function()
    local c = st.cast
    if not c then return nil end
    local now = SN(function() return GetTime() end)
    if not now then return nil end
    local el = math.floor(now * 1000 - c.s)
    if el < 0 then el = 0 end
    return { d = c.e - c.s, el = el }
end

-- The small, always-sent document.
function WC.collectProf()
    tally = { secret = 0, threw = 0 }
    local list = WC.collectProfessions()
    local ctl = {}
    for _, k in ipairs(CTL_STATIC) do ctl[k] = ctlSlot(k) end
    -- CLASS-ONLY LINES (user ruling 2026-09-28 22:15): nothing here is per class. The list is what the game
    -- returns for THIS character, so a rogue's Poisons appears for a rogue only. A line the game opens in
    -- this window but does not list (a hunter's Beast Training, if Forever opens it here) is still named,
    -- so the room can tell instead of showing a book it cannot place.
    local openName, openListed
    if st.open and st.openID then
        local b = baseInfo()
        openName = b and TS(function() return b.professionName end) or nil
        openListed = false
        for _, e in ipairs(list) do if e.id == st.openID then openListed = true end end
    end
    local doc = {
        who = who(),
        v = PV,
        list = list,
        open = st.open and st.openID or nil,
        openName = openName,
        openListed = openListed,
        foreign = (st.open and foreign()) or nil,
        sel = st.sel,
        selName = st.sel and st.known[st.sel] and st.known[st.sel].name or nil,
        busy = st.busy and { id = st.busy.id, n = st.busy.n, made = st.made, cast = castNow(),
                             left = SN(function() return C_TradeSkillUI.GetRemainingRecasts() end) } or nil,
        cb = TB(function() return UnitAffectingCombat("player") end),
        ctl = ctl,
        pad = WC.pad and WC.pad.describe() or nil,
        padFull = padFull,
        held = ctlr.held or nil,
        tabs = (ctlr.held and ctlr.tabs) or nil,
        cslot = closeSlot(),
        bslot = bookSlot(),
        win = st.win or nil,
        mode = ctlr.want and ctlr.mode or nil,
        back = (ctlr.held and ctlr.backOn) or nil,
        bstop = (ctlr.held and ctlr.stopOn) or nil,        -- B cancels the craft now (the room shows B Cancel)
        heldWhy = (not ctlr.held) and ctlr.want and ctlr.why or nil,
        str = words(),
        sec = { secret = tally.secret, threw = tally.threw },
    }
    return doc
end

-- ---------------------------------------------------------------- emission

local lastProf, lastRec = nil, nil

-- Collect both documents and send what changed. Returns true when something was written.
-- Does NOT push: callers decide, so a heartbeat ride-along costs no flush of its own.
local function emit(force)
    local wrote = false
    local rec = WC.collectRecipes()
    if rec then
        local key = WC.encode(rec)
        if key ~= lastRec or force then
            lastRec = key
            WC.send(WC.encode({ type = "profrec", p = rec }))
            wrote = true
        end
    end
    local prof = WC.collectProf()
    local key = WC.encode(prof)
    if key ~= lastProf or force then
        lastProf = key
        WC.send(WC.encode({ type = "prof", p = prof }))
        wrote = true
    end
    return wrote
end
WC.emitProfessions = emit

-- Trade skill events come in bursts (a bag update per reagent used), so coalesce.
local queued = false
local function soon()
    if queued then return end
    queued = true
    C_Timer.After(0.25, function()
        queued = false
        if emit(false) then WC.push() end
    end)
end

-- the crafted items' tooltips (above): a few per tick (the 0.1 s window ticker); when the queue empties with something new,
-- the book is sent again
local function tipTick()
    if #tipQueue == 0 then return end
    if not st.open then tipQueue, tipQueued = {}, {} return end
    local did = false
    for _ = 1, TIP_PER_TICK do
        local q = table.remove(tipQueue, 1)
        if not q then break end
        tipQueued[q.id] = nil
        local lines = readTip(q)
        if lines == nil then
            tipTries[q.id] = (tipTries[q.id] or 0) + 1      -- not loaded yet: GET_ITEM_INFO_RECEIVED asks again
            st.tipsWaiting = true
        else
            tipCache[q.id] = lines
            did = true
        end
    end
    if did and #tipQueue == 0 then soon() end
end

local function refreshOpen()
    local info = baseInfo()
    st.openID = info and SN(function() return info.professionID end) or nil
end

local BLOCKED_CAP = 5
local blockedCount = 0
local ERROR_WINDOW = 5          -- seconds after a room action in which the game's error text is the room's
local lastActionAt = -1000

local function closed()
    st.open, st.openID, st.busy = false, nil, nil
    st.sel, st.known, st.selMap = nil, {}, {}
    lastRec = nil                   -- the next open always sends its list, even if unchanged
end

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, ...)
    if event == "TRADE_SKILL_SHOW" then
        st.open = true
        refreshOpen()
        padWindow(true)
        if not builtClose and WC.professionsOpenRow then WC.professionsOpenRow() end   -- TapPad queues it in combat
    elseif event == "TRADE_SKILL_CLOSE" then
        closed()
        padWindow(false)
    elseif event == "TRADE_SKILL_DATA_SOURCE_CHANGED" then
        local before = st.openID
        refreshOpen()
        if st.openID ~= before then
            -- another profession: its recipes are new keys, and a stale select tap only selects
            st.selMap, st.sel, st.known, lastRec = {}, nil, {}, nil
        end
    elseif event == "UPDATE_TRADESKILL_CAST_STOPPED" then
        st.busy, st.cast = nil, nil
    elseif event == "UNIT_SPELLCAST_START" then
        -- the craft's cast began (one per item): its start and end, the game's own numbers (UnitCastingInfo, ms)
        if not st.busy or V((...)) ~= "player" then return end
        local ok, _, _, _, startMS, endMS = CALL(UnitCastingInfo, "player")
        startMS, endMS = VN(startMS), VN(endMS)
        st.cast = (ok and startMS and endMS and endMS > startMS) and { s = startMS, e = endMS } or nil
    elseif event == "UNIT_SPELLCAST_STOP" or event == "UNIT_SPELLCAST_SUCCEEDED" then
        if V((...)) ~= "player" then return end
        st.cast, st.castEnd = nil, GetTime()
    elseif event == "UNIT_SPELLCAST_INTERRUPTED" or event == "UNIT_SPELLCAST_FAILED" then
        -- these fire for every unit in sight: only the player's own, and only while crafting
        if not st.busy or V((...)) ~= "player" then return end
        st.busy, st.cast = nil, nil
    elseif event == "TRADE_SKILL_ITEM_CRAFTED_RESULT" then
        local data = ...
        if type(data) == "table" then
            st.made = st.made + 1
            local item = SN(function() return data.itemID end)
            WC.send(WC.encode({ type = "profmade", v = PV, item = item,
                                qty = SN(function() return data.quantity end),
                                name = item and TS(function() return C_Item.GetItemNameByID(item) end) or nil }))
            if st.busy and st.made >= st.busy.n then st.busy = nil end
        end
    elseif event == "TRADE_SKILL_CRAFT_BEGIN" then
        if st.busy then st.busy.started = true end
    elseif event == "ADDON_ACTION_BLOCKED" or event == "ADDON_ACTION_FORBIDDEN" then
        -- A protected call from this file is refused silently (no Lua error), so this is the only
        -- proof. CAPPED: on 2026-09-28 an uncapped reporter turned a taint flood into freezes.
        local who, fn = V((...)), V((select(2, ...)))
        if who ~= ADDON then return end
        blockedCount = blockedCount + 1
        if blockedCount <= BLOCKED_CAP then
            WC.send(WC.encode({ type = "profack", op = "blocked", ok = false, v = PV,
                                why = type(fn) == "string" and fn:sub(1, 80) or nil,
                                n = blockedCount, last = blockedCount == BLOCKED_CAP or nil }))
            WC.push()
        end
        return
    elseif event == "UI_ERROR_MESSAGE" then
        -- The game's red error text, for the room's foot (SPEC 4.3: "refused by the game", 3 s). Only in
        -- the few seconds after one of the room's own actions: the rest is the Chat room's.
        if GetTime() - lastActionAt > ERROR_WINDOW then return end
        local _, msg = ...
        msg = V(msg)
        if type(msg) == "string" and msg ~= "" then
            WC.send(WC.encode({ type = "profack", op = "error", ok = false, v = PV, why = msg:sub(1, 120) }))
            WC.push()
            -- refused because of the fight (the game's own words for it): the capture's red flash (v8)
            if fightRefused and (msg == TS(function() return ERR_NOT_IN_COMBAT end)
                                 or msg == TS(function() return SPELL_FAILED_AFFECTING_COMBAT end)) then
                fightRefused()
            end
        end
        return
    elseif event == "PLAYER_REGEN_ENABLED" then
        retake()
    elseif event == "PLAYER_INTERACTION_MANAGER_FRAME_HIDE" then
        -- an NPC window took the controller from us (lost "npc"); when it closes, ask again, a moment later so the
        -- capture has let it go first
        C_Timer.After(0.3, function() retake() end)
        return
    elseif event == "GET_ITEM_INFO_RECEIVED" then
        -- fires for every item the client loads: only when a recipe of ours is waiting for a name
        if not (st.open and (st.itemsWaiting or st.tipsWaiting)) then return end
    elseif event == "BAG_UPDATE_DELAYED" then
        if not st.open then return end          -- only the open list's counts depend on the bags
    end
    soon()
end)
local WANT = {
    "PLAYER_ENTERING_WORLD", "SKILL_LINES_CHANGED", "NEW_RECIPE_LEARNED",
    "TRADE_SKILL_SHOW", "TRADE_SKILL_CLOSE", "TRADE_SKILL_LIST_UPDATE",
    "TRADE_SKILL_DATA_SOURCE_CHANGED", "TRACKED_RECIPE_UPDATE",
    "UPDATE_TRADESKILL_CAST_STOPPED", "TRADE_SKILL_ITEM_CRAFTED_RESULT", "TRADE_SKILL_CRAFT_BEGIN",
    "ADDON_ACTION_BLOCKED", "ADDON_ACTION_FORBIDDEN", "UI_ERROR_MESSAGE", "PLAYER_INTERACTION_MANAGER_FRAME_HIDE",
    "UNIT_SPELLCAST_INTERRUPTED", "UNIT_SPELLCAST_FAILED",
    "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_STOP", "UNIT_SPELLCAST_SUCCEEDED",
    "BAG_UPDATE_DELAYED", "GET_ITEM_INFO_RECEIVED",
    "PLAYER_REGEN_DISABLED", "PLAYER_REGEN_ENABLED",
}
local missing = {}
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", professions = PV, missingEvents = missing })) end)
end

-- RIDE THE CORE HEARTBEAT, as Quests does: re-sending both documents every 15 s costs no flush of
-- its own, and it is how the app gets them back after it restarts.
local RIDE = { heartbeat = true, login = true, zone = true, manual = true }
if type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if RIDE[reason] then emit(true) end
    end)
end

-- ---------------------------------------------------------------- the controller (a guest of the shared capture)
-- While the room is on the bottom screen the controller belongs to it (UI Art's SPEC 5): the app taps `hold` when the room
-- shows and `free` when it is left. We are a GUEST of interact's WowCompanion.capture (version 1): it takes the eight
-- buttons from the game, gives them to us (the D-pad on the press, A / B / X / Y on the release) and puts the game's own
-- professions window to alpha 0 by NAME (spec.hide). This file never touches that frame itself. The app owns the glow (the
-- list's order, the search and the filter are its own), so every press is relayed as {"type":"profpad"} and the app sends
-- at most one tap for it. The capture is never held in combat; after a fight or an NPC window it is asked for again, only
-- while the room still wants it.
local GUEST = "professions"

local function capture()
    local c = WC.capture
    if type(c) == "table" and (c.version or 0) >= 1 and type(c.take) == "function" and type(c.release) == "function" then return c end
end

local CLOSE_BUTTON = "ProfessionsFrameCloseButton"   -- the game's own close button (PortraitFrameTemplate's $parentCloseButton)

-- USER (2026-09-30 16:4x, via tracking): "while crafting pressing B should cancel the craft, but now it goes home and then cancles
-- if I push b again". The game's cancel is its own SECURE slash command /stopcasting (Blizzard's slashcommands.lua: SpellStopCasting).
-- While a craft runs, B is a click (the capture's `back` binding) on THIS secure button, and the template runs that command in the
-- game's own code, on the player's own press: nothing in this file calls the game, and no chat is typed. The game's own B on its
-- window clicks the close button instead (SmartNavigation:AttemptClose), which does NOT cancel: measured 16:38, the craft ran on
-- until a second B reached the game's base Back. A secure button registered for both fires once per press (TapPad secureCell).
local STOP_BUTTON = "WowCompanionProfessionsStop"
local STOP_MACRO = "/stopcasting"
local stopBtn
local function buildStop()
    if stopBtn then return true end
    if InCombatLockdown() then return false end            -- a secure button's attributes are set out of combat only
    local ok, b = pcall(CreateFrame, "Button", STOP_BUTTON, UIParent, "SecureActionButtonTemplate")
    if not ok or type(b) ~= "table" then return false end
    b:RegisterForClicks("AnyDown", "AnyUp")                 -- no size, no place: only B's binding can click it
    b:SetAttribute("type", "macro")
    b:SetAttribute("macrotext", STOP_MACRO)
    stopBtn = b
    return true
end
buildStop()

-- B cancels while the craft runs: during its cast, and for a moment between two items of one Create (the game has no cast then).
-- Past that moment with no cast (a craft that ended without the game's word), B is the close again.
local STOP_GAP = 1.5
local function crafting()
    if not st.busy then return false end
    if st.cast then return true end
    return GetTime() - math.max(st.castEnd or 0, st.busy.at or 0) < STOP_GAP
end
local function wantStop() return crafting() and (stopBtn ~= nil or buildStop()) end

local CAP_SPEC = {
    hide = { "ProfessionsFrame" },
    press = function(k)
        ctlr.n = ctlr.n + 1
        WC.send(WC.encode({ type = "profpad", v = PV, k = V(k), n = ctlr.n }))
        WC.push()
    end,
    lost = function(why)
        ctlr.held, ctlr.waiting, ctlr.why = false, false, V(why)
        WC.send(WC.encode({ type = "profpad", v = PV, lost = ctlr.why, n = ctlr.n }))
        WC.push()
        soon()                  -- the prof doc's `held` follows
    end,
}

-- USER RULING 2026-09-30 ("Copy the game"): while a profession is open in the game, B is the GAME'S OWN close button on BOTH
-- pages, clicked by the capture (version 2 `spec.back`), so the game closes its window in its own code and the room goes Home.
-- With nothing open B stays ours (the room just goes Home). The button exists only once the game has loaded its window: before
-- that take() answers "bad spec", and we take without it.
local function wantBack(c)
    return (st.open or st.win) and (c.version or 1) >= 2
end

-- LB / RB change the tabs, as the game's own tab indicators do (capture version 4 `spec.extra`). Never a button the player's
-- setup uses as its emulated Shift / Ctrl / Alt (taking it would break that modifier: Interact's note).
local SHOULDERS = { "PADLSHOULDER", "PADRSHOULDER" }
local function extraKeys(c)
    if (c.version or 1) < 4 then return nil end
    local used = {}
    for _, cv in ipairs({ "GamePadEmulateShift", "GamePadEmulateCtrl", "GamePadEmulateAlt" }) do
        local v = TS(function() return C_CVar.GetCVar(cv) end)
        if v then used[v] = true end
    end
    local out = {}
    for _, k in ipairs(SHOULDERS) do if not used[k] then out[#out + 1] = k end end
    return out
end

-- p20 (Home as integrator, the user's pick 2026-10-06 "Home makes it as integrator"): B is the game's close button ONLY while that
-- button is itself visible. In controller mode the game HIDES ProfessionsFrameCloseButton; capture v17 (i95+) then still bound B to
-- it (its window shows), and that B, which the game's gamepad navigation ALSO hears, closed the window with the navigation half
-- reset: the next open threw the game's own SmartNavigation.lua:611 'activeInfo' error (b94 23:42:02.903). With the button hidden, B
-- stays the room's (as on b92): the app's B leaves the room and its close cell closes the window by a tap the navigation never hears
-- p21 (b95 23:54:15, the same error on p20): ANY click-close (that binding, the room's close cell, a second toggle of the game's
-- button) while the game's gamepad navigation has its focus inside the window leaves it half set, and the next open throws. The
-- one clean close is the game's OWN gamepad B (its navigation's back). So with the close button hidden, B goes to the GAME untouched
-- (capture `pass`, contract v2): the game closes its window in its own code, padWindow sees it hide and lets go with "B" (Home)
-- READ only: the one call on the game's button is IsVisible (no taint path). true / false = shown / HIDDEN by the game; nil = the game
-- has not made it yet (its window never loaded)
local function closeVis() return TB(function() return _G[CLOSE_BUTTON]:IsVisible() end) end
local function closeVisible() return closeVis() == true end

local function takePad()
    local c = capture()
    if not c then ctlr.held, ctlr.why = false, "no controller capture" return false, ctlr.why end
    local spec = { hide = CAP_SPEC.hide, press = CAP_SPEC.press, lost = CAP_SPEC.lost, extra = extraKeys(c) }
    local back = wantBack(c)
    if back then spec.back = wantStop() and STOP_BUTTON or (closeVisible() and CLOSE_BUTTON or nil) end
    -- p21: no back button to name (the game hid it): B is the GAME's own (its navigation closes the window); `back` stays on, so
    -- the window's hide lets the controller go with "B"
    if back and spec.back == nil then
        if closeVis() == false then spec.pass = { "PAD2" } else back = false end   -- not loaded yet: B the room's, as before
    end
    -- CAPTURE CONTRACT v9 (Interact i78, 2026-10-01; the user's ruling: every room opens the game's own window behind it, as this
    -- one does): `leave` = what the generic leave (touch / the bar) and the fight's leave click: ALWAYS the window's close button,
    -- never the craft's cancel (a leave closes the window; the craft runs on). `window` = this take is for the game's window: it
    -- takes the controller over from a room that kept it (that room hears lost "taken") instead of "busy"; never in a fight.
    if (c.version or 1) >= 9 then
        spec.window = true
        if builtClose then spec.leave = CLOSE_BUTTON end              -- the button exists once the game has loaded its window
    end
    local ok, why = c.take(GUEST, spec)
    if not ok and spec.leave and V(why) == "bad spec" then              -- a capture that refuses it: the old leave (the close cell)
        spec.leave = nil
        ok, why = c.take(GUEST, spec)
    end
    if not ok and spec.back == STOP_BUTTON and V(why) == "bad spec" then  -- a capture that refuses the cancel: B closes, as before
        spec.back = CLOSE_BUTTON
        ok, why = c.take(GUEST, spec)
    end
    if not ok and spec.pass and V(why) == "bad spec" then                -- p21: a capture without `pass`: B the room's (p20)
        spec.pass, back = nil, false
        ok, why = c.take(GUEST, spec)
    end
    if not ok and back and V(why) == "bad spec" then
        spec.back, back = nil, false
        ok, why = c.take(GUEST, spec)
    end
    if not ok and spec.extra and V(why) == "bad spec" then          -- a capture that refuses the shoulders: take without them
        spec.extra = nil
        ok, why = c.take(GUEST, spec)
    end
    -- CAPTURE v11 (Interact i82): a take that finds another holder is QUEUED and answers true like a granted one. Out of a fight the
    -- room does not hold until the grant (the capture then takes for it): it WAITS, says "queued", and the app opens the game's
    -- window only once `held` comes (else the window shows un-hidden on the top screen). A fight's pending take counts as holding.
    local waiting = false
    if ok and (c.version or 1) >= 11 and not InCombatLockdown() then
        local okS, state = CALL(c.state)
        if okS and type(state) == "table" and state.holder ~= GUEST then waiting = true end
    end
    ctlr.held = ok and not waiting or false
    ctlr.waiting = waiting
    ctlr.backOn = ok and back or false
    ctlr.stopOn = ok and back and spec.back == STOP_BUTTON or false
    ctlr.tabs = ok and spec.extra ~= nil and #spec.extra > 0 or false
    ctlr.why = waiting and "queued" or (not ok) and V(why) or nil
    return ctlr.held, ctlr.why
end

local function freePad(why)
    local c = capture()
    if c and (ctlr.held or ctlr.waiting) then CALL(c.release, GUEST, why) end    -- a queued take is given up the same way (v11)
    ctlr.held, ctlr.waiting, ctlr.backOn, ctlr.stopOn = false, false, false, false
end

-- capture version 3: say what B does next, so Home knows (it shows B on its button for "home"). Copying the game (2026-09-30): B
-- leaves the room on both pages; while a craft runs it cancels the craft instead, and the room stays.
local function sayNext()
    local c = capture()
    if ctlr.held and c and (c.version or 1) >= 3 and type(c.bnext) == "function" then
        CALL(c.bnext, GUEST, ctlr.stopOn and "back" or "home")
    end
end

-- CAPTURE CONTRACT v8 (Interact, 2026-10-01; the user's ruling: a room KEEPS the controller through a fight and flashes red):
-- where the room refuses a protected action because of the fight, the capture is told; Home pulses the red flash. Safe outside
-- a fight (it answers false, "no fight"); an older capture has no `refused`, and nothing happens.
fightRefused = function()
    local c = capture()
    if c and type(c.refused) == "function" then CALL(c.refused, GUEST) end
end

-- v11: the queued take was granted (the capture took for this room when the holder let go): the room holds from now on
local function waitTick()
    if not ctlr.waiting then return end
    if not ctlr.want then ctlr.waiting = false return end
    local c = capture(); if not c then return end
    local okS, state = CALL(c.state)
    if okS and type(state) == "table" and state.holder == GUEST then
        ctlr.waiting, ctlr.held, ctlr.why = false, true, nil
        sayNext()
        soon()
    end
end

-- A craft began or ended while the room holds the controller: B becomes the cancel, or the close again (the holder's re-take
-- replaces `back`; bindings cannot change in combat, so it waits for the fight to end).
local function stopTick()
    if not ctlr.want or not ctlr.held or not ctlr.backOn or InCombatLockdown() then return end
    if wantStop() == (ctlr.stopOn or false) then return end
    takePad()
    sayNext()
    if stopNote then stopNote(ctlr.stopOn) end
    soon()
end

-- The game's window opened or closed while the room holds the controller. Opened: B becomes the game's own close button.
-- Closed while that was so: EITHER the player pressed B (the game closed its window: the room goes HOME, user ruling 23:12, so
-- the controller goes back with the word "B" and the app is told), OR the room's own close cell did it because the player left
-- the room by touch (then the controller goes back quietly: the player chose where to go). Which one is decided a moment later,
-- so the close cell's report (it runs at the end of the same click) counts in whatever order the game fires its event.
local closeTapAt = -1000
padWindow = function(opened)
    if not ctlr.want or not ctlr.held then return end
    if opened then
        if not ctlr.backOn and wantBack(capture() or {}) then takePad() end
        return
    end
    if not ctlr.backOn then return end
    C_Timer.After(0, function()
        if not ctlr.want or not ctlr.held then return end
        ctlr.want = false
        if GetTime() - closeTapAt < 2 then freePad() return end   -- the close cell's click (same click, a frame apart)
        freePad("B")
        ctlr.n = ctlr.n + 1
        WC.send(WC.encode({ type = "profpad", v = PV, k = "BACK", n = ctlr.n }))
        WC.push()
    end)
end

local winNote              -- the diagnostics' note when the window comes or goes (end of file)
local awaitTake            -- the entry's take once the game's window has shown (with the actions)
local function winTick()
    waitTick()
    stopTick()
    tipTick()
    local now = winShown()
    if now == st.win then return end
    st.win = now
    if now and not builtClose and WC.professionsOpenRow then WC.professionsOpenRow() end    -- its close button exists now
    if now and ctlr.awaitWin and awaitTake then C_Timer.After(0, awaitTake) end            -- the entry's take, after the show
    if winNote then winNote(now) end
    padWindow(now)
    soon()
end
C_Timer.NewTicker(0.1, winTick)
WC.professionsWinTick = winTick          -- for the harness only (its ticker is a stub)

-- asked for again by itself only when nobody holds the capture (the capture's own rule for a guest)
-- In a fight too (SPEC_COMBAT 9, the user's agreed spec; capture i81: a take in a fight answers ok + pending until Home taps
-- enter): the capture decides, not this file.
retake = function()
    if not ctlr.want or ctlr.held then return end
    local c = capture(); if not c then return end
    local okS, state = CALL(c.state)
    if okS and type(state) == "table" and state.holder ~= nil then return end
    if takePad() then sayNext() end
    soon()                      -- the prof doc's `held`: the app opens the game's window only once the room holds (p14)
end

-- ---------------------------------------------------------------- actions (pad taps)

local function ack(op, id, ok, why, extra)
    local out = { type = "profack", op = op, id = id, ok = ok and true or false, why = why, v = PV }
    if extra then for k, v in pairs(extra) do out[k] = v end end
    WC.send(WC.encode(out))
    soon()
    WC.push()
end

local function nameOf(rid)
    local r = rid and st.known[rid]
    return r and r.name or nil
end

-- the checks every recipe action shares; returns the recipe or nil after refusing
local function target(op)
    if not st.open then ack(op, nil, false, "no profession open") return nil end
    if foreign() then ack(op, nil, false, "not your own profession") return nil end
    local rid = st.sel
    if not rid or not st.known[rid] then ack(op, rid, false, "no recipe selected") return nil end
    return rid
end

local BUSY_GRACE = 15       -- seconds past a generous cast time after which "busy" is dropped

local function craft(op, rid, count)
    if st.known[rid].ench then return ack(op, rid, false, "needs an item to enchant") end
    -- a safety net in case the game's stop event never comes: a queue cannot outlast this
    if st.busy and GetTime() - st.busy.at > BUSY_GRACE + 6 * st.busy.n then st.busy = nil end
    if st.busy then return ack(op, rid, false, "already crafting") end
    local can = SN(function() return C_TradeSkillUI.GetCraftableCount(rid) end) or 0
    if count == nil then count = can end
    if count < 1 or can < 1 then return ack(op, rid, false, "nothing can be made", { can = can }) end
    if count > can then return ack(op, rid, false, "only " .. can .. " can be made", { can = can }) end
    local ok = CALL(C_TradeSkillUI.CraftRecipe, rid, count)
    if ok then st.busy, st.made = { id = rid, n = count, at = GetTime() }, 0 end
    ack(op, rid, ok, (not ok) and "the game refused" or nil, { n = count, name = nameOf(rid) })
end

local CTL = {}
for n = 1, MAKE_MAX do
    CTL["make:" .. n] = function()
        local rid = target("make"); if rid then craft("make", rid, n) end
    end
end
CTL.all = function()
    local rid = target("all"); if rid then craft("all", rid, nil) end
end
local function setTrack(op, want)
    local rid = target(op); if not rid then return end
    CALL(C_TradeSkillUI.SetRecipeTracked, rid, want, false)
    local now = TB(function() return C_TradeSkillUI.IsRecipeTracked(rid, false) end) or false
    ack(op, rid, now == want, now ~= want and "did not take" or nil, { name = nameOf(rid) })
end
CTL.track = function() setTrack("track", true) end
CTL.untrack = function() setTrack("untrack", false) end
-- the room shows its book / a page: hold the controller in that mode (the holder's re-take replaces the spec, `back` included)
local function takeNow()
    local ok, why = takePad()
    if ok then sayNext() end
    -- refused because another room's guest still holds it (2026-09-30 22:49: the Calendar room kept it after it was left): ask
    -- again a moment later, never taking it from a holder (retake checks); the app waits for `held` before opening the window
    if not ok and V(why) == "busy" then
        C_Timer.After(0.5, function() retake() end)
        C_Timer.After(1.5, function() retake() end)
    end
    return ok, why
end
local function hold(mode)
    ctlr.want, ctlr.mode, ctlr.awaitWin = true, mode, false
    local ok, why = takeNow()
    ack(mode, nil, ok, why, { back = ctlr.backOn or nil, tabs = ctlr.tabs or nil })
    if diagNote then diagNote(mode) end
end
-- THE ENTRY, IN THE ORDER EVERY ROOM USES SINCE R1 (Home, 2026-10-02, after SmartNavigation.lua:611 "activeInfo a nil value" on a
-- fast room switch: the game's window OnShow asks the game's controller navigation to focus a button before the navigation has made
-- the window its active frame; a take already up when the window shows is the suspect): out of a fight, with nobody holding the
-- controller and the game's window not up yet, the room answers "open now" (the app's ONE book tap: the game's own Professions
-- button) and takes the controller ONE FRAME AFTER the window has shown. Never opened while the take would only queue or be pending:
-- then it takes at once and says `noopen`. If the window never shows (a lost tap), it takes anyway after a moment: never a dead B.
local AWAIT_MAX = 1.5
awaitTake = function()
    if not ctlr.awaitWin then return end
    ctlr.awaitWin = false
    if not ctlr.want or ctlr.held then return end
    takeNow()
    soon()
end
CTL.enter = function()
    ctlr.want, ctlr.mode, ctlr.awaitWin = true, "book", false
    local c = capture()
    local fight = InCombatLockdown()
    local okS, state = false, nil
    if c then okS, state = CALL(c.state) end
    local holder = okS and type(state) == "table" and state.holder or nil
    -- p22 (Home's bar NO-GAP hand-over, AGENT_LOG b79 "pre-empts pad:bar"): Home's bar cursor ("pad:bar") now KEEPS the controller
    -- until the opened room takes it; every room's take pre-empts it (capture v7, in a fight v12). So it counts as NOBODY here: the
    -- entry still opens the game's window first, and awaitTake's take one frame after the show pre-empts the bar (lost "taken").
    -- Without this, a bar pick answered `noopen` and the game's window was never opened behind the room
    if holder == "pad:bar" then holder = nil end
    if c and not fight and not st.win and builtBook and holder == nil then
        ctlr.awaitWin = true
        ack("book", nil, true, nil, { await = true })
        C_Timer.After(AWAIT_MAX, function() awaitTake() end)
        if diagNote then diagNote("book") end
        return
    end
    local ok, why = takeNow()
    ack("book", nil, ok, why, { back = ctlr.backOn or nil, tabs = ctlr.tabs or nil, noopen = (fight or holder ~= nil) or nil })
    if diagNote then diagNote("book") end
end
CTL.book = function() hold("book") end
CTL.page = function() hold("page") end
CTL.free = function()
    ctlr.want, ctlr.awaitWin = false, false
    freePad()
    ack("free", nil, true)
end
-- THE SECURE OPEN ROW. Its cells are SecureActionButtonTemplate buttons whose attributes are set out of combat (TapPad queues the
-- work in combat); a tap on one is a hardware click, and the template casts the spell in the game's own code. The post-hook below
-- only reports the tap: the game's TRADE_SKILL_SHOW tells whether the window really opened.
local function initOpen(btn, col)
    if col == BOOK_COL then
        -- a secure CLICK on the game's own Professions button (a frame the game made; only handed to the template)
        local b = _G[MICRO_BUTTON]
        builtBook = type(b) == "table"
        btn:SetAttribute("type", builtBook and "click" or nil)
        btn:SetAttribute("clickbutton", builtBook and b or nil)
        return
    end
    if col == CLOSE_COL then
        -- a secure CLICK on the game's own close button (a frame the game made; this file only hands it to the template, never
        -- calls anything on it). Before the game has loaded its window there is nothing to click, and the cell does nothing.
        local b = _G[CLOSE_BUTTON]
        builtClose = type(b) == "table"
        btn:SetAttribute("type", builtClose and "click" or nil)
        btn:SetAttribute("clickbutton", builtClose and b or nil)
        return
    end
    local spell = openSpell[col]
    builtSpell[col] = spell or false
    if spell then
        btn:SetAttribute("type", "spell")
        btn:SetAttribute("spell", spell)
    else
        btn:SetAttribute("type", nil)
        btn:SetAttribute("spell", nil)
    end
end

local function onOpenTap(col)
    if col == BOOK_COL then
        lastActionAt = GetTime()
        ack("win", nil, builtBook, (not builtBook) and "the game's Professions button is not there" or nil)
        return
    end
    if col == CLOSE_COL then
        -- leaving the room by touch: the game's own button has closed its window; the controller goes back quietly (see padWindow)
        closeTapAt = GetTime()
        ack("close", nil, builtClose, (not builtClose) and "the game's window is not loaded" or nil)
        C_Timer.After(0.3, function()
            if ctlr.want and not st.open and not st.win then ctlr.want = false; freePad() end
        end)
        return
    end
    lastActionAt = GetTime()        -- the game's error text in the next seconds answers this tap
    ack("open", openLine[col] or nil, openSpell[col] and true or false, (not openSpell[col]) and "nothing in that column" or nil)
end

local function ncolsOpen()
    local n = math.max(CLOSE_COL, BOOK_COL) + 1
    for col in pairs(openSpell) do if col + 1 > n then n = col + 1 end end
    return n
end

WC.professionsOpenRow = function()
    if not WC.pad then return end
    openDirty = false
    WC.pad.secureRow("profopen", ncolsOpen(), initOpen, onOpenTap)
end

if WC.pad then
    local rowCtl = WC.pad.op("prof", "profctl", function(key)
        lastActionAt = GetTime()        -- the game's error text in the next seconds answers this tap
        if type(key) ~= "string" then return ack("ctl", nil, false, "empty column") end
        local fn = CTL[key]
        if fn then fn() else ack("ctl", nil, false, "unknown key") end
    end)
    local rowSel = WC.pad.op("profsel", "profsel", function(col)
        local rid = type(col) == "number" and st.selMap[col] or nil
        if not st.open or not rid then return ack("select", nil, false, "nothing in that column") end
        st.sel = rid
        ack("select", rid, true, nil, { name = nameOf(rid) })
    end)
    -- The pad has a fixed number of rows and the rooms that load last get none (pad owner,
    -- 2026-09-28: demand is over pad4's cap). Say so once, and in every prof doc, so the room
    -- can tell the player "the tap pad is full" instead of waiting for an answer that never comes.
    -- the secure open row is reserved now, empty, so its place in the pad is fixed; its cells come with the professions
    local rowOpen = WC.pad.secureRow("profopen", 0, initOpen, onOpenTap)
    if not rowCtl or not rowSel or not rowOpen then
        padFull = {}
        if not rowCtl then padFull[#padFull + 1] = "prof" end
        if not rowSel then padFull[#padFull + 1] = "profsel" end
        if not rowOpen then padFull[#padFull + 1] = "profopen" end
        pcall(function() WC.send(WC.encode({ type = "cap", professions = PV, padRowsMissing = padFull })) end)
    end
    -- claim the columns now, in a fixed order, so every key keeps its column for the session
    for _, k in ipairs(CTL_STATIC) do ctlSlot(k) end
    claimSelColumns()
    WC.pad.onLayout(function() soon() end)
end

-- ---------------------------------------------------------------- p8: LOOK-ONLY diagnostics (device run 2026-09-30)
-- WHY: on the Thor, B on a page made the app tap this room's `book` cell (the Android log shows the tap) and the tap never
-- reached the cell; the game's keyboard focus went to a text box for 0.9 s instead (AGENT_LOG, "FIRST DEVICE RUN"). The user
-- asked for a look-only diagnostic and one retest. So, only while the room uses the controller or a profession is open,
-- this says WHERE each mouse press really landed and WHAT was under it, and WHICH text box takes the keyboard.
-- LOOK ONLY: it calls Get* / Is* on the frames the game hands back (GetMouseFoci, GetCurrentKeyBoardFocus) and on this
-- room's own pad cells. It sets nothing, calls no Blizzard Lua, names no game frame, and is capped per session.
local DIAG_MAX = 150
local diagN = 0

local function diagOn() return ctlr.want or ctlr.held or st.open or st.win end

-- the game's controller focus for its professions window (Interact's list, 2026-09-30): fields READ only, no function of the
-- game's is called but IsShown
local function gpFocus()
    local f = gameWin()
    local out = {}
    local ok = pcall(function()
        local m = GamepadMode and GamepadMode.FrameControlsManager
        if type(m) == "table" then
            local sf = m.shownFrames
            if type(sf) == "table" then
                out.sf = #sf
                out.inSF = false
                for _, x in ipairs(sf) do if f and x == f then out.inSF = true end end
            end
            out.foc = (f ~= nil and m.focusedFrame == f) or false
            out.uif = m.isUIFocused and true or false
        end
        local ti = f and f.TabIndicators
        if type(ti) == "table" then
            out.ti = TB(function() return ti:IsShown() end)
            out.vt = type(ti.visibleTabs) == "table" and #ti.visibleTabs or nil
            out.ci = VN(ti.currentIndex)
        end
        local bp = f and f.BookPage
        if type(bp) == "table" then out.book = TB(function() return bp:IsShown() end) end
    end)
    if not ok then out.err = true end
    return out
end
local function r1(v) return math.floor(v * 10 + 0.5) / 10 end

-- the pad's own units (describe(): physical pixels from the screen's top-left), so a rect compares with a cell directly
local function units()
    local i = WC.pad and WC.pad.info
    local us = SN(function() return UIParent:GetEffectiveScale() end)
    local uh = SN(function() return UIParent:GetHeight() end)
    if type(i) ~= "table" or not us or us <= 0 or not uh then return nil end
    return i, us, uh
end

-- a frame's rect as { left, top, width, height } in pad pixels, or nil
local function pxRect(fr)
    local i, us, uh = units()
    if not i or type(fr) ~= "table" then return nil end
    local l = SN(function() return fr:GetLeft() end)
    local b = SN(function() return fr:GetBottom() end)
    local w = SN(function() return fr:GetWidth() end)
    local h = SN(function() return fr:GetHeight() end)
    local es = SN(function() return fr:GetEffectiveScale() end)
    if not (l and b and w and h and es) then return nil end
    local k = es / us
    return { r1(l * k * i.sx), r1((uh - (b + h) * k) * i.sy), r1(w * k * i.sx), r1(h * k * i.sy) }
end

local function dname(fr)
    if type(fr) ~= "table" then return nil end
    local n = TS(function() return fr:GetDebugName() end) or TS(function() return fr:GetName() end)
    return n and n:sub(1, 100) or "(no name)"
end

local function frameInfo(fr)
    if type(fr) ~= "table" then return nil end
    return { n = dname(fr), r = pxRect(fr),
             s = TS(function() return fr:GetFrameStrata() end), l = TN(function() return fr:GetFrameLevel() end),
             vis = TB(function() return fr:IsVisible() end), m = TB(function() return fr:IsMouseEnabled() end) }
end

-- where the pointer is, in pad pixels
local function cursorPx()
    local i, us, uh = units()
    if not i then return nil end
    local ok, x, y = CALL(GetCursorPosition)
    x, y = VN(x), VN(y)
    if not ok or not x or not y then return nil end
    return { r1(x / us * i.sx), r1((uh - y / us) * i.sy) }
end

-- what the game says is under the pointer, top first (at most three)
local function underPointer()
    local ok, a, b, c
    if type(GetMouseFoci) == "function" then
        local okT, t = CALL(GetMouseFoci)
        if okT and type(t) == "table" then ok, a, b, c = true, t[1], t[2], t[3] end
    elseif type(GetMouseFocus) == "function" then
        ok, a = CALL(GetMouseFocus)
    end
    if not ok then return nil end
    local out = {}
    for _, fr in ipairs({ a, b, c }) do out[#out + 1] = frameInfo(fr) end
    return out
end

local function focusNow()
    local ok, fr = CALL(GetCurrentKeyBoardFocus)
    if ok and type(fr) == "table" then return fr end
end

-- this room's own cells that matter for the missed tap: are they there, where, and on top?
local function cells()
    local r = WC.pad and WC.pad.rows and WC.pad.rows.prof
    if type(r) ~= "table" or type(r.cells) ~= "table" then return nil end
    local out = {}
    for _, k in ipairs({ "book", "page", "free" }) do
        local col = ctlSlot(k)
        local btn = col and r.cells[col]
        if btn then local info = frameInfo(btn); info.n = nil; info.col = col; out[k] = info end
    end
    return out
end

local function diag(ev, extra)
    if diagN >= DIAG_MAX then return end
    diagN = diagN + 1
    local d = { type = "profdiag", v = PV, ev = ev, n = diagN, last = diagN == DIAG_MAX or nil,
                t = r1(GetTime()), held = ctlr.held or nil, mode = ctlr.want and ctlr.mode or nil, open = st.open or nil,
                cur = cursorPx(), under = underPointer(),
                gp = { on = TB(function() return C_CVar.GetCVarBool("GamePadEnable") end),
                       cursor = TB(function() return IsGamePadCursorControlEnabled() end),
                       free = TB(function() return IsGamePadFreelookEnabled() end) } }
    if extra then for k, v in pairs(extra) do d[k] = v end end
    WC.send(WC.encode(d))       -- no push of its own: the next ack / heartbeat flushes it (t = when it happened)
end

-- the room took the controller (book / page): where its cells are right now
diagNote = function(mode)
    diag("hold", { cells = cells(), fc = gpFocus() })
end

local lastFocus = nil
local function focusTick()
    if not diagOn() and not lastFocus then return end
    local fr = focusNow()
    if fr == lastFocus then return end
    lastFocus = fr
    diag("focus", { focus = frameInfo(fr) or false })
end

local df = CreateFrame("Frame")
df:SetScript("OnEvent", function(_, event, button)
    if event == "GLOBAL_MOUSE_DOWN" then
        if diagOn() then diag("down", { btn = VS(button) }) end
    elseif event == "TRADE_SKILL_SHOW" then
        C_Timer.After(0.5, function() if diagOn() then diag("shown", { cells = cells(), fc = gpFocus() }) end end)
    end
end)
for _, e in ipairs({ "GLOBAL_MOUSE_DOWN", "TRADE_SKILL_SHOW" }) do pcall(function() df:RegisterEvent(e) end) end
C_Timer.NewTicker(0.1, focusTick)
WC.professionsDiagTick = focusTick      -- for the harness only (its ticker is a stub)
-- the game's window came or went: its controller focus now, and again half a second later (after the game's own callbacks)
-- B became the craft's cancel, or the close again
stopNote = function(on) diag(on and "stop" or "nostop") end

winNote = function(shown)
    diag(shown and "win" or "nowin", { fc = gpFocus() })
    C_Timer.After(0.5, function() diag(shown and "win+" or "nowin+", { fc = gpFocus() }) end)
end
