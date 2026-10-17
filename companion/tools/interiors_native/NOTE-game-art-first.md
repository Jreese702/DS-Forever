# Note for Track B: the game's own art is now the main interior picture

The user's decision, 2026-10-17 (via tracking), on whether we still need to draw indoor art:
**(a) COMBINE.** It came up after the user saw High Fidelity World Map, which draws caves and buildings from the
client's own minimap tiles.

## What changes
- **The picture**: wherever the game has baked minimap art for a building (the WMO minimap tiles the in-game minimap
  shows indoors), that art is drawn. The art harvest reads it on the device, per WMO group, so nothing goes in git or
  the APK. The MAP session builds this: first a harvest probe that logs how many buildings have art, then MapView and
  WorldMapRoomView draw it under the floor outlines.
- **Track B's generator stays underneath** for:
  - floor partitions, stacked-floor picking and level choice (the game's per-group heights are coarse);
  - walkability, doors and ramps for routing (CursorRoute);
  - the picture itself **only where the game has no minimap art** for a building or cave.

## What to stop
Polishing the rendered look (fills, wall ink, shading) of buildings and caves the game already covers.
The probe's log will list which ones those are. Until it lands, put fidelity work on geometry (floors, stacking,
walkable cells) rather than on looks.

## Still wanted from Track B
- The floor and level outlines as they are now (`fold_storeys`, see HANDOFF-interiors-overlap.md). The game's art is
  drawn under them and they choose the floor.
- A plain fallback render for buildings with no game art.
- Walkable cells and door positions for the router.
