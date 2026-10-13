-- WowCompanionInteract / Interact.lua
-- The INTERACTION OVERLAY feed: whatever NPC window is open -- a gossip list, a quest being offered
-- or turned in, a vendor -- sent to the Thor's bottom screen, which shows it as a pop-up over every
-- room until the window closes.
--
-- WHY A SEPARATE ADDON FOLDER: same reason as WowCompanionQuests. Parallel sessions push their own
-- files to the Thor; living in its own folder means this never touches the shared .toc or Core.lua,
-- and a push of it can never overwrite anyone else's file. ## Dependencies guarantees load order.
--
-- THREE SLOTS, NOT ONE "CURRENT DIALOG". The game hands one window to the next in an order we do not
-- control: picking a quest from a gossip list opens the quest frame, and GOSSIP_CLOSED can land AFTER
-- QUEST_DETAIL. A single current-dialog variable would then close the overlay on the quest it had just
-- opened. So gossip, quest and merchant are tracked separately, every document carries all three, and
-- the app shows the most specific one that is open (quest > merchant > gossip). It closes when all
-- three are empty.
--
-- OPEN IS DECIDED AT EMIT TIME, one frame after the events. Show/close events only mark a slot and
-- queue an emit. When the emit runs, the Blizzard frame's IsShown() is the truth -- but only once that
-- frame has been SEEN shown for that slot, so a frame name that is wrong on this build (or a UI that
-- uses a different frame) can never hide a real dialog; until then the later of show/close wins. When
-- the two disagree, one re-check 0.3 s later settles it (a late show, or a close event that never came).
--
-- WHAT IT COSTS: an emit only when the open windows' content changes, plus the core 15 s heartbeat (a
-- snapshot post-hook, as in Quests), which is how the app recovers after the log rotates and how a
-- missed close heals. With no NPC window open that is one ~60-byte line per heartbeat.
--
-- ACTIONS arrive on the SHARED TAP PAD (TapPad.lua, user ruling 2026-09-27): one touch in the app =
-- one tap on one invisible cell = one game call. A cell's KEY is the command itself ("accept 60",
-- "opt 95316", "buy 3 2512"), so the pad and the /wci slash command (kept for testing by hand) run
-- the same code. Every document publishes the column of every action it offers (`slot`, `act`) and
-- the pad's geometry (`pad`). Every action first checks that the window it answers is still the one
-- open (same quest id, option still offered, same item in that vendor or bag slot) and reports back
-- with an {"type":"interactack"} line, so a stale tap is refused instead of answering a different
-- dialog. Nothing here chains, times or reacts: the user decides every call.
--
-- FIVE PAD ROWS: `ipick` (gossip options and quest lists), `iact` (accept / decline / continue /
-- reward / close / repair / junk / buyback), `ibuy` (vendor items), `isell` (bag stacks), `iqty`
-- (how many to buy). A row has 40 columns and a freed column rests for 30 s, which is why lists and
-- answers do not share one.
--
-- HOW MANY TO BUY. A tap carries no number, so a purchase of more than one is TWO taps from ONE
-- touch on Buy: the `iqty` cell in column k-1 says "k purchases", then the item's `ibuy` cell buys.
-- The first tap changes nothing in the game: it sets a number in this file that the NEXT buy uses
-- once, within QTY_SECONDS. The purchase itself is still one game call, BuyMerchantItem(index, n),
-- exactly what the game's own split-stack box makes. If the first tap is lost, one is bought; if
-- the second is lost, nothing is. The reply always says how many were bought (`n`).
--
-- THE CONTROLLER (user rulings 2026-09-27 / 28). While a window is open and the companion is in
-- charge (`mode`), the D-pad and the four face buttons are taken from the game with priority
-- override bindings that click a button of ours, the way the game's own controller UI takes a key.
-- MEASURED on the Thor 2026-09-28: a binding of ours, set after the game's, wins on the same key,
-- and clearing ours uncovers the game's untouched. The sticks are never taken.
--   * THE HIGHLIGHT LIVES HERE, not in the app: the addon receives the press, moves `nav.cur` and
--     acts on A by itself. So what A does is always what this file has highlighted, and the
--     controller needs no tap at all. The app draws `nav` when it arrives.
--   * D-pad moves. A acts on the highlighted thing. At a vendor A on an item only PICKS it and
--     moves to the Buy / Sell button; there left / right change the quantity and A does it; B steps
--     back to the list. At a quest OFFER A is Accept and B is Decline, whatever is highlighted
--     (user, 2026-09-28). Everywhere else B closes the window.
--   * TWO WAYS IN, the first one wins. (1) A frame of ours that receives controller buttons
--     DIRECTLY (EnableGamePadButton + OnGamePadButtonDown / Up), the way the game's own radial
--     menus do: a frame hears a button before any binding is looked up, so the game setting its
--     bindings again cannot take the button away. (2) The override bindings, as before, for a
--     build where (1) does not deliver. `nav.via` says which one brought the last press.
--   * NOTHING IS HIDDEN UNTIL (1) IS PROVEN in this session: a press has arrived through the
--     frame. MEASURED 2026-09-28: at the vendor the game takes A's binding back after every
--     take-back of ours, so with the bindings alone A reaches the game's own window. A window
--     that might still receive A must stay VISIBLE. So every window is taken at once, by both
--     ways, and the game's copy stays on the top screen until the first press has come through
--     the frame; from then on it is hidden. (i7 left the first vendor of a session to the game
--     instead; the user found the controller "back on the top screen" and could not tell why.)
--   * HIDING (user, 2026-09-28: "Yes, tied to NPC windows setting"). While we hold the buttons
--     the game's own window is made INVISIBLE: SetAlpha(0), never Hide() or SetParent, on the
--     frames the frame probe found (the window, the bags a vendor opens, the hint panel, the
--     controller cursor's own pieces). The moment we let go, every alpha is put back.
--   * THE GAME TAKES KEYS BACK. Its own controller UI sets its bindings again whenever its window
--     changes, and a binding set later wins. So ours are CHECKED every time a document is built
--     and on UPDATE_BINDINGS, and set again when the game has taken one (`nav.lost` counts that).
--     `nav.last` is the last key that reached this file, so a recording shows what arrived.
--   * The buttons go back to the game the moment no window is open, when combat starts
--     (PLAYER_REGEN_DISABLED fires before the lockdown), and when the companion says it is no
--     longer in charge. `mode` is off until the companion asks for it, through the hub's layout
--     tap (element "dialogs"); with no companion the game is untouched.
--
-- Every value goes through S() (the T* / P* wrappers below), so nothing raw is ever concatenated, and
-- secrecy on this build cannot silently kill the whole line.

local ADDON = ...
local WC = _G.WowCompanion
if type(WC) ~= "table" or type(WC.S) ~= "function" or type(WC.send) ~= "function" then return end
local S, CALL = WC.S, WC.CALL

local IV = "i111"     -- bump on every change; the app shows it, so a stale addon is visible

local function say(s)
    if DEFAULT_CHAT_FRAME then DEFAULT_CHAT_FRAME:AddMessage("|cffffd100WowCompanion|r " .. s) end
end

local function real(v)
    return v ~= nil and v ~= WC.ABSENT and v ~= WC.SECRET and v ~= WC.THREW
end

-- S() that also counts sentinel hits, so a shift in the secrecy map under a patch shows up on the
-- wire (`sec`) instead of as a quietly emptier pop-up. ABSENT is not counted: nil is a legitimate
-- answer for most of these.
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
-- the same checks for a value a multi-return call already handed us
local function PS(v) return TS(function() return v end) end
local function PN(v) return TN(function() return v end) end
local function PB(v) return TB(function() return v end) end

-- Multi-return read that keeps nil holes. CALL() unpacks through #, and # is undefined on a table
-- with a nil in the middle, so a nil texture could silently drop every value after it.
local function R(fn)
    local function pack(ok, ...) return ok, select("#", ...), { ... } end
    local ok, n, r = pack(pcall(fn))
    if not ok then tally.threw = tally.threw + 1; return nil end
    r.n = n
    return r
end

local function creatureId(guid)
    if type(guid) ~= "string" then return nil end
    -- "Creature-0-6782-1-279-30-0000ABCDEF": the 6th dash-separated field is the creature id
    local n = 0
    for p in guid:gmatch("[^-]+") do
        n = n + 1
        if n == 6 then return tonumber(p) end
    end
end

local function linkID(link)
    if type(link) ~= "string" then return nil end
    local s = link:match("item:(%d+)")
    return s and tonumber(s)
end

local function npc()
    return {
        name = TS(function() return UnitName("npc") end),
        id = creatureId(TS(function() return UnitGUID("npc") end)),
    }
end

-- ---------------------------------------------------------------- quest frame

local questStage = nil      -- "greeting" | "detail" | "progress" | "complete"

-- GetQuestItemInfo(kind, i) -> name, texture, count, quality, isUsable, itemID
local function questItems(kind, n)
    local out = {}
    for i = 1, n or 0 do
        local r = R(function() return GetQuestItemInfo(kind, i) end)
        if r then
            out[#out + 1] = {
                i = i,
                name = PS(r[1]), icon = PN(r[2]), n = PN(r[3]), q = PN(r[4]),
                usable = PB(r[5]),
                id = PN(r[6]) or linkID(TS(function() return GetQuestItemLink(kind, i) end)),
            }
        end
    end
    return out
end

local function rewards()
    return {
        money = TN(function() return GetRewardMoney() end),
        xp = TN(function() return GetRewardXP() end),
        items = questItems("reward", TN(function() return GetNumQuestRewards() end)),
        choices = questItems("choice", TN(function() return GetNumQuestChoices() end)),
    }
end

local function collectQuest()
    local q = { stage = questStage, npc = npc() }
    if questStage == "greeting" then
        -- an old-style quest giver: a plain list, no gossip options
        q.text = TS(function() return GetGreetingText() end)
        q.available, q.active = {}, {}
        for i = 1, TN(function() return GetNumAvailableQuests() end) or 0 do
            q.available[#q.available + 1] = {
                i = i,
                title = TS(function() return GetAvailableTitle(i) end),
                level = TN(function() return GetAvailableLevel(i) end),
            }
        end
        for i = 1, TN(function() return GetNumActiveQuests() end) or 0 do
            local id = TN(function() return GetActiveQuestID(i) end)
            local r = R(function() return GetActiveTitle(i) end)
            local done = r and PB(r[2])
            if done == nil and id then done = TB(function() return C_QuestLog.IsComplete(id) end) end
            q.active[#q.active + 1] = {
                i = i,
                id = id,
                title = TS(function() return GetActiveTitle(i) end),
                level = TN(function() return GetActiveLevel(i) end),
                done = done == true,        -- b-npc-quest-icons: always sent; only true earns the gold ?
            }
        end
        return q
    end
    q.id = TN(function() return GetQuestID() end)
    q.title = TS(function() return GetTitleText() end)
    if questStage == "detail" then
        q.text = TS(function() return GetQuestText() end)
        q.objectives = TS(function() return GetObjectiveText() end)
        q.rewards = rewards()
        q.auto = TB(function() return QuestGetAutoAccept() end) or nil
        q.group = TN(function() return GetSuggestedGroupSize() end)
    elseif questStage == "progress" then
        q.text = TS(function() return GetProgressText() end)
        q.completable = TB(function() return IsQuestCompletable() end)
        q.required = questItems("required", TN(function() return GetNumQuestItems() end))
        q.money = TN(function() return GetQuestMoneyToGet() end)
    elseif questStage == "complete" then
        q.text = TS(function() return GetRewardText() end)
        q.rewards = rewards()
    end
    return q
end

-- ---------------------------------------------------------------- gossip

-- C_GossipInfo.GetAvailableQuests / GetActiveQuests entries
local function questList(list)
    local out = {}
    if type(list) ~= "table" then return out end
    for _, e in ipairs(list) do
        out[#out + 1] = {
            id = TN(function() return e.questID end),
            title = TS(function() return e.title end),
            level = TN(function() return e.questLevel end),
            trivial = TB(function() return e.isTrivial end) or nil,
            done = TB(function() return e.isComplete end) == true,     -- always sent: the app's gold ? only on true
            repeatable = TB(function() return e.repeatable end) or nil,
            important = TB(function() return e.isImportant end) or nil,
        }
    end
    return out
end

local function collectGossip()
    local g = { npc = npc(), options = {} }
    g.text = TS(function() return C_GossipInfo.GetText() end)
    local ok, opts = CALL(function() return C_GossipInfo.GetOptions() end)
    if ok and type(opts) == "table" then
        for k, o in ipairs(opts) do
            g.options[#g.options + 1] = {
                id = TN(function() return o.gossipOptionID end),
                name = TS(function() return o.name end),
                icon = TN(function() return o.icon end),
                -- Enum.GossipOptionStatus: 0 available, 1 unavailable, 2 locked, 3 already complete
                status = TN(function() return o.status end),
                order = TN(function() return o.orderIndex end) or k,
                k = k,
            }
        end
        table.sort(g.options, function(a, b)
            if a.order ~= b.order then return a.order < b.order end
            return a.k < b.k
        end)
        for _, o in ipairs(g.options) do o.k = nil end
    end
    local oka, av = CALL(function() return C_GossipInfo.GetAvailableQuests() end)
    g.available = questList(oka and av)
    local okc, ac = CALL(function() return C_GossipInfo.GetActiveQuests() end)
    g.active = questList(okc and ac)
    return g
end

-- ---------------------------------------------------------------- merchant

-- Only the player's own bags. 5..10 is the bank range on this Classic-numbered build.
local BAGS_LAST = 4
local requested = {}

local function sellList()
    local out, junk, junkValue = {}, 0, 0
    for bag = 0, BAGS_LAST do
        for slot = 1, TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0 do
            local ok, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
            if ok and type(info) == "table" then
                local id = TN(function() return info.itemID end)
                local noValue = TB(function() return info.hasNoValue end)
                if id and not noValue then
                    -- C_Item.GetItemInfo: 1 name ... 11 sellPrice. nil until the client has it cached.
                    local r = R(function() return C_Item.GetItemInfo(id) end)
                    local price = r and PN(r[11])
                    if not (r and r[1]) and not requested[id] then
                        requested[id] = true
                        CALL(function() C_Item.RequestLoadItemDataByID(id) end)
                    end
                    if price and price > 0 then
                        local n = TN(function() return info.stackCount end) or 1
                        local q = TN(function() return info.quality end)
                        out[#out + 1] = {
                            bag = bag, s = slot, id = id, n = n, q = q, price = price,
                            name = r and PS(r[1]),
                            icon = TN(function() return info.iconFileID end),
                            locked = TB(function() return info.isLocked end) or nil,
                        }
                        if q == 0 then junk = junk + 1; junkValue = junkValue + price * n end
                    end
                end
            end
        end
    end
    return out, junk, junkValue
end

local function collectMerchant()
    local m = { npc = npc(), items = {}, buyback = {} }
    m.money = TN(function() return GetMoney() end)
    m.canRepair = TB(function() return CanMerchantRepair() end)
    if m.canRepair then
        local r = R(function() return GetRepairAllCost() end)
        if r then m.repairCost = PN(r[1]); m.needsRepair = PB(r[2]) end
    end
    for i = 1, TN(function() return GetMerchantNumItems() end) or 0 do
        local row = { i = i }
        local ok, info = CALL(function() return C_MerchantFrame.GetItemInfo(i) end)
        if ok and type(info) == "table" then
            row.name = TS(function() return info.name end)
            row.icon = TN(function() return info.texture end)
            row.price = TN(function() return info.price end)
            row.stack = TN(function() return info.stackCount end)
            row.avail = TN(function() return info.numAvailable end)     -- -1 = unlimited
            row.buyable = TB(function() return info.isPurchasable end)
            row.usable = TB(function() return info.isUsable end)
            row.ext = TB(function() return info.hasExtendedCost end) or nil
        end
        row.id = TN(function() return GetMerchantItemID(i) end)
                 or linkID(TS(function() return GetMerchantItemLink(i) end))
        row.max = TN(function() return GetMerchantItemMaxStack(i) end)
        if row.id then row.q = TN(function() return C_Item.GetItemQualityByID(row.id) end) end
        m.items[#m.items + 1] = row
    end
    -- GetBuybackItemInfo(i) -> name, icon, price, quantity, numAvailable, isUsable, isBound
    for i = 1, TN(function() return GetNumBuybackItems() end) or 0 do
        local r = R(function() return GetBuybackItemInfo(i) end)
        if r then
            local id = linkID(TS(function() return GetBuybackItemLink(i) end))
            m.buyback[#m.buyback + 1] = {
                i = i, id = id, name = PS(r[1]), icon = PN(r[2]), price = PN(r[3]), n = PN(r[4]),
                q = id and TN(function() return C_Item.GetItemQualityByID(id) end) or nil,
            }
        end
    end
    m.sell, m.junk, m.junkValue = sellList()
    return m
end

-- ---------------------------------------------------------------- the windows switched on
-- A window is taken over (hidden on top, the controller taken, its document sent) only once its app half
-- ships: an addon on the Thor ahead of its app must never hide a window with nothing on the bottom screen.
-- Switch one on in the same build as its app view.
local WINDOWS = { trainer = true, stable = true, mail = true, auction = true, taxi = true, bank = true, loot = true, petition = true }
WC.interactWindows = WINDOWS     -- the harness switches a dormant window on (never the game)
WC.interactWindows = WINDOWS      -- the harness switches them on

-- ---------------------------------------------------------------- trainer
-- THE TRAINER (user, 2026-09-30: "we are still missing some NPC windows, like trainers"; first of the
-- order Trainer, Stable, Mail, Auction house). Class and profession trainers share the game's
-- ClassTrainerFrame (Forever's camelot cut, blizzard_trainerui). Read only: name, sub text, icon, the
-- game's category ("available" / "unavailable" / "used"; "header" rows are the game's groups), required
-- level, cost, required skill. The spell's own words come as the tooltip (doc.tip, "look train <i>").
local TRAINER_MAX = 80
-- a PROFESSION trainer (the game's rank bar has a maximum): no Spellbook there (the user, 2026-09-30: "Profession
-- trainer ... should not work like the class trainer. The spellboook should not be there.")
-- What decides it holds WITHOUT the skill too (user bug 2026-09-30: a Blacksmith showed the Spellbook to a player with no
-- Blacksmithing: no rank bar there): the game's IsTradeskillTrainer(), or any service the game prices as a profession
-- (GetTrainerServiceCost's isProfession), or a rank bar.
local function trProfession()
    if TB(function() return IsTradeskillTrainer() end) then return true end
    local n = TN(function() return GetNumTrainerServices() end) or 0
    for i = 1, math.min(n, 40) do
        local c = R(function() return GetTrainerServiceCost(i) end)
        if c and PB(c[2]) then return true end
    end
    local rv = R(function() return GetTrainerTradeskillRankValues() end)
    return (rv and real(rv[1]) and (PN(rv[3]) or 0) > 0) and true or false
end
local function collectTrainer()
    local t = { npc = npc(), services = {} }
    t.money = TN(function() return GetMoney() end)
    t.greeting = TS(function() return GetTrainerGreetingText() end)
    t.kind = TN(function() return C_Trainer.GetTrainerType() end)
    t.prof = trProfession() or nil                -- a profession trainer, with or without the skill: no Spellbook
    t.filters = {}
    for _, k in ipairs({ "available", "unavailable", "used" }) do
        t.filters[k] = TB(function() return GetTrainerServiceTypeFilter(k) end)
    end
    local n = TN(function() return GetNumTrainerServices() end) or 0
    for i = 1, math.min(n, TRAINER_MAX) do
        local r = R(function() return GetTrainerServiceInfo(i) end)
        if r and real(r[1]) then
            local sv = { i = i, name = PS(r[1]), cat = PS(r[2]), icon = PN(r[3]), lvl = PN(r[4]), sub = PS(r[5]) }
            if sv.cat ~= "header" then
                local c = R(function() return GetTrainerServiceCost(i) end)
                if c then sv.cost = PN(c[1]); sv.prof = PB(c[2]) end
                local k = R(function() return GetTrainerServiceSkillReq(i) end)
                if k and real(k[1]) then sv.skill = PS(k[1]); sv.rank = PN(k[2]); sv.skillOk = PB(k[3]) end
            end
            t.services[#t.services + 1] = sv
        end
    end
    if n > TRAINER_MAX then t.more = n - TRAINER_MAX end
    -- UI Art's TRAINER_SPEC section 8: the profession's rank bar, the next rank drawn first, the player's level,
    -- which profession it teaches, and ability requirements
    local rv = R(function() return GetTrainerTradeskillRankValues() end)
    if rv and real(rv[1]) then t.skillName = PS(rv[1]); t.rank = PN(rv[2]); t.maxRank = PN(rv[3]); t.rankMod = PN(rv[4]) end
    t.step = TN(function() return GetTrainerServiceStepIndex() end)
    if t.step and t.step < 1 then t.step = nil end
    t.level = TN(function() return UnitLevel("player") end)
    t.class = TS(function() local _, c = UnitClass("player"); return c end)
    for _, sv in ipairs(t.services) do
        if sv.cat ~= "header" then
            if not t.line then t.line = TS(function() return GetTrainerServiceSkillLine(sv.i) end) end
            local na = TN(function() return GetTrainerServiceNumAbilityReq(sv.i) end) or 0
            for j = 1, math.min(na, 3) do
                local ar = R(function() return GetTrainerServiceAbilityReq(sv.i, j) end)
                if ar and real(ar[1]) then
                    sv.abil = sv.abil or {}
                    sv.abil[#sv.abil + 1] = { n = PS(ar[1]), ok = PB(ar[2]) }
                end
            end
        end
    end
    t.categorize = TB(function() return C_Trainer.GetCategorizeTrainerUI() end)
    return t
end

-- ---------------------------------------------------------------- stable master (hunters)
-- THE STABLE (user's order, second: Trainer, Stable, Mail, Auction house). Forever's camelot cut,
-- PetStableFrame (blizzard_stableui/camelot): slot 1 = the pet that is out, slots 2.. = the stable.
-- Read only through C_StableInfo; a MOVE is C_StableInfo.SetPetSlot(from, to) (the game's own swap);
-- a new slot is C_StableInfo.BuyStableSlot() (what the game's confirmation calls), armed by one press and
-- bought by a second within ARM_SECONDS, because it costs money and the game asks first too.
local STABLE_MAX = 12
local function collectStable()
    local st = { npc = npc(), pets = {} }
    st.money = TN(function() return GetMoney() end)
    st.slots = TN(function() return C_StableInfo.GetNumStableSlots() end)
    st.cost = TN(function() return C_StableInfo.GetNextStableSlotCost() end)
    st.maxSlots = TN(function() return Constants.PetConsts.MAX_STABLE_SLOTS end)
    local total = TN(function() return Constants.PetConsts.NUM_PET_SLOTS_HUNTER end) or ((st.slots or 0) + 1)
    for i = 1, math.min(total, STABLE_MAX) do
        local ok, info = CALL(function() return C_StableInfo.GetStablePetInfo(i) end)
        local row = { i = i, open = (i == 1) or ((i - 2) < (st.slots or 0)) }
        if ok and type(info) == "table" then
            row.name = TS(function() return info.name end)
            row.family = TS(function() return info.familyName end)
            row.level = TN(function() return info.level end)
            row.icon = TN(function() return info.icon end)
            row.loyalty = TS(function() return info.loyaltyName end)
            row.loyaltyLevel = TN(function() return info.loyaltyLevel end)
            row.xp = TN(function() return info.experience end)
            row.xpNeed = TN(function() return info.experienceNeeded end)
            row.spec = TS(function() return info.specialization end) or TS(function() return info.specializationName end)
        end
        st.pets[#st.pets + 1] = row
    end
    return st
end

-- ---------------------------------------------------------------- mailbox
-- THE MAILBOX (user's order, third). First step: the INBOX. The list (sender, subject, money, COD, days
-- left, attachments, read), one letter opened (its text and attachments). Taking is the game's own C calls
-- (TakeInboxItem, TakeInboxMoney, AutoLootMailItem). NOT in this step: a letter with a COD charge (it costs
-- money: the game asks first; refused here), returning, deleting, sending (needs a keyboard).
local MAIL_MAX, ATTACH_MAX = 50, 16
local MAIL = { open = nil }       -- the letter opened on the bottom screen
local function collectMail()
    local ml = { mails = {} }
    ml.money = TN(function() return GetMoney() end)
    local n = TN(function() return GetInboxNumItems() end) or 0
    for i = 1, math.min(n, MAIL_MAX) do
        local r = R(function() return GetInboxHeaderInfo(i) end)
        if r then
            ml.mails[#ml.mails + 1] = { i = i, icon = PN(r[1]), sender = PS(r[3]), subject = PS(r[4]), money = PN(r[5]),
                                        cod = PN(r[6]), days = PN(r[7]), items = PN(r[8]), read = PB(r[9]),
                                        returned = PB(r[10]), gm = PB(r[13]), qty1 = PN(r[14]) }
        end
    end
    ml.more = n > MAIL_MAX and (n - MAIL_MAX) or nil
    local o = MAIL.open
    if o and o <= n then
        local open = { i = o, items = {} }
        local tx = R(function() return GetInboxText(o) end)
        if tx then open.body = PS(tx[1]); open.invoice = PB(tx[5]) end
        for a = 1, ATTACH_MAX do
            local it = R(function() return GetInboxItem(o, a) end)
            if it and real(it[1]) then
                open.items[#open.items + 1] = { a = a, name = PS(it[1]), id = PN(it[2]), icon = PN(it[3]), count = PN(it[4]), q = PN(it[5]) }
            end
        end
        ml.open = open
    elseif o then
        MAIL.open = nil
    end
    return ml
end

-- ---------------------------------------------------------------- auction house
-- THE AUCTION HOUSE, step 1 (user, 2026-09-30: "Browse and buy": "The game's category tree (no typing),
-- results with prices, buying a single item or a stack in a quantity you pick on two presses (first shows
-- the total, second buys), plus a read-only list of your auctions. Selling and typed search come next").
-- Forever runs the MODERN auction house (C_AuctionHouse; its own camelot category data, AuctionCategories,
-- which is READ, never written). Answers come as events; this file keeps what it asked for and what came.
-- PURCHASES: StartCommoditiesPurchase and PlaceBid are RESTRICTED calls, made inside the press itself (the
-- controller's own button event, or a tap's click). A commodity: press 1 = StartCommoditiesPurchase -> the
-- game's COMMODITY_PRICE_UPDATED total; press 2 (within AH_ARM s) = ConfirmCommoditiesPurchase. An item:
-- press 1 = arm with its buyout; press 2 = PlaceBid(auctionID, buyout). Anything else cancels.
local AH_ARM, AH_ROWS, AH_RESULTS = 10, 40, 60
local AH = { path = {}, results = nil, key = nil, qty = 1, buy = nil, owned = nil, tab = "browse", asked = nil }
local function ahCategory(path)
    local list = AuctionCategories
    local cat
    for _, i in ipairs(path) do
        if type(list) ~= "table" then return nil end
        cat = list[i]
        if type(cat) ~= "table" then return nil end
        list = cat.subCategories
    end
    return cat, list
end
local function ahKeyInfo(itemKey)
    local ok, info = CALL(function() return C_AuctionHouse.GetItemKeyInfo(itemKey) end)
    if ok and type(info) == "table" then return info end
end
-- ---------------------------------------------------------------- the flight master
-- THE FLIGHT MASTER (user, 2026-09-30: "1, the game's view: the whole continent"; UI Art's FLIGHT_SPEC 2). Forever's
-- window is the modern FlightMapFrame: the taxi map's uiMap ID (GetTaxiMapID) and its nodes from the game's own
-- C_TaxiMap.GetAllTaxiNodes (position 0..1 on that uiMap, state Current / Reachable / Unreachable, slotIndex). Every
-- reachable node carries its route (the hops' slots, GetNumRoutes + TaxiGetNodeSlot), so the app draws the chosen
-- route at once, the finger's choice too. TAXI.pos keeps where each node is, for the D-pad's "nearest that way".
local TAXI_MAX = 60
local TAXI = { pos = {} }
local function collectTaxi()
    local t = { money = TN(function() return GetMoney() end), npc = { name = TS(function() return UnitName("npc") end) }, nodes = {} }
    t.map = TN(function() return GetTaxiMapID() end)
    TAXI.pos = {}
    local list
    if t.map and C_TaxiMap and C_TaxiMap.GetAllTaxiNodes then
        local ok, l = CALL(function() return C_TaxiMap.GetAllTaxiNodes(t.map) end)
        if ok and type(l) == "table" then list = l end
    end
    for _, n in ipairs(list or {}) do
        local i = TN(function() return n.slotIndex end)
        local x, y = TN(function() return n.position.x end), TN(function() return n.position.y end)
        local st = TN(function() return n.state end)            -- Enum.FlightPathState: 0 Current, 1 Reachable, 2 Unreachable
        -- the game HIDES an unreachable point (not discovered), unless it is a map-layer transition
        -- (fm_flightpathdataprovider.lua; user, 2026-09-30: "it shows a bunch of flight paths that I have not discovered")
        local shown = st ~= 2 or TB(function() return n.isMapLayerTransition end) == true
        if i and x and y and shown and #t.nodes < TAXI_MAX then
            local node = { i = i, name = TS(function() return n.name end), x = x, y = y,
                           state = (st == 0 and "current") or (st == 1 and "reachable") or "unreachable" }
            if node.state == "reachable" then
                node.cost = TN(function() return TaxiNodeCost(i) end)
                local hops = TN(function() return GetNumRoutes(i) end) or 0
                if hops > 0 then
                    node.hops = {}
                    for h = 1, math.min(hops, 12) do
                        node.hops[#node.hops + 1] = TN(function() return TaxiGetNodeSlot(i, h, true) end)
                    end
                end
            end
            if node.state == "current" then t.cur = i end
            TAXI.pos[i] = { x, y }
            t.nodes[#t.nodes + 1] = node
        end
    end
    return t
end

-- ---------------------------------------------------------------- the bank
-- THE BANK (user, 2026-09-30: "We are missing the bank" / "Yes, build it"; UI Art's BANK_SPEC). Forever's bank (camelot
-- bankframe.lua): two named tabs, "Bank" and the account bank (ACCOUNT_BANK_PANEL_TITLE, only while C_Bank.CanViewBank
-- says so); the bank is its purchased TABS (C_Bank.FetchPurchasedBankTabData: each .ID a container, the first the main
-- bank), more tabs bought one by one (FetchNextPurchasableBankTabData, C_Bank.PurchaseBankTab). Here: one page per bought
-- tab, its slots; the player's bags beside it in Bags' order. Every MOVE is Bags' own (WC.bagsMove / WC.bagsFirstEmpty,
-- Bags.lua: the guards, the game's pick-up + put-down); nothing here picks an item up.
local BANK_SLOTS_MAX = 40
local BANK = { acct = false, page = 1, hand = nil, box = false }
local function bankTypeNow()
    if BANK.acct then return TN(function() return Enum.BankType.Account end) end
    return TN(function() return Enum.BankType.Character end)
end
local function bankItem(bag, slot)
    local ok, it = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
    if not (ok and type(it) == "table") then return nil end
    local id = TN(function() return it.itemID end)
    if not id then return nil end
    return { bag = bag, slot = slot, id = id, icon = TN(function() return it.iconFileID end),
             count = TN(function() return it.stackCount end), quality = TN(function() return it.quality end),
             locked = TB(function() return it.isLocked end) or nil }
end
local function bankTabs(ty)
    local out = {}
    local ok, tabs = CALL(function() return C_Bank.FetchPurchasedBankTabData(ty) end)
    if ok and type(tabs) == "table" then
        for _, t in ipairs(tabs) do
            local id = TN(function() return t.ID end)
            if id then out[#out + 1] = { id = id, icon = TN(function() return t.icon end), name = TS(function() return t.name end) } end
        end
    end
    return out
end
local function collectBank()
    local b = { money = TN(function() return GetMoney() end), npc = { name = TS(function() return UnitName("npc") end) } }
    local acctT = TN(function() return Enum.BankType.Account end)
    b.account = (acctT and TB(function() return C_Bank.CanViewBank(acctT) end)) or nil
    if not b.account then BANK.acct = false end
    b.acct = BANK.acct or nil
    local ty = bankTypeNow()
    b.tabs = ty and bankTabs(ty) or {}
    b.maxTabs = ty and TN(function() return C_Bank.FetchMaxNumBankTabs(ty) end) or nil
    if ty then
        local okN, nx = CALL(function() return C_Bank.FetchNextPurchasableBankTabData(ty) end)
        if okN and type(nx) == "table" then b.cost = TN(function() return nx.tabCost end) end
    end
    b.pages = math.max(1, #b.tabs)
    if BANK.page > b.pages then BANK.page = 1 end
    b.page = BANK.page
    local cur = b.tabs[BANK.page]
    b.slots = {}
    if cur then
        b.bag = cur.id
        b.n = math.min(TN(function() return C_Container.GetContainerNumSlots(cur.id) end) or 0, BANK_SLOTS_MAX)
        for sl = 1, b.n do local it = bankItem(cur.id, sl); if it then b.slots[#b.slots + 1] = it end end
    end
    -- the player's bags, in the Bags room's order (backpack, bags 1-4, then the reagent bag when there is one), with
    -- their shape for the grid
    b.bags = {}
    local lastBag = TN(function() return Enum.BagIndex.ReagentBag end) or 4
    for bag = 0, lastBag do
        local n = math.min(TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0, BANK_SLOTS_MAX)
        if n > 0 then
            local e = { bag = bag, slots = n, items = {} }
            for sl = 1, n do local it = bankItem(bag, sl); if it then e.items[#e.items + 1] = it end end
            b.bags[#b.bags + 1] = e
        end
    end
    if BANK.hand then b.hand = { bag = BANK.hand[1], slot = BANK.hand[2] } end
    b.box = BANK.box or nil
    return b
end
local function isBankBag(bag)
    for _, k in ipairs({ "Character", "Account" }) do
        local ty = TN(function() return Enum.BankType[k] end)
        for _, t in ipairs(ty and bankTabs(ty) or {}) do if t.id == bag then return true end end
    end
    return false
end

-- ---------------------------------------------------------------- the loot window
-- LOOTING (user, 2026-10-01: "we need to add a pop up for Looting"; "Every loot window"). The game's LootFrame
-- (mainline/lootframe.lua): LOOT_OPENED / LOOT_CLOSED, GetNumLootItems / GetLootSlotInfo, LootSlot(i) = ONE game action.
-- No "Loot All" (many actions for one press: the game's own auto-loot does that). The game's "Inventory is full" while
-- the window is open (UI_ERROR_MESSAGE == ERR_INV_FULL, the same thing its own frame listens for) marks `full`: the
-- make-space screen (its delete waits for the proof that an addon may delete on this client).
-- an item's name by its id: C_Item.GetItemNameByID, else the first return of C_Item.GetItemInfo (never a blank line)
local function itemName(id)
    local n = TS(function() return C_Item.GetItemNameByID(id) end)
    if n then return n end
    return TS(function() return (C_Item.GetItemInfo(id)) end)
end
local LOOT_MAX = 20
local LOOT = { full = false, bind = false, running = false, hold = nil, rare = nil,
               try = nil,       -- the loot slot of the last LootSlot of ours
               want = nil,      -- the slot that did not fit ("Inventory is full" right after trying it): Make Space's goal
               landN = 0, landing = nil }   -- LOOTWIN_SPEC 7: the item looted into the freed tile (the app flies it there)
-- MAKE SPACE's delete (user: "Our confirm, A held 1 s"; on the button's own glyph; blue / purple one plain Yes / No after it).
-- ON since Bags' grey-junk test PROVED on the Thor (2026-10-01 08:57: Ruined Pelt and Large Bear Tooth destroyed by the same
-- PickupContainerItem + DeleteCursorItem pair WC.bagsDestroy runs; 0 forbidden / blocked lines) and the user chose this delete.
local DELETE_ON = true
local HOLD_SECONDS = 1.0
WC.interactDeleteForTest = function(on) DELETE_ON = on and true or false end   -- the harness only; the build flips DELETE_ON
local LOOT_STEP = 0.15      -- Loot All: one slot, then the next this much later (the game's own pace for its auto-loot)
-- MAKE SPACE FOR A QUEST (the user, 2026-10-05: "a quest giver that is giving me an item but my inventory is full" -> "Same
-- Make Space flow as looting"). The same screen and state (LOOT.full / hold / rare) with LOOT.ms = "quest": the game said
-- "Inventory is full" right after OUR accept or reward on an open quest window; after the delete that action is retried once
-- (the user's exception, as the loot's: one hold = the delete + the retry).
LOOT.ms = nil           -- "quest" while Make Space serves the quest window (else the loot window)
LOOT.retry = nil        -- the quest action to run again after the delete: { cmd, a, c, name, icon, q }
-- r-loot-on-top (a player's request, 2026-10-13): Home > Settings "Loot window: Top screen". The game's own loot window then stays
-- the game's: this file never collects it, never takes the controller for it and never touches LootFrame's alpha (isOpen("loot")
-- answers false, so every loot path here is off). Kept in WowCompanionDB.lootTop, which the app keeps across updates (SvKeep,
-- PadMap m51). Default (nil / false) = the bottom screen's pop-up, as before.
function LOOT.top()
    return type(WowCompanionDB) == "table" and WowCompanionDB.lootTop == true
end
-- is Make Space up, for whichever window it serves
function LOOT.on()
    if not LOOT.full then return false end
    local open = LOOT.isOpen         -- isOpen is declared further down (set there)
    if not open then return false end
    if LOOT.ms == "quest" then return open("quest") end
    return open("loot")
end
-- the free slots in the general bags (what a quest's items can go into)
function LOOT.freeGeneral()
    local n = 0
    for bag = 0, 4 do
        local okF, free, fam = CALL(function() return C_Container.GetContainerNumFreeSlots(bag) end)
        if okF and type(free) == "number" and (fam == nil or (type(fam) == "number" and fam == 0)) then n = n + free end
    end
    return n
end
-- MAKE SPACE BEFORE the game is asked (2026-10-05, measured: the game hides its quest window the moment the action runs, so a
-- refusal that comes after it can no longer be answered): opens Make Space over the still-open quest window
-- LOOTWIN 9: the slots free for the quest's rewards now. A tile deleted for it (list `freed`) that still holds its item counts
-- as free too: the game empties it a moment later (each counted once: only while it is still occupied)
function LOOT.roomNow(freed)
    local pending = 0
    for _, fa in ipairs(freed or {}) do
        local okI, it = CALL(function() return C_Container.GetContainerItemInfo(fa.bag, fa.slot) end)
        if okI and type(it) == "table" and TN(function() return it.itemID end) then pending = pending + 1 end
    end
    return LOOT.freeGeneral() + pending
end

function LOOT.questFirst(rt)
    LOOT.full, LOOT.ms, LOOT.retry, LOOT.hold, LOOT.rare = true, "quest", rt, nil, nil
    if LOOT.poke then LOOT.poke() end      -- N and emitSoon are declared further down (LOOT.poke is set there)
    return true
end

-- the general bags and their items (what Make Space shows), for the quest
function LOOT.buildSpace()
    local space = { bags = {}, skipped = {} }
    for bag = 0, 4 do
        local okF, _, fam = CALL(function() return C_Container.GetContainerNumFreeSlots(bag) end)
        local general = (fam == nil or (type(fam) == "number" and fam == 0))
        local n = math.min(TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0, 40)
        if n > 0 then
            local name = TS(function() return C_Container.GetBagName(bag) end)
            if general then
                local e = { bag = bag, name = name, slots = n, items = {} }
                for sl = 1, n do
                    local ok2, it = CALL(function() return C_Container.GetContainerItemInfo(bag, sl) end)
                    local id = ok2 and type(it) == "table" and TN(function() return it.itemID end)
                    if id then
                        e.items[#e.items + 1] = { bag = bag, slot = sl, id = id, icon = TN(function() return it.iconFileID end),
                            count = TN(function() return it.stackCount end), quality = TN(function() return it.quality end),
                            name = itemName(id) }
                    end
                end
                space.bags[#space.bags + 1] = e
            elseif name then
                space.skipped[#space.skipped + 1] = name
            end
        end
    end
    space.delete = DELETE_ON or nil
    if LOOT.hold then space.hold = { id = LOOT.hold.id } end
    if LOOT.rare then space.rare = LOOT.rare end
    local rt = LOOT.retry
    if rt then space.want = { cmd = rt.cmd, name = rt.name, icon = rt.icon, q = rt.q, count = rt.count, title = rt.title,
                              anyway = rt.anyway or nil, items = rt.items, need = rt.need,
                              free = rt.need and math.min(LOOT.roomNow(rt.freed), rt.need) or nil, freed = rt.freed } end
    return space
end

local function collectLoot()
    local l = { items = {}, full = LOOT.full or nil, running = LOOT.running or nil }
    local n = TN(function() return GetNumLootItems() end) or 0
    for i = 1, math.min(n, LOOT_MAX) do
        local r = R(function() return GetLootSlotInfo(i) end)
        if r and (real(r[2]) or PB(r[10])) then
            l.items[#l.items + 1] = { i = i, icon = PN(r[1]), name = PS(r[2]), count = PN(r[3]), q = PN(r[5]),
                locked = PB(r[6]) or nil, quest = PB(r[7]) or nil, coin = PB(r[10]) or nil,
                id = TN(function() local link = GetLootSlotLink(i); return link and (C_Item.GetItemInfoInstant(link)) end) }
        end
    end
    l.free = 0
    local space = (LOOT.full and LOOT.ms ~= "quest") and { bags = {}, skipped = {} } or nil
    for bag = 0, 4 do
        local okF, free, fam = CALL(function() return C_Container.GetContainerNumFreeSlots(bag) end)
        local general = (fam == nil or (type(fam) == "number" and fam == 0))
        if okF and type(free) == "number" and general then l.free = l.free + free end
        local n = math.min(TN(function() return C_Container.GetContainerNumSlots(bag) end) or 0, 40)
        if space and n > 0 then
            local name = TS(function() return C_Container.GetBagName(bag) end)
            if general then
                local e = { bag = bag, name = name, slots = n, items = {} }
                for sl = 1, n do
                    local ok2, it = CALL(function() return C_Container.GetContainerItemInfo(bag, sl) end)
                    local id = ok2 and type(it) == "table" and TN(function() return it.itemID end)
                    if id then
                        e.items[#e.items + 1] = { bag = bag, slot = sl, id = id, icon = TN(function() return it.iconFileID end),
                            count = TN(function() return it.stackCount end), quality = TN(function() return it.quality end),
                            name = itemName(id) }
                    end
                end
                space.bags[#space.bags + 1] = e
            elseif name then
                space.skipped[#space.skipped + 1] = name     -- "Not shown: your quiver ..." (they cannot hold this loot)
            end
        end
    end
    if space then
        space.delete = DELETE_ON or nil
        if LOOT.hold then space.hold = { id = LOOT.hold.id } end
        if LOOT.rare then space.rare = LOOT.rare end
        l.space = space
    end
    return l
end

local function collectAuction()
    local ah = { money = TN(function() return GetMoney() end), tab = AH.tab, path = AH.path, qty = AH.qty,
                 npc = { name = TS(function() return UnitName("npc") end) } }
    ah.ready = TB(function() return C_AuctionHouse.IsThrottledMessageSystemReady() end)
    -- the categories as the game shows them (UI Art's AUCTION_SPEC 3): the top level, and the path browsed open in
    -- place, each level one deeper. `i` is the path ("5.1"); only the category browsed is `sel`, its parents are `open`.
    local cat = ahCategory(AH.path)
    ah.cats = {}
    local function walk(list, prefix, lv)
        if type(list) ~= "table" or lv > 2 then return end
        for i, c in ipairs(list) do
            if type(c) == "table" then
                local p = {}
                for k = 1, #prefix do p[k] = prefix[k] end
                p[#p + 1] = i
                local onPath = #p <= #AH.path
                for k = 1, #p do if AH.path[k] ~= p[k] then onPath = false; break end end
                ah.cats[#ah.cats + 1] = { i = table.concat(p, "."), name = TS(function() return c.name end), lv = lv,
                    sub = (type(c.subCategories) == "table" and #c.subCategories > 0) or nil,
                    sel = (onPath and #p == #AH.path) or nil, open = (onPath and #p < #AH.path) or nil }
                if onPath then walk(c.subCategories, p, lv + 1) end
            end
        end
    end
    walk(AuctionCategories, {}, 0)
    ah.nOwned = TN(function() return C_AuctionHouse.GetNumOwnedAuctions() end)
    ah.catName = cat and TS(function() return cat.name end) or nil
    -- the browse results of the last category asked for
    if AH.results then
        ah.results = {}
        local ok, rs = CALL(function() return C_AuctionHouse.GetBrowseResults() end)
        if ok and type(rs) == "table" then
            for i = 1, math.min(#rs, AH_RESULTS) do
                local r = rs[i]
                local key = r and r.itemKey
                local info = key and ahKeyInfo(key)
                ah.results[#ah.results + 1] = { i = i, id = key and TN(function() return key.itemID end),
                    name = info and TS(function() return info.itemName end), icon = info and TN(function() return info.iconFileID end),
                    q = info and TN(function() return info.quality end), price = TN(function() return r.minPrice end),
                    count = TN(function() return r.totalQuantity end), commodity = info and TB(function() return info.isCommodity end) }
            end
            AH.rs = rs
        end
    end
    -- one item opened: its listings (an item) or its price tiers (a commodity)
    if AH.key then
        local key = AH.key
        local info = ahKeyInfo(key)
        local it = { id = TN(function() return key.itemID end), name = info and TS(function() return info.itemName end),
                     icon = info and TN(function() return info.iconFileID end), q = info and TN(function() return info.quality end),
                     commodity = info and TB(function() return info.isCommodity end), rows = {} }
        if it.commodity and it.id then
            local n = TN(function() return C_AuctionHouse.GetNumCommoditySearchResults(it.id) end) or 0
            local avail = 0
            for i = 1, math.min(n, AH_ROWS) do
                local ok, r = CALL(function() return C_AuctionHouse.GetCommoditySearchResultInfo(it.id, i) end)
                if ok and type(r) == "table" then
                    local q = TN(function() return r.quantity end) or 0
                    avail = avail + q
                    it.rows[#it.rows + 1] = { i = i, price = TN(function() return r.unitPrice end), count = q, mine = TB(function() return r.containsOwnerItem end) }
                end
            end
            it.avail = avail
        else
            local n = TN(function() return C_AuctionHouse.GetNumItemSearchResults(key) end) or 0
            for i = 1, math.min(n, AH_ROWS) do
                local ok, r = CALL(function() return C_AuctionHouse.GetItemSearchResultInfo(key, i) end)
                if ok and type(r) == "table" then
                    it.rows[#it.rows + 1] = { i = i, auction = TN(function() return r.auctionID end), price = TN(function() return r.buyoutAmount end),
                        bid = TN(function() return r.bidAmount end), count = TN(function() return r.quantity end),
                        mine = TB(function() return r.containsOwnerItem end), left = TN(function() return r.timeLeft end) }
                end
            end
        end
        ah.item = it
    end
    if AH.buy then ah.buy = { kind = AH.buy.kind, total = AH.buy.total, unit = AH.buy.unit, qty = AH.buy.qty, row = AH.buy.row,
                              waiting = AH.buy.waiting or nil, armed = AH.buy.armedAt and true or nil } end
    -- your auctions, read only
    if AH.tab == "owned" then
        ah.owned = {}
        local n = TN(function() return C_AuctionHouse.GetNumOwnedAuctions() end) or 0
        for i = 1, math.min(n, AH_ROWS) do
            local ok, r = CALL(function() return C_AuctionHouse.GetOwnedAuctionInfo(i) end)
            if ok and type(r) == "table" then
                local info = r.itemKey and ahKeyInfo(r.itemKey)
                ah.owned[#ah.owned + 1] = { i = i, name = info and TS(function() return info.itemName end), icon = info and TN(function() return info.iconFileID end),
                    count = TN(function() return r.quantity end), price = TN(function() return r.buyoutAmount end), bid = TN(function() return r.bidAmount end),
                    left = TN(function() return r.timeLeft end), status = TN(function() return r.status end) }
            end
        end
    end
    return ah
end
local function ahCancelBuy()
    if AH.buy and AH.buy.kind == "commodity" and AH.buy.started then
        CALL(function() C_AuctionHouse.CancelCommoditiesPurchase() end)
    end
    AH.buy = nil
end

-- ---------------------------------------------------------------- which windows are open

local FRAME = { gossip = "GossipFrame", quest = "QuestFrame", merchant = "MerchantFrame", trainer = "ClassTrainerFrame",
                stable = "PetStableFrame", mail = "MailFrame", auction = "AuctionHouseFrame", taxi = "FlightMapFrame",
                bank = "BankFrame", loot = "LootFrame", petition = "PetitionFrame" }
local ev = 0
local shownAt = { gossip = 0, quest = 0, merchant = 0, trainer = 0, stable = 0, mail = 0, auction = 0, taxi = 0, bank = 0, loot = 0, petition = 0 }
local hiddenAt = { gossip = 0, quest = 0, merchant = 0, trainer = 0, stable = 0, mail = 0, auction = 0, taxi = 0, bank = 0, loot = 0, petition = 0 }
local frameSeen = {}        -- slot -> true once its Blizzard frame has been seen shown
local disagreed = false

local function frameShown(slot)
    local fr = _G[FRAME[slot]]
    if type(fr) ~= "table" or type(fr.IsShown) ~= "function" then return nil end
    local ok, v = pcall(fr.IsShown, fr)
    if ok and type(v) == "boolean" then return v end
end

local function isOpen(slot)
    if slot == "loot" and LOOT.top() then return false end      -- r-loot-on-top: the game keeps its loot window
    local byEvent = shownAt[slot] > hiddenAt[slot]
    local vis = frameShown(slot)
    if vis and byEvent then frameSeen[slot] = true end
    if frameSeen[slot] and vis ~= nil then
        if vis ~= byEvent then disagreed = true end
        return vis
    end
    return byEvent
end
LOOT.isOpen = isOpen               -- for LOOT.on (declared above this)

local function anyOpenByEvent()
    for slot in pairs(FRAME) do
        if shownAt[slot] > hiddenAt[slot] then return true end
    end
    return false
end

-- ---------------------------------------------------------------- emit

local lastJson = nil
local queued = false
local recheckEv = -1

-- Give every action the document offers its column on the tap pad. A key keeps its column for as
-- long as it is offered; pad.retain() frees the rest (TapPad quarantines a freed column).
local NS = { "ipick", "iact", "ibuy", "isell" }
-- The REMEMBERED PROOF (user, 2026-09-28: "Remember the proof"). SavedVariables do not read back on
-- this client, so the APP keeps it: "on game build GB, capture code CAPV, the frame heard the
-- controller". After every load the app taps the `trust` cell once, and the game's windows are
-- hidden from the first visit on. CAPV: bump whenever the way the controller is captured changes.
local CAPV = 1
local GB = TS(function() local v, b = GetBuildInfo(); return tostring(v) .. "." .. tostring(b) end)
local BOOT = TN(function() return time() end) or 0
local padNow                -- the pad's geometry, for a document with no window in it
local QTY_MAX = 40          -- one `iqty` column per count
local GUARD = 0.5           -- seconds: no vendor action this soon after the window opened or the last one

local ROLL_MAX = 4
local HEAL = {}                 -- the self-heal cell's column (assignSlots sits above `local N`)
local ROLLCELLS = {}            -- rollID -> { need, greed, pass, de, transmog } cells (Dialogs.lua: WC.rollCells)
-- THE GUILD CHARTER (the user, 2026-10-09: "Yes, next"): the game's PetitionFrame, on PETITION_SHOW. What its own window shows
-- (read from the client's petitionframe.lua): the charter's title, the guild master, the instructions, the signatures up to the
-- minimum ("Not yet signed" after them); Sign (not the originator), Request Signature + Rename (the originator). Its buttons are
-- the GAME'S, pressed by secure clicks (Dialogs d18: touch cells and A as a binding click), so its own confirm / rename boxes come
-- from its own code; Close = ClosePetition, as the other windows close.
function LOOT.collectPetition()
    local p = { npc = { name = TS(function() return UnitName("npc") end) } }
    local r = R(function() return GetPetitionInfo() end)
    if r then
        p.kind, p.title, p.body, p.max, p.master, p.mine, p.min = PS(r[1]), PS(r[2]), PS(r[3]), PN(r[4]), PS(r[5]), PB(r[6]) or nil, PN(r[7])
    end
    local n = TN(function() return GetNumPetitionNames() end) or 0
    p.signed = n
    p.names = {}
    for i = 1, math.min(math.max(p.min or 0, n), 20) do
        p.names[#p.names + 1] = (i <= n) and (TS(function() return (GetPetitionNameInfo(i)) end) or "?") or ""
    end
    p.instructions = TS(function() return PetitionFrameInstructions:GetText() end)
    local function st(name)
        local b = _G[name]
        if type(b) ~= "table" or TB(function() return b:IsShown() end) ~= true then return nil end
        return TB(function() return b:IsEnabled() end) ~= false
    end
    p.btn = { sign = st("PetitionFrameSignButton"), offer = st("PetitionFrameRequestButton"), rename = st("PetitionFrameRenameButton") }
    if type(WC.dialogs) == "table" and type(WC.dialogs.petCells) == "function" then
        local ok, c = pcall(WC.dialogs.petCells)
        if ok and type(c) == "table" then p.cells = c end
    end
    return p
end

local function assignSlots(doc)
    local pad = WC.pad
    if not (pad and pad.slotFor and pad.retain and pad.describe) then return end
    local present = { ipick = {}, iact = {}, ibuy = {}, isell = {} }
    local function slot(ns, key)
        present[ns][key] = true
        local ok, col = pcall(pad.slotFor, ns, key)
        if ok then return col end
    end
    local any = false
    local g, q, m = doc.gossip, doc.quest, doc.merchant
    -- asked for first and kept for the whole session: its column never goes to anything else
    local trustCol = slot("iact", "trust")
    slot("iact", "alive")       -- the app's sign of life for hidden dialogs (Dialogs.lua reads its column)
    -- the app's no-action self-heal for a stuck "npc" hold (Home, 2026-09-30: one rule for every room)
    HEAL.col = slot("iact", "release npc")
    -- LOOT ROLLS: one cell per roll the game allows, on isell (the vendor's sell list and a roll seldom meet)
    ROLLCELLS = {}
    local okIds, ids = CALL(function() return GetActiveLootRollIDs() end)
    if okIds and type(ids) == "table" then
        for k, id in ipairs(ids) do
            id = TN(function() return id end)
            if id and k <= ROLL_MAX then
                local r = R(function() return GetLootRollItemInfo(id) end)
                if r and real(r[2]) then
                    local c = { pass = slot("isell", "roll " .. id .. " pass") }
                    if PB(r[6]) then c.need = slot("isell", "roll " .. id .. " need") end
                    if PB(r[7]) then c.greed = slot("isell", "roll " .. id .. " greed") end
                    if PB(r[8]) then c.de = slot("isell", "roll " .. id .. " de") end
                    if PB(r[13]) then c.transmog = slot("isell", "roll " .. id .. " transmog") end
                    ROLLCELLS[id] = c
                end
            end
        end
    end
    -- capture v5: the bar's Select listener, switched on / off by the app (Home's bar selector)
    local selOn, selOff = slot("iact", "sel on"), slot("iact", "sel off")
    -- r-loot-on-top: Home > Settings' switch (HomeLootSwitch); `top` is the add-on's word, the row shows it
    local ltOn, ltOff = slot("iact", "loottop on"), slot("iact", "loottop off")
    if GB and trustCol then
        doc.proof = { gb = GB, cv = CAPV, boot = BOOT, col = trustCol }
        if selOn and selOff then doc.proof.selc = { on = selOn, off = selOff } end
        if ltOn and ltOff then doc.proof.lootc = { on = ltOn, off = ltOff, top = LOOT.top() } end
    end
    if g then
        any = true
        for _, o in ipairs(g.options) do if o.id then o.slot = slot("ipick", "opt " .. o.id) end end
        for _, e in ipairs(g.available) do if e.id then e.slot = slot("ipick", "take " .. e.id) end end
        for _, e in ipairs(g.active) do if e.id then e.slot = slot("ipick", "turnin " .. e.id) end end
    end
    if q then
        any = true
        if q.stage == "greeting" then
            for _, e in ipairs(q.available) do e.slot = slot("ipick", "pick avail " .. e.i) end
            for _, e in ipairs(q.active) do e.slot = slot("ipick", "pick active " .. e.i) end
        elseif q.id then
            q.act = {}
            if q.stage == "detail" then
                q.act.accept = slot("iact", "accept " .. q.id)
                q.act.decline = slot("iact", "decline " .. q.id)
            elseif q.stage == "progress" then
                q.act.continue = slot("iact", "continue " .. q.id)
            elseif q.stage == "complete" then
                local choices = q.rewards and q.rewards.choices or {}
                if #choices == 0 then
                    q.act.reward = slot("iact", "reward " .. q.id)
                else
                    for _, c in ipairs(choices) do c.slot = slot("iact", "reward " .. q.id .. " " .. c.i) end
                end
            end
            -- every reward item can be LOOKED at (its tooltip), on any page that shows it
            if type(q.rewards) == "table" then
                for _, kind in ipairs({ "choices", "items" }) do
                    for _, c in ipairs(q.rewards[kind] or {}) do
                        if c.i then c.look = slot("ipick", "look qitem " .. (kind == "choices" and "choice" or "reward") .. " " .. c.i) end
                    end
                end
            end
        end
    end
    if m then
        any = true
        -- LOOK cells (a finger's selection asks for the tooltip; nothing happens in the game) live on the
        -- `ipick` row: the pad is full (38/38, 2026-09-30), and the vendor uses no other ipick cell. Handed
        -- out buy list first, then buyback, then the bags: an item past the row's 40 columns has none.
        m.act = { repair = slot("iact", "repair"), junk = slot("iact", "junk") }
        -- the app reads this to know that the quantity row exists (column = count - 1)
        if pad.rows and pad.rows.iqty then m.qtyMax = QTY_MAX end
        for _, it in ipairs(m.items) do
            if it.id then
                it.slot = slot("ibuy", "buy " .. it.i .. " " .. it.id)
                it.look = slot("ipick", "look buy " .. it.i .. " " .. it.id)
            end
        end
        for _, it in ipairs(m.buyback) do
            if it.id then
                it.slot = slot("iact", "back " .. it.i .. " " .. it.id)
                it.look = slot("ipick", "look back " .. it.i .. " " .. it.id)
            end
        end
        for _, it in ipairs(m.sell) do
            it.slot = slot("isell", "sell " .. it.bag .. " " .. it.s .. " " .. it.id)
            it.look = slot("ipick", "look sell " .. it.bag .. " " .. it.s .. " " .. it.id)
        end
    end
    local au = doc.auction
    if au then
        any = true
        au.act = { browse = slot("iact", "ahtab browse"), owned = slot("iact", "ahtab owned"), back = slot("iact", "ahback"),
                   buy = slot("iact", "ahbuy"), less = slot("iact", "ahqty less"), more = slot("iact", "ahqty more") }
        if au.tab ~= "owned" then
            for _, c in ipairs(au.cats) do c.slot = slot("ipick", "ahcat " .. c.i) end
        end
        if au.item then
            for _, r in ipairs(au.item.rows) do if not au.item.commodity then r.slot = slot("ibuy", "ahrow " .. r.i) end end
        elseif au.results then
            for _, r in ipairs(au.results) do r.slot = slot("ibuy", "ahres " .. r.i) end
        end
    end
    local lt = doc.loot
    if lt then
        any = true
        for _, it in ipairs(lt.items) do it.slot = slot("ibuy", "loot " .. it.i); it.look = slot("ipick", "look loot " .. it.i) end
        lt.act = { all = slot("iact", "lootall"), back = slot("iact", "lootback"), no = slot("iact", "lootno") }
        if lt.space then
            lt.space.cells = {}
            for _, e in ipairs(lt.space.bags) do
                lt.space.cells[tostring(e.bag)] = { del = slot("isell", "lootdelc " .. e.bag), rare = slot("isell", "lootdelr " .. e.bag),
                                                    look = slot("ipick", "lookbag " .. e.bag) }
            end
        end
    end
    local qs = doc.quest and doc.quest.space
    if qs then
        any = true
        qs.cells = {}
        for _, e in ipairs(qs.bags) do
            qs.cells[tostring(e.bag)] = { del = slot("isell", "lootdelc " .. e.bag), rare = slot("isell", "lootdelr " .. e.bag),
                                          look = slot("ipick", "lookbag " .. e.bag) }
        end
        qs.act = { back = slot("iact", "lootback"), no = slot("iact", "lootno") }
    end
    local bk = doc.bank
    if bk then
        any = true
        -- a finger: the slot travels as the count (`qty`, the vendor's cell) right before ONE container cell
        bk.act = { buy = slot("iact", "bkbuy"), box = slot("iact", "bkbox"), cancel = slot("iact", "bkcancel"),
                   char = slot("iact", "bktab char"), acct = bk.account and slot("iact", "bktab acct") or nil }
        bk.cells = {}
        local function cont(bag)
            bk.cells[tostring(bag)] = { go = slot("ibuy", "bkdo " .. bag), pick = slot("isell", "bkpick " .. bag),
                                        put = slot("isell", "bkput " .. bag) }
        end
        if bk.bag then cont(bk.bag) end
        for _, e in ipairs(bk.bags) do cont(e.bag) end
        bk.pageCells = {}
        for pg = 1, bk.pages do bk.pageCells[pg] = slot("iact", "bkpage " .. pg) end
    end
    local tx = doc.taxi
    if tx then
        any = true
        for _, n in ipairs(tx.nodes) do if n.state == "reachable" then n.slot = slot("ibuy", "taxi " .. n.i) end end
    end
    local ml = doc.mail
    if ml then
        any = true
        for _, l in ipairs(ml.mails) do l.slot = slot("ibuy", "mail " .. l.i) end
        if ml.open then
            local o = ml.open
            o.act = { all = slot("isell", "mailall " .. o.i), money = slot("isell", "mailmoney " .. o.i), back = slot("iact", "mailback") }
            for _, it in ipairs(o.items) do it.slot = slot("isell", "mailtake " .. o.i .. " " .. it.a) end
        end
    end
    if doc.petition then any = true end
    local st = doc.stable
    if st then
        any = true
        for _, pt in ipairs(st.pets) do pt.slot = slot("ibuy", "pet " .. pt.i) end
        st.act = { box = slot("iact", "stablebox"), buy = slot("iact", "stableslot") }
    end
    local tr = doc.trainer
    if tr then
        any = true
        tr.act = { spell = (not trProfession()) and slot("iact", "trspell") or nil, menu = slot("iact", "trmenu"), available = slot("iact", "trf available"), unavailable = slot("iact", "trf unavailable"),
                   used = slot("iact", "trf used"), categorize = slot("iact", "trf categorize") }
        for _, sv in ipairs(tr.services) do
            if sv.cat == "header" then sv.slot = slot("ipick", "trhead " .. sv.i) end
            if sv.cat ~= "header" then
                sv.slot = slot("ibuy", "train " .. sv.i)
                sv.look = slot("ipick", "look train " .. sv.i)
            end
        end
    end
    if any then doc.act = { close = slot("iact", "close") } end
    local ok, d = pcall(pad.describe)
    padNow = ok and d or nil
    if any then doc.pad = padNow end
    for _, ns in ipairs(NS) do pcall(pad.retain, ns, present[ns]) end
end

-- ---------------------------------------------------------------- controller: what can be highlighted

-- N holds the whole controller state. `zones` is rebuilt from every document: an ordered list of
-- grids, each { name, cols, items = { { id =, ok = } ... } }. An id is the command a tap would
-- send, or a name for the things that are not commands ("tab buy", "go", "complete").
local N = { mode = false, holding = false, n = 0, sig = nil, zones = {},
            cur = nil, tab = "buy", sel = nil, qty = 1, most = 1, pick = nil, scroll = 0,
            offer = nil, last = nil, lost = 0, lostKey = nil, lostTo = nil, lostBy = {},
            via = nil, frameSeen = false, hid = 0,
            trusted = false,        -- the app handed the remembered proof back (ACT.trust)
            heard = {}, heardN = 0, -- buttons that are not ours, as the frame heard them (read-only)
            rel = nil,              -- the last time the controller was handed back: { n, by, who }
            relN = 0, backAt = nil, -- how often since the load; when B last asked for a close
            distrust = nil }        -- the watchdog's reason; set once, it stands until the next load

local function navZone(name, cols) return { name = name, cols = cols, items = {} } end
N.MS_COLS = 6               -- Make Space's tiles per row: the app draws exactly this many (InteractView.MS_COLS)
local function navAdd(z, id, ok) z.items[#z.items + 1] = { id = id, ok = ok ~= false } end

-- where an id sits: zone index, item index
local function navFind(id)
    if not id then return nil end
    for zi, z in ipairs(N.zones) do
        for ii, it in ipairs(z.items) do
            if it.id == id then return zi, ii end
        end
    end
end

local function navZoneNamed(name)
    for zi, z in ipairs(N.zones) do if z.name == name then return z, zi end end
end

local function navFirst(z)
    if not z then return nil end
    for _, it in ipairs(z.items) do if it.ok then return it.id end end
end

-- how many purchases the quantity may reach: the limits of the game's own split-stack box
local function navMost(m, id)
    for _, it in ipairs(m.items) do
        if it.id and ("buy " .. it.i .. " " .. it.id) == id then
            if it.ext then return 1 end
            local stack = (it.stack and it.stack > 0) and it.stack or 1
            local most = math.max(1, math.floor((it.max or stack) / stack))
            if it.price and it.price > 0 and m.money then
                most = math.min(most, math.max(1, math.floor(m.money / it.price)))
            end
            if it.avail and it.avail > 0 then most = math.min(most, it.avail) end
            return math.min(most, QTY_MAX)
        end
    end
    return 1
end

-- Make Space's tiles, with where each one is drawn ({ c, r }: its column and its row, a bag starting a new row)
function N.msZone(sp)
    local z = navZone("msgrid", N.MS_COLS)
    N.msPos = {}
    local row = 0
    for _, e in ipairs(sp.bags or {}) do
        local ids = {}
        for _, it in ipairs(e.items) do ids[it.slot] = it.id end
        for sl = 1, e.slots do
            local id = "bag " .. e.bag .. " " .. sl .. " " .. (ids[sl] or 0)
            navAdd(z, id)
            N.msPos[id] = { c = (sl - 1) % N.MS_COLS, r = row + math.floor((sl - 1) / N.MS_COLS) }
        end
        row = row + math.ceil(e.slots / N.MS_COLS)
    end
    return z
end

function N.build(doc)
    local g, q, m = doc.gossip, doc.quest, doc.merchant
    local tr = doc.trainer
    local sig, first
    N.zones = {}
    N.offer = nil
    if q and q.space then
        -- MAKE SPACE for the quest: the same grid as the loot's
        sig = "lts"
        N.zones = { N.msZone(q.space) }
        first = navFirst(N.zones[1])
    elseif q and q.stage == "greeting" then
        sig = "l|" .. tostring(q.npc and q.npc.name)
        local z = navZone("list", (#q.active + #q.available > 4) and 2 or 1)
        z.major = "col"
        -- b-npc-quest-order: ready to turn in, then new, then in progress (the app draws them in this order)
        for _, e in ipairs(q.active) do if e.done then navAdd(z, "pick active " .. e.i) end end
        for _, e in ipairs(q.available) do navAdd(z, "pick avail " .. e.i) end
        for _, e in ipairs(q.active) do if not e.done then navAdd(z, "pick active " .. e.i) end end
        N.zones[1] = z
        first = navFirst(z)
    elseif q and q.id then
        sig = "q|" .. q.stage .. "|" .. q.id
        local choices = (q.stage == "complete" and q.rewards and q.rewards.choices) or {}
        if #choices > 0 then
            local z = navZone("choices", 1)
            for _, c in ipairs(choices) do navAdd(z, "reward " .. q.id .. " " .. c.i) end
            N.zones[#N.zones + 1] = z
        end
        local z = navZone("act", 2)
        if q.stage == "detail" then
            navAdd(z, "decline " .. q.id); navAdd(z, "accept " .. q.id)
            first = "accept " .. q.id
            N.offer = q.id
        elseif q.stage == "progress" then
            navAdd(z, "continue " .. q.id, q.completable ~= false)
            first = "continue " .. q.id
        else
            -- the turn-in (user, 2026-09-30: "highlighting the complete quest button is ugly, if there is a reward,
            -- after selecting it then the quest complete button should only have the A glyph"): with rewards to
            -- choose, the cursor stays on them (Complete is never a stop); with none, it rests on Complete unseen
            navAdd(z, "complete", #choices == 0)
            first = (#choices > 0) and navFirst(N.zones[1]) or "complete"
        end
        N.zones[#N.zones + 1] = z
        if sig ~= N.sig then N.pick = (#choices == 1) and ("reward " .. q.id .. " " .. choices[1].i) or nil end
    elseif m then
        sig = "m|" .. tostring(m.npc and m.npc.name)
        if sig ~= N.sig then N.tab = "buy" end
        local tabs = navZone("tabs", 4)
        navAdd(tabs, "tab buy"); navAdd(tabs, "tab sell"); navAdd(tabs, "tab back")
        -- the design's row: Buy, Sell, Buyback, then Repair and Junk when there is something to do
        tabs.cols = 5
        if m.canRepair and m.needsRepair then navAdd(tabs, "repair") end
        if (m.junk or 0) > 0 then navAdd(tabs, "junk") end
        local grid = navZone("grid", 2)
        if N.tab == "buy" then
            for _, it in ipairs(m.items) do if it.id then navAdd(grid, "buy " .. it.i .. " " .. it.id) end end
        elseif N.tab == "sell" then
            for _, it in ipairs(m.sell) do navAdd(grid, "sell " .. it.bag .. " " .. it.s .. " " .. it.id) end
        else
            for _, it in ipairs(m.buyback) do if it.id then navAdd(grid, "back " .. it.i .. " " .. it.id) end end
        end
        -- ONE PRESS (user, 2026-09-29 11:23: "instead of doing A to select, and then buy, just skip
        -- straight to buy with A, and same thing on Sell"): no picking step, no Buy button position
        N.zones = { tabs, grid }
        -- the cursor starts on what is already SELECTED: the tab that is open (user, 2026-09-30, LATER 19:
        -- "when opening to a page that has tabs and something already highlighted because its selected, that
        -- feels like a really great natural starting point for the cursor")
        first = "tab " .. N.tab
    elseif doc.loot and doc.loot.space then
        -- MAKE SPACE: the cursor walks the general bags; its key is the bag item's own tooltip key
        sig = "lts"
        N.zones = { N.msZone(doc.loot.space) }
        first = navFirst(N.zones[1])
    elseif doc.loot then
        local lt = doc.loot
        sig = "lt"
        local z = navZone("list", 1)
        for _, it in ipairs(lt.items) do navAdd(z, "loot " .. it.i, not it.locked) end
        N.zones = { z }
        first = navFirst(z)
    elseif doc.bank then
        local bk = doc.bank
        sig = "bk|" .. (bk.acct and "a" or "c") .. "|" .. tostring(bk.page)
        local zb = navZone("bkgrid", 7)
        for sl = 1, (bk.n or 0) do navAdd(zb, "bkslot " .. bk.bag .. " " .. sl) end
        local zt
        if bk.pages > 1 then
            zt = navZone("bktabs", 1)
            for pg = 1, bk.pages do navAdd(zt, "bkpage " .. pg) end
        end
        local zg = navZone("bkbags", 7)
        for _, e in ipairs(bk.bags) do for sl = 1, e.slots do navAdd(zg, "bkslot " .. e.bag .. " " .. sl) end end
        N.zones = zt and { zb, zt, zg } or { zb, zg }
        first = navFirst(zb) or navFirst(zg)
    elseif doc.taxi then
        local tx = doc.taxi
        sig = "tx|" .. tostring(tx.map)
        local z = navZone("taxi", 1)
        for _, n in ipairs(tx.nodes) do navAdd(z, "taxi " .. n.i) end
        N.zones = { z }
        first = (tx.cur and ("taxi " .. tx.cur)) or navFirst(z)
    elseif doc.auction then
        local au = doc.auction
        local tabs = navZone("tabs", 2)
        navAdd(tabs, "ahtab browse"); navAdd(tabs, "ahtab owned")
        local zr = navZone("ahright", 1)
        N.ahSel = nil
        if au.tab == "owned" then
            -- your auctions: read only; the cursor walks them (A does nothing)
            sig = "ah|owned"
            for _, o in ipairs(au.owned or {}) do navAdd(zr, "ahown " .. o.i) end
            N.zones = { tabs, zr }
            first = navFirst(zr) or "ahtab owned"
        else
            local zc = navZone("ahcats", 1)
            for _, c in ipairs(au.cats) do
                navAdd(zc, "ahcat " .. c.i)
                if c.sel then N.ahSel = "ahcat " .. c.i end
            end
            if au.item then
                sig = "ah|item|" .. tostring(au.item.id)
                if au.item.commodity then navAdd(zr, "ahbuy")          -- a commodity: A buys; X / Y the quantity
                else for _, r in ipairs(au.item.rows) do navAdd(zr, "ahrow " .. r.i, (r.price or 0) > 0 and not r.mine) end end
                first = navFirst(zr) or N.ahSel or navFirst(zc)
            else
                -- browsing: the cursor stays on the category asked for (right goes to its results); back from an item,
                -- on the result that was opened
                sig = "ah|b|" .. table.concat(au.path or {}, ".")
                for _, r in ipairs(au.results or {}) do navAdd(zr, "ahres " .. r.i) end
                local back
                for _, r in ipairs(au.results or {}) do if r.i == AH.lastRes then back = "ahres " .. r.i end end
                AH.lastRes = nil
                first = back or N.ahSel or navFirst(zc)
            end
            N.zones = { tabs, zc, zr }
        end
    elseif doc.mail then
        local ml = doc.mail
        -- UI Art's MAIL_SPEC 4: the letters on the left (always); an open letter's attachments, then its money, on the right
        local z = navZone("mlist", 1)
        for _, l in ipairs(ml.mails) do navAdd(z, "mail " .. l.i) end
        local za = navZone("mattach", 4)
        if ml.open then
            local o = ml.open
            local hdr
            for _, l in ipairs(ml.mails) do if l.i == o.i then hdr = l end end
            for _, it in ipairs(o.items) do navAdd(za, "mailtake " .. o.i .. " " .. it.a) end
            if hdr and (hdr.money or 0) > 0 then navAdd(za, "mailmoney " .. o.i) end
            sig = "mo|" .. o.i
            first = navFirst(za) or ("mail " .. o.i)
        else
            sig = "ml"
            -- back from a letter: the cursor is on that letter again (LATER 19: start on what was selected)
            first = (MAIL.back and ("mail " .. MAIL.back)) or navFirst(z)
            MAIL.back = nil
        end
        N.zones = { z, za }
        if first == nil then first = "none" end
    elseif doc.petition then
        local pt = doc.petition
        sig = "pe|" .. tostring(pt.title)
        local z = navZone("petacts", 3)
        if pt.btn.sign ~= nil then navAdd(z, "petsign", pt.btn.sign) end
        if pt.btn.offer ~= nil then navAdd(z, "petoffer", pt.btn.offer) end
        if pt.btn.rename ~= nil then navAdd(z, "petrename", pt.btn.rename) end
        N.zones = { z }
        first = navFirst(z) or "none"
    elseif doc.stable then
        local st = doc.stable
        sig = "s|" .. tostring(st.npc and st.npc.name)
        local z = navZone("grid", 4)
        -- a locked (red) slot: the cursor may rest on it; A does nothing there (UI Art's STABLE_SPEC 6)
        for _, pt in ipairs(st.pets) do navAdd(z, "pet " .. pt.i) end
        N.zones = { z }
        first = "pet 1"
        -- the pet picked to move, and the armed purchase, belong to THIS window
        if sig ~= N.sig then N.petFrom, N.armAt, N.stableBox = nil, nil, nil end
    elseif tr then
        sig = "t|" .. tostring(tr.npc and tr.npc.name)
        if sig ~= N.sig then N.trFold, N.trMenu = {}, nil end
        N.trFold = N.trFold or {}
        if N.trMenu then
            -- the Settings menu (the game's, flattened): the three filters and Categorize
            local zm = navZone("trmenu", 1)
            for _, k in ipairs({ "available", "unavailable", "used" }) do navAdd(zm, "trf " .. k) end
            navAdd(zm, "trf categorize")
            N.zones = { zm }
            first = "trf available"
            N.trLines = nil
        else
            local z = navZone("trlist", 2)
            local lines, firstAvail = {}, nil
            local function svc(sv)
                navAdd(z, "train " .. sv.i)
                if not firstAvail and sv.cat == "available" then firstAvail = "train " .. sv.i end
            end
            if tr.step then
                for _, sv in ipairs(tr.services) do
                    if sv.i == tr.step then svc(sv); lines[#lines + 1] = { "train " .. sv.i } end
                end
            end
            local head, row = nil, nil
            for _, sv in ipairs(tr.services) do
                if sv.cat == "header" then
                    head = sv.i
                    navAdd(z, "trhead " .. sv.i)
                    lines[#lines + 1] = { "trhead " .. sv.i }
                    row = nil
                elseif sv.i ~= tr.step and not (head and N.trFold[head]) then
                    svc(sv)
                    if row and #row < 2 then row[#row + 1] = "train " .. sv.i
                    else row = { "train " .. sv.i }; lines[#lines + 1] = row end
                end
            end
            N.trLines = lines
            N.zones = { z }
            first = firstAvail or navFirst(z)
        end
    elseif g then
        local ids = {}
        local z = navZone("list", 1)
        z.major = "col"
        -- b-npc-quest-order: ready to turn in, then new, then in progress (the app draws them in this order)
        for _, e in ipairs(g.active) do if e.done then navAdd(z, "turnin " .. tostring(e.id), e.id ~= nil) end end
        for _, e in ipairs(g.available) do navAdd(z, "take " .. tostring(e.id), e.id ~= nil) end
        for _, e in ipairs(g.active) do if not e.done then navAdd(z, "turnin " .. tostring(e.id), e.id ~= nil) end end
        for _, o in ipairs(g.options) do
            navAdd(z, "opt " .. tostring(o.id), o.id ~= nil and (o.status or 0) == 0)
            ids[#ids + 1] = tostring(o.id)
        end
        if #z.items > 4 then z.cols = 2 end
        sig = "g|" .. tostring(g.npc and g.npc.name) .. "|" .. table.concat(ids, ",")
        N.zones[1] = z
        first = navFirst(z)
    end

    if sig ~= N.sig then
        -- a different window: start where the eye starts, with nothing picked
        N.sig = sig
        N.cur, N.sel, N.qty, N.scroll = first, nil, 1, 0
        N.openAt = TN(function() return GetTime() end)
        N.xHold = nil
        if not (q and q.id) then N.pick = nil end
    end
    if not sig then return nil end

    -- the window changed under the highlight (an item sold, a list refreshed): keep what still exists
    if N.sel and not navFind(N.sel) then
        N.sel = nil
        if N.cur == "go" then N.cur = nil end
    end
    if N.cur == "go" and not N.sel then N.cur = nil end
    if N.pick and not navFind(N.pick) then N.pick = nil end
    local zi, ii = navFind(N.cur)
    if not zi or not N.zones[zi].items[ii].ok then
        -- the highlighted item went (sold, bought out): keep the PLACE, not the start (user, 2026-09-30:
        -- "when selling, the cursor should not return to the beginning, just shift the Items on the list
        -- to fill the blanks and keep the cursor where it is")
        local kept = nil
        if N.curAt and N.curAt.sig == sig then
            for _, z in ipairs(N.zones) do
                if z.name == N.curAt.zone and #z.items > 0 then
                    local j = math.min(N.curAt.idx, #z.items)
                    while j >= 1 and not z.items[j].ok do j = j - 1 end
                    if j >= 1 then kept = z.items[j].id end
                end
            end
        end
        N.cur = kept or first
        zi, ii = navFind(N.cur)
    end
    if zi and ii then N.curAt = { sig = sig, zone = N.zones[zi].name, idx = ii } end
    -- i108: A on the charter's button under the cursor = a binding click of Dialogs' secure button that presses it
    local petA = nil
    if doc.petition then
        local nm = ({ petsign = "WowCompanionPetSign", petoffer = "WowCompanionPetOffer", petrename = "WowCompanionPetRename" })[N.cur or ""]
        if nm and type(_G[nm]) == "table" then petA = nm end
    end
    if petA ~= N.petA then
        N.petA = petA
        if N.holding and N.padBind and TB(function() return InCombatLockdown() end) ~= true then N.padBind("PAD1") end      -- (padBind: declared further down)
    end
    -- the vendor's highlighted item IS the one A acts on (sel, for the app's strip)
    local c = type(N.cur) == "string" and N.cur or ""
    N.sel = (m and (c:match("^buy ") or c:match("^sell ") or c:match("^back "))) and c or nil
    if N.qtyFor ~= N.sel then N.qty, N.qtyFor = 1, N.sel end
    N.most = (m and N.sel and N.tab == "buy") and navMost(m, N.sel) or 1
    if N.qty > N.most then N.qty = N.most end
    if N.qty < 1 then N.qty = 1 end

    local rects = (WC.shield and WC.shield.rects()) or {}     -- defined further down
    return { on = N.holding, n = N.n, last = N.last, via = N.via, hid = N.hid > 0 and N.hid or nil,
             shield = #rects > 0 and rects or nil,
             proven = N.frameSeen or nil, lost = N.lost > 0 and N.lost or nil,
             lostKey = N.lostKey, lostTo = N.lostTo, lostBy = next(N.lostBy) and N.lostBy or nil,
             offer = N.offer and true or nil,
             cur = N.cur, tab = m and N.tab or nil, sel = N.sel, ts = (N.ts or 0) ~= 0 and N.ts or nil,
             from = N.petFrom, armed = N.armAt and true or nil, xh = N.xHold or nil, box = N.stableBox or nil,
             folds = N.trFoldList and N.trFoldList() or nil, menu = N.trMenu or nil,
             qty = (m and N.sel) and N.qty or nil, most = (m and N.sel) and N.most or nil,
             pick = N.pick, scroll = N.scroll > 0 and N.scroll or nil }
end

local function buildDoc()
    tally.secret, tally.threw = 0, 0
    disagreed = false
    local doc = { type = "interact", iv = IV }
    if isOpen("gossip") then doc.gossip = collectGossip() end
    if isOpen("quest") and questStage then doc.quest = collectQuest() end
    if doc.quest and LOOT.ms == "quest" and LOOT.full then doc.quest.space = LOOT.buildSpace() end
    if isOpen("merchant") then doc.merchant = collectMerchant() end
    if WINDOWS.trainer and isOpen("trainer") then doc.trainer = collectTrainer() end
    if WINDOWS.stable and isOpen("stable") then doc.stable = collectStable() end
    if WINDOWS.petition and isOpen("petition") then doc.petition = LOOT.collectPetition() end
    N.lastStable = doc.stable
    if WINDOWS.mail and isOpen("mail") then doc.mail = collectMail() end
    if WINDOWS.auction and isOpen("auction") then doc.auction = collectAuction() end
    if WINDOWS.taxi and isOpen("taxi") then doc.taxi = collectTaxi() end
    if WINDOWS.bank and isOpen("bank") then doc.bank = collectBank() end
    if WINDOWS.loot and isOpen("loot") then doc.loot = collectLoot() end
    -- LOOTWIN_SPEC 7: the item just looted into the freed tile, for 3 s (it outlives the window when it was the last item)
    local ld = LOOT.landing
    if ld and (TN(function() return GetTime() end) or 0) - ld.at < 3 then
        doc.landing = { n = ld.n, from = ld.from, bag = ld.bag, slot = ld.slot, icon = ld.icon, name = ld.name, count = ld.count,
                        q = ld.q, last = ld.last, quest = ld.quest, items = ld.items, tiles = ld.tiles }
    end
    doc.sec = { secret = tally.secret, threw = tally.threw }
    doc.combat = TB(function() return InCombatLockdown() end) or nil
    assignSlots(doc)
    -- The controller takes the buttons only while a window is open and the companion is in charge.
    -- sync() binds or lets go BEFORE the document is built, so `nav.on` is what is true now.
    local open = (doc.gossip or doc.quest or doc.merchant or doc.trainer or doc.stable or doc.mail or doc.auction or doc.taxi or doc.bank or doc.loot or doc.petition) and true or false
    if N.sync then N.sync(open, doc) end
    if N.grantWait then N.grantWait() end       -- v11: the queued take, once nobody holds
    doc.rel = N.rel
    -- (G is declared further down: the holder is read through the capture's own state())
    if N.lend and WC.capture and WC.capture.state and WC.capture.state().holder == N.lend then doc.lend = N.lend end
    -- who holds the controller (Home, 2026-09-30: to free a generic "pad:<room>" guest left holding for a room no
    -- longer on screen): "npc", a guest's id, or absent
    if WC.capture and WC.capture.state then doc.holder = WC.capture.state().holder end
    if N.capDoc then N.capDoc(doc) end      -- v18: b2 = what B is bound to (declared further down)
    -- v8: a room holds through this fight (Home: the room flashes red; `n` counts its "in combat" refusals: a pulse each)
    -- (G is declared further down: through state())
    if WC.capture and WC.capture.state then
        local st = WC.capture.state()
        if st.combat then doc.fight = { n = st.fightN or 0 } end
    end
    -- v9: while a room holds through a fight, EVERY leave the app makes is one tap on this cell (B is the leave by itself)
    if (doc.fight or (N.fight and N.holding and doc.combat)) and N.fc then doc.fightLeave = { row = N.fc.row, col = N.fc.col } end
    -- the generic leave cell, whenever a room holds (in a fight it is the same cell as fightLeave)
    if N.leaveAim and doc.holder and doc.holder ~= "npc" then
        if not doc.combat then N.leaveAim() end
        if N.lc then doc.leave = { row = N.lc.row, col = N.lc.col } end
    end
    -- v11: a queued take (granted when the holder lets go; Home may show it)
    if N.wait then doc.wait = { ["for"] = N.wait.id } end
    -- v10: a take waiting for the app's ENTER tap
    if N.pend and N.ra then doc.enter = { row = N.ra.erow, col = N.ra.ecol, ["for"] = N.pend.id, n = N.pend.n } end
    if doc.holder == "npc" then
        doc.heal = HEAL.col             -- the app's self-heal cell, only while an "npc" hold exists
        if not doc.pad then doc.pad = padNow end   -- with no window in the document the app still needs the pad to tap it
    end
    if N.selSync then N.selSync() end
    if N.bar and (N.bar.want or N.bar.bound) then doc.sel = { want = N.bar.want or nil, on = N.bar.bound or nil, n = N.bar.n > 0 and N.bar.n or nil } end
    -- WHERE THE NEXT B LEADS (user, 2026-09-29: the Home button shows B whenever B would take the
    -- player Home). "home" | "back" | absent = the controller is not ours. A game dialog on top is
    -- answered by the game's own B first, and the screen then stays on its room: "back".
    if type(WC.bnextOf) == "function" then doc.bnext = WC.bnextOf() end
    if doc.proof then
        local p = doc.proof
        p.seen, p.trusted, p.bad = N.frameSeen or nil, N.trusted or nil, N.distrust
        if N.heardN > 0 then
            p.heard = {}
            for c in pairs(N.heard) do p.heard[#p.heard + 1] = c end
            table.sort(p.heard)
            -- which buttons the game uses as Shift / Ctrl / Alt: those are never listed, by design
            p.mods = { shift = TS(function() return GetCVar("GamePadEmulateShift") end),
                       ctrl = TS(function() return GetCVar("GamePadEmulateCtrl") end),
                       alt = TS(function() return GetCVar("GamePadEmulateAlt") end) }
        end
        -- the app needs the pad to tap `trust`, and no window is open to bring it
        if N.mode and not open and not (N.frameSeen or N.trusted or N.distrust) then doc.pad = padNow end
    end
    if N.mode then
        if open then doc.nav = N.build(doc) else N.sig = nil end
        doc.mode = true
    elseif not open then
        N.sig = nil
    end
    -- THE TOOLTIP (after the nav: the highlight of THIS window). The controller's highlight wins; else
    -- the finger's selection (N.look), for as long as its window is open.
    if doc.merchant or doc.quest or doc.trainer or doc.auction or doc.loot then
        local key = (N.holding and N.mode and N.cur and N.canLook(N.cur)) and N.cur or N.look
        -- the auction house: an item opened shows its tooltip without a cursor too (a finger opens it at once)
        if not key and doc.auction and AH.key then key = "ahbuy" end
        if key ~= N.tsKey then N.ts, N.tsKey = 0, key end
        if key then doc.tip = N.tipOf(key) end
        -- the loot window compares AUTOMATICALLY (X is Loot All there): hold-X belongs to the other windows
        N.tipCmp = (doc.tip and doc.tip.cmp and not doc.loot) and true or false
    else
        N.look = nil
    end
    if N.capCheck then N.capCheck() end      -- v13: a hold taken / let go inside the document's own sync
    if N.backSync then N.backSync() end      -- v14: B bound to the guest's back only while it closes a shown window
    return doc
end

-- ---------------------------------------------------------------- the census (read-only)
-- WHICH windows and dialogs this client really opens (user, 2026-09-28: every window is to come
-- to the bottom screen; the order follows what is used). Two secure hooks, which run AFTER the
-- game's own function and cannot change it, add 1 to a count in memory. Nothing is written per
-- event: the counts ride in the heartbeat's document, inside the flush the heartbeat makes anyway,
-- and only when a count changed. "p:" = a panel (ShowUIPanel), "d:" = a dialog (StaticPopup_Show).
local CENSUS_MAX, CENSUS_NAME = 60, 48
-- The log turns over and takes old documents with it (seen 2026-09-28: three minutes gone). So the
-- counts are sent again at every CENSUS_EVERY-th heartbeat, changed or not: bytes in a document
-- that is written anyway, never a flush of their own.
local CENSUS_EVERY = 8
local census = { n = 0, names = {}, dirty = false, dropped = 0, beats = 0 }
local function censusAdd(kind, name)
    name = PS(name)
    if not name or name == "" then return end
    local key = kind .. name:sub(1, CENSUS_NAME)
    if not census.names[key] then
        if census.n >= CENSUS_MAX then census.dropped = census.dropped + 1; return end
        census.n = census.n + 1
        census.names[key] = 0
    end
    census.names[key] = census.names[key] + 1
    census.dirty = true
end
if type(hooksecurefunc) == "function" then
    if type(ShowUIPanel) == "function" then
        pcall(hooksecurefunc, "ShowUIPanel", function(frame)
            pcall(function()
                if type(frame) == "table" and frame.GetName then censusAdd("p:", frame:GetName()) end
            end)
        end)
    end
    if type(StaticPopup_Show) == "function" then
        pcall(hooksecurefunc, "StaticPopup_Show", function(which)
            pcall(censusAdd, "d:", which)
        end)
    end
end

local beat = false          -- true while the heartbeat's document is built
local function emitNow(force)
    local doc = buildDoc()
    local counted = false
    if beat and census.n > 0 then
        census.beats = census.beats + 1
        counted = census.dirty or census.beats % CENSUS_EVERY == 0
    end
    if counted then
        doc.census = { n = census.names, dropped = census.dropped > 0 and census.dropped or nil }
    end
    local json = WC.encode(doc)
    if counted and type(json) == "string" then census.dirty = false end
    -- One follow-up look when the events and the frames disagree: it settles both a frame that
    -- showed late and a close event that never arrived. Once per event, so it cannot loop.
    if disagreed and recheckEv ~= ev then
        recheckEv = ev
        C_Timer.After(0.3, function() emitNow(false) end)
    end
    if json == lastJson and not force then return false end
    lastJson = json
    WC.send(json)
    WC.push()
    return true
end

-- Several events land in one frame (a gossip that hands over to a quest; a merchant that also
-- repairs): collect once, at the end of the frame.
local function emitSoon()
    if N.capCheck then N.capCheck() end      -- v13: tell the listeners at once when the holder changed (declared further down)
    if queued then return end
    queued = true
    C_Timer.After(0, function()
        queued = false
        emitNow(false)
    end)
end
LOOT.poke = function() N.n = N.n + 1; emitSoon() end     -- for LOOT.questFirst (declared above N and emitSoon)

-- ---------------------------------------------------------------- events

local function mark(slot, shown)
    ev = ev + 1
    if shown then shownAt[slot] = ev else hiddenAt[slot] = ev end
end

local ON = {
    GOSSIP_SHOW = function() mark("gossip", true) end,
    GOSSIP_CLOSED = function() mark("gossip", false) end,
    QUEST_GREETING = function() questStage = "greeting"; mark("quest", true) end,
    QUEST_DETAIL = function() questStage = "detail"; mark("quest", true) end,
    QUEST_PROGRESS = function() questStage = "progress"; mark("quest", true) end,
    QUEST_COMPLETE = function() questStage = "complete"; mark("quest", true) end,
    QUEST_FINISHED = function()
        if LOOT.ms == "quest" then LOOT.full, LOOT.ms, LOOT.retry, LOOT.hold, LOOT.rare = false, nil, nil, nil, nil end
        mark("quest", false)
    end,
    MERCHANT_SHOW = function() mark("merchant", true) end,
    MERCHANT_CLOSED = function() mark("merchant", false) end,
    TRAINER_SHOW = function() mark("trainer", true) end,
    TRAINER_CLOSED = function() mark("trainer", false) end,
    PET_STABLE_SHOW = function() mark("stable", true) end,
    PETITION_SHOW = function() mark("petition", true) end,
    PETITION_CLOSED = function() mark("petition", false) end,
    PET_STABLE_CLOSED = function() mark("stable", false) end,
    MAIL_SHOW = function() MAIL.open = nil; mark("mail", true) end,
    MAIL_CLOSED = function() MAIL.open = nil; mark("mail", false) end,
    AUCTION_HOUSE_SHOW = function() AH.path, AH.results, AH.key, AH.buy, AH.tab = {}, nil, nil, nil, "browse"; mark("auction", true) end,
    AUCTION_HOUSE_CLOSED = function() AH.buy = nil; mark("auction", false) end,
    TAXIMAP_OPENED = function() mark("taxi", true) end,
    START_LOOT_ROLL = function() if type(WC.dialogs) == "table" then pcall(WC.dialogs.look) end end,
    CANCEL_LOOT_ROLL = function() if type(WC.dialogs) == "table" then pcall(WC.dialogs.look) end end,
    CANCEL_ALL_LOOT_ROLLS = function() if type(WC.dialogs) == "table" then pcall(WC.dialogs.look) end end,
    TAXIMAP_CLOSED = function() mark("taxi", false) end,
    BANKFRAME_OPENED = function() BANK.page, BANK.hand, BANK.box, BANK.acct = 1, nil, false, false; mark("bank", true) end,
    BANKFRAME_CLOSED = function() BANK.hand, BANK.box = nil, false; mark("bank", false) end,
    LOOT_OPENED = function()
        LOOT.full, LOOT.bind, LOOT.running, LOOT.hold, LOOT.rare = false, false, false, nil, nil
        LOOT.try, LOOT.want = nil, nil
        mark("loot", true)
        -- the game's panel fade-in runs after our alpha 0: a document once it has finished puts it back (N.hide, STICKY)
        pcall(C_Timer.After, 0.35, emitSoon)
        pcall(C_Timer.After, 0.8, emitSoon)
    end,
    LOOT_CLOSED = function()
        LOOT.full, LOOT.bind, LOOT.running, LOOT.hold, LOOT.rare = false, false, false, nil, nil
        LOOT.try, LOOT.want = nil, nil
        mark("loot", false)
    end,
    -- a bind-on-pickup item asks first (the game's dialog, mirrored): Loot All stops there
    LOOT_BIND_CONFIRM = function() LOOT.bind = true end,
    UI_ERROR_MESSAGE = function(_, msg)
        -- the game's "Inventory is full" while its loot window is open: the make-space screen (user: "By itself")
        local full = msg ~= nil and msg == TS(function() return ERR_INV_FULL end)
        if full and shownAt.loot > hiddenAt.loot and not LOOT.top() then
            LOOT.full, LOOT.ms = true, nil
            LOOT.want = LOOT.try            -- the item that did not fit (none known: the first item left, at the delete)
        elseif full and isOpen("quest") and N.qtry and (TN(function() return GetTime() end) or 0) - N.qtry.at <= 2 then
            -- a quest giver's item does not fit: Make Space for the quest, then the same accept / reward again
            local t = N.qtry
            local rt = { cmd = t.cmd, a = t.a, c = t.c, title = TS(function() return GetTitleText() end) }
            if t.cmd == "reward" and t.c and t.c > 0 then
                rt.name = TS(function() return (GetQuestItemInfo("choice", t.c)) end)
                local r = R(function() return GetQuestItemInfo("choice", t.c) end)
                rt.icon, rt.count, rt.q = r and PN(r[2]), r and PN(r[3]), r and PN(r[4])
            end
            LOOT.full, LOOT.ms, LOOT.retry, LOOT.hold, LOOT.rare = true, "quest", rt, nil, nil
            N.qtry = nil
            N.n = N.n + 1
            emitSoon()
        end
    end,
    -- the game's own total for a commodity purchase: the purchase is armed with it
    COMMODITY_PRICE_UPDATED = function(unit, total)
        if AH.buy and AH.buy.kind == "commodity" and AH.buy.waiting then
            AH.buy.unit, AH.buy.total = PN(unit), PN(total)
            AH.buy.waiting, AH.buy.armedAt = nil, TN(function() return GetTime() end)
        end
    end,
    COMMODITY_PURCHASE_SUCCEEDED = function() AH.buy = nil end,
    COMMODITY_PURCHASE_FAILED = function() AH.buy = nil end,
}
-- Content refreshes: these change what an OPEN window shows, and are ignored otherwise.
-- GET_ITEM_INFO_RECEIVED fires constantly all over the game, so this gate matters.
local REFRESH = {
    "GOSSIP_OPTIONS_REFRESHED", "QUEST_ITEM_UPDATE", "MERCHANT_UPDATE", "BAG_UPDATE_DELAYED",
    "UPDATE_INVENTORY_DURABILITY", "PLAYER_MONEY", "GET_ITEM_INFO_RECEIVED",
    "PLAYER_INTERACTION_MANAGER_FRAME_HIDE", "TRAINER_UPDATE", "PET_STABLE_UPDATE", "MAIL_INBOX_UPDATE",
    "AUCTION_HOUSE_BROWSE_RESULTS_UPDATED", "AUCTION_HOUSE_BROWSE_RESULTS_ADDED", "COMMODITY_SEARCH_RESULTS_UPDATED",
    "PLAYERBANKSLOTS_CHANGED", "BANK_TABS_CHANGED", "LOOT_SLOT_CLEARED", "LOOT_SLOT_CHANGED",
    "ITEM_SEARCH_RESULTS_UPDATED", "OWNED_AUCTIONS_UPDATED", "ITEM_KEY_ITEM_INFO_RECEIVED", "AUCTION_HOUSE_THROTTLED_SYSTEM_READY",
}

local OPENS = {
    GOSSIP_SHOW = "gossip", QUEST_GREETING = "quest", QUEST_DETAIL = "quest", QUEST_PROGRESS = "quest",
    QUEST_COMPLETE = "quest", MERCHANT_SHOW = "merchant", TRAINER_SHOW = "trainer", PET_STABLE_SHOW = "stable", MAIL_SHOW = "mail", AUCTION_HOUSE_SHOW = "auction",
    TAXIMAP_OPENED = "taxi", BANKFRAME_OPENED = "bank", LOOT_OPENED = "loot", PETITION_SHOW = "petition",
}

local f = CreateFrame("Frame")
f:SetScript("OnEvent", function(_, event, ...)
    N.busy = nil                -- the game answered: a press-wait ends (see navRun)
    local h = ON[event]
    if h then
        h(...)
        -- a window opening: the game draws it within this very event, the document comes a frame later
        local slot = OPENS[event]
        if slot and N.early then N.early(slot) end
        emitSoon()
        return
    end
    if event == "PLAYER_MONEY" and N.watchMoney then N.watchMoney() end
    if anyOpenByEvent() or lastJson == nil then emitSoon() end
end)

local missing = {}
local WANT = {}
for e in pairs(ON) do WANT[#WANT + 1] = e end
for _, e in ipairs(REFRESH) do WANT[#WANT + 1] = e end
table.sort(WANT)
for _, e in ipairs(WANT) do
    local ok, registered = pcall(function() return f:RegisterEvent(e) end)
    if not ok or registered == false then missing[#missing + 1] = e end
end
if #missing > 0 then
    pcall(function() WC.send(WC.encode({ type = "cap", addon = "interact", missingEvents = missing })) end)
end

-- Ride the core heartbeat (15 s): re-send even when unchanged, so the app gets the pop-up back
-- after the log rotates and drops a stale one if a close was ever missed. Our line is written
-- inside the heartbeat's own frame, so it goes out in the same pad burst: no extra flush.
if type(hooksecurefunc) == "function" and type(WC.snapshot) == "function" then
    hooksecurefunc(WC, "snapshot", function(reason)
        if reason == "heartbeat" then
            beat = true
            pcall(emitNow, true)
            beat = false
        end
    end)
end

-- ---------------------------------------------------------------- actions (/wci)

-- Pad taps answer on the wire only; the app shows the result. Slash commands also answer in chat.
local quiet = false

local function ack(cmd, ok, why, n)
    WC.send(WC.encode({ type = "interactack", iv = IV, cmd = cmd, ok = ok and true or false, why = why, n = n }))
    WC.push()
    if not ok and not quiet then say("|cffff5555/wci " .. cmd .. ": " .. tostring(why) .. "|r") end
end

local function has(list, id)
    for _, e in ipairs(list or {}) do if e.id == id then return true end end
    return false
end

-- Every action returns ok, why. `why` is a short reason shown on the bottom screen when refused.
local ACT = {}

local function run(fn)
    LOOT.ranGame = true         -- a GAME call was made (the press-wait gate in navRun counts only these; LOOT: N is declared below)
    if CALL(fn) then return true end
    return false, "the game call failed"
end

-- /wci opt <gossipOptionID>
function ACT.opt(a)
    local id = tonumber(a)
    if not id then return false, "no option id" end
    if not isOpen("gossip") then return false, "no gossip open" end
    if not has(collectGossip().options, id) then return false, "option not offered" end
    return run(function() C_GossipInfo.SelectOption(id) end)
end

-- /wci take <questID>     a quest offered in a gossip list
function ACT.take(a)
    local id = tonumber(a)
    if not id then return false, "no quest id" end
    if not isOpen("gossip") then return false, "no gossip open" end
    if not has(collectGossip().available, id) then return false, "quest not offered" end
    return run(function() C_GossipInfo.SelectAvailableQuest(id) end)
end

-- /wci turnin <questID>   a quest in progress, listed in a gossip list
function ACT.turnin(a)
    local id = tonumber(a)
    if not id then return false, "no quest id" end
    if not isOpen("gossip") then return false, "no gossip open" end
    if not has(collectGossip().active, id) then return false, "quest not listed" end
    return run(function() C_GossipInfo.SelectActiveQuest(id) end)
end

-- /wci pick avail|active <index>   an old-style quest greeting list
function ACT.pick(which, a)
    local i = tonumber(a)
    if not i then return false, "no index" end
    if not (isOpen("quest") and questStage == "greeting") then return false, "no quest list open" end
    local q = collectQuest()
    if which == "avail" and q.available[i] then
        return run(function() SelectAvailableQuest(i) end)
    elseif which == "active" and q.active[i] then
        return run(function() SelectActiveQuest(i) end)
    end
    return false, "not in the list"
end

-- The quest actions all answer the quest frame, so they share one guard: the stage must match and
-- the open quest must be the one the tap was made on.
local function questGuard(stage, a)
    local id = tonumber(a)
    if not id then return nil, "no quest id" end
    if not (isOpen("quest") and questStage == stage) then return nil, "no " .. stage .. " open" end
    if TN(function() return GetQuestID() end) ~= id then return nil, "a different quest is open" end
    return id
end

-- /wci accept <questID> [anyway]
-- WITH COMPLETELY FULL BAGS (the user, 2026-10-05: "Make space first"): Make Space opens before the game is asked (the game
-- cannot say whether the quest hands out an item, and it closes its window at once); after the delete the quest is accepted;
-- B there = accept anyway (a quest that gives nothing).
function ACT.accept(a, anyway)
    local id, why = questGuard("detail", a)
    if not id then return false, why end
    if anyway ~= "anyway" and not TB(function() return QuestGetAutoAccept() end) and LOOT.freeGeneral() == 0 then
        return LOOT.questFirst({ cmd = "accept", a = a, anyway = true, title = TS(function() return GetTitleText() end) })
    end
    if TB(function() return QuestGetAutoAccept() end) then
        -- already in the log; the game's own button just closes the frame
        return run(function() CloseQuest() end)
    end
    N.qtry = { cmd = "accept", a = a, at = TN(function() return GetTime() end) or 0 }
    return run(function() AcceptQuest() end)
end

-- /wci decline <questID>
function ACT.decline(a)
    local id, why = questGuard("detail", a)
    if not id then return false, why end
    return run(function() DeclineQuest() end)
end

-- /wci continue <questID>   the "bring me the items" page -> the reward page
function ACT.continue(a)
    local id, why = questGuard("progress", a)
    if not id then return false, why end
    if not TB(function() return IsQuestCompletable() end) then return false, "not complete yet" end
    return run(function() CompleteQuest() end)
end

-- /wci reward <questID> [choice]   finish the turn-in, taking reward <choice>
function ACT.reward(a, c, freed)
    local id, why = questGuard("complete", a)
    if not id then return false, why end
    local n = TN(function() return GetNumQuestChoices() end) or 0
    local choice = tonumber(c)
    if n == 1 then choice = choice or 1 end
    if n > 1 and not (choice and choice >= 1 and choice <= n) then return false, "pick a reward" end
    if n == 0 then choice = 0 end
    -- the reward's items must fit FIRST: the fixed rewards plus the chosen one, against the free general slots
    local need = (TN(function() return GetNumQuestRewards() end) or 0) + ((choice and choice > 0) and 1 or 0)
    -- the slot just deleted counts as free while the game is still emptying it (exactly once: only if it still holds an item)
    if need > 0 and LOOT.roomNow((freed == "freed") and LOOT.freedAt or nil) < need then
        local rt = { cmd = "reward", a = a, c = choice, need = need, title = TS(function() return GetTitleText() end) }
        local kind, ix = (choice and choice > 0) and "choice" or "reward", (choice and choice > 0) and choice or 1
        local r = R(function() return GetQuestItemInfo(kind, ix) end)
        rt.name, rt.icon, rt.count, rt.q = r and PS(r[1]), r and PN(r[2]), r and PN(r[3]), r and PN(r[4])
        -- EVERY item the reward hands out (the user, 10-06: with 2 rewards it "only looked like it was making space for a
        -- single item"): the fixed rewards, then the chosen one; the app shows them all with "<free> of <need> slots free"
        rt.items = {}
        local function add(k, i)
            local ri = R(function() return GetQuestItemInfo(k, i) end)
            if ri then rt.items[#rt.items + 1] = { name = PS(ri[1]), icon = PN(ri[2]), count = PN(ri[3]), q = PN(ri[4]) } end
        end
        for i = 1, (TN(function() return GetNumQuestRewards() end) or 0) do add("reward", i) end
        if choice and choice > 0 then add("choice", choice) end
        return LOOT.questFirst(rt)
    end
    N.qtry = { cmd = "reward", a = a, c = choice, at = TN(function() return GetTime() end) or 0 }
    return run(function() GetQuestReward(choice) end)
end

-- the app's tap after a load: "the frame heard the controller on this game build before"
function ACT.trust()
    if N.distrust then return false, "not trusted: " .. tostring(N.distrust) end
    N.trusted = true
    return true
end

-- /wci loottop on|off   r-loot-on-top (Home > Settings): the game keeps its own loot window on the top screen. Turned on while our
-- loot pop-up is up, every alpha we set goes back at once (the game's window shows again); the next document lets the controller go.
function ACT.loottop(a)
    if a ~= "on" and a ~= "off" then return false, "on or off" end
    if type(WowCompanionDB) ~= "table" then WowCompanionDB = {} end      -- the table is everyone's: only the one key is ours
    local was = LOOT.top()
    WowCompanionDB.lootTop = (a == "on")
    if WowCompanionDB.lootTop and not was then
        LOOT.full, LOOT.bind, LOOT.running, LOOT.hold, LOOT.rare = false, false, false, nil, nil
        if shownAt.loot > hiddenAt.loot then N.unhide() end
    end
    N.n = N.n + 1
    emitSoon()
    return true
end

-- /wci sel on|off   capture v5: the bar's Select listener (Home's bar selector); see N.selSync
function ACT.sel(a)
    if a ~= "on" and a ~= "off" then return false, "on or off" end
    N.bar.want = (a == "on")
    if N.selSync then N.selSync() end
    emitSoon()
    return true
end

-- /wci loot <slot>   the game's LootSlot: ONE game action (a bind-on-pickup item asks first: the game's dialog, mirrored)
function ACT.loot(a)
    local i = tonumber(a)
    if not (i and isOpen("loot")) then return false, "no loot window open" end
    local r = R(function() return GetLootSlotInfo(i) end)
    if not (r and (real(r[2]) or PB(r[10]))) then return false, "already looted" end
    if PB(r[6]) then return false, "locked" end
    LOOT.try = i
    return run(function() LootSlot(i) end)
end

-- THE AUTO-LOOT AFTER THE DELETE (the user, 2026-10-02: "Yes, loot it automatically and close"; their exception to one press =
-- one game action, like Loot All: one hold = the delete + this one LootSlot). The item that did not fit (LOOT.want; else the first
-- real item left) goes into the slot just freed, the only free one, and the app flies it there (LOOT.landing). The game closes
-- its loot window itself when that was the last item; else the compact window comes back (no B, no second Loot).
function LOOT.auto(bag, sl)
    if LOOT.ms == "quest" then
        -- the quest: the same accept / reward again, once (the user's exception); a new "full" opens Make Space again
        local rt = LOOT.retry
        if rt then
            rt.freed = rt.freed or {}
            rt.freed[#rt.freed + 1] = { bag = bag, slot = sl }
        end
        -- LOOTWIN 9: a reward of several items with room for only some of them: Make Space stays up (the freed tile keeps its
        -- gold rim, the next card its tick); nothing is retried and nothing flies before there is room for all
        if rt and rt.cmd == "reward" and rt.need and isOpen("quest") and LOOT.roomNow(rt.freed) < rt.need then
            LOOT.hold = nil
            emitSoon()
            return
        end
        LOOT.full, LOOT.hold, LOOT.ms, LOOT.retry = false, nil, nil, nil
        if rt and isOpen("quest") then
            if rt.icon then
                LOOT.landN = LOOT.landN + 1
                LOOT.landing = { n = LOOT.landN, from = -1, bag = bag, slot = sl, icon = rt.icon, name = rt.name, count = rt.count,
                                 q = rt.q, quest = true, last = true, at = TN(function() return GetTime() end) or 0,
                                 items = (rt.items and #rt.items > 1) and rt.items or nil,
                                 tiles = (rt.items and #rt.items > 1) and rt.freed or nil }
            end
            -- the slot just freed is still being emptied by the game: the retry counts it as free (accept: anyway; reward: +1)
            local ok, why
            LOOT.freedAt = rt.freed
            if rt.cmd == "accept" then ok, why = ACT.accept(rt.a, "anyway")
            else ok, why = ACT.reward(rt.a, rt.c, "freed") end
            LOOT.freedAt = nil
            if not ok then ack(rt.cmd, false, why) end
        end
        emitSoon()
        return
    end
    LOOT.full, LOOT.hold = false, nil
    if not isOpen("loot") then emitSoon(); return end
    local n = TN(function() return GetNumLootItems() end) or 0
    local left, pick, first = 0, nil, nil
    for i = 1, math.min(n, LOOT_MAX) do
        local r = R(function() return GetLootSlotInfo(i) end)
        if r and (real(r[2]) or PB(r[10])) then
            left = left + 1
            if not PB(r[10]) and not PB(r[6]) then
                first = first or i
                if i == LOOT.want then pick = i end
            end
        end
    end
    pick = pick or first
    if not pick then emitSoon(); return end
    local r = R(function() return GetLootSlotInfo(pick) end)
    LOOT.landN = LOOT.landN + 1
    LOOT.landing = { n = LOOT.landN, from = pick, bag = bag, slot = sl, icon = PN(r and r[1]), name = PS(r and r[2]),
                     count = PN(r and r[3]), q = PN(r and r[5]), last = (left <= 1) or nil,
                     at = TN(function() return GetTime() end) or 0 }
    LOOT.try, LOOT.want = pick, nil
    CALL(function() LootSlot(pick) end)
    emitSoon()
end

-- /wci lootdel <bag> <slot> <itemID> [rare]   MAKE SPACE: ONE delete, after the hold (and for blue / purple the box's Yes);
-- Bags' own WC.bagsDestroy (its guards: the item still there, not in combat, nothing on the cursor, legendary refused)
function ACT.lootdel(a, b, c, d)
    LOOT.rare = nil
    if not LOOT.on() then return false, "no make-space screen" end
    if not DELETE_ON then return false, "deleting waits for the test that proves it works here" end
    if type(WC.bagsDestroy) ~= "function" then return false, "the bags addon is too old" end
    local bag, sl, id = tonumber(a), tonumber(b), tonumber(c)
    if not (bag and sl and id) then return false, "no item" end
    local ok, why = WC.bagsDestroy(bag, sl, id, d == "rare")
    -- THE GAME DELETES A MOMENT LATER (the user, 2026-10-02: "the item vanishes and it says refused"): Bags checks the slot in
    -- the same instant and answers "refused" for a delete still on its way. So "refused" is re-checked 0.6 s later: the slot
    -- empty = done (the loot window comes back); still there = said then, plainly.
    if not ok and why == "refused" then
        pcall(C_Timer.After, 0.6, function()
            local okI, it = CALL(function() return C_Container.GetContainerItemInfo(bag, sl) end)
            local still = okI and type(it) == "table" and TN(function() return it.itemID end) == id
            if still then
                ack("lootdel", false, "the game kept that item")
            elseif LOOT.full then
                LOOT.auto(bag, sl)
            end
        end)
        return true
    end
    -- a slot is free: the wanted item goes into it (then the loot window comes back, or closes when it was the last)
    if ok then LOOT.auto(bag, sl) end
    return ok, why
end

-- /wci lootall   LOOT ALL: the user's ONE exception to one-touch-one-action, on the loot window only (2026-10-01: "Loot all is
-- a default behavior when looting ... it should be available as an option"). Slot by slot: the first inside the press,
-- the rest LOOT_STEP apart; it STOPS at a bind-on-pickup confirm (the game's dialog decides), at "Inventory is full" (Make
-- Space opens), or when the window closes. Locked slots are skipped.
function ACT.lootall()
    if not isOpen("loot") then return false, "no loot window open" end
    if LOOT.running then return false, "already looting" end
    local queue = {}
    local n = TN(function() return GetNumLootItems() end) or 0
    for i = 1, math.min(n, LOOT_MAX) do
        local r = R(function() return GetLootSlotInfo(i) end)
        if r and (real(r[2]) or PB(r[10])) and not PB(r[6]) then queue[#queue + 1] = i end
    end
    if #queue == 0 then return false, "nothing to loot" end
    -- a new Loot All tries again: an old "full" or bind confirm no longer stops it (the game says so again if it still is)
    LOOT.running, LOOT.bind, LOOT.full = true, false, false
    local k = 0
    local function step()
        k = k + 1
        if k > #queue or not isOpen("loot") or LOOT.full or LOOT.bind then
            LOOT.running = false
            emitSoon()
            return
        end
        LOOT.try = queue[k]
        CALL(function() LootSlot(queue[k]) end)
        pcall(C_Timer.After, LOOT_STEP, step)
    end
    step()
    return true
end

-- /wci roll <rollID> need|greed|pass|de|transmog   the game's own roll (RollOnLoot): ONE game action. The game's
-- controller does the same itself (GamepadGroupLootFrame_SetupGamepad), so this is not a protected call.
local ROLL_TYPE = { pass = 0, need = 1, greed = 2, de = 3, transmog = 4 }
function ACT.roll(a, b)
    local id, ty = tonumber(a), ROLL_TYPE[b or ""]
    if not (id and ty) then return false, "no such roll" end
    local r = R(function() return GetLootRollItemInfo(id) end)
    if not (r and real(r[2])) then return false, "the roll is over" end
    local can = { [1] = PB(r[6]), [2] = PB(r[7]), [3] = PB(r[8]), [4] = PB(r[13]) }
    if ty ~= 0 and not can[ty] then return false, "you cannot roll that on this item" end
    return run(function() RollOnLoot(id, ty) end)
end

-- /wci release npc   SELF-HEAL (Home's rule for every room, 2026-09-30): let go of the controller held for an NPC window
-- when no NPC window is open and no guest holds it. A no-action tap; refused while a window is really open.
function ACT.release(a)
    if a ~= "npc" then return false, "release what?" end
    -- (G is declared further down: the guest is read through the capture's own state())
    local st = WC.capture and WC.capture.state and WC.capture.state()
    if st and st.holder and st.holder ~= "npc" then return false, "a room holds it" end
    if not N.holding then return true end
    for slot in pairs(FRAME) do
        if isOpen(slot) then return false, "a window is open" end
    end
    N.release("heal")
    emitSoon()
    return true
end

-- the app shows a dialog on the bottom screen and says so, again and again while it does
function ACT.alive()
    if type(WC.dialogs) == "table" and type(WC.dialogs.alive) == "function" then WC.dialogs.alive() end
    return true
end

-- /wci close   close whichever NPC windows are open
function ACT.close()
    local any = false
    if isOpen("quest") then any = true; CALL(function() CloseQuest() end) end
    if isOpen("merchant") then any = true; CALL(function() CloseMerchant() end) end
    if isOpen("gossip") then any = true; CALL(function() C_GossipInfo.CloseGossip() end) end
    if isOpen("trainer") then any = true; CALL(function() CloseTrainer() end) end
    if isOpen("stable") then any = true; CALL(function() C_StableInfo.ClosePetStables() end) end
    if isOpen("petition") then any = true; CALL(function() ClosePetition() end) end
    if isOpen("mail") then any = true; CALL(function() CloseMail() end) end
    if isOpen("auction") then any = true; ahCancelBuy(); CALL(function() C_AuctionHouse.CloseAuctionHouse() end) end
    if isOpen("taxi") then any = true; CALL(function() CloseTaxiMap() end) end
    if isOpen("loot") then any = true; CALL(function() CloseLoot() end) end
    if isOpen("bank") then
        any = true
        BANK.hand, BANK.box = nil, false
        CALL(function() if C_Bank and C_Bank.CloseBankFrame then C_Bank.CloseBankFrame() else CloseBankFrame() end end)
    end
    if not any then return false, "nothing open" end
    return true
end

-- ---------------------------------------------------------------- tooltips (LATER 1 + 2, user 2026-09-30)
-- "when I am buying something its not really clear what I am buying, like what does this water do, or
-- what are the stats on this armor" / "on quest rewards, I need a tooltip to see what my reward choices
-- actually are". The GAME'S OWN tooltip lines for ONE item at a time: the controller's highlight, else
-- the item a finger selected (a `look` cell on the ipick row: changes nothing in the game). Same line format as
-- Bags / Character: t / c = left text and colour "rrggbb", r / rc = right text and colour. A secret or
-- unreadable line is left out, never guessed. COMPARE: what is worn in the item's slot(s), the same way.
local TIP_MAX = 24
local function hexOf(col)
    if type(col) ~= "table" then return nil end
    local r = TN(function() return col.r end); local g = TN(function() return col.g end); local b = TN(function() return col.b end)
    if not (r and g and b) then return nil end
    local function h(v) return math.max(0, math.min(255, math.floor(v * 255 + 0.5))) end
    return string.format("%02x%02x%02x", h(r), h(g), h(b))
end
-- the game's plural escape "|4singular:plural;" (Thor, 2026-10-01: "(1 |4Hr:Hr; Cooldown)"): the form by the number
-- before it, the plural when there is none
local function plural(t)
    t = t:gsub("(%d+)(%s*)|4([^:;]*):([^;]*);", function(n, sp, one, many)
        return n .. sp .. ((tonumber(n) == 1) and one or many)
    end)
    return (t:gsub("|4[^:;]*:([^;]*);", "%1"))
end
local function tipText(v)
    local t = TS(function() return v end)
    if t and t:byte(1) and t:byte(1) > 3 then return plural(t) end
    return nil
end
local function linesOf(fn)
    local ok, data = CALL(fn)
    if not ok or type(data) ~= "table" or type(data.lines) ~= "table" then return nil end
    N.tipData = data
    local out = {}
    for i = 1, math.min(#data.lines, TIP_MAX) do
        local ln = data.lines[i]
        if type(ln) == "table" then
            local t = tipText(ln.leftText)
            if t then
                local row = { t = t, c = hexOf(ln.leftColor) }
                local rt = tipText(ln.rightText)
                if rt then row.r = rt; row.rc = hexOf(ln.rightColor) end
                out[#out + 1] = row
            end
        end
    end
    return #out > 0 and out or nil
end
-- where an item is worn: the game's equip location -> the character slots to compare against
local EQUIP_SLOTS = {
    INVTYPE_HEAD = { 1 }, INVTYPE_NECK = { 2 }, INVTYPE_SHOULDER = { 3 }, INVTYPE_BODY = { 4 },
    INVTYPE_CHEST = { 5 }, INVTYPE_ROBE = { 5 }, INVTYPE_WAIST = { 6 }, INVTYPE_LEGS = { 7 },
    INVTYPE_FEET = { 8 }, INVTYPE_WRIST = { 9 }, INVTYPE_HAND = { 10 }, INVTYPE_FINGER = { 11, 12 },
    INVTYPE_TRINKET = { 13, 14 }, INVTYPE_CLOAK = { 15 }, INVTYPE_WEAPON = { 16, 17 },
    INVTYPE_2HWEAPON = { 16 }, INVTYPE_WEAPONMAINHAND = { 16 }, INVTYPE_WEAPONOFFHAND = { 17 },
    INVTYPE_SHIELD = { 17 }, INVTYPE_HOLDABLE = { 17 }, INVTYPE_RANGED = { 18 }, INVTYPE_RANGEDRIGHT = { 18 },
    INVTYPE_THROWN = { 18 }, INVTYPE_RELIC = { 18 }, INVTYPE_TABARD = { 19 },
}
-- the item id behind a look key, and its tooltip reader
local function lookSource(key)
    if type(key) ~= "string" then return nil end
    local w = {}
    for word in key:gmatch("%S+") do w[#w + 1] = word end
    local a, b, c, d = tonumber(w[2]), tonumber(w[3]), tonumber(w[4]), nil
    if w[1] == "buy" and a then
        return b, function() return C_TooltipInfo.GetMerchantItem(a) end
    elseif w[1] == "back" and a then
        return b, function()
            if C_TooltipInfo.GetBuybackItem then return C_TooltipInfo.GetBuybackItem(a) end
            return C_TooltipInfo.GetMerchantBuybackItem(a)
        end
    elseif (w[1] == "sell" or w[1] == "bag") and a and b then
        -- "bag <bag> <slot> <itemID>": any item in the player's bags (the Bags room's cursor, via WC.tipOf)
        return c, function() return C_TooltipInfo.GetBagItem(a, b) end
    elseif w[1] == "reward" and b then
        -- a controller highlight on a reward choice: "reward <questID> <i>"
        return nil, function() return C_TooltipInfo.GetQuestItem("choice", b) end
    elseif w[1] == "train" and a then
        return nil, function() return C_TooltipInfo.GetTrainerService(a) end
    elseif (w[1] == "ahres" and a) or w[1] == "ahrow" or w[1] == "ahbuy" then
        -- THE AUCTION HOUSE's tooltips (user, 2026-09-30: "we need tool tips in the ah"): a result, or the item opened
        -- (its listings / its price tiers); the game's own item tooltip by its item key
        local key
        if w[1] == "ahres" then key = AH.rs and AH.rs[a] and AH.rs[a].itemKey else key = AH.key end
        local id = key and TN(function() return key.itemID end)
        if not id then return nil end
        local lvl, suf = TN(function() return key.itemLevel end), TN(function() return key.itemSuffix end)
        return id, function()
            if C_TooltipInfo.GetItemKey then return C_TooltipInfo.GetItemKey(id, lvl or 0, suf or 0) end
            return C_TooltipInfo.GetItemByID(id)
        end
    elseif w[1] == "loot" and a then
        local link = TS(function() return GetLootSlotLink(a) end)
        local id = link and TN(function() return (C_Item.GetItemInfoInstant(link)) end)
        return id, function() return C_TooltipInfo.GetLootItem(a) end
    elseif w[1] == "roll" and a then
        -- a LOOT ROLL (user, 2026-09-30: a weapon or armour "should probably show it and my currently equipped
        -- compared automatically"): the game's own roll tooltip; the compare and its stat changes as at a vendor
        local link = TS(function() return GetLootRollItemLink(a) end)
        local id = link and TN(function() return (C_Item.GetItemInfoInstant(link)) end)
        return id, function() return C_TooltipInfo.GetLootRollItem(a) end
    elseif w[1] == "qitem" and (w[2] == "choice" or w[2] == "reward") and b then
        return nil, function() return C_TooltipInfo.GetQuestItem(w[2], b) end
    elseif w[1] == "link" and #w == 2 and w[2]:match("^%a+:[%-%d:]+$") then
        -- a LINK in a chat message (Chat, 2026-10-07: the user wants to view what is linked): "link item:19019:0:..." or
        -- "link spell:133", the link's inner part; the game's own tooltip for it, an item's with the compare
        local pl = w[2]
        return tonumber(pl:match("^item:(%d+)")), function() return C_TooltipInfo.GetHyperlink(pl) end
    end
    return nil
end
local function canLook(key) return select(2, lookSource(key)) ~= nil end
N.canLook = canLook
local function questItemID(kind, i)
    return TN(function() local _, _, _, _, _, id = GetQuestItemInfo(kind, i); return id end)
end
function N.tipOf(key)
    if type(key) ~= "string" or not C_TooltipInfo then return nil end
    local id, fn = lookSource(key)
    if not fn then return nil end
    local lines = linesOf(fn)
    if not lines then return { key = key, none = true } end
    local tip = { key = key, lines = lines }
    if not id then
        local k, i = key:match("^qitem (%a+) (%d+)$")
        if k then id = questItemID(k, tonumber(i)) end
        local ci = key:match("^reward %d+ (%d+)$")
        if ci then id = questItemID("choice", tonumber(ci)) end
    end
    if id then
        local loc = TS(function() return select(4, C_Item.GetItemInfoInstant(id)) end)
        local slots = loc and EQUIP_SLOTS[loc]
        if slots then
            local cmp = {}
            -- the item looked at, as the game's comparison wants it (its guid or its link)
            local item = nil
            if fn then
                N.tipData = nil
                linesOf(fn)
                local d = N.tipData
                if type(d) == "table" then
                    local guid, link = TS(function() return d.guid end), TS(function() return d.hyperlink end)
                    if guid then item = { guid = guid } elseif link then item = { hyperlink = link } end
                end
            end
            for _, sl in ipairs(slots) do
                if TN(function() return GetInventoryItemID("player", sl) end) then
                    local l = linesOf(function() return C_TooltipInfo.GetInventoryItem("player", sl) end)
                    if l then
                        -- THE STAT CHANGES (user, 2026-09-30: "The compare is missing the stat changes"): the game's own
                        -- lines from C_TooltipComparison.GetItemComparisonDelta, under its own header, as its shopping
                        -- tooltip adds them; nothing is computed here
                        local link = TS(function() return GetInventoryItemLink("player", sl) end)
                        if item and link and C_TooltipComparison and C_TooltipComparison.GetItemComparisonDelta then
                            local ok, delta = CALL(function() return C_TooltipComparison.GetItemComparisonDelta(item, { hyperlink = link }) end)
                            if ok and type(delta) == "table" and #delta > 0 then
                                l[#l + 1] = { t = " " }
                                l[#l + 1] = { t = TS(function() return ITEM_DELTA_DESCRIPTION end) or "If you replace this item, the following stat changes will occur:", c = "ffd100" }
                                for _, dl in ipairs(delta) do
                                    local txt = TS(function() return dl end)
                                    if txt then
                                        -- the game colours only the number ("|cff00ff00+129|r Armor"): the line takes that
                                        -- colour (green a gain, red a loss) and every code is stripped (user: "a string of text")
                                        local hex = txt:match("|c%x%x(%x%x%x%x%x%x)")
                                        local clean = (txt:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""))
                                        l[#l + 1] = { t = clean, c = hex and hex:lower() or "ffffff" }
                                    end
                                end
                            end
                        end
                        cmp[#cmp + 1] = { slot = sl, lines = l }
                    end
                end
            end
            if #cmp > 0 then tip.cmp = cmp end
        end
    end
    return tip
end
-- /wci look <key...>   the item a finger selected; nothing happens in the game
-- Dialogs.lua's loot rolls: their cells and the selected roll's tooltip (with the compare)
WC.rollCells = function(id) return ROLLCELLS[id] end
WC.rollTip = function(id) return N.tipOf("roll " .. tostring(id)) end
-- the tooltip of a key, with the compare and its stat changes, for other rooms (Bags: "bag <bag> <slot> <itemID>")
WC.tipOf = function(key) return N.tipOf(key) end
function ACT.look(a, b, c, d)
    local key = table.concat({ a, b, c, d }, " ")
    if not (a and canLook(key)) then return false, "nothing to look at" end
    N.look = key
    -- what a finger selected is where the controller cursor goes on (LATER 19: the selected thing is the
    -- natural starting point)
    if N.holding and N.mode and navFind(key) and N.cur ~= key then N.cur, N.ts = key, 0 end
    emitSoon()
    return true
end

-- ---------------------------------------------------------------- auction house actions
local function ahSorts()
    local o = TN(function() return Enum.AuctionHouseSortOrder.Price end) or 0
    return { { sortOrder = o, reverseSort = false } }
end
local function ahIsOpen() return isOpen("auction") end
-- a commodity's quantity, one step (X / Y, or a tap on - / +): never past what is listed; a change cancels an armed purchase
local function ahStep(d)
    local info = AH.key and ahKeyInfo(AH.key)
    if not (info and TB(function() return info.isCommodity end)) then return false, "not a commodity" end
    local avail = 0
    local id = TN(function() return AH.key.itemID end)
    local n = id and TN(function() return C_AuctionHouse.GetNumCommoditySearchResults(id) end) or 0
    for i = 1, n do
        local ok, r = CALL(function() return C_AuctionHouse.GetCommoditySearchResultInfo(id, i) end)
        if ok and type(r) == "table" and not TB(function() return r.containsOwnerItem end) then avail = avail + (TN(function() return r.quantity end) or 0) end
    end
    local q = math.max(1, math.min(math.max(1, avail), AH.qty + d))
    if q ~= AH.qty then AH.qty = q; ahCancelBuy() end
    emitSoon()
    return true
end
-- /wci ahqty less|more   touch's - / + on a commodity's quantity
function ACT.ahqty(a)
    if not ahIsOpen() then return false, "no auction house open" end
    return ahStep((a == "more") and 1 or -1)
end
-- /wci ahtab browse|owned
function ACT.ahtab(a)
    if not ahIsOpen() then return false, "no auction house open" end
    if a ~= "browse" and a ~= "owned" then return false, "no such tab" end
    ahCancelBuy()
    AH.tab = a
    if a == "owned" then CALL(function() C_AuctionHouse.QueryOwnedAuctions(ahSorts()) end) end
    emitSoon()
    return true
end
-- /wci ahcat <path>   a category anywhere in the tree ("5" or "5.1"): browse it (its sub-categories open under it)
function ACT.ahcat(a)
    if not ahIsOpen() then return false, "no auction house open" end
    local path = {}
    for n in tostring(a or ""):gmatch("%d+") do path[#path + 1] = tonumber(n) end
    if #path == 0 then return false, "no such category" end
    local cat = ahCategory(path)
    if not cat then return false, "no such category" end
    if TB(function() return C_AuctionHouse.IsThrottledMessageSystemReady() end) == false then return false, "the auction house is busy: one moment" end
    AH.path, AH.key, AH.buy = path, nil, nil
    local query = { searchString = "", sorts = ahSorts(), filters = {}, itemClassFilters = cat.filters or {} }
    local done, err = run(function() C_AuctionHouse.SendBrowseQuery(query) end)
    if done then AH.results = true end
    return done, err
end
-- /wci ahres <i>   one result: ask for its listings (an item) or its price tiers (a commodity)
function ACT.ahres(a)
    local i = tonumber(a)
    if not (i and ahIsOpen()) then return false, "no auction house open" end
    local r = AH.rs and AH.rs[i]
    if not (r and r.itemKey) then return false, "the results changed" end
    ahCancelBuy()
    AH.key, AH.qty, AH.res = r.itemKey, 1, i
    return run(function() C_AuctionHouse.SendSearchQuery(r.itemKey, ahSorts(), true) end)
end
-- /wci ahbuy   a commodity, AH.qty of it: press 1 starts the game's purchase (its total comes back), press 2 confirms
function ACT.ahbuy()
    if not ahIsOpen() then return false, "no auction house open" end
    local key = AH.key
    local info = key and ahKeyInfo(key)
    local id = key and TN(function() return key.itemID end)
    if not (id and info and TB(function() return info.isCommodity end)) then return false, "nothing to buy" end
    local t = TN(function() return GetTime() end) or 0
    local b = AH.buy
    if b and b.kind == "commodity" and b.armedAt and t - b.armedAt <= AH_ARM and b.qty == AH.qty then
        if (TN(function() return GetMoney() end) or 0) < (b.total or 0) then ahCancelBuy(); return false, "not enough money" end
        AH.buy = nil
        return run(function() C_AuctionHouse.ConfirmCommoditiesPurchase(id, b.qty) end)
    end
    ahCancelBuy()
    local done, err = run(function() C_AuctionHouse.StartCommoditiesPurchase(id, AH.qty) end)
    if done then AH.buy = { kind = "commodity", qty = AH.qty, started = true, waiting = true } end
    return done, err
end
-- /wci ahrow <i>   an item's listing: press 1 arms it with its buyout, press 2 (same listing) buys it (PlaceBid)
function ACT.ahrow(a)
    local i = tonumber(a)
    if not (i and ahIsOpen() and AH.key) then return false, "no auction house open" end
    local ok, r = CALL(function() return C_AuctionHouse.GetItemSearchResultInfo(AH.key, i) end)
    if not (ok and type(r) == "table") then return false, "the listings changed" end
    local auction, buyout = TN(function() return r.auctionID end), TN(function() return r.buyoutAmount end)
    if not (auction and buyout and buyout > 0) then return false, "no buyout on this one" end
    if TB(function() return r.containsOwnerItem end) then return false, "that one is yours" end
    local t = TN(function() return GetTime() end) or 0
    local b = AH.buy
    if b and b.kind == "item" and b.row == i and b.armedAt and t - b.armedAt <= AH_ARM then
        if (TN(function() return GetMoney() end) or 0) < buyout then AH.buy = nil; return false, "not enough money" end
        AH.buy = nil
        return run(function() C_AuctionHouse.PlaceBid(auction, buyout) end)
    end
    AH.buy = { kind = "item", row = i, total = buyout, qty = TN(function() return r.quantity end), armedAt = t }
    emitSoon()
    return true
end
-- /wci ahback   one level up: a purchase armed -> cancelled; an item -> the results; results -> the categories; a category -> its parent
function ACT.ahback()
    if not ahIsOpen() then return false, "no auction house open" end
    if AH.buy then ahCancelBuy()
    elseif AH.key then AH.key = nil; AH.lastRes = AH.res
    elseif AH.tab == "owned" then AH.tab = "browse"
    elseif #AH.path > 0 then
        local path = {}
        for k = 1, #AH.path - 1 do path[k] = AH.path[k] end
        AH.path = path
        AH.results = nil
    else return false, "at the top" end
    emitSoon()
    return true
end

-- ---------------------------------------------------------------- the flight master's action
-- /wci taxi <slot>   fly there: the game's TakeTaxiNode, ONE game action
function ACT.taxi(a)
    local i = tonumber(a)
    if not (i and isOpen("taxi")) then return false, "no flight master open" end
    local ty = TS(function() return TaxiNodeGetType(i) end)
    if ty == "CURRENT" then return false, "you are here" end
    if ty ~= "REACHABLE" then return false, "not discovered" end
    local cost = TN(function() return TaxiNodeCost(i) end) or 0
    if (TN(function() return GetMoney() end) or 0) < cost then return false, "not enough money" end
    return run(function() TakeTaxiNode(i) end)
end

-- ---------------------------------------------------------------- mail actions
local function mailHeader(i)
    local r = R(function() return GetInboxHeaderInfo(i) end)
    if not r then return nil end
    return { money = PN(r[5]) or 0, cod = PN(r[6]) or 0, items = PN(r[8]) or 0 }
end

-- /wci mail <i>   open a letter on the bottom screen (reading it marks it read, as the game does)
function ACT.mail(a)
    local i = tonumber(a)
    if not (i and isOpen("mail")) then return false, "no mailbox open" end
    if not mailHeader(i) then return false, "the inbox changed" end
    MAIL.open = i
    emitSoon()
    return true
end
function ACT.mailback()
    MAIL.back, MAIL.open = MAIL.open, nil
    emitSoon()
    return true
end
-- /wci mailtake <i> <a>   one attachment (TakeInboxItem); never on a COD letter (it costs money)
function ACT.mailtake(a, b)
    local i, k = tonumber(a), tonumber(b)
    if not (i and k and isOpen("mail")) then return false, "no mailbox open" end
    local h = mailHeader(i)
    if not h then return false, "the inbox changed" end
    if h.cod > 0 then return false, "this letter costs money (COD): use the top screen" end
    return run(function() TakeInboxItem(i, k) end)
end
function ACT.mailmoney(a)
    local i = tonumber(a)
    if not (i and isOpen("mail")) then return false, "no mailbox open" end
    local h = mailHeader(i)
    if not h or h.money <= 0 then return false, "no money in it" end
    return run(function() TakeInboxMoney(i) end)
end
-- /wci mailall <i>   everything in a letter (AutoLootMailItem), never on a COD letter
function ACT.mailall(a)
    local i = tonumber(a)
    if not (i and isOpen("mail")) then return false, "no mailbox open" end
    local h = mailHeader(i)
    if not h then return false, "the inbox changed" end
    if h.cod > 0 then return false, "this letter costs money (COD): use the top screen" end
    return run(function() AutoLootMailItem(i) end)
end

-- ---------------------------------------------------------------- stable actions
local ARM_SECONDS = 3

-- /wci petmove <from> <to>   the game's own swap of two stable slots (C_StableInfo.SetPetSlot)
function ACT.petmove(a, b)
    local from, to = tonumber(a), tonumber(b)
    if not (from and to) or from == to then return false, "no move" end
    if not isOpen("stable") then return false, "no stable open" end
    local ok, info = CALL(function() return C_StableInfo.GetStablePetInfo(from) end)
    if not (ok and type(info) == "table") then return false, "no pet there" end
    local done, err = run(function() C_StableInfo.SetPetSlot(from, to) end)
    return done, err
end

-- /wci pet <i>   a touch on a pet slot: the first picks it, a second on another slot moves it there
function ACT.pet(a)
    local i = tonumber(a)
    if not i then return false, "no slot" end
    if not isOpen("stable") then return false, "no stable open" end
    if N.petFrom and N.petFrom ~= i then
        local from = N.petFrom
        N.petFrom = nil
        return ACT.petmove(from, i)
    end
    N.petFrom = (N.petFrom == i) and nil or i
    emitSoon()
    return true
end

-- /wci stablebox   X / a tap on Purchase: the game's own purchase box (UI Art's STABLE_SPEC 5); A / Yes in it buys
function ACT.stablebox()
    if not isOpen("stable") then return false, "no stable open" end
    local slots = TN(function() return C_StableInfo.GetNumStableSlots() end) or 0
    local max = TN(function() return Constants.PetConsts.MAX_STABLE_SLOTS end)
    if max and slots >= max then return false, "every stable slot is yours already" end
    N.stableBox = (not N.stableBox) or nil
    emitSoon()
    return true
end
-- /wci stableslot   buy the next stable slot: C_StableInfo.BuyStableSlot (what the game's own confirmation calls)
function ACT.stableslot()
    if not isOpen("stable") then return false, "no stable open" end
    local cost = TN(function() return C_StableInfo.GetNextStableSlotCost() end) or 0
    if (TN(function() return GetMoney() end) or 0) < cost then return false, "not enough money" end
    local done, err = run(function() C_StableInfo.BuyStableSlot() end)
    return done, err
end

-- ---------------------------------------------------------------- trainer actions

-- /wci trf available|unavailable|used|categorize   the game's own filter switches
function ACT.trf(a)
    if not isOpen("trainer") then return false, "no trainer open" end
    if a == "categorize" then
        local on = TB(function() return C_Trainer.GetCategorizeTrainerUI() end)
        return run(function() C_Trainer.SetCategorizeTrainerUI(not on) end)
    end
    if a ~= "available" and a ~= "unavailable" and a ~= "used" then return false, "no such filter" end
    local on = TB(function() return GetTrainerServiceTypeFilter(a) end)
    return run(function() SetTrainerServiceTypeFilter(a, not on) end)
end
-- /wci trmenu   the trainer's Settings menu (touch: the drop-down; controller: +)
function ACT.trmenu()
    if not isOpen("trainer") then return false, "no trainer open" end
    N.trMenu = (not N.trMenu) or nil
    emitSoon()
    return true
end
-- the folded groups as a plain list of header numbers (a table keyed by number would encode as an array)
function N.trFoldList()
    if not N.trFold then return nil end
    local out = {}
    for h in pairs(N.trFold) do out[#out + 1] = h end
    table.sort(out)
    return #out > 0 and out or nil
end
-- /wci trspell   Y at the trainer (or a tap on its "Y Spellbook" hint): the user, 2026-09-30, "Y opens the Spellbook
-- room; B comes back". The trainer window stays open (hidden); the controller is LENT to the Spellbook room as a guest
-- (capture.take("spells") passes while N.lend == "spells"); its release with why "trainer" gives it back here.
function ACT.trspell()
    if not isOpen("trainer") then return false, "no trainer open" end
    if trProfession() then return false, "a profession trainer has no Spellbook" end
    local room = WowCompanion and WowCompanion.spellsRoom
    if type(room) ~= "table" or type(room.open) ~= "function" then return false, "the Spellbook room is not loaded" end
    N.trMenu = nil
    N.lend = "spells"
    local ok, why = pcall(room.open, "book", "trainer")
    if not ok or why == false then N.lend = nil; return false, "the Spellbook room did not open" end
    emitSoon()
    return true
end
-- /wci trhead <i>   fold / open a group (touch)
function ACT.trhead(a)
    local h = tonumber(a)
    if not (h and isOpen("trainer")) then return false, "no trainer open" end
    N.trFold = N.trFold or {}
    N.trFold[h] = (not N.trFold[h]) or nil
    emitSoon()
    return true
end

-- /wci train <i>   learn one service (BuyTrainerService, the game's own C call; one press = one action)
function ACT.train(a)
    local i = tonumber(a)
    if not i then return false, "no service" end
    if not isOpen("trainer") then return false, "no trainer open" end
    local r = R(function() return GetTrainerServiceInfo(i) end)
    if not (r and real(r[1])) then return false, "the trainer's list changed" end
    if PS(r[2]) ~= "available" then return false, "cannot be learned yet" end
    local c = R(function() return GetTrainerServiceCost(i) end)
    local cost = c and PN(c[1]) or 0
    if (TN(function() return GetMoney() end) or 0) < cost then return false, "not enough money" end
    local done, err = run(function() BuyTrainerService(i) end)
    return done, err
end

-- ---------------------------------------------------------------- vendor actions

local function merchantGuard()
    if not isOpen("merchant") then return false, "no vendor open" end
    return true
end

-- /wci qty <k>   the next buy, if it comes within QTY_SECONDS, makes k purchases instead of one
local QTY_SECONDS = 3
local wanted = nil

function ACT.qty(a)
    local k = tonumber(a)
    wanted = nil
    if not k or k < 1 or k > QTY_MAX or k ~= math.floor(k) then return false, "not a count" end
    if isOpen("bank") or isOpen("loot") or LOOT.on() then wanted = { k = k, at = GetTime() }; return true end     -- a bank / bag slot's number
    local ok, why = merchantGuard()
    if not ok then return false, why end
    wanted = { k = k, at = GetTime() }
    return true
end

-- ---------------------------------------------------------------- bank actions (moves are Bags': WC.bagsMove)
local function bankReady()
    if not isOpen("bank") then return false, "no bank open" end
    if type(WC.bagsMove) ~= "function" or type(WC.bagsFirstEmpty) ~= "function" then return false, "the bags addon is too old" end
    return true
end
local function bankSlotArg(a, b)
    -- "<bag> <slot>" from the controller, or "<bag>" + the count cell's number from a finger
    local bag, sl = tonumber(a), tonumber(b)
    if bag and not sl then
        sl = 1
        if wanted and GetTime() - wanted.at <= QTY_SECONDS then sl = wanted.k end
        wanted = nil
    end
    return bag, sl
end
-- /wci bkwd <bag> <slot>   withdraw: to the first free slot of your bags (ONE game action, Bags' move)
function ACT.bkwd(a, b)
    local ok, why = bankReady(); if not ok then return false, why end
    local bag, sl = bankSlotArg(a, b)
    local it = bag and sl and bankItem(bag, sl)
    if not it then return false, "empty" end
    local tb, ts = WC.bagsFirstEmpty("bags", it.id)
    if not tb then return false, ts or "your bags are full" end
    return WC.bagsMove(bag, sl, tb, ts)
end
-- /wci bkdep <bag> <slot>   deposit: to the first free slot of the bank
function ACT.bkdep(a, b)
    local ok, why = bankReady(); if not ok then return false, why end
    local bag, sl = bankSlotArg(a, b)
    local it = bag and sl and bankItem(bag, sl)
    if not it then return false, "empty" end
    local tb, ts = WC.bagsFirstEmpty(BANK.acct and "account" or "bank", it.id)
    if not tb then return false, ts or "the bank is full" end
    return WC.bagsMove(bag, sl, tb, ts)
end
-- /wci bkdo <bag>   a finger's Deposit / Withdraw: the slot is the count cell's number
function ACT.bkdo(a)
    local bag = tonumber(a)
    if not bag then return false, "no slot" end
    if isBankBag(bag) then return ACT.bkwd(a) end
    return ACT.bkdep(a)
end
-- /wci bkmove <bag> <slot> <toBag> <toSlot>   the item in the hand put down (Bags' move, the game's pick-up + put-down)
function ACT.bkmove(a, b, c, d)
    local ok, why = bankReady(); if not ok then return false, why end
    BANK.hand = nil
    local fb, fs, tb, ts = tonumber(a), tonumber(b), tonumber(c), tonumber(d)
    if not (fb and fs and tb and ts) then return false, "no slot" end
    return WC.bagsMove(fb, fs, tb, ts)
end
-- /wci bkpick <bag>   a finger picks the slot (the count cell's number) into the hand; nothing is sent to the game
function ACT.bkpick(a)
    if not isOpen("bank") then return false, "no bank open" end
    local bag, sl = bankSlotArg(a)
    if not (bag and sl and bankItem(bag, sl)) then return false, "empty" end
    BANK.hand = { bag, sl }
    emitSoon()
    return true
end
-- /wci bkput <bag>   a finger puts the hand down on the slot (the count cell's number)
function ACT.bkput(a)
    local bag, sl = bankSlotArg(a)
    local h = BANK.hand
    if not h then return false, "nothing in the hand" end
    return ACT.bkmove(h[1], h[2], bag, sl)
end
function ACT.bkcancel() BANK.hand, BANK.box = nil, false; emitSoon(); return true end
-- /wci bkbox   the purchase box (the next tab's cost); its Yes = bkbuy
function ACT.bkbox()
    if not isOpen("bank") then return false, "no bank open" end
    local ty = bankTypeNow()
    local okN, nx = CALL(function() return C_Bank.FetchNextPurchasableBankTabData(ty) end)
    if not (okN and type(nx) == "table") then return false, "nothing to buy" end
    BANK.box = not BANK.box
    emitSoon()
    return true
end
-- /wci bkbuy   the next bank tab: the game's C_Bank.PurchaseBankTab, inside the press (ONE game action)
function ACT.bkbuy()
    if not (isOpen("bank") and BANK.box) then return false, "open the purchase box first" end
    BANK.box = false
    local ty = bankTypeNow()
    local okN, nx = CALL(function() return C_Bank.FetchNextPurchasableBankTabData(ty) end)
    if not (okN and type(nx) == "table") then return false, "nothing to buy" end
    local cost = TN(function() return nx.tabCost end) or 0
    if (TN(function() return GetMoney() end) or 0) < cost then return false, "not enough money" end
    return run(function() C_Bank.PurchaseBankTab(ty) end)
end
-- /wci bkpage <n>   a page (a bought tab)
function ACT.bkpage(a)
    local pg = tonumber(a)
    if not (pg and isOpen("bank")) then return false, "no bank open" end
    BANK.page, BANK.hand = pg, nil
    emitSoon()
    return true
end
-- /wci bktab char|acct   the two named tabs
function ACT.bktab(a)
    if not isOpen("bank") then return false, "no bank open" end
    local acctT = TN(function() return Enum.BankType.Account end)
    if a == "acct" and not (acctT and TB(function() return C_Bank.CanViewBank(acctT) end)) then return false, "no account bank" end
    BANK.acct, BANK.page, BANK.hand, BANK.box = (a == "acct"), 1, nil, false
    emitSoon()
    return true
end

-- a finger: "Hold to delete" held 1 s on the screen (the app times it), the slot as the count cell's number
local function lootTouch(a, rare)
    local bag = tonumber(a)
    local sl = 1
    if wanted and GetTime() - wanted.at <= QTY_SECONDS then sl = wanted.k end
    wanted = nil
    local ok2, it = CALL(function() return C_Container.GetContainerItemInfo(bag, sl) end)
    local id = ok2 and type(it) == "table" and TN(function() return it.itemID end)
    if not (bag and id) then return false, "empty" end
    local q = TN(function() return it.quality end) or 0
    if q >= 3 and not rare then
        LOOT.rare = { bag = bag, slot = sl, id = id, q = q, name = itemName(id) }
        emitSoon()
        return true
    end
    return ACT.lootdel(bag, sl, id, rare and "rare" or nil)
end
function ACT.lootdelc(a) return lootTouch(a, false) end
-- /wci lookbag <bag>   a finger chose a bag slot on Make Space (the slot as the count): its tooltip, nothing in the game
function ACT.lookbag(a)
    local bag = tonumber(a)
    local sl = 1
    if wanted and GetTime() - wanted.at <= QTY_SECONDS then sl = wanted.k end
    wanted = nil
    local ok2, it = CALL(function() return C_Container.GetContainerItemInfo(bag, sl) end)
    local id = ok2 and type(it) == "table" and TN(function() return it.itemID end)
    if not (bag and id) then return false, "empty" end
    N.look = "bag " .. bag .. " " .. sl .. " " .. id
    emitSoon()
    return true
end
function ACT.lootdelr(a) return lootTouch(a, true) end
function ACT.lootback()
    local rt = LOOT.ms == "quest" and LOOT.retry
    LOOT.full, LOOT.hold, LOOT.rare = false, nil, nil
    if LOOT.ms == "quest" then LOOT.ms, LOOT.retry = nil, nil end
    emitSoon()
    if rt and rt.anyway then return ACT.accept(rt.a, "anyway") end      -- the accept's Back = accept anyway
    return true
end
function ACT.lootno() LOOT.rare = nil; emitSoon(); return true end

-- /wci buy <index> <itemID>   one purchase (the vendor's own stack size), or as many as the
-- quantity tap just before it asked for
function ACT.buy(a, b)
    local i, id = tonumber(a), tonumber(b)
    -- the count is used ONCE, by the buy that follows it, whatever that buy turns out to be
    local k = 1
    if wanted and GetTime() - wanted.at <= QTY_SECONDS then k = wanted.k end
    wanted = nil
    if not (i and id) then return false, "no item" end
    local ok, why = merchantGuard()
    if not ok then return false, why end
    if TN(function() return GetMerchantItemID(i) end) ~= id then return false, "the vendor's list changed" end
    local okI, info = CALL(function() return C_MerchantFrame.GetItemInfo(i) end)
    if not (okI and type(info) == "table") then return false, "item not readable" end
    if TB(function() return info.hasExtendedCost end) then return false, "costs more than money" end
    if TB(function() return info.isPurchasable end) == false then return false, "cannot be bought" end
    local left = TN(function() return info.numAvailable end)
    if left == 0 then return false, "sold out" end
    local price = TN(function() return info.price end) or 0
    local stack = TN(function() return info.stackCount end) or 1
    if stack < 1 then stack = 1 end
    if k == 1 then
        if (TN(function() return GetMoney() end) or 0) < price then return false, "not enough money" end
        -- no count: what a plain click on the vendor's button does
        local done, err = run(function() BuyMerchantItem(i) end)
        return done, err, stack
    end
    -- The game counts ITEMS, in steps of the vendor's stack, up to the item's full stack: the
    -- same limits its own split-stack box applies (MerchantFrame.lua).
    local n = k * stack
    local most = TN(function() return GetMerchantItemMaxStack(i) end) or stack
    if n > most then return false, "at most " .. most .. " at a time" end
    if left and left > 0 and k > left then return false, "only " .. left .. " left" end
    if (TN(function() return GetMoney() end) or 0) < price * k then return false, "not enough money" end
    local done, err = run(function() BuyMerchantItem(i, n) end)
    return done, err, n
end

-- /wci back <index> <itemID>   buy an item back
function ACT.back(a, b)
    local i, id = tonumber(a), tonumber(b)
    if not (i and id) then return false, "no item" end
    local ok, why = merchantGuard()
    if not ok then return false, why end
    if linkID(TS(function() return GetBuybackItemLink(i) end)) ~= id then return false, "the buyback list changed" end
    return run(function() BuybackItem(i) end)
end

-- /wci sell <bag> <slot> <itemID>   sell one stack. With a vendor open, using a bag item sells it.
-- With NO vendor open the same call would use or equip it, so the guard below is the whole point.
function ACT.sell(a, b, c)
    local bag, slot, id = tonumber(a), tonumber(b), tonumber(c)
    if not (bag and slot and id) then return false, "no item" end
    if bag < 0 or bag > BAGS_LAST then return false, "not a carried bag" end
    local ok, why = merchantGuard()
    if not ok then return false, why end
    if frameShown("merchant") == false then return false, "no vendor open" end
    local okI, info = CALL(function() return C_Container.GetContainerItemInfo(bag, slot) end)
    if not (okI and type(info) == "table") then return false, "that slot is empty" end
    if TN(function() return info.itemID end) ~= id then return false, "a different item is there now" end
    if TB(function() return info.isLocked end) then return false, "item is locked" end
    if TB(function() return info.hasNoValue end) then return false, "the vendor will not buy it" end
    return run(function() C_Container.UseContainerItem(bag, slot) end)
end

-- /wci repair   repair everything, from the player's own money
function ACT.repair()
    local ok, why = merchantGuard()
    if not ok then return false, why end
    if not TB(function() return CanMerchantRepair() end) then return false, "this vendor cannot repair" end
    local r = R(function() return GetRepairAllCost() end)
    local cost = r and PN(r[1]) or 0
    if cost <= 0 then return false, "nothing to repair" end
    if (TN(function() return GetMoney() end) or 0) < cost then return false, "not enough money" end
    return run(function() RepairAllItems() end)
end

-- /wci junk   sell every grey item
function ACT.junk()
    local ok, why = merchantGuard()
    if not ok then return false, why end
    local _, junk = sellList()
    if (junk or 0) == 0 then return false, "no junk to sell" end
    return run(function() C_MerchantFrame.SellAllJunkItems() end)
end

-- One command, from the pad or from chat: "<verb> <args...>".
local lastActAt = 0         -- GetTime() of the last action WE ran, for the money watchdog
local function dispatch(cmd)
    lastActAt = TN(function() return GetTime() end) or 0
    local w = {}
    for word in cmd:gmatch("%S+") do w[#w + 1] = word end
    local fn = ACT[w[1]]
    if not fn then ack(cmd, false, "unknown command"); return end
    local ok, done, why, n = pcall(fn, w[2], w[3], w[4], w[5])
    if not ok then ack(cmd, false, "threw"); return end
    -- A count that was taken needs no answer of its own: the buy that follows reports how many it
    -- bought, and every answer costs a flush. A count that was REFUSED is worth the line.
    if (w[1] == "qty" or w[1] == "alive" or w[1] == "look" or w[1] == "lookbag") and done then return end
    ack(cmd, done, why, done and n or nil)
    emitSoon()
end

-- ---------------------------------------------------------------- controller: taking the buttons

local PAD_KEYS = { "PADDUP", "PADDDOWN", "PADDLEFT", "PADDRIGHT", "PAD1", "PAD2", "PAD3", "PAD4" }
local PAD_DIR = { PADDUP = "up", PADDDOWN = "down", PADDLEFT = "left", PADDRIGHT = "right" }
local PAD_SET = {}
for _, k in ipairs(PAD_KEYS) do PAD_SET[k] = true end
-- CONTRACT VERSION 4: the buttons beyond the eight a guest may ask for (`extra`), delivered like the
-- face buttons and kept from the game while listed. First need: Select (PADBACK) as "back" in the
-- Spellbook's Bind box, so that B stays a button a spell can go on (user, 2026-09-29: "in that menu
-- lets just use the select button for back").
local EXTRA_OK = { PADBACK = true, PADFORWARD = true, PADLSHOULDER = true, PADRSHOULDER = true,
                   PADLTRIGGER = true, PADRTRIGGER = true, PADLSTICK = true, PADRSTICK = true }
local padOwner = CreateFrame("Frame", "WowCompanionInteractPadOwner", UIParent)
local padSink = CreateFrame("Button", "WowCompanionInteractPad", UIParent)
padSink:RegisterForClicks("AnyDown", "AnyUp")
-- the frame that hears controller buttons directly; shown only while we hold
local padGrab = CreateFrame("Frame", "WowCompanionInteractGrab", UIParent)
pcall(function()
    padGrab:SetFrameStrata("TOOLTIP")
    padGrab:SetFrameLevel(9000)
    padGrab:SetSize(1, 1)
    padGrab:SetPoint("TOPLEFT", UIParent, "TOPLEFT", 0, 0)
    padGrab:Hide()
end)

-- THE SHARED CAPTURE (WowCompanion.capture, at the end of this section). One holder at a time:
-- the NPC windows (this file) or ONE guest, e.g. the Bags room. `G` is the guest.
local G = { holder = nil, spec = nil, chord = nil, eat = nil }
-- the extra keys the guest holds right now, as a list (bindings, re-assertion)
local function extraKeys()
    local out = {}
    local ex = G.holder and G.spec and G.spec.extra
    if ex then for k in pairs(ex) do out[#out + 1] = k end end
    table.sort(out)
    return out
end

-- ---------------------------------------------------------------- controller: hiding the game's window

-- what the frame probe found visible only while a window is open (2026-09-28), by window
local HIDE = {
    gossip = { "GossipFrame" },
    quest = { "QuestFrame" },
    merchant = { "MerchantFrame", "ContainerFrameCombinedBags", "GamepadBagBar" },
    trainer = { "ClassTrainerFrame" },
    stable = { "PetStableFrame" },
    petition = { "PetitionFrame" },
    -- the game opens the bags with the mailbox and the auction house as it does at a vendor (user, 2026-09-30:
    -- "it opens backpack in the top screen"): hidden by alpha the same way, never closed
    mail = { "MailFrame", "OpenMailFrame", "ContainerFrameCombinedBags", "GamepadBagBar" },
    auction = { "AuctionHouseFrame", "ContainerFrameCombinedBags", "GamepadBagBar" },
    taxi = { "FlightMapFrame", "TaxiFrame" },
    bank = { "BankFrame", "ContainerFrameCombinedBags", "GamepadBagBar" },
    loot = { "LootFrame" },
}
local HIDE_ALWAYS = { "GamepadPersistentInputLegend" }
local hidden = {}           -- frame or region -> the alpha it had

-- THE SHIELD (user, 2026-09-28: "Nothing, shield over that window"). Alpha 0 makes a window
-- invisible; its buttons still take a finger on the top screen. So while a window is hidden, a
-- frame of OUR OWN lies over its rectangle and swallows the taps. The game's frames are still
-- only ever touched by alpha. Above the game's windows and dialogs, below the tap pad's cells.
-- Released by the same paths as the hiding; a reload starts with none.
local shieldPool, shieldOf = {}, {}         -- spare frames; target -> { frame, owner, box }

-- The rectangle to cover, in SCREEN units (a frame's own units times its effective scale): the
-- window AND what hangs outside it. Seen on the Thor: the vendor's tabs sit below its frame, and
-- the frame has a scale of its own, so its plain rectangle is neither the right size nor all of it.
-- Children and their children, shown ones only.
local SHIELD_KIDS = 120
local function shieldBox(target)
    local L, B, Rt, T
    local seen = 0
    local function add(f)
        local r = R(function() return f:GetRect() end)
        local sc = TN(function() return f:GetEffectiveScale() end)
        if type(r) ~= "table" or not sc then return end
        local l, b, w, h = PN(r[1]), PN(r[2]), PN(r[3]), PN(r[4])
        if not (l and b and w and h) or w <= 0 or h <= 0 then return end
        l, b, w, h = l * sc, b * sc, w * sc, h * sc
        L = math.min(L or l, l)
        B = math.min(B or b, b)
        Rt = math.max(Rt or (l + w), l + w)
        T = math.max(T or (b + h), b + h)
    end
    local function walk(f, depth)
        add(f)
        if depth >= 2 then return end
        pcall(function()
            for _, kid in ipairs({ f:GetChildren() }) do
                seen = seen + 1
                if seen > SHIELD_KIDS then return end
                if type(kid) == "table" and kid.IsShown and kid:IsShown() then walk(kid, depth + 1) end
            end
        end)
    end
    walk(target, 0)
    if not L then return nil end
    return { L, B, Rt - L, T - B }
end
local function shieldClear(target)
    local e = shieldOf[target]
    if not e then return end
    pcall(function() e.frame:EnableMouse(false) end)
    pcall(function() e.frame:Hide() end)
    shieldPool[#shieldPool + 1] = e.frame
    shieldOf[target] = nil
end
local function shieldCover(target, owner)
    if type(target) ~= "table" then return end
    if TB(function() return target:IsShown() end) ~= true then shieldClear(target); return end
    local box = shieldBox(target)
    local us = TN(function() return UIParent:GetEffectiveScale() end)
    local e = shieldOf[target]
    local f = e and e.frame or table.remove(shieldPool) or CreateFrame("Frame", nil, UIParent)
    local ok = pcall(function()
        f:SetFrameStrata("FULLSCREEN_DIALOG")
        f:SetFrameLevel(100)
        f:ClearAllPoints()
        if box and us and us > 0 then
            -- placed by numbers, in our parent's units: it follows the window at every document
            f:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", box[1] / us, box[2] / us)
            f:SetSize(box[3] / us, box[4] / us)
        else
            f:SetAllPoints(target)
        end
        f:EnableMouse(true)
        f:Show()
    end)
    if ok then
        shieldOf[target] = { frame = f, owner = owner, box = box }
    elseif e then
        shieldClear(target)
    else
        pcall(function() f:Hide() end)
        shieldPool[#shieldPool + 1] = f
    end
end
local function shieldClearAll(owner)
    local gone = {}
    for target, e in pairs(shieldOf) do
        if owner == nil or e.owner == owner then gone[#gone + 1] = target end
    end
    for _, target in ipairs(gone) do shieldClear(target) end
end
-- Where the shields lie: { left, bottom, width, height } each, in SCREEN units (the game's 768
-- high screen: a pixel is a unit times the screen's height / 768). Empty = laid over the frame
-- itself, its place unknown to us.
local function shieldRects()
    local out = {}
    for _, e in pairs(shieldOf) do
        local b = e.box
        if b then
            out[#out + 1] = { math.floor(b[1] + 0.5), math.floor(b[2] + 0.5), math.floor(b[3] + 0.5), math.floor(b[4] + 0.5) }
        else
            out[#out + 1] = {}
        end
    end
    return out
end
WC.shield = { cover = shieldCover, clear = shieldClear, clearAll = shieldClearAll, rects = shieldRects }

local function dim(obj)
    if type(obj) ~= "table" or hidden[obj] ~= nil then return end
    local ok, a = pcall(function() return obj:GetAlpha() end)
    if not ok or type(a) ~= "number" then return end
    if pcall(function() obj:SetAlpha(0) end) then
        hidden[obj] = a
        N.hid = N.hid + 1
    end
end

-- The game's item tooltip. The cursor of the game's own (invisible) window still rests on an item,
-- so its tooltip stood on the top screen (seen on the Thor, 2026-09-28). The game sets this
-- frame's alpha itself as it fades it: it is put to 0 again at every document, and back to 1.
local function dimTip()
    local tip = _G.GameTooltip
    if type(tip) ~= "table" then return end
    if not pcall(function() tip:SetAlpha(0) end) then return end
    if hidden[tip] == nil then
        hidden[tip] = 1
        N.hid = N.hid + 1
    end
end

function N.unhide()
    shieldClearAll("npc")
    for obj, a in pairs(hidden) do
        pcall(function() obj:SetAlpha(a) end)
        hidden[obj] = nil
    end
    N.hid = 0
end

-- Frames whose OWN show animation fades them in after our alpha 0 (the loot window: ScrollingFlatPanelMixin.Open plays the
-- panel's fade-in; the user saw it on the top screen, 2026-10-01): their alpha 0 is put back at every document, as the
-- tooltip's is, and once more when the game's fade has run.
-- b-loot-flash (the board, 2026-10-13: "It should never appear on top"): the fade (ShowAnim, 0.1 s, setToFinalAlpha) drives
-- the FRAME's own alpha, so between its end and our next document the window stood at 1. Its PIECES (children, regions) are
-- dimmed as well: what is drawn is the frame's alpha times theirs, so the fade has nothing to show. Put back by N.unhide.
local STICKY = { LootFrame = true }
local function dimPieces(fr)
    pcall(function() for _, ch in ipairs({ fr:GetChildren() }) do dim(ch) end end)
    pcall(function() for _, rg in ipairs({ fr:GetRegions() }) do dim(rg) end end)
end
function N.hide(doc)
    for kind, names in pairs(HIDE) do
        if doc[kind] then
            for _, name in ipairs(names) do
                dim(_G[name])
                if STICKY[name] and hidden[_G[name]] ~= nil then
                    pcall(function() _G[name]:SetAlpha(0) end)
                    dimPieces(_G[name])
                end
                if hidden[_G[name]] ~= nil then shieldCover(_G[name], "npc") end
            end
        end
    end
    for _, name in ipairs(HIDE_ALWAYS) do dim(_G[name]) end
    dimTip()
    -- The controller's cursor: the game sets the alpha of the frame ITSELF as it shows and hides
    -- it, and reads it back (IsCursorHidden). So the frame is left alone and its pieces are dimmed.
    local cursor = _G.SmartNavigation
    if type(cursor) == "table" then
        pcall(function() for _, c in ipairs({ cursor:GetChildren() }) do dim(c) end end)
        pcall(function() for _, r in ipairs({ cursor:GetRegions() }) do dim(r) end end)
    end
end

local function inCombat()
    return TB(function() return InCombatLockdown() end) == true
end

-- CAPTURE VERSION 5: THE BAR'S SELECT LISTENER (Home, 2026-09-30, LATER 7: "press select to get a
-- controller selector that starts on the home button"). ONLY Select (PADBACK) is bound, on an owner of
-- its own, and only while the app wants it (`sel on`), the companion is in charge, NOBODY holds the
-- controller (no NPC window, no guest), there is no fight, and no dialog waits for the game's focus key
-- (an unfocused invite: Select IS that key, Dialogs.lua d10's line promises it). A press is one
-- document {type:"cappad", room:"bar", k:"PADBACK", n}. Everything else Select does stays the game's.
N.bar = { want = false, bound = false, n = 0, re = 0 }
local selOwner = CreateFrame("Frame", "WowCompanionInteractSelOwner", UIParent)
local selSink = CreateFrame("Button", "WowCompanionInteractSel", UIParent)
selSink:RegisterForClicks("AnyDown", "AnyUp")
selSink:SetScript("OnClick", function(_, key, down)
    if down or not N.bar.bound then return end      -- on the release, like the face buttons
    N.bar.n = N.bar.n + 1
    WC.send(WC.encode({ type = "cappad", room = "bar", k = "PADBACK", n = N.bar.n }))
    WC.push()
end)
local SEL_BIND = "CLICK WowCompanionInteractSel:PADBACK"
function N.selSync()
    if inCombat() then return end                   -- frozen; PLAYER_REGEN_DISABLED already let go
    local unfocused = 0
    if type(WC.dialogs) == "table" and type(WC.dialogs.unfocused) == "function" then
        local ok, n = pcall(WC.dialogs.unfocused)
        if ok and type(n) == "number" then unfocused = n end
    end
    local want = N.bar.want and N.mode and not N.holding and not G.holder and unfocused == 0
    if want and not N.bar.bound then
        if pcall(function() SetOverrideBindingClick(selOwner, true, "PADBACK", "WowCompanionInteractSel", "PADBACK") end) then
            N.bar.bound = true
            N.bar.re = 0
        end
    elseif not want and N.bar.bound then
        if pcall(function() ClearOverrideBindings(selOwner) end) then N.bar.bound = false end
    end
end
-- the guest's frames, by name; a name the client does not have, or a frame that is not shown, is fine
local function guestHide()
    if not (G.holder and (N.frameSeen or N.trusted)) then return end
    for _, name in ipairs(type(G.spec.hide) == "table" and G.spec.hide or {}) do
        if type(name) == "string" then
            dim(_G[name])
            if hidden[_G[name]] ~= nil then shieldCover(_G[name], "npc") end
        end
    end
    N.hide({})
end

local padBind               -- defined with the bindings, further down

-- CAPTURE v10: A ROOM KEEPS THE CONTROLLER THROUGH A FIGHT ON SPELLS' ROUTER (i81; SPEC_COMBAT items 4-12, the user's
-- ControllerForever pattern). MEASURED on this client: a grab frame cannot be switched in a fight (EnableGamePadButton is a
-- protected function, silently ignored: the 10:47 / 11:03 dead pad); our bindings cannot change in a fight; [combat]
-- STATE-DRIVER snippets never run here (i80, 32 fights); CLICK-WRAPPED secure snippets DO run in a fight (Spells' m21 B).
-- So in a fight the pad is Spells' router (PadMap m22: permanent overrides on A B X Y + the D-pad to secure buttons, bound
-- from each fight's start to its end) and this file only flips the router's per-button attribute "wc-mode":
--   * a room (or an NPC window) holds at PLAYER_REGEN_DISABLED (before the lockdown): our frame goes fully off, our
--     bindings go, every router button gets wc-mode "room" (+ wc-room-dest: our sink for faces and D-pad, the LEAVE button
--     for B). The room gets its presses, the game none.
--   * LEAVE (B in room mode, or the app's tap on doc.leave / doc.fightLeave = idlg col 24): WowCompanionRoomLeave's wrapped
--     snippet sets wc-mode nil on every router button through frame refs, and the same click clicks the game window's
--     close under the room (aimed before the lockdown). Its post-hook lets the holder go (lost "B", rel B). The next press
--     is the fight's.
--   * ENTER (a room's take, or an NPC window, during a fight): the take is PENDING (doc.enter = idlg col 25, n) until the
--     app's tap lands on WowCompanionRoomEnter, whose snippet sets wc-mode "room"; its post-hook makes the holder real.
--   * the fight ends with a holder: back to the frame route, the room stays (item 8).
--   * no router / no secure cells (the pad without secure rows): the old release at the fight start.
local FIGHT_KEEP = true
N.fightKeep = FIGHT_KEEP

-- v8: the window under the room and the close button the game's own B would click; nil, nil = no window open; false = one
-- is open that cannot be closed by a click of ours (no close button), or another game window is focused too
local F = {}               -- v9: the fight hold's pieces (one local: the main chunk is at its 200)
function F.shown(f) return type(f) == "table" and TB(function() return f:IsShown() end) == true end
-- capture v14 (Home, 2026-10-06: Social's B clicked nothing on the new SocialUIFrame; its macro clicks the Friends /
-- Communities close buttons): would a click on button <name> close a window that is shown now? false only when it surely
-- would not: a macro whose every /click names no button or one not visible, a click button whose target is not visible,
-- a button not visible itself. A macro with no /click (Professions' /stopcasting) and anything unreadable count as yes.
function F.closes(name, depth, hide)
    local b = type(name) == "string" and _G[name]
    if type(b) ~= "table" then return false end
    depth = depth or 0
    if depth > 3 then return true end
    local kind = TS(function() return b:GetAttribute("type") end) or TS(function() return b:GetAttribute("type1") end)
    if kind == "macro" then
        local mt = TS(function() return b:GetAttribute("macrotext") end) or TS(function() return b:GetAttribute("macrotext1") end)
        if not mt or not mt:find("/click") then return true end
        for t in mt:gmatch("/click%s+([%w_]+)") do
            if t ~= name and F.closes(t, depth + 1, hide) then return true end
        end
        return false
    end
    if kind == "click" then
        local cb = T(function() return b:GetAttribute("clickbutton") end)
        return type(cb) == "table" and F.seen(cb, hide)
    end
    return F.seen(b, hide)
end
-- capture v17 (Spells, b92: the Spellbook's B showed the window instead of closing it): the game HIDES a window's close button in
-- controller mode (PlayerSpellsFrameMixin / ProfessionsMixin :InitializeGamepad -> CloseButton:Hide()), and a click on it still
-- closes the window. So a button counts while it OR the window it sits on (its parent) is visible; unreadable counts as yes.
function F.seen(x, hide)
    if TB(function() return x:IsVisible() end) ~= false then return true end
    local p = T(function() return x:GetParent() end)
    if type(p) == "table" and TB(function() return p:IsVisible() end) == true then return true end
    -- v18 (b93 Talents, "stays open when pressing B"): the button may sit in a container the game hides too; it counts while a
    -- guest window (spec.hide) that holds it anywhere below is visible
    for _, n in ipairs(type(hide) == "table" and hide or {}) do
        local w = type(n) == "string" and _G[n]
        if type(w) == "table" and TB(function() return w:IsVisible() end) == true then
            local a = p
            for _ = 1, 8 do
                if type(a) ~= "table" then break end
                if a == w then return true end
                local up = a
                a = T(function() return up:GetParent() end)
            end
        end
    end
    return false
end
function F.target()
    local sp = G.spec or {}
    local win, btn
    -- capture v9: spec.leave (the window's own close, e.g. Professions while a craft runs: its back is /stopcasting) first
    local named = (type(sp.leave) == "string" and F.closes(sp.leave, 0, sp.hide)) and sp.leave
                  or (type(sp.backWant or sp.back) == "string" and F.closes(sp.backWant or sp.back, 0, sp.hide)) and (sp.backWant or sp.back) or nil
    if named then
        btn = _G[named]
        win = T(function() return btn:GetParent() end)
        if not F.shown(win) then win = nil end
    end
    if not btn then
        for _, name in ipairs(type(sp.hide) == "table" and sp.hide or {}) do
            local f = type(name) == "string" and _G[name]
            if F.shown(f) then
                win = f
                local cb = T(function() return f.CloseButton end)
                btn = type(cb) == "table" and cb or nil
                break
            end
        end
    end
    -- every window the game's controller manager has open must be this one (read only)
    local others = false
    pcall(function()
        local m = GamepadMode and GamepadMode.FrameControlsManager
        for _, f in ipairs(m and m.shownFrames or {}) do
            if f ~= win and F.shown(f) then others = true end
        end
    end)
    if others then return false end
    if win and not btn then return false end
    return win, btn
end
-- the secure pieces (made at load, out of combat; a client without them simply never arms)
-- one wrapped-click snippet for both buttons: every router button (frame refs "wc-r-1" .. "wc-r-<wc-rn>") gets this button's
-- "wc-set" as its wc-mode ("room" for ENTER, nothing for LEAVE)
F.FLIP = [=[
    local set = self:GetAttribute("wc-set")
    local n = self:GetAttribute("wc-rn") or 0
    for i = 1, n do
        local r = self:GetFrameRef("wc-r-" .. i)
        if r then r:SetAttribute("wc-mode", set) end
    end
]=]
pcall(function()
    F.hold = CreateFrame("Frame", "WowCompanionFightHold", UIParent, "SecureHandlerBaseTemplate")
    F.leave = CreateFrame("Button", "WowCompanionRoomLeave", UIParent, "SecureActionButtonTemplate")
    F.leave:RegisterForClicks("AnyDown", "AnyUp")
    SecureHandlerWrapScript(F.leave, "OnClick", F.hold, F.FLIP)
    F.enter = CreateFrame("Button", "WowCompanionRoomEnter", UIParent, "SecureActionButtonTemplate")
    F.enter:RegisterForClicks("AnyDown", "AnyUp")
    F.enter:SetAttribute("wc-set", "room")
    SecureHandlerWrapScript(F.enter, "OnClick", F.hold, F.FLIP)
end)
-- the router's buttons (Spells, PadMap m22: made at login, never destroyed) and what each one delivers in room mode
F.ROUTER = { { "WowCompanionCombatFace8", "PAD1" }, { "WowCompanionFaceB", "PAD2" }, { "WowCompanionCombatFace5", "PAD3" },
             { "WowCompanionCombatFace6", "PAD4" }, { "WowCompanionFaceD1", "PADDLEFT" }, { "WowCompanionFaceD2", "PADDUP" },
             { "WowCompanionFaceD3", "PADDRIGHT" }, { "WowCompanionFaceD4", "PADDDOWN" } }
-- out of combat or before the lockdown: refs + destinations + the two cells; true = a fight hold is possible
function N.routerArm()
    N.ra = nil
    if not (F.hold and F.leave and F.enter) then return false end
    local pm = WC.padMap
    if not (type(pm) == "table" and type(pm.routerReady) == "function" and pm.routerReady()) then return false end
    local cell, row, col, ecell, erow, ecol
    if type(WC.dialogs) == "table" and type(WC.dialogs.closeCell) == "function" then cell, row, col = WC.dialogs.closeCell() end
    if type(WC.dialogs) == "table" and type(WC.dialogs.enterCell) == "function" then ecell, erow, ecol = WC.dialogs.enterCell() end
    if not (cell and ecell) then return false end
    local btns = {}
    for _, e in ipairs(F.ROUTER) do
        local b = _G[e[1]]
        if type(b) ~= "table" then return false end
        btns[#btns + 1] = b
    end
    local ok = pcall(function()
        for i, b in ipairs(btns) do
            b:SetAttribute("wc-room-dest", (F.ROUTER[i][2] == "PAD2") and "WowCompanionRoomLeave" or "WowCompanionInteractPad")
            SecureHandlerSetFrameRef(F.leave, "wc-r-" .. i, b)
            SecureHandlerSetFrameRef(F.enter, "wc-r-" .. i, b)
        end
        F.leave:SetAttribute("wc-rn", #btns)
        F.enter:SetAttribute("wc-rn", #btns)
        cell:SetAttribute("clickbutton", F.leave)
        ecell:SetAttribute("clickbutton", F.enter)
    end)
    if not ok then return false end
    N.ra = { btns = btns, row = row, col = col, erow = erow, ecol = ecol }
    return true
end
-- out of combat / before the lockdown only: every router button to a mode ("room" or nil)
function N.routerMode(mode)
    if not N.ra or inCombat() then return end
    for _, b in ipairs(N.ra.btns) do pcall(function() b:SetAttribute("wc-mode", mode) end) end
end

-- before the lockdown: aim the leave button (and the app's fight cell at it); true = the room may keep the controller
function N.fightArm()
    N.fc = nil
    if not N.ra then return false end
    local win, btn = F.target()
    if win == false then win, btn = nil, nil end         -- several game windows: the leave closes none of them (the router wins anyway)
    pcall(function()
        F.leave:SetAttribute("type", btn and "click" or nil)
        F.leave:SetAttribute("clickbutton", btn)
    end)
    N.fc = { row = N.ra.row, col = N.ra.col }
    return true
end

-- the leave button was clicked in a fight: its snippet already cleared the bindings; the room is let go
function N.fightLeft()
    if not N.fight or not N.holding then return end
    local id, spec = G.holder, G.spec
    if not id then N.npcLeft = true end
    N.fight = nil
    if N.lend == id then N.lend = nil end
    G.holder, G.spec, G.chord, G.eat = nil, nil, nil, nil
    N.unhide()
    N.holding = false
    N.npcExtra = {}
    N.backAt = nil
    N.relN = N.relN + 1
    N.rel = { n = N.relN, by = "B", who = id or "npc" }
    if spec and type(spec.lost) == "function" then pcall(spec.lost, "B") end
    emitSoon()
end
-- THE GENERIC LEAVE (Home, 2026-10-01; the user: rooms "should act the EXACT same for all rooms"): the SAME secure button out
-- of a fight. One click on the idlg cell (doc.leave) clicks the holder's own back / close (aimed out of combat, the button
-- the game's own B would click) and lets the holder go as "left" (lost("left")); a holder with no game window is only let go.
-- REFUSED for LEAVE_YOUNG seconds after a take: the close is not aimed yet, and the release checks the age, so a tap that
-- lands after a NEW room took can never close the new room.
local LEAVE_YOUNG = 2
function N.leaveOut()
    if N.fight or not G.holder or inCombat() then return end
    local t = TN(function() return GetTime() end) or 0
    if t - (N.takeAt or 0) < LEAVE_YOUNG then return end
    local id, spec = G.holder, G.spec
    if N.lend == id then N.lend = nil end
    G.holder, G.spec, G.chord, G.eat = nil, nil, nil, nil
    N.release("left", id)
    if spec and type(spec.lost) == "function" then pcall(spec.lost, "left") end
    emitSoon()
end
-- out of combat, at every document: aim the leave button at the holder's close (or at nothing while the take is young)
function N.leaveAim()
    N.lc = nil
    if inCombat() or not F.leave or not G.holder then return end
    local cell, row, col
    if type(WC.dialogs) == "table" and type(WC.dialogs.closeCell) == "function" then cell, row, col = WC.dialogs.closeCell() end
    if not cell then return end
    local t = TN(function() return GetTime() end) or 0
    local young = t - (N.takeAt or 0) < LEAVE_YOUNG
    local win, btn = F.target()
    local aim = (not young and win and btn) or nil
    pcall(function()
        F.leave:SetAttribute("type", aim and "click" or nil)
        F.leave:SetAttribute("clickbutton", aim)
        cell:SetAttribute("clickbutton", F.leave)
    end)
    N.lc = { row = row, col = col }
    if young and not F.aging then
        F.aging = true
        pcall(C_Timer.After, LEAVE_YOUNG + 0.1, function() F.aging = nil; emitSoon() end)
    end
end
if F.leave then
    pcall(function() F.leave:HookScript("OnClick", function() if N.fight then N.fightLeft() else N.leaveOut() end end) end)
end
-- THE ENTER landed (its snippet set the router to room): the pending holder becomes real
N.enterN = 0
function N.fightEntered()
    local pe = N.pend
    if not (inCombat() and N.ra) or N.holding then return end      -- repeats of one tap, or a tap out of a fight: nothing
    if not pe then
        -- a stray ENTER (nothing pending): its snippet already set the router to room; held by nobody's room, so that B
        -- (always the leave in room mode) brings the fight back
        N.fight, N.holding, N.npcExtra = "router", true, {}
        N.fc = { row = N.ra.row, col = N.ra.col }
        emitSoon()
        return
    end
    N.pend = nil
    N.fight = "router"
    N.holding = true
    N.npcExtra = {}
    N.fc = { row = N.ra.row, col = N.ra.col }
    if pe.id ~= "npc" then
        G.holder, G.spec, G.chord, G.eat = pe.id, pe.spec, nil, nil
        G.bnext = "home"
        N.takeAt = TN(function() return GetTime() end) or 0
        guestHide()
    end
    emitSoon()
end
if F.enter then pcall(function() F.enter:HookScript("OnClick", function() N.fightEntered() end) end) end
-- v11: the queue (one place: the latest requester)
function N.queue(id, spec)
    local old = N.wait
    if old and old.id ~= id and type(old.spec.lost) == "function" then pcall(old.spec.lost, "taken") end
    N.wait = { id = id, spec = spec }
    emitSoon()
    return true, "queued"           -- v11: ok, but NOT held yet (Professions: no game window opened before the grant)
end
-- at every document: nobody holds any more -> the waiter takes (out of a fight at once; in a fight it becomes a pending ENTER)
function N.grantWait()
    local w = N.wait
    if not w or G.holder or N.holding or N.pend then return end
    N.wait = nil
    local ok, got, why = pcall(WC.capture.take, w.id, w.spec)
    if not (ok and got) and type(w.spec.lost) == "function" then pcall(w.spec.lost, tostring(why or "refused")) end
end
-- a take (or an NPC window) wants the controller during a fight: pending until the app's tap lands on the ENTER cell
function N.wantEnter(id, spec)
    if not N.ra then return false end
    if N.pend and N.pend.id == id then return true end
    N.enterN = N.enterN + 1
    N.pend = { id = id, spec = spec, n = N.enterN }
    emitSoon()
    return true
end

-- the capture lets go of the guest by itself: "combat", "npc"
local function guestLost(why)
    if not G.holder then return end
    local spec = G.spec
    if N.lend == G.holder then N.lend = nil end
    G.holder, G.spec, G.chord, G.eat = nil, nil, nil, nil
    N.unhide()
    -- what the guest had left to the game is ours again, for whoever holds next; its extra keys
    -- go back to the game
    if N.holding and not inCombat() then
        for _, k in ipairs(PAD_KEYS) do padBind(k) end
        if spec and spec.extra then
            for k in pairs(spec.extra) do pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
        end
    end
    if type(spec.lost) == "function" then pcall(spec.lost, why) end
    -- v20: the room suspended under an overlay is lost the same way (an NPC window, a fight without the router, a pre-emption)
    local u = G.under
    if u then
        G.under = nil
        if N.holding and not inCombat() and u.spec and u.spec.extra then
            for k in pairs(u.spec.extra) do pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
        end
        if u.spec and type(u.spec.lost) == "function" then pcall(u.spec.lost, why) end
    end
end

-- WHY the controller goes back, for the app (user, 2026-09-28: "Pressing b to leave the cursor
-- should take me back to the home by default for everything"): "B" = the player left with B;
-- "closed" = the window went by itself or by an answer; "combat"; "mode" = the companion is no
-- longer in charge; "npc" = an NPC window took it from a guest; or a guest's own word.
local B_WINDOW = 3          -- seconds in which a window that closes counts as closed by that B
function N.release(why, who)
    -- v9: a room holding through a fight is let go ONLY by the secure leave button (N.fightLeft)
    if N.fight then return end
    -- the game's window comes back first: it must never stay invisible with nobody driving it
    N.unhide()
    pcall(function() padGrab:EnableGamePadButton(false) end)
    pcall(function() padGrab:EnableGamePadStick(false) end)
    N.stickOn = false
    pcall(function() padGrab:Hide() end)
    if not N.holding then return end
    -- Not in combat: bindings cannot be changed there. Outside v8, combat never finds us holding, because
    -- PLAYER_REGEN_DISABLED lets go first.
    N.npcExtra = {}
    if pcall(function() ClearOverrideBindings(padOwner) end) then
        N.holding = false
        local t = TN(function() return GetTime() end) or 0
        if not why then why = (N.backAt and t - N.backAt <= B_WINDOW) and "B" or "closed" end
        N.backAt = nil
        N.relN = N.relN + 1
        N.rel = { n = N.relN, by = why, who = who or "npc" }
    end
end

-- CONTRACT VERSION 2: what a guest may leave to the GAME. Lesson of 2026-09-28 22:57: a guest that
-- closed the game's window by calling the game's own Lua function from a press poisoned the game's
-- controller code (2,050 refused calls). So the close is the game's to do, in the game's own code:
--   spec.back = "<global button>"   B is a CLICK on that button, through a binding
--   spec.pass = { "PAD2", ... }     these keys go to the game untouched
-- wantOf(k): what key k is bound to while we hold: our sink, the guest's button, or nil = not ours.
local function wantOf(k)
    local sp = G.holder and G.spec
    if sp then
        if sp.pass and sp.pass[k] then return nil end
        if k == "PAD2" and sp.back then return sp.back, "LeftButton" end
        if k == "PAD1" and sp.a then return sp.a, "LeftButton" end
    end
    -- the charter (i108): A on one of its buttons is a binding click of the secure button that presses the game's own
    if k == "PAD1" and not G.holder and N.petA then return N.petA, "LeftButton" end
    -- the key's own name rides along as the "mouse button", so one sink serves all eight
    return "WowCompanionInteractPad", k
end

function padBind(k)
    local button, mouse = wantOf(k)
    if not button then
        return pcall(function() SetOverrideBinding(padOwner, true, k, nil) end)
    end
    return pcall(function() SetOverrideBindingClick(padOwner, true, k, button, mouse) end)
end
N.padBind = function(k) return padBind(k) end      -- for N.build (declared above this)

-- does the frame keep this key of the eight for us right now?
local function keeps(k)
    local sp = G.holder and G.spec
    if not sp then return true end
    if sp.pass and sp.pass[k] then return false end
    -- an armed chord comes before `back`: B is its cancel
    if k == "PAD2" and sp.back and not G.chord then return false end
    if k == "PAD1" and sp.a then return false end
    return true
end

function N.hold()
    if N.holding or inCombat() then return end
    local any = false
    for _, k in ipairs(PAD_KEYS) do
        if padBind(k) then any = true end
    end
    -- the direct way in; a build without it still has the bindings
    local direct = pcall(function()
        padGrab:Show()
        padGrab:EnableGamePadButton(true)
    end)
    N.holding = any or direct
    N.reasserts = 0
    N.selSync()                 -- v5: the bar's Select listener stands aside while an NPC window holds
end

-- THE STICKS are the game's (user bug 2026-09-30: "My control stick is not working inside of rooms ... the control
-- stick should move my charactor"). A frame with stick input on takes BOTH sticks, so the grab turns it on only
-- while an NPC WINDOW holds (the right stick scrolls its tooltips; see onStick), never while a room holds as guest.
N.stickOn = false
function N.stickSync(npcOpen)
    local want = (N.holding and not G.holder and npcOpen) and true or false
    if want == N.stickOn then return end
    N.stickOn = want
    pcall(function() padGrab:EnableGamePadStick(want) end)
end

-- The game's controller UI sets its own bindings again when its window changes (a tab, a page, a
-- new list), and the later binding wins. Take back what it took. Bounded per window, so that two
-- pieces of code can never pass a key back and forth without end.
local REASSERT_MAX = 40
function N.check()
    if not N.holding or inCombat() then return end
    local keys = {}
    for _, k in ipairs(PAD_KEYS) do keys[#keys + 1] = k end
    for _, k in ipairs(extraKeys()) do keys[#keys + 1] = k end
    if not G.holder then for k in pairs(N.npcExtra) do keys[#keys + 1] = k end end
    for _, k in ipairs(keys) do
        local now = TS(function() return GetBindingAction(k, true) end)
        local button, mouse = wantOf(k)
        if button and now ~= ("CLICK " .. button .. ":" .. mouse) then
            if (N.reasserts or 0) >= REASSERT_MAX then return end
            N.reasserts = (N.reasserts or 0) + 1
            N.lost = N.lost + 1
            -- WHICH key, and WHAT held it: the recording must name the key the game keeps taking
            N.lostKey, N.lostTo = k, now
            N.lostBy[k] = (N.lostBy[k] or 0) + 1
            padBind(k)
        end
    end
end

-- No flash (user, 2026-09-28): the game shows its window inside the opening event and the document
-- is built a frame later, so the window was drawn once before it was dimmed. With the direct way
-- in PROVEN in this session, the keys are taken and the window dimmed inside the event. Until the
-- document has built the highlight for THIS window, presses are swallowed (N.stale).
function N.early(slot)
    if WINDOWS[slot] == false then return end      -- not switched on yet: the game keeps its window
    if slot == "loot" and LOOT.top() then return end   -- r-loot-on-top: the game keeps its loot window
    -- an NPC window opens: it comes first, whoever is in charge of it. The guest lets go.
    if G.holder then
        guestLost("npc")
        if not N.mode then N.release("npc", "npc") end
    end
    if not (N.mode and (N.frameSeen or N.trusted)) or inCombat() then return end
    if not N.holding then
        N.hold()
        N.stale = N.holding
    end
    if N.holding then N.hide({ [slot] = true }) end
end

-- THE WATCHDOG. Whatever says that the game may be hearing the controller while its window is
-- invisible: the window comes back at once, the proof is dropped for this session, and the
-- document says so (`proof.bad`), which makes the app forget it.
function N.distrustNow(why)
    N.distrust = N.distrust or why
    N.trusted, N.frameSeen = false, false
    N.unhide()
end

-- money moved at a vendor we hide on the app's word alone, and no action of ours moved it
function N.watchMoney()
    if not (N.holding and N.hid > 0 and N.trusted and not N.frameSeen) then return end
    local now = TN(function() return GetTime() end) or 0
    if now - lastActAt > 2 then N.distrustNow("money") end
end

function N.sync(open, doc)
    N.stale = false
    if N.mode and open and not inCombat() then
        if N.holding then N.check() else N.hold() end
        -- the game's copy goes only once a press has come through the frame (see the header)
        if N.holding and doc and (N.frameSeen or N.trusted) then N.hide(doc) end
        -- a window's own extra keys (the trainer: + = its Settings menu, as the game's footer shows)
        -- THE LOOT WINDOW LEAVES THE STICKS ALONE (the user on 1.0.2.9-batch2: "looting locks the walking stick, it should remain
        -- unlocked and walking away should close the window as normal"): the grab's stick input took the left stick too while loot
        -- held, so the character could not walk off and let the game close the window. With loot (and its Make Space) the sticks
        -- stay the game's; the right stick's tooltip scroll is the other windows' only.
        N.stickSync(not (doc and doc.loot))
        if N.holding and not G.holder then
            -- the trainer's Settings menu is on SELECT (the user, 2026-10-09: Start opens the pause wheel from every room and pop-up)
            local want = (doc and doc.trainer) and { PADBACK = true }
                or (doc and doc.bank and doc.bank.account) and { PADLSHOULDER = true, PADRSHOULDER = true } or {}
            for k in pairs(N.npcExtra) do
                if not want[k] then pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
            end
            for k in pairs(want) do if not N.npcExtra[k] then padBind(k) end end
            N.npcExtra = want
        end
    elseif not G.holder then
        N.release()
    end
    -- v10: an NPC / loot window opens in a fight: it waits for the ENTER tap (the app taps when its pop-up shows)
    if N.mode and open and inCombat() and not N.holding and not G.holder and not N.npcLeft then N.wantEnter("npc") end
    if not open then
        N.npcLeft = nil                                  -- a window left with B in a fight is not entered again until it closes
        if N.pend and N.pend.id == "npc" then N.pend = nil end
    end
end

-- The companion is, or is no longer, in charge of the NPC windows.
function N.setMode(on)
    N.mode = on and true or false
    if not N.mode and not G.holder then N.release("mode") end
    -- ONE setting for every game window the companion shows: the hub has five bits and this
    -- element holds the last one, so the game's dialogs (Dialogs.lua) follow it
    if type(WC.dialogs) == "table" and type(WC.dialogs.setMode) == "function" then
        pcall(WC.dialogs.setMode, N.mode)
    end
    emitSoon()
    return true
end

-- ---------------------------------------------------------------- controller: the presses

local function navStep(z, ii, d)
    -- walk from item ii in direction d (an index step) to the next one that can be highlighted
    local j = ii + d
    while z.items[j] do
        if z.items[j].ok then return j end
        j = j + d
    end
end

function N.move(dir)
    local zi, ii = navFind(N.cur)
    if not zi then return end
    local z = N.zones[zi]
    local name = z.name
    if name == "trlist" and N.trLines then
        local li, col
        for a, line in ipairs(N.trLines) do for b, id in ipairs(line) do if id == N.cur then li, col = a, b end end end
        if not li then return end
        if dir == "left" and col > 1 then N.cur = N.trLines[li][col - 1]
        elseif dir == "right" and N.trLines[li][col + 1] then N.cur = N.trLines[li][col + 1]
        elseif dir == "up" and li > 1 then local l = N.trLines[li - 1]; N.cur = l[math.min(col, #l)]
        elseif dir == "down" and li < #N.trLines then local l = N.trLines[li + 1]; N.cur = l[math.min(col, #l)] end
        return
    end
    if name == "trmenu" then
        local j = navStep(z, ii, (dir == "up") and -1 or ((dir == "down") and 1 or 0))
        if (dir == "up" or dir == "down") and j then N.cur = z.items[j].id end
        return
    end
    if name == "bkgrid" or name == "bkbags" or name == "bktabs" then
        -- the bank's grid, its page tabs, your bags: left / right cross from one to the next at the same row
        local function rowOf(zz, i) return math.floor((i - 1) / (zz.cols or 1)) end
        local function into(zz, row, fromRight)
            if not zz or #zz.items == 0 then return end
            local c = zz.cols or 1
            local rows = math.floor((#zz.items - 1) / c)
            local r = math.min(row, rows)
            local i = r * c + (fromRight and c or 1)
            i = math.min(i, #zz.items)
            N.cur = zz.items[i].id
        end
        local order = {}
        for _, n in ipairs({ "bkgrid", "bktabs", "bkbags" }) do if navZoneNamed(n) then order[#order + 1] = n end end
        local at
        for k, n in ipairs(order) do if n == name then at = k end end
        local c = z.cols or 1
        local col = (ii - 1) % c
        local row = rowOf(z, ii)
        if dir == "up" and ii - c >= 1 then N.cur = z.items[ii - c].id
        elseif dir == "down" and z.items[ii + c] then N.cur = z.items[ii + c].id
        elseif dir == "down" and row < rowOf(z, #z.items) then N.cur = z.items[#z.items].id
        elseif dir == "left" and col > 0 then N.cur = z.items[ii - 1].id
        elseif dir == "right" and col < c - 1 and z.items[ii + 1] then N.cur = z.items[ii + 1].id
        elseif dir == "right" and at and order[at + 1] then
            local nz = navZoneNamed(order[at + 1])
            into(nz, nz.name == "bktabs" and math.min(row, #nz.items - 1) or row, false)
        elseif dir == "left" and at and order[at - 1] then
            local nz = navZoneNamed(order[at - 1])
            into(nz, nz.name == "bktabs" and math.min(row, #nz.items - 1) or row, true)
        end
        return
    end
    if name == "msgrid" then
        -- Make Space: to the tile drawn that way (never across the end of a row; up / down to the nearest column)
        local me = N.msPos and N.msPos[N.cur]
        if not me then return end
        local best, bestD
        for _, it in ipairs(z.items) do
            local p = N.msPos[it.id]
            if p and it.ok then
                if (dir == "left" and p.r == me.r and p.c == me.c - 1) or (dir == "right" and p.r == me.r and p.c == me.c + 1) then
                    best, bestD = it.id, 0
                elseif (dir == "up" and p.r == me.r - 1) or (dir == "down" and p.r == me.r + 1) then
                    local d = math.abs(p.c - me.c)
                    if not bestD or d < bestD then best, bestD = it.id, d end
                end
            end
        end
        if best then N.cur = best end
        return
    end
    if name == "taxi" then
        local me = TAXI.pos[tonumber(tostring(N.cur):match("%d+") or "")]
        if not me then return end
        local best, bestCost
        for _, it in ipairs(z.items) do
            local p = TAXI.pos[tonumber(it.id:match("%d+") or "")]
            if p and it.id ~= N.cur then
                local dx, dy = p[1] - me[1], p[2] - me[2]
                local along = (dir == "right" and dx) or (dir == "left" and -dx) or (dir == "down" and dy) or -dy
                local side = (dir == "right" or dir == "left") and math.abs(dy) or math.abs(dx)
                if along > 0.001 and side <= along * 2 then
                    local cost = along + 2 * side
                    if not bestCost or cost < bestCost then best, bestCost = it.id, cost end
                end
            end
        end
        if best then N.cur = best end
        return
    end
    if name == "ahcats" or name == "ahright" or (name == "tabs" and navZoneNamed("ahright")) then
        if name == "tabs" then
            if dir == "left" or dir == "right" then
                local j = navStep(z, ii, dir == "left" and -1 or 1)
                if j then N.cur = z.items[j].id end
            elseif dir == "down" then
                N.cur = N.ahSel or navFirst(navZoneNamed("ahcats")) or navFirst(navZoneNamed("ahright")) or N.cur
            end
        elseif dir == "up" or dir == "down" then
            local j = navStep(z, ii, dir == "up" and -1 or 1)
            if j then N.cur = z.items[j].id
            elseif dir == "up" then N.cur = "ahtab " .. AH.tab end
        elseif dir == "right" and name == "ahcats" then
            N.cur = navFirst(navZoneNamed("ahright")) or N.cur
        elseif dir == "left" and name == "ahright" and navZoneNamed("ahcats") then
            N.cur = N.ahSel or navFirst(navZoneNamed("ahcats")) or N.cur
        end
        return
    end
    if name == "mlist" then
        if dir == "right" then
            local za = navZoneNamed("mattach")
            if za and #za.items > 0 then N.cur = za.items[1].id end
            return
        end
        local j = navStep(z, ii, (dir == "up") and -1 or ((dir == "down") and 1 or 0))
        if (dir == "up" or dir == "down") and j then N.cur = z.items[j].id end
        return
    end
    if name == "mattach" then
        if dir == "left" and ii == 1 then
            if MAIL.open then N.cur = "mail " .. MAIL.open end
            return
        end
        local j = navStep(z, ii, (dir == "left") and -1 or ((dir == "right") and 1 or 0))
        if (dir == "left" or dir == "right") and j then N.cur = z.items[j].id end
        return
    end
    if name == "mailbar" then
        -- an opened letter's money / take-all: left and right between them, up to the attachments
        local j = navStep(z, ii, (dir == "left") and -1 or ((dir == "right") and 1 or 0))
        if (dir == "left" or dir == "right") and j then N.cur = z.items[j].id end
        if dir == "up" then local gz = navZoneNamed("grid"); if gz and #gz.items > 0 then N.cur = gz.items[#gz.items].id end end
        return
    end
    if name == "buyslot" then
        -- the stable's "buy a slot": up goes back to the last pet slot
        if dir == "up" then
            local gz = navZoneNamed("grid")
            if gz then for j = #gz.items, 1, -1 do if gz.items[j].ok then N.cur = gz.items[j].id; break end end end
        end
        return
    end
    if name == "bar" then
        -- on the Buy / Sell button: left and right are the quantity, up goes back to the list
        if dir == "left" then N.qty = math.max(1, N.qty - 1)
        elseif dir == "right" then N.qty = math.min(N.most, N.qty + 1)
        elseif dir == "up" and N.sel then N.cur = N.sel end
        return
    end
    if name == "act" and N.offer then
        -- a quest offer: A is Accept and B is Decline, so there is nothing to move between
        if dir == "down" then N.scroll = N.scroll + 1
        elseif dir == "up" then N.scroll = math.max(0, N.scroll - 1) end
        return
    end
    if name == "act" then
        if dir == "left" or dir == "right" then
            local j = navStep(z, ii, dir == "left" and -1 or 1)
            if j then N.cur = z.items[j].id
            elseif dir == "left" and navZoneNamed("choices") then N.cur = N.pick or navFirst(navZoneNamed("choices")) end
        elseif navZoneNamed("choices") then
            if dir == "up" then
                local c = navZoneNamed("choices")
                N.cur = N.pick or c.items[#c.items].id
            end
        else
            -- a quest page with only buttons: up and down scroll what is read
            if dir == "down" then N.scroll = N.scroll + 1 else N.scroll = math.max(0, N.scroll - 1) end
        end
        return
    end
    if name == "choices" then
        -- the rewards only: Complete is not a stop (A on the picked reward completes)
        if dir == "up" or dir == "down" then
            local j = navStep(z, ii, dir == "up" and -1 or 1)
            if j then N.cur = z.items[j].id end
        end
        return
    end
    if name == "tabs" then
        if dir == "left" or dir == "right" then
            local j = navStep(z, ii, dir == "left" and -1 or 1)
            if j then N.cur = z.items[j].id end
        elseif dir == "down" then
            N.cur = navFirst(navZoneNamed("grid")) or N.cur
        end
        return
    end
    if z.major == "col" then
        -- the page of lines: entries run DOWN the first column, then down the second
        local rows = (z.cols == 2) and math.ceil(#z.items / 2) or #z.items
        local row = (ii - 1) % rows
        local j
        if dir == "up" and row > 0 then j = navStep(z, ii, -1)
            if j and math.floor((j - 1) / rows) ~= math.floor((ii - 1) / rows) then j = nil end
        elseif dir == "down" and row < rows - 1 then j = navStep(z, ii, 1)
            if j and math.floor((j - 1) / rows) ~= math.floor((ii - 1) / rows) then j = nil end
        elseif dir == "left" and ii - rows >= 1 then j = ii - rows
        elseif dir == "right" and z.cols == 2 and ii <= rows then
            j = math.min(ii + rows, #z.items)
            if j <= rows then j = nil end
        end
        if j and z.items[j] and z.items[j].ok then N.cur = z.items[j].id end
        return
    end
    -- a grid, row-major: the vendor's
    local c = z.cols
    local col = (ii - 1) % c
    if dir == "left" and col > 0 and z.items[ii - 1].ok then N.cur = z.items[ii - 1].id
    elseif dir == "right" and col < c - 1 and z.items[ii + 1] and z.items[ii + 1].ok then N.cur = z.items[ii + 1].id
    elseif dir == "up" or dir == "down" then
        local j = ii + (dir == "up" and -c or c)
        while z.items[j] and not z.items[j].ok do j = j + (dir == "up" and -c or c) end
        if z.items[j] then N.cur = z.items[j].id
        elseif dir == "down" and z.items[ii + 1] and math.floor((#z.items - 1) / c) > math.floor((ii - 1) / c) then
            -- the last row is short: down from the right-hand column lands on its last item
            if z.items[#z.items].ok then N.cur = z.items[#z.items].id end
        elseif dir == "up" and (name == "grid" or name == "list") and navZoneNamed("tabs") then
            -- up from the top row: onto the tab that is open (the vendor's, or the auction house's)
            if navFind("tab " .. tostring(N.tab)) then N.cur = "tab " .. N.tab
            elseif navFind("ahtab " .. tostring(AH.tab)) then N.cur = "ahtab " .. AH.tab
            else N.cur = navFirst(navZoneNamed("tabs")) or N.cur end
        elseif dir == "down" and name == "grid" and navZoneNamed("buyslot") then
            N.cur = "stableslot"
        elseif dir == "down" and name == "grid" and navZoneNamed("mailbar") then
            N.cur = navFirst(navZoneNamed("mailbar")) or N.cur
        end
    end
end

-- X (PAD3, the left face) lowers the quantity, Y (PAD4, the top face) raises it (user, 2026-09-29:
-- "Maybe put X or Y on the QTY buttons, holding them down counts by 10s, QTY should not go higher
-- than a player can afford"). One step on the press; held past HOLD_FIRST seconds, steps of ten
-- every HOLD_EVERY seconds until it is let go. Never above N.most (purse, stock, the vendor's
-- limit, QTY_MAX), never below 1.
local HOLD_FIRST, HOLD_EVERY = 0.5, 0.25
N.hold10 = nil
function N.qtyKey(key, down)
    local d = (key == "PAD4") and 1 or -1
    if not down then
        if N.hold10 and N.hold10.key == key then N.hold10 = nil end
        return false
    end
    if not (N.sel and N.tab == "buy" and N.most > 1) then return false end
    N.qty = math.max(1, math.min(N.most, N.qty + d))
    local rec = { key = key, at = TN(function() return GetTime() end) or 0 }
    N.hold10 = rec
    local function step()
        if N.hold10 ~= rec or not N.holding then return end
        local t = TN(function() return GetTime() end) or 0
        if t - rec.at >= HOLD_FIRST then
            local q = math.max(1, math.min(N.most, N.qty + 10 * d))
            if q ~= N.qty then N.qty = q; N.n = N.n + 1; emitSoon() end
        end
        pcall(C_Timer.After, HOLD_EVERY, step)
    end
    pcall(C_Timer.After, HOLD_EVERY, step)
    return true
end

-- PRESS-WAIT (the user, 2026-10-07: "some kind of visual for the delay though, to stop the player from spamming after the press";
-- the pick "B: the game's spinner"): a controller action is not run AGAIN while the game has not answered the last run of the same
-- action, i.e. until the game's next event for the open windows (N.busy is cleared there) or for 2 s at most. The app shows the
-- spinner on that button meanwhile. Touch has its own gate in the app.
local function navRun(cmd, count)
    local now = TN(function() return GetTime() end) or 0
    if N.busy and N.busy.cmd == cmd and now < N.busy.till then return end
    quiet = true
    if count and count > 1 then wanted = { k = count, at = GetTime() } end
    LOOT.ranGame = false
    pcall(dispatch, cmd)
    quiet = false
    -- only a run that made a GAME call waits for the game; the add-on's own steps (back, arm, a page) never do
    N.busy = LOOT.ranGame and { cmd = cmd, till = now + 2 } or nil
end

-- A TAB OPENS WHEN THE CURSOR LANDS ON IT (the user, 2026-10-07: "the character tabs auto open to the tab that the cursor is
-- on- I REALLY like this and want to make the other tabs consistent with this behavior"): the vendor's Buy / Sell / Buyback, the
-- auction house's Buy / Auctions, the bank's page tabs. Only a DIFFERENT tab switches (the Auctions tab asks the server for your
-- auctions: once per landing, never again for the tab already open); a bank page waits while the buy-a-tab box is up (A is its Yes)
function N.tabLand(id)
    if type(id) ~= "string" then return end
    local t = id:match("^tab (%a+)$")
    if t then
        if N.tab ~= t then N.tab, N.sel, N.qty = t, nil, 1 end
        return
    end
    local a = id:match("^ahtab (%a+)$")
    if a then
        if AH.tab ~= a then navRun(id) end
        return
    end
    local pg = tonumber(id:match("^bkpage (%d+)$") or "")
    if pg and not BANK.box and BANK.page ~= pg then navRun(id) end
end

function N.act()
    if N.stableBox and isOpen("stable") then
        -- the game's own purchase box is up: A = Yes, the ONE game action
        N.stableBox = nil
        navRun("stableslot")
        return
    end
    if N.offer then navRun("accept " .. N.offer) return end
    local zi, ii = navFind(N.cur)
    if not zi then return end
    local name, id = N.zones[zi].name, N.cur
    if not N.zones[zi].items[ii].ok then return end
    if name == "tabs" then
        local t = id:match("^tab (%a+)$")
        if t then N.tab, N.sel, N.qty = t, nil, 1 else navRun(id) end
    elseif type(id) == "string" and (id:match("^ahcat ") or id:match("^ahres ") or id:match("^ahrow ") or id == "ahbuy") then
        navRun(id)
    elseif type(id) == "string" and id:match("^loot %d+$") then
        navRun(id)
    elseif type(id) == "string" and id:match("^bag %d+ %d+ %d+$") and isOpen("loot") then
        -- make space: A alone does nothing; A HELD 1 s deletes (handled with the press and the release)
    elseif type(id) == "string" and id:match("^bkslot ") and BANK.box then
        navRun("bkbuy")                     -- the purchase box is up: A is its Yes
    elseif type(id) == "string" and id:match("^bkslot ") then
        local bag, sl = id:match("^bkslot (%-?%d+) (%d+)$")
        if BANK.hand then
            navRun("bkmove " .. BANK.hand[1] .. " " .. BANK.hand[2] .. " " .. bag .. " " .. sl)
        else
            navRun((isBankBag(tonumber(bag)) and "bkwd " or "bkdep ") .. bag .. " " .. sl)
        end
    elseif type(id) == "string" and id:match("^bkpage %d+$") then
        if BANK.box then navRun("bkbuy") else navRun(id) end
    elseif type(id) == "string" and id:match("^taxi %d+$") then
        navRun(id)
    elseif type(id) == "string" and id:match("^ahown ") then
        -- your auctions are read only in step 1
    elseif type(id) == "string" and (id:match("^mail %d+$") or id:match("^mailtake ") or id:match("^mailmoney ") or id:match("^mailall ")) then
        local t = TN(function() return GetTime() end) or 0
        if id:match("^mail %d+$") then
            local i = tonumber(id:match("%d+"))
            if MAIL.open == i then
                local za = navZoneNamed("mattach")
                if za and #za.items > 0 then N.cur = za.items[1].id end
                return
            end
            navRun(id)                              -- opening a letter takes nothing: no guard
        else
            if t - (N.openAt or -1e9) < GUARD or t - (N.actAt or -1e9) < GUARD then return end
            N.actAt = t
            navRun(id)
        end
    elseif type(id) == "string" and id:match("^pet %d+$") then
        -- A on a pet picks it; A on another slot (empty or not) moves it there, the game's own swap
        local i = tonumber(id:match("%d+"))
        local st = N.lastStable
        local row = st and st.pets and st.pets[i]
        if row and not row.open then return end         -- a locked slot: nothing
        if not N.petFrom and row and not row.name then return end   -- an empty slot with nothing in the hand
        if N.petFrom and N.petFrom ~= i then
            navRun("petmove " .. N.petFrom .. " " .. i)
            N.petFrom = nil
        else
            N.petFrom = (N.petFrom == i) and nil or i
        end
    elseif type(id) == "string" and id:match("^trhead %d+$") then
        local h = tonumber(id:match("%d+"))
        N.trFold[h] = (not N.trFold[h]) or nil       -- the app's look follows nav.folds
    elseif type(id) == "string" and id:match("^trf ") then
        navRun(id)
    elseif (name == "list" or name == "trlist") and type(id) == "string" and id:match("^train ") then
        local t = TN(function() return GetTime() end) or 0
        if t - (N.openAt or -1e9) < GUARD or t - (N.actAt or -1e9) < GUARD then return end
        N.actAt = t
        navRun(id)
    elseif name == "grid" then
        -- ONE PRESS (user, 2026-09-29 11:23; this replaces the pick-then-Buy ruling of 2026-09-28):
        -- A buys the highlighted item (the quantity shown), sells it, or buys it back. Two guards:
        -- not within GUARD seconds of the window opening (the A that talked to the vendor is not a
        -- purchase) nor of the last action here (a bounced or doubled press buys once).
        local t = TN(function() return GetTime() end) or 0
        if t - (N.openAt or -1e9) < GUARD or t - (N.actAt or -1e9) < GUARD then return end
        N.actAt = t
        navRun(id, (N.tab == "buy") and N.qty or 1)
    elseif name == "choices" then
        -- A picks a reward; A on the reward already picked completes the quest with it (the A glyph on Complete)
        if N.pick == id then navRun(id) else N.pick = id end
    elseif id == "complete" then
        if navZoneNamed("choices") and not N.pick then
            ack("reward", false, "pick a reward")
        else
            navRun(N.pick or ("reward " .. tostring(N.sig and N.sig:match("|(%d+)$"))))
        end
    else
        navRun(id)
    end
end

function N.back()
    if N.trMenu and isOpen("trainer") then
        N.trMenu = nil                      -- the trainer's Settings menu closes first
    elseif isOpen("auction") and (AH.buy or AH.key or AH.tab == "owned" or #AH.path > 0) then
        navRun("ahback")                    -- the auction house: one level up first
    elseif MAIL.open and isOpen("mail") then
        MAIL.back, MAIL.open = MAIL.open, nil  -- an opened letter: B goes back to the inbox first, onto it
    elseif LOOT.on() and LOOT.rare then
        LOOT.rare = nil                     -- make space: the blue / purple box says No
    elseif LOOT.on() then
        -- make space: Back to the loot window (or the quest's page, its action not retried) -- for an accept: accept anyway
        local rt = LOOT.ms == "quest" and LOOT.retry
        LOOT.full, LOOT.hold = false, nil
        if LOOT.ms == "quest" then LOOT.ms, LOOT.retry = nil, nil end
        if rt and rt.anyway then navRun("accept " .. tostring(rt.a) .. " anyway") end
    elseif isOpen("bank") and (BANK.box or BANK.hand) then
        BANK.box, BANK.hand = false, nil    -- the bank: the purchase box, then the item in the hand, go first
    elseif N.stableBox or N.petFrom then
        N.stableBox, N.petFrom = nil, nil   -- the stable: B closes the purchase box / puts the pet back first
    elseif N.offer then
        N.backAt = TN(function() return GetTime() end)
        navRun("decline " .. N.offer)       -- B at an offer is Decline (user, 2026-09-28)
    else
        N.backAt = TN(function() return GetTime() end)
        navRun("close")
    end
end

local lastDirect = {}       -- key .. phase -> when the frame delivered it

local function padPress(key, down, via)
    if not N.holding then return end
    local tag = key .. (down and "d" or "u")
    if via == "frame" then
        lastDirect[tag] = GetTime()
        if not N.frameSeen then
            N.frameSeen = true
            emitSoon()          -- whatever this key does: the next document hides the game's window
            guestHide()
        end
    elseif N.fight then
        -- v9: in a fight the room's keys come through the secure header's bindings (the frame is off)
    elseif lastDirect[tag] and GetTime() - lastDirect[tag] < 0.1 then
        return                  -- the same press, heard a second time through the binding
    elseif N.frameSeen or N.trusted then
        N.distrustNow("bind")   -- the frame did not hear this press: it may not hear the next A
    end
    if N.stale then return end      -- taken inside the opening event; the highlight is not built yet
    if G.holder then
        -- capture v6: a key the guest asked for on both edges (not while a chord waits)
        if G.spec.both and G.spec.both[key] and not G.chord then
            pcall(G.spec.press, key, down and true or false)
            return
        end
        -- the guest: same timing as ours (a direction on the press, a face button on the release)
        if (PAD_DIR[key] ~= nil) ~= (down and true or false) then return end
        if G.chord then
            -- waiting for a chord: B gives up, the other seven do nothing
            if key == "PAD2" then
                local c = G.chord
                G.chord = nil
                if type(c.cancel) == "function" then pcall(c.cancel) end
            end
            return
        end
        pcall(G.spec.press, key)
        return
    end
    N.last, N.via = key, via
    local dir = PAD_DIR[key]
    if dir then
        if not down then return end
        if N.xHold then
            -- X held on a comparable item: the D-pad scrolls the expanded tooltip, the cursor stays
            if dir == "up" or dir == "down" then N.ts = (N.ts or 0) + ((dir == "down") and 1 or -1) end
            N.n = N.n + 1
            emitSoon()
            return
        end
        local was = N.cur
        N.move(dir)
        if N.cur ~= was then
            N.ts = 0                            -- another item: its tooltip starts at the top
            N.tabLand(N.cur)                    -- a tab the cursor lands on opens (no A)
        end
    else
        if key == "PAD3" and (N.xHold or (down and N.tipCmp)) then
            -- HOLD X TO COMPARE (user, 2026-09-30: "if the Item is comparable, it should show a button under the
            -- tooltip saying X to compare and then expand the tooltip when held, and then scroll up and down
            -- with the dpad or control stick and then it goes back to normal when not holding X")
            N.xHold = down or nil
            N.ts = 0
        elseif key == "PAD3" and WINDOWS.loot and isOpen("loot") and not G.holder and not LOOT.full then
            if down then return end
            navRun("lootall")
        elseif (key == "PAD3" or key == "PAD4") and WINDOWS.bank and isOpen("bank") and not G.holder then
            if down then return end
            if key == "PAD3" then navRun("bkbox")
            else
                -- Y: the item under the cursor into the hand (nothing is sent to the game: A puts it down)
                local bag, sl = tostring(N.cur or ""):match("^bkslot (%-?%d+) (%d+)$")
                if bag and bankItem(tonumber(bag), tonumber(sl)) then BANK.hand = { tonumber(bag), tonumber(sl) } end
            end
        elseif key == "PAD1" and LOOT.on() and not G.holder then
            local t = TN(function() return GetTime() end) or 0
            if LOOT.rare then
                if not down then local r = LOOT.rare; navRun("lootdel " .. r.bag .. " " .. r.slot .. " " .. r.id .. " rare") end
            elseif down then
                if tostring(N.cur or ""):match("^bag %d+ %d+ [1-9]%d*$") then LOOT.hold = { id = N.cur, at = t }; N.n = N.n + 1; emitSoon() end
            else
                local h = LOOT.hold
                LOOT.hold = nil
                if h and h.id == N.cur and t - h.at >= HOLD_SECONDS then
                    local b, sl, id = h.id:match("^bag (%d+) (%d+) (%d+)$")
                    local ok2, it = CALL(function() return C_Container.GetContainerItemInfo(tonumber(b), tonumber(sl)) end)
                    local q = ok2 and type(it) == "table" and TN(function() return it.quality end) or 0
                    if q >= 3 then
                        LOOT.rare = { bag = tonumber(b), slot = tonumber(sl), id = tonumber(id), q = q,
                                      name = itemName(tonumber(id)) }
                    else
                        navRun("lootdel " .. b .. " " .. sl .. " " .. id)
                    end
                end
                N.n = N.n + 1
                emitSoon()
            end
        elseif (key == "PAD3" or key == "PAD4") and isOpen("merchant") and not G.holder
               and tostring(N.cur or ""):match("^tab %a+$") then
            -- on the Buy / Sell / Buyback tabs (no item under the cursor, so no quantity and no compare): X = Repair All,
            -- Y = Sell Junk, the game's own one-call actions (user, 2026-10-01: "Y for sell all junk and X for repair all")
            if down then return end
            navRun((key == "PAD3") and "repair" or "junk")
        elseif key == "PAD3" and WINDOWS.mail and isOpen("mail") and MAIL.open and not G.holder then
            if down then return end
            navRun("mailall " .. MAIL.open)
        elseif key == "PAD3" and WINDOWS.stable and isOpen("stable") and not G.holder then
            if down then return end
            navRun("stablebox")
        elseif key == "PAD4" and WINDOWS.trainer and isOpen("trainer") and not G.holder and not trProfession() then
            if down then return end
            navRun("trspell")
        elseif (key == "PAD3" or key == "PAD4") and AH.key and isOpen("auction") then
            -- the auction house: a commodity's quantity (one step per press); a change cancels an armed purchase
            if not down then return end
            ahStep((key == "PAD4") and 1 or -1)
        elseif key == "PAD3" or key == "PAD4" then
            -- the quantity: on the press, and the release ends a hold. Nothing here closes a
            -- window, so the release still comes to us.
            if not N.qtyKey(key, down) then return end
        else
            -- on the RELEASE, so that the game never sees a button-up whose press it did not see
            if down then return end
            if key == "PAD1" then N.act() elseif key == "PAD2" then N.back()
            elseif key == "PADBACK" and N.npcExtra.PADBACK and isOpen("trainer") then navRun("trmenu")
            elseif (key == "PADLSHOULDER" or key == "PADRSHOULDER") and N.npcExtra[key] and isOpen("bank") then
                navRun("bktab " .. ((key == "PADRSHOULDER") and "acct" or "char"))
            else return end
        end
    end
    N.n = N.n + 1
    emitSoon()
end

padSink:SetScript("OnClick", function(_, key, down) padPress(key, down, "bind") end)

-- A button with the modifiers the game applies to it, as the game writes a binding: "SHIFT-PADRSHOULDER".
-- The buttons that ARE the modifiers (the game's emulated Shift / Ctrl / Alt) give nil.
local function chordOf(button)
    for _, cv in ipairs({ "GamePadEmulateShift", "GamePadEmulateCtrl", "GamePadEmulateAlt" }) do
        if TS(function() return GetCVar(cv) end) == button then return nil end
    end
    local c = button
    if TB(function() return IsShiftKeyDown() end) then c = "SHIFT-" .. c end
    if TB(function() return IsControlKeyDown() end) then c = "CTRL-" .. c end
    if TB(function() return IsAltKeyDown() end) then c = "ALT-" .. c end
    return c
end

local HEARD_MAX = 24
N.passDown = {}             -- capture v15/16: keys of the eight whose press went to the game: { t = press time, told = passed sent }
-- Ours are consumed; everything else (shoulders, triggers, the sticks' clicks) goes on to the game,
-- unless a guest is waiting for a chord: then the next one is the chord, and the game does not get it.
local function direct(down)
    return function(self, button)
        if N.fight then
            -- CAPTURE v8, a room holding through a fight: every button stops here (pass-on was set OFF before the lockdown;
            -- SetPropagateKeyboardInput is BLOCKED in a fight, measured). The room's keys go to the room; B that the room
            -- leaves to the game (spec.back / pass) leaves the room instead, so B always gets the player out.
            if not (G.holder and type(button) == "string") then return end
            return              -- v9: the frame is off in a fight; nothing may act here
        end
        local mine = (PAD_SET[button] and N.holding and keeps(button))
                     or (N.holding and not G.holder and N.npcExtra[button])
                     or (N.holding and G.holder and G.spec and G.spec.extra and G.spec.extra[button]) or false
        -- capture v16 (Home, b90 / b91): a key whose PRESS went to the game (spec.back / pass) is marked. Its release, IF the
        -- frame hears it (it does not while an override binding owns the key: measured b91 23:15), is the game's too, and the
        -- guest is told once (unless backSync already told it, when the window that B closed hid first)
        local skipPassed = false
        if PAD_SET[button] then
            if down then N.passDown[button] = (not mine and G.holder) and { t = TN(function() return GetTime() end) or 0 } or nil
            elseif N.passDown[button] then
                skipPassed = N.passDown[button].told and true or false
                N.passDown[button] = nil
                mine = false
            end
        end
        local eaten = false
        if not mine and N.holding and type(button) == "string" then
            local chord = chordOf(button)
            if down and chord and not PAD_SET[button] and not N.heard[chord] and N.heardN < HEARD_MAX then
                -- read-only: WHICH other buttons the frame hears on this client, and with which modifiers
                N.heard[chord] = true
                N.heardN = N.heardN + 1
                emitSoon()
            end
            if PAD_SET[button] then
                -- one of the eight that this guest leaves to the game: not a chord, not listed.
                -- The guest is TOLD, on the release, so that it knows a window that closes now was
                -- closed by that key and not by the player walking away.
                if not down and not skipPassed and G.holder and type(G.spec.passed) == "function" then
                    pcall(G.spec.passed, button)
                end
            elseif G.eat == button and not down then
                G.eat, eaten = nil, true            -- the release of the chord's button
            elseif G.holder and G.chord and chord and down then
                local c = G.chord
                G.chord, G.eat, eaten = nil, button, true
                if type(c.done) == "function" then pcall(c.done, chord) end
            end
        end
        pcall(function() self:SetPropagateKeyboardInput(not (mine or eaten)) end)
        if mine then padPress(button, down, "frame") end
    end
end
pcall(function() padGrab:SetScript("OnGamePadButtonDown", direct(true)) end)
pcall(function() padGrab:SetScript("OnGamePadButtonUp", direct(false)) end)

-- THE RIGHT STICK scrolls the tooltip stack (user, 2026-09-30: "I want the right control stick to scroll the
-- tooltips up when this happens to be able to read it all"). Only while an NPC window holds the controller
-- (never for a guest, never in a fight: the hold is let go then). Up / down past half way = one step, then a
-- step every STICK_EVERY seconds while held; `nav.ts` counts the steps (the app scrolls by them). A new
-- tooltip starts at 0. The stick's other uses (the camera) are the game's again when the window closes.
local STICK_EVERY = 0.15
N.ts, N.tsKey, N.stickDir = 0, nil, 0
N.npcExtra = {}             -- keys beyond the eight that the open NPC window uses (the trainer's +)
local function stickStep(d)
    N.ts = (N.ts or 0) + d
    N.n = N.n + 1
    emitSoon()
end
-- Returns true = UNHANDLED: the game gets that stick (Blizzard's own inputBindingAxisListener does the same, in
-- blizzard_gamepadsharedutility/inputbindingstack/inputaxisbinding.lua). So the LEFT stick ("Movement") always walks,
-- even with an NPC window open, as in the game; only the right stick is kept, for the tooltip scroll.
local function onStick(_, stick, x, y)
    if not N.holding or G.holder then return true end
    if type(stick) ~= "string" or not (stick:find("Right") or stick:find("Camera") or stick:find("Look")) then return true end
    local d = (type(y) == "number" and y > 0.5) and -1 or ((type(y) == "number" and y < -0.5) and 1 or 0)
    if d == N.stickDir then return false end
    N.stickDir = d
    if d == 0 then return false end
    stickStep(d)
    local function again()
        if N.stickDir ~= d or not N.holding then return end
        stickStep(d)
        pcall(C_Timer.After, STICK_EVERY, again)
    end
    pcall(C_Timer.After, STICK_EVERY * 2, again)
    return false
end
pcall(function() padGrab:SetScript("OnGamePadStick", onStick) end)

-- /wci mode on|off   for the harness and the PC client; on the Thor the hub's layout tap does it
function ACT.mode(a)
    if a ~= "on" and a ~= "off" then return false, "on or off" end
    N.setMode(a == "on")
    return true
end

do
    local cf = CreateFrame("Frame")
    cf:SetScript("OnEvent", function(_, event)
        if event == "PLAYER_REGEN_DISABLED" then
            if N.bar.bound and pcall(function() ClearOverrideBindings(selOwner) end) then N.bar.bound = false end
            local who = G.holder
            local armed = not (G.spec and G.spec.noFight) and FIGHT_KEEP and N.mode and N.routerArm()
            if armed and N.holding and N.fightArm() then         -- a room OR an NPC window (item 10)
                -- CAPTURE v8 (user, 2026-10-01: "I don't want the game to take the controller at all, I want the room that
                -- I am in to flash red, and then going back home by what ever means, engages the combat transition"):
                -- the room KEEPS the controller through the fight. Before the lockdown: our bindings go (so they never sit
                -- over Spells' fight face, and nothing is left to clear in the fight), and the frame stops passing
                -- anything on. Only when the frame route is proven this session; otherwise today's release.
                pcall(function() ClearOverrideBindings(padOwner) end)
                pcall(function()
                    padGrab:EnableGamePadButton(false)
                    padGrab:EnableGamePadStick(false)
                    padGrab:SetPropagateKeyboardInput(true)
                    padGrab:Hide()
                end)
                N.stickOn = false
                N.routerMode("room")
                N.fight = "router"
                N.fightNo = 0
                emitSoon()
            else
                guestLost("combat")
                N.release("combat", who)         -- before the lockdown: the fight gets every button
            end
        elseif event == "UPDATE_BINDINGS" then
            -- v5: the Select listener, taken back if the game re-bound Select (bounded like the eight)
            if N.bar.bound and not inCombat() and N.bar.re < 40
               and TS(function() return GetBindingAction("PADBACK", true) end) ~= SEL_BIND then
                N.bar.re = N.bar.re + 1
                pcall(function() SetOverrideBindingClick(selOwner, true, "PADBACK", "WowCompanionInteractSel", "PADBACK") end)
            end
            -- somebody changed a binding: if it was the game taking a key of ours, take it back now,
            -- not at the next document. Our own change comes through here too and finds nothing to do.
            if N.holding then
                local before = N.lost
                if G.holder then N.reasserts = 0 end    -- the guest has no documents to bound it by
                N.check()
                if N.lost ~= before and not G.holder then emitSoon() end
            end
            return
        end
        if event == "PLAYER_REGEN_ENABLED" then
            N.fc = nil
            N.pend = nil
            N.npcLeft = nil
            if F.leave then pcall(function() F.leave:SetAttribute("type", nil) end) end
            N.routerMode(nil)
            if N.fight then
                -- v9: the room held to the fight's end: back to the frame + its bindings, as before the fight
                N.fight = nil
                if G.holder and N.holding then
                    for _, k in ipairs(PAD_KEYS) do padBind(k) end
                    for _, k in ipairs(extraKeys()) do padBind(k) end
                    pcall(function() padGrab:Show(); padGrab:EnableGamePadButton(true) end)
                end
            end
        end
        emitSoon()              -- after the fight the open window takes them again, through sync()
    end)
    pcall(function() cf:RegisterEvent("PLAYER_REGEN_DISABLED") end)
    pcall(function() cf:RegisterEvent("PLAYER_REGEN_ENABLED") end)
    pcall(function() cf:RegisterEvent("UPDATE_BINDINGS") end)
end

-- ---------------------------------------------------------------- the shared capture
-- OWNER: the interact session. FROZEN 2026-09-28 (AGENT_LOG); bags is the first guest.
--   capture.take(id, spec) -> ok, why     spec.press(key)  PADDUP/DOWN/LEFT/RIGHT on the press,
--                                                          PAD1..PAD4 on the release
--                                         spec.hide = { names }   alpha 0 while held, once the
--                                                          capture is proven or trusted
--                                         spec.lost(why)   "combat" | "npc": we let go by ourselves
--   capture.release(id)
--   capture.state() -> { holder, proven, trusted, bad }    holder "npc" = the NPC windows
--   capture.chord(id, onChord, onCancel)  one shot: the next button that is not one of the eight,
--                                         as the game's chord string; B cancels
WC.interactMode = function() return N.mode end

-- defined here, where G and N are known; buildDoc() above calls it
WC.bnextOf = function()
    local dlg = type(WC.dialogs) == "table" and type(WC.dialogs.open) == "function" and WC.dialogs.open() or 0
    if G.holder then
        if dlg > 0 then return "back" end
        return G.bnext
    end
    if N.holding and N.mode then
        if dlg > 0 then return "back" end
        -- B steps back inside the window first wherever N.back does (the Home disc shows B, and a TOUCH on it closes the
        -- window, only when B would really go Home: the user, 2026-10-01, "Yes, everywhere it shows B")
        if N.cur == "go" then return "back" end
        if N.trMenu and isOpen("trainer") then return "back" end
        if isOpen("auction") and (AH.buy or AH.key or AH.tab == "owned" or #AH.path > 0) then return "back" end
        if MAIL.open and isOpen("mail") then return "back" end
        if LOOT.on() then return "back" end
        if isOpen("bank") and (BANK.box or BANK.hand) then return "back" end
        if N.stableBox or N.petFrom then return "back" end
        return "home"
    end
    return nil
end
WC.interactPoke = function() emitSoon() end

-- v18: the doc's "b2": the guest's back (w) and what B is bound to now (b: that name, or "room" = the guest's own)
function N.capDoc(doc)
    local sp = G.holder and G.spec
    if sp and sp.backWant then doc.b2 = { w = sp.backWant, b = sp.back or "room" } end
    -- i98 READ-ONLY PROBE (b94 Professions: SmartNavigation.lua:611, activeInfo nil on the next show): the game's controller
    -- navigation state, while a room holds or the game's navigation has any panel: sn = { a = the active panel's frame,
    -- n = how many panels, p = the first four }. Fields are only READ (never written: no taint).
    local sn = _G.SmartNavigation
    if type(sn) ~= "table" then return end
    local function nm(f)
        local n = type(f) == "table" and TS(function() return f:GetName() end)
        return n or "?"
    end
    local ai = T(function() return sn.activeInfo end)
    local ps = T(function() return sn.activePanels end)
    local n = type(ps) == "table" and #ps or 0
    if type(ai) ~= "table" and n == 0 and not G.holder then return end
    local list = {}
    for i = 1, math.min(n, 4) do
        local pi = ps[i]
        list[#list + 1] = type(pi) == "table" and nm(pi.frame) or "?"
    end
    doc.sn = { a = type(ai) == "table" and nm(ai.frame) or "-", n = n, p = list }
end

-- v14: B follows whether the guest's back closes a shown window now (bound to it, or the guest's own)
function N.backSync()
    local sp = G.holder and G.spec
    if not (sp and sp.backWant) or not N.holding or inCombat() then return end
    local pd = N.passDown.PAD2
    local now = TN(function() return GetTime() end) or 0
    if pd and now - (pd.t or 0) > 0.5 then N.passDown.PAD2, pd = nil, nil end      -- v16: a mark never outlives its press (0.5 s)
    local b = F.closes(sp.backWant, 0, sp.hide) and sp.backWant or nil
    if b == sp.back then return end
    local was = sp.back
    sp.back = b
    padBind("PAD2")
    -- v16: B pressed through the binding just closed the guest's window (the frame may never hear B's release while the binding
    -- owns it): the guest is told now, once, and the release (if heard) is the game's and tells nothing more
    if was and not b and pd and not pd.told then
        pd.told = true
        if type(sp.passed) == "function" then pcall(sp.passed, "PAD2") end
    end
end
-- the guest's windows (spec.hide) showing / hiding re-run it a frame later (a post-hook, once per window; nothing of theirs runs ours)
F.hooked = {}
function F.hookShow(hide)
    for _, name in ipairs(type(hide) == "table" and hide or {}) do
        local f = type(name) == "string" and _G[name]
        if type(f) == "table" and not F.hooked[name] then
            F.hooked[name] = true
            local later = function() C_Timer.After(0, function() N.backSync() end) end
            pcall(function() f:HookScript("OnShow", later); f:HookScript("OnHide", later) end)
        end
    end
end

-- v13: the holder-change listeners (WC.capture.onChange)
N.capListeners = {}
function N.capCheck()
    local k = tostring(G.holder or (N.holding and "npc") or "") .. "|" .. tostring(N.fight or "")
    if k == N.capKey then return end
    N.capKey = k
    for _, fn in ipairs(N.capListeners) do pcall(fn) end
end

WC.capture = {
    version = 20,                    -- v20: spec.overlay: one level over a holding room; the room is suspended and resumed, not lost
                                     -- v19: spec.a (A = a click on that button, as back for B); spec.noFight (let go at a fight's start)
                                     -- v18: a back button counts while a guest window holding it shows; doc.b2 says B's binding
                                     -- v17: a back button the game hides (controller mode) counts while its window is visible
                                     -- v16: passed("PAD2") when B through spec.back hid the window (the release may never be heard)
                                     -- v15: a key's release follows its press (to the game, with passed) even if B was re-bound between
                                     -- v14: spec.back / spec.leave count only while a click on them closes a shown window
                                     -- v13: onChange(fn): called at once whenever the holder (or the fight's room mode) changes                    -- v10: the fight hold on the router; a take in a fight returns ok but PENDING (doc.enter)
                                     -- v11: a take refused "busy" / "npc" is QUEUED (ok; granted when the holder lets go)
    take = function(id, spec)
        if type(id) ~= "string" or id == "" or id == "npc" then return false, "bad id" end
        if type(spec) ~= "table" or type(spec.press) ~= "function" then return false, "bad spec" end
        -- version 2: `back` must name a button that exists; `pass` must name keys of the eight
        local back, pass = spec.back, nil
        if back ~= nil and (type(back) ~= "string" or type(_G[back]) ~= "table") then return false, "bad spec" end
        -- version 19: `a` names the button A clicks (like `back` for B): the dialogs' guest answers a pop-up the game did not
        -- give the controller's focus (the user, 2026-10-07: "All such pop-ups")
        local aBtn = spec.a
        if aBtn ~= nil and (type(aBtn) ~= "string" or type(_G[aBtn]) ~= "table") then return false, "bad spec" end
        -- version 9: `leave` names the button a leave clicks (the window's close), when it is not `back`
        local leave = spec.leave
        if leave ~= nil and (type(leave) ~= "string" or type(_G[leave]) ~= "table") then return false, "bad spec" end
        if spec.pass ~= nil then
            if type(spec.pass) ~= "table" then return false, "bad spec" end
            pass = {}
            for _, k in ipairs(spec.pass) do
                if not PAD_SET[k] then return false, "bad spec" end
                pass[k] = true
            end
        end
        -- version 4: `extra` names buttons beyond the eight, from EXTRA_OK
        local extra = nil
        if spec.extra ~= nil then
            if type(spec.extra) ~= "table" then return false, "bad spec" end
            extra = {}
            for _, k in ipairs(spec.extra) do
                if not EXTRA_OK[k] then return false, "bad spec" end
                extra[k] = true
            end
            if not next(extra) then extra = nil end
        end
        -- version 6 (Bags, 2026-09-30: "X tapped = More, X held = Compare"): `both` names keys of the eight whose PRESS
        -- and RELEASE both go to the guest, as press(key, true) / press(key, false); the rest keep today's timing.
        -- A list of names ({ "PAD3" }) or a set ({ PAD3 = true }).
        local both = nil
        if spec.both ~= nil then
            if type(spec.both) ~= "table" then return false, "bad spec" end
            both = {}
            for k, v in pairs(spec.both) do
                local name = (type(k) == "number") and v or (v == true and k) or nil
                if not PAD_SET[name] then return false, "bad spec" end
                both[name] = true
            end
            if not next(both) then both = nil end
        end
        if inCombat() then
            -- capture v10: in a fight a take is PENDING until the app taps the ENTER cell (doc.enter)
            local cspec = { press = spec.press, lost = spec.lost, hide = spec.hide, back = back and F.closes(back, 0, spec.hide) and back or nil,
                            backWant = back, leave = spec.leave, pass = pass, a = aBtn, noFight = spec.noFight == true or nil,
                            extra = extra, both = both, passed = type(spec.passed) == "function" and spec.passed or nil }
            -- capture v12 (Home, 2026-10-06, the rooms consistency pass): HOME'S BAR CURSOR is PRE-EMPTED in a fight too, while the
            -- router is in room mode. Both reach the pad through the same sink, so it is only the holder that changes: no binding,
            -- no attribute, nothing protected (guestLost re-binds keys only out of a fight; SetAlpha and our own shields are free).
            if G.holder == "pad:bar" and id ~= "pad:bar" and N.fight == "router" and N.holding then
                guestLost("taken")
                N.relN = N.relN + 1
                N.rel = { n = N.relN, by = "taken", who = "pad:bar" }
                G.holder, G.spec, G.chord, G.eat, G.bnext = id, cspec, nil, nil, "home"
                if N.wait and N.wait.id == id then N.wait = nil end
                N.takeAt = TN(function() return GetTime() end) or 0
                guestHide()
                emitSoon()
                return true
            end
            if (G.holder and G.holder ~= id) or (N.holding and not G.holder) then return N.queue(id, spec) end
            if not G.holder and not N.holding and N.wantEnter(id, cspec) then
                return true, "pending"      -- v10: ok, but NOT held until the app's ENTER tap lands
            end
            return false, "combat"
        end
        -- capture v7 (Home, 2026-10-01: the 08:57 race, the bar's free lost to padstray so a room's take hit a bar still
        -- holding): HOME'S BAR CURSOR ("pad:bar") is a transient picker that always closes when it picks, so a room's take
        -- PRE-EMPTS it: the bar is told "taken" and the room holds. Every other holder still answers "busy". Not in a fight
        -- (the take refused "combat" above).
        -- capture v20 (Quests / Home, 2026-10-09: Start opens the pause wheel over EVERY room): an OVERLAY take (spec.overlay) while
        -- a ROOM guest holds is granted at once and the room is SUSPENDED, not lost: no lost() / leave / back click, its hidden
        -- windows stay hidden, its extra keys give way. The overlay's release resumes it exactly as it was. One level only: under
        -- an NPC window or another overlay it is queued as before; in a fight (above) as before.
        if G.under and G.under.holder == id then
            G.under.retake = spec           -- the suspended room takes again: applied when it resumes
            return true, "suspended"
        end
        local suspended = false
        if spec.overlay == true and G.holder and G.holder ~= id and not G.under and not (G.spec and G.spec.overlay) then
            G.under = { holder = G.holder, spec = G.spec, bnext = G.bnext, takeAt = N.takeAt }
            G.holder, G.spec, G.chord, G.eat = nil, nil, nil, nil
            suspended = true
        end
        local preempted = false
        -- capture v9 (Home, 2026-10-01, uniform rooms): a take for a GAME WINDOW the game just opened (spec.window) pre-empts
        -- the room that holds, as the bar cursor always was: that room is told lost("taken") and the new one holds
        if G.holder and G.holder ~= id and (G.holder == "pad:bar" or spec.window) then
            local old = G.holder
            guestLost("taken")
            N.relN = N.relN + 1
            N.rel = { n = N.relN, by = "taken", who = old }
            preempted = true
        end
        -- capture v11 (Home, 2026-10-02: Chat's free lost behind Bags' take left Bags on screen with nobody holding): a take that
        -- finds another holder is QUEUED, not refused. The latest requester wins (an earlier waiter is told lost("taken")); it is
        -- granted the moment the holder lets go (at the next document); its own release gives it up.
        if not suspended and ((G.holder and G.holder ~= id) or (not preempted and not G.holder and N.holding and N.lend ~= id)) then
            return N.queue(id, spec)
        end
        local again = G.holder == id
        local oldExtra = again and G.spec and G.spec.extra or nil
        if not N.holding then N.hold() end
        if not N.holding then return false, "no way in" end
        -- our own copy: the guest's table is not ours to change, and cannot change under us
        -- v14: B is bound to `back` only while a click on it would close a shown window (else B is the guest's own, relayed
        -- to press); N.backSync follows it at every document, so a window shown after the take gets its B then
        G.spec = { press = spec.press, lost = spec.lost, hide = spec.hide, back = back and F.closes(back, 0, spec.hide) and back or nil, backWant = back,
                   leave = leave, pass = pass, extra = extra, both = both, a = aBtn, noFight = spec.noFight == true or nil,
                   overlay = spec.overlay == true or nil,
                   passed = type(spec.passed) == "function" and spec.passed or nil }
        if back then F.hookShow(spec.hide) end
        G.holder, G.chord, G.eat = id, nil, nil
        if N.wait and N.wait.id == id then N.wait = nil end      -- v11: a waiter that got it another way waits no more
        if not again then N.takeAt = TN(function() return GetTime() end) or 0 end
        N.stickSync(false)      -- a room never takes the sticks: the character still moves
        N.selSync()             -- v5: the bar's Select listener stands aside before the guest's keys are bound
        if not again then G.bnext = "home" end
        N.stale = false
        emitSoon()          -- one document: the app learns that B now leads where the guest says
        for _, k in ipairs(PAD_KEYS) do padBind(k) end
        -- a take again replaces the extra list: what left it goes back to the game
        if oldExtra then
            for k in pairs(oldExtra) do
                if not (extra and extra[k]) then pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
            end
        end
        -- v20: the suspended room's extra keys give way to the overlay's
        if suspended and G.under.spec and G.under.spec.extra then
            for k in pairs(G.under.spec.extra) do
                if not (extra and extra[k]) then pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
            end
        end
        for _, k in ipairs(extraKeys()) do padBind(k) end
        -- the holder takes again: what left its list is visible again; all within this call, so nothing flashes
        if again then N.unhide() end
        guestHide()
        C_Timer.After(0, function() if N.backSync then N.backSync() end end)     -- v17: B checked again a frame later
        return true
    end,
    -- why: "B" when the player left with B (the app then shows Home); anything else, or nothing, is "guest"
    release = function(id, why)
        if N.pend and N.pend.id == id and id ~= nil then N.pend = nil; emitSoon(); return true end     -- a pending enter given up
        if N.wait and N.wait.id == id and id ~= nil then N.wait = nil; emitSoon(); return true end     -- v11: a queued take given up
        if G.under and G.under.holder == id and id ~= nil then
            -- v20: the suspended room lets go: dropped, its windows come back; the overlay carries on
            G.under = nil
            N.unhide()
            guestHide()
            emitSoon()
            return true
        end
        if G.holder ~= id or id == nil then return false, "not the holder" end
        if G.under then
            -- v20: the overlay lets go (its B too): the suspended room RESUMES exactly as it was, told nothing. In a fight only the
            -- holder changes (nothing is bound there: the router routes by holder, as v12's swap)
            local u, ovExtra = G.under, G.spec and G.spec.extra
            G.under = nil
            G.holder, G.spec, G.chord, G.eat, G.bnext = u.holder, u.spec, nil, nil, u.bnext
            if N.holding and not N.fight and not inCombat() then
                for _, k in ipairs(PAD_KEYS) do padBind(k) end
                if ovExtra then
                    for k in pairs(ovExtra) do
                        if not (G.spec and G.spec.extra and G.spec.extra[k]) then pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
                    end
                end
                for _, k in ipairs(extraKeys()) do padBind(k) end
            end
            guestHide()
            N.relN = N.relN + 1
            N.rel = { n = N.relN, by = "resume", who = id, to = u.holder }     -- never "B": the app stays on the room
            emitSoon()
            if u.retake and not inCombat() then pcall(WC.capture.take, u.holder, u.retake) end
            return true
        end
        -- v10: in a fight only the secure leave button gives the pad back: the app taps doc.fightLeave
        if N.fight then return false, "tap leave" end
        if N.lend == id then
            N.lend = nil
            if why == "trainer" and isOpen("trainer") and N.holding and not inCombat() then
                -- back to the trainer WITHOUT letting go (no flash of its window on the top screen): the keys are
                -- the trainer's again at once, the guest's extra keys go back to the game
                local spec = G.spec
                G.holder, G.spec, G.chord, G.eat = nil, nil, nil, nil
                for _, k in ipairs(PAD_KEYS) do padBind(k) end
                if spec and spec.extra then
                    for k in pairs(spec.extra) do pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
                end
                N.stickSync(true)
                N.relN = N.relN + 1
                N.rel = { n = N.relN, by = "trainer", who = id }
                emitSoon()
                return true
            end
            if why == "trainer" then why = "B" end      -- the trainer closed meanwhile: as B, Home
        end
        G.holder, G.spec, G.chord, G.eat = nil, nil, nil, nil
        N.release(PS(why) or "guest", id)
        emitSoon()          -- one document, so that the app learns why the controller came back
        return true
    end,
    -- version 2: change, while holding, which of the eight go to the game untouched ({} = none).
    -- For a guest whose B is its own inside a menu and the game's own at the top level.
    pass = function(id, keys)
        if G.holder ~= id or id == nil then return false, "not the holder" end
        if type(keys) ~= "table" then return false, "bad spec" end
        if inCombat() then return false, "combat" end
        local pass = {}
        for _, k in ipairs(keys) do
            if not PAD_SET[k] then return false, "bad spec" end
            pass[k] = true
        end
        G.spec.pass = next(pass) and pass or nil
        for _, k in ipairs(PAD_KEYS) do padBind(k) end
        return true
    end,
    -- version 4: change, while holding, which buttons beyond the eight are the guest's ({} = none).
    -- Delivered to spec.press(key) on the release, like the face buttons; kept from the game while
    -- listed; given back to the game when they leave the list, at release and in combat.
    extra = function(id, keys)
        if G.holder ~= id or id == nil then return false, "not the holder" end
        if type(keys) ~= "table" then return false, "bad spec" end
        local extra = {}
        for _, k in ipairs(keys) do
            if not EXTRA_OK[k] then return false, "bad spec" end
            extra[k] = true
        end
        if inCombat() then return false, "combat" end
        local old = G.spec.extra or {}
        G.spec.extra = next(extra) and extra or nil
        for k in pairs(old) do
            if not extra[k] then pcall(function() SetOverrideBinding(padOwner, true, k, nil) end) end
        end
        for _, k in ipairs(extraKeys()) do padBind(k) end
        return true
    end,
    state = function()
        return { holder = G.holder or (N.holding and "npc") or nil, proven = N.frameSeen or false,
                 combat = (N.fight and G.holder) and true or nil, fightN = (N.fight and G.holder) and (N.fightNo or 0) or nil,
                 trusted = N.trusted or false, bad = N.distrust,
                 under = G.under and G.under.holder or nil }       -- v20: the room suspended under an overlay
    end,
    -- version 3: where the guest's next B leads, "home" or "back" (a menu open, an item picked);
    -- the guest calls it whenever that changes. The app shows B on the Home button for "home".
    bnext = function(id, where)
        if G.holder ~= id or id == nil then return false, "not the holder" end
        if where ~= "home" and where ~= "back" then return false, "bad spec" end
        if G.bnext ~= where then
            G.bnext = where
            emitSoon()
        end
        return true
    end,
    -- version 8: the room refused an action because of the fight ("in combat"): Home pulses the red flash once
    refused = function(id)
        if G.holder ~= id or id == nil then return false, "not the holder" end
        if not N.fight then return false, "no fight" end
        N.fightNo = (N.fightNo or 0) + 1
        emitSoon()
        return true
    end,
    -- version 13 (Spells, 2026-10-06, the rooms consistency pass): fn() is called (pcall'd, no arguments: read state())
    -- right after anything changes state().holder or the fight's room mode, so a key owner can re-check at once
    onChange = function(fn)
        if type(fn) ~= "function" then return false, "bad spec" end
        N.capListeners[#N.capListeners + 1] = fn
        return true
    end,
    chord = function(id, onChord, onCancel)
        if G.holder ~= id or id == nil then return false, "not the holder" end
        if type(onChord) ~= "function" then return false, "bad spec" end
        G.chord = { done = onChord, cancel = onCancel }
        return true
    end,
}

-- The companion says it is in charge through the hub's ONE layout tap (Hub.lua): element
-- "dialogs" hidden = the companion shows the NPC windows and the controller drives them there.
if type(WC.hub) == "table" and type(WC.hub.element) == "function" then
    pcall(WC.hub.element, "dialogs", {
        hide = function() return N.setMode(true) end,
        show = function() return N.setMode(false) end,
        hidden = function() return N.mode end,
        combatOk = true,        -- in combat only the flag changes; the buttons wait for the fight to end
    })
end

-- ---------------------------------------------------------------- the tap pad rows
if WC.pad and type(WC.pad.op) == "function" then
    local function viaPad(key)
        quiet = true
        -- an unknown column arrives as key == nil: a tap on a cell nothing is assigned to
        if type(key) ~= "string" then ack("?", false, "nothing on that cell")
        else pcall(dispatch, key) end
        quiet = false
    end
    for _, ns in ipairs(NS) do pcall(WC.pad.op, ns, ns, viaPad) end
    -- the quantity row never changes: column k-1 is "k purchases", claimed once and kept
    if pcall(WC.pad.op, "iqty", "iqty", viaPad) and type(WC.pad.slotFor) == "function" then
        for k = 1, QTY_MAX do pcall(WC.pad.slotFor, "iqty", "qty " .. k) end
    end
    -- a new UI scale moves every cell: re-publish the geometry at once, not at the next change
    if type(WC.pad.onLayout) == "function" then
        WC.pad.onLayout(function() if lastJson ~= nil then emitSoon() end end)
    end
end

SLASH_WCI1 = "/wci"
SlashCmdList["WCI"] = function(arg)
    arg = (arg or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
    if arg == "" then
        emitNow(true)
        say("interact " .. IV .. "  gossip=" .. tostring(isOpen("gossip")) ..
            " quest=" .. tostring(isOpen("quest")) .. " merchant=" .. tostring(isOpen("merchant")))
        return
    end
    dispatch(arg)
end


