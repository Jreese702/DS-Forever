# SafePath harness (off-device)

This harness runs the app's own SafePath, Climb and Caves code on dumps from the PC client, so a zone's network, exits, caves and test
routes can be reviewed as pictures before anything goes near the device. The rules are in `SAFEPATH_SPEC.md`; the steps for one zone are in
`ZONE_PLAYBOOK.md`. Both are agent guidance, which this repo keeps private (`docs/` is ignored), so they live outside git: in
`E:/safepath-work/docs/` (the shared work folder) and in the main checkout's `E:/dsf-wt-map/docs/safepath/`.

**Only code lives here.** Game dumps, art, tables and pictures stay in the work folder (`SAFEPATH_WORK`, default `E:/safepath-work`),
which is outside the repo and never committed.

## What's needed

| Need | Default | Override |
|---|---|---|
| The installed client | `C:\Program Files (x86)\World of Warcraft`, product `wow_classic_beta` | `WOW_DIR`, `WOW_PRODUCT` |
| The CASC reader tools (`casc.py`, `db2.py`) | `E:/wc-addon-map/tools/icons` | `CASC_TOOLS` |
| The community listfile | `E:/wc-scratch-c9979a42/casc/community-listfile.csv` | `LISTFILE` |
| QuestieDB Forever's toc (the spawns) | `E:/wow-build/questiedb/unz/QuestieDB/QuestieDB_Forever.toc` | `QDBTOC` |
| The Android SDK (for `android.jar`) | — | `ANDROID_HOME` |
| Python 3 with numpy and Pillow | — | — |
| Gradle (the repo's wrapper, offline) | — | — |
| The work folder | `E:/safepath-work` | `SAFEPATH_WORK` |

The work folder's layout:

| Path | Contents |
|---|---|
| `kalimdor/`, `azeroth/`, ... | Per continent: `adt/` (root ADTs), `tex0/` (texture ADTs and `names.txt`), `obj0/` (object ADTs), `wmo/` (WMO roots), `wmogroup/` (cave WMO groups), `minimap/` (PNG tiles). |
| `db2/` | `AreaTable`, `Map`, `UiMapAssignment`, `WMOAreaTable`, `Taxi*`, `UiTextureAtlas*`. |
| `artroot/` | The app's art harvest: `worldmap/<uiMap>/` (base and overlays, `index.json`) and `transports.json`. Copy it from an existing work folder; `transports.json` is rebuilt by the app's `TransportRoutes` from the `Taxi*` tables. |
| `monitor-1.7.2.jar` | androidx test monitor, for Robolectric. |
| `app.jar` | The app's classes (`run.sh` builds it). |
| `out/` | Everything the harness writes. |

## Dump a zone (local CASC reads only: no downloads)

```bash
cd tools/safepath-harness/dump
python dump_db2.py                                   # once: the tables into <work>/db2
python dump_zone_tiles.py <uiMap> <dir> <continent>  # the zone's root and tex0 ADTs; names.txt is merged across zones
python dump_obj.py <dir> <continent>                 # the obj0 ADTs (MODF) and every placed WMO's root
python dump_minimap.py <dir> <continent>             # the minimap tile for each dumped ADT (the 3D view's drape)
python dump_cavegroups.py <dir>                      # cave / tunnel / mine / den WMOs' group files (their floors)
```

- `<dir>` is the continent's folder: `kalimdor` (continent 1) or `azeroth` (continent 0).
- Dump the zone, its capital and its neighbours into the same folder.

## The zone spec: `zones/<tag>.json`

```json
{"tag": "elwynn", "dir": "azeroth", "faction": "alliance", "zone": 1429, "area": 12, "level": 6,
 "maps": [[1429, 12], [1453, 1519], [1436, 40], [1433, 44]], "cities": [1453], "border": [1429, 1436],
 "title": "Elwynn Forest", "crop": [-8350, -10200, 1300, -1800],
 "closeups": [{"name": "Fargodeep Mine", "box": [-9700, -10000, 400, 0]}],
 "goals": [
  {"name": "Fargodeep Mine's mouth", "from": {"node": "Goldshire"}, "to": {"cave": 110773, "near": [-9834, 194]}},
  {"name": "Hogger (Forest's Edge)", "from": {"node": "Goldshire"}, "to": {"mob": "Hogger"}}
 ]}
```

| Field | Meaning |
|---|---|
| `tag` | The zone's short name; the output files are named by it. |
| `dir` | The dump folder (continent). |
| `faction` | The test character's side: `horde` or `alliance`. Its danger counts only mobs hostile to it. |
| `zone`, `area` | The zone's uiMap id and its AreaTable id. |
| `level` | The test character's level, for the danger weights. |
| `maps` | `[uiMap, AreaTable id]` for the zone first, then its capital and neighbours. Each is drafted; the zone gets the routes. |
| `cities` | Capitals among `maps` (their ground read from the map art). |
| `border` | The pair of zones whose crossings get the border picture. |
| `title`, `crop` (`[top x, bottom x, left y, right y]`, the zone's land), `closeups` (name and box, the same order) | For the renders. |
| `goals` | 8–10 routes, as below. With no `goals` key, Durotar's own built-in goals are used. |

Goal fields:

- `from`:
  - `{"node": "<a named network node>"}`;
  - `{"exit": "<exit name>"}` (where it opens onto this zone's own level ground);
  - `{"xy": [x, y]}`.
- `to`:
  - `{"cave": <WMO file id>, "near": [x, y]}`: the cave's mouth nearest the start;
  - `{"wmo": <file id>, "near": [x, y]}`: a building;
  - `{"camp": "<subzone name>"}`: its hostile spawns at their thickest;
  - `{"mob": "<creature name>", "near"?: [x, y]}`;
  - `{"node": ...}`;
  - `{"exit": "<name>", "how"?: "bridge"}`;
  - `{"xy": [x, y]}`.
- World yards: `x` north, `y` west. Starts and goals are snapped to the nearest standable spot within 15 yd.

## Run, check, render

```bash
bash tools/safepath-harness/run.sh tools/safepath-harness/zones/<tag>.json "ROUTE2|EXIT|CAVE|WALLS|GOAL|EMPTY"
cd tools/safepath-harness/py
export ZDIR=<dir>                       # the dump folder the Python tools read
python catalog.py  $SAFEPATH_WORK/out/levels_routes_<tag>.json   # goal picking: caves, nodes, exits, subzones, mobs, buildings
python segcheck.py $SAFEPATH_WORK/out/levels_routes_<tag>.json   # every segment against the raw heights and faces: must say "none"
python calib.py    $SAFEPATH_WORK/out/levels_routes_<tag>.json   # each route's highest climb by steep faces (30–50 degrees)
python render3d.py ../zones/<tag>.json                           # <tag>_heightmap.png, <tag>_3d.png, <tag>_<closeup>.png
```

`run.sh` does four things:

1. builds the app's classes (`:dsforever:compileDebugKotlin`);
2. jars them into `<work>/app.jar`;
3. runs `robo/` (Robolectric, `RenderSafePath`);
4. greps its output.

It writes these into `<work>/out/`:

- `safepath_<uiMap>.draft.json` and `safepath_draft_<uiMap>.png`: the draft on the zone's art;
- `safepath_border_<a>_<b>.png`: the crossings;
- `levels_routes_<tag>.json`: the routes, caves, danger, network, exits' ground checks, mobs and subzones, for the renders.

Output lines to look for:

| Line | What it reports |
|---|---|
| `SAFEPATH <zone>: N nodes ...` | The draft. |
| `EXIT <name> (<how>) ... WALKABLE / NOT WALKABLE` | The ground flood from each exit. |
| `CAVE <fid> at x,y: n mouths ...; m ways` | Each cave found. |
| `ROUTE2 <letter> <from> -> <goal>: N yd, n flags` | Each route; "(the goal unreachable ...)" means it is not reached. |
| `WALLS route X crosses a wall on segments [...]` | The hard rule broken. Must never appear. |
| `GOAL ... not found` | A spec goal that doesn't resolve. |

## Other tools (`py/`)

| Tool | Use |
|---|---|
| `heights.py` | The ADT heights, water and WMO placements in Python. |
| `faces.py` | The game's triangles: a point's face slope. |
| `flood.py x,y x,y ...` | Whether points are on one walkable region (the outer-vertex rule). |
| `line_probe.py <art.png> <out.png>` | The green zones' painted-road detector (not in the app yet; see the spec). |
| `mddf_find.py <regex> t b l r [dir]` | Doodads (M2) by name in a box. |
| `modd_find.py <regex> <wmo regex> [dir]` | A WMO's own doodads (MODD). |
| `wmofloor.py <fid> <groups>`, `floorcheck.py <fid> <groups> ox oy oz yawdeg` | A cave WMO's groups and its floor against the terrain. |

## Deliverables per zone (checklist)

- [ ] The drafted network: counts, the main roads present, the picture checked.
- [ ] Exits: each checked on the ground. Flag the false ones; check the crossings with the neighbour.
- [ ] Caves: each with its mouths and ways; known mines and caves all present.
- [ ] 8–10 test routes to real goals, flags numbered, all reached.
- [ ] No `WALLS` line; segcheck `none`.
- [ ] `<tag>_heightmap.png` and `<tag>_3d.png` (danger, caves, network, exits drawn) plus close-ups, sent to the user.
- [ ] Special cases flagged (lifts, multi-floor dens, tunnels, trees and ramps), each handled or marked "needs a manual link".
- [ ] The report to tracking (see the playbook).
