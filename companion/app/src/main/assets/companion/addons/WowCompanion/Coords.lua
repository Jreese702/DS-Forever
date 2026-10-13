-- WowCompanion / Coords.lua
-- The bridge between the bundled static POI dataset (WORLD coordinates, straight out of the
-- 3.3.5a world DB) and the bottom-screen map (NORMALISED uiMap coordinates, 0..1).
--
-- AXES -- get this right or the map is silently mirrored:
--   WORLD space (UnitPosition, and the DB's position_x/position_y):
--       +x = NORTH      +y = WEST      units are yards
--   NORMALISED uiMap space (C_Map.GetPlayerMapPosition, quest/POI/taxi positions):
--       (0,0) = top-left = NORTH-WEST corner
--       +x = EAST       +y = SOUTH     unitless 0..1
--   So the two spaces are SWAPPED *and* both signs are FLIPPED:
--       normalised x  <-  -world y        (west -> east)
--       normalised y  <-  -world x        (north -> south)
--
-- The rect comes from the LIVE client, so it is always correct for whatever map art this
-- build ships -- we never bake rects offline and hope they still match.
--   VERIFIED PRESENT: C_Map.GetWorldPosFromMapPos(uiMapID, mapPosition)
--                       -> continentID, worldPosition   (a Vector2D: .x = world X, .y = world Y)
--   Corner (0,0) is the NW corner, so it returns MAX x (north) and MAX y (west).
--   Corner (1,1) is the SE corner, so it returns MIN x (south) and MIN y (east).
--   Hence:  top > bottom  and  left > right, always. Both are asserted below.

local ADDON = ...
local WC = _G.WowCompanion
local CALL, isPlain = WC.CALL, WC.isPlain

-- Read a Vector2D without depending on the mixin methods existing: the C API hands back a
-- table with .x/.y, and :GetXY() is FrameXML sugar that may or may not be loaded.
-- type(secret) is SAFE and reports "number", but ARITHMETIC on a secret THROWS. Every corner
-- value feeds straight into multiplication, so isPlain() is mandatory here, not paranoia.
local function num(v)
    if type(v) ~= "number" then return nil end
    if not isPlain(v) then return nil end
    return v
end

local function vec(v)
    if type(v) ~= "table" then return nil end
    local ok, x, y = CALL(function() return v:GetXY() end)
    if ok then
        local nx, ny = num(x), num(y)
        if nx and ny then return nx, ny end
    end
    local ok2, x2, y2 = CALL(function() return v.x, v.y end)
    if ok2 then
        local nx, ny = num(x2), num(y2)
        if nx and ny then return nx, ny end
    end
end

local rectCache = {}   -- [uiMapID] = rect or false (false = this map has no world rect)

-- Returns rect = { continent, top, bottom, left, right, height, width } or nil.
--   top/bottom are WORLD X (north/south). left/right are WORLD Y (west/east).
function WC.zoneRect(uiMapID)
    if not uiMapID then return nil end
    local c = rectCache[uiMapID]
    if c ~= nil then return c or nil end

    local okA, contA, nw = CALL(function()
        return C_Map.GetWorldPosFromMapPos(uiMapID, { x = 0, y = 0 })
    end)
    local okB, contB, se = CALL(function()
        return C_Map.GetWorldPosFromMapPos(uiMapID, { x = 1, y = 1 })
    end)
    if not (okA and okB) then rectCache[uiMapID] = false return nil end

    local top, left     = vec(nw)
    local bottom, right = vec(se)
    if not (top and left and bottom and right) then rectCache[uiMapID] = false return nil end

    -- Instance/cosmic/flight maps have no world rect and come back degenerate. Also catches
    -- a transposed Vector2D: if .x and .y were swapped these inequalities break.
    local h, w = top - bottom, left - right
    if h <= 1 or w <= 1 then rectCache[uiMapID] = false return nil end

    local rect = {
        continent = contA, top = top, bottom = bottom, left = left, right = right,
        height = h, width = w, aspect = w / h,
    }
    rectCache[uiMapID] = rect
    return rect
end

-- world -> normalised. wx = DB position_x (north), wy = DB position_y (west).
function WC.worldToMap(rect, wx, wy)
    if not rect then return nil end
    return (rect.left - wy) / rect.width,      -- nx: west-axis, inverted
           (rect.top  - wx) / rect.height      -- ny: north-axis, inverted
end

-- normalised -> world, for "walk me to the thing I tapped".
function WC.mapToWorld(rect, nx, ny)
    if not rect then return nil end
    return rect.top  - ny * rect.height,       -- wx (north)
           rect.left - nx * rect.width         -- wy (west)
end

-- Distance MUST be computed in world space. Zone maps are 3:2, not square, so a normalised
-- distance is wrong by up to 50% depending on the bearing.
function WC.worldDist(ax, ay, bx, by)
    local dx, dy = ax - bx, ay - by
    return math.sqrt(dx * dx + dy * dy)        -- world units are yards
end

-- ---------------------------------------------------------------- self test
-- Route (b) used as an ASSERTION, not as the mechanism: the player's own position must round
-- trip through the rect. Fails loudly if the axes are ever wrong on a future client build.
function WC.checkCoords()
    local ok, id = CALL(function() return C_Map.GetBestMapForUnit("player") end)
    if not ok or not id then return { ok = false, why = "no uiMapID" } end
    local rect = WC.zoneRect(id)
    if not rect then
        return { ok = false, why = "no world rect", uiMapID = WC.SN(function() return id end) }
    end

    local okp, pos = CALL(function() return C_Map.GetPlayerMapPosition(id, "player") end)
    -- NOT `okp and vec(pos) or nil`: Lua's and/or truncates a multi-return to its first value,
    -- which silently made ny nil on every call.
    local nx, ny
    if okp then nx, ny = vec(pos) end
    local okw, rwx, rwy, _, rinst = CALL(function() return UnitPosition("player") end)
    local wx, wy = num(rwx), num(rwy)
    if not (nx and okw and wx and wy) then return { ok = false, why = "no position" } end
    local instanceID = num(rinst)

    local cx, cy = WC.worldToMap(rect, wx, wy)
    local ex, ey = math.abs(cx - nx), math.abs(cy - ny)
    return {
        ok = (ex < 0.002 and ey < 0.002),
        -- num() not the raw values: uiMapID comes straight out of GetBestMapForUnit and
        -- continent out of GetWorldPosFromMapPos, and either could be a secret on this build.
        -- This table is both concatenated into a chat line and JSON-encoded onto the wire, and
        -- a secret poisons the whole line -- C_Log.LogMessage then refuses it silently.
        uiMapID = num(id), continent = num(rect.continent), instanceID = instanceID,
        worldX = wx, worldY = wy,
        clientNX = nx, clientNY = ny, computedNX = cx, computedNY = cy,
        errX = ex, errY = ey,
        errYards = WC.worldDist(0, 0, ex * rect.height, ey * rect.width),
        rect = { top = rect.top, bottom = rect.bottom, left = rect.left, right = rect.right },
        aspect = rect.aspect,
    }
end
