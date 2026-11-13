# Two bugs that stop any non-Thor handheld, and what fixes them

Measured on a **Retroid Pocket Nova** (QCS8550 / Adreno 740, Android 13, 12 GB) with the
**Retroid Dual Screen Add-on**, 2026-11-13. The companion now drives the game there end to end,
tap bridge included.

Both bugs are generic. Neither is about the Nova as such: the first hits any handheld whose second
screen is **bigger** than its game screen, the second hits any handheld whose panel is **not
16:9**. Neither changes anything on a Thor, and that is checkable rather than hoped for — in both
cases the Thor's values already satisfy the new rule.

They are independent, and each on its own was enough to make the companion useless.

They differ in origin, which matters for where a fix belongs. Bug 1 is DS Forever's own. Bug 2 is a
**divergence from upstream**: `jaredgei/wow-forever-android` already fits the profile's size to the
window and has a test for the non-16:9 case, and this fork lost that path. See "Bug 2 is ours, not
upstream's" below -- it also names the better long-term fix, which is bigger than this change.

---

## Bug 1 — the game's screen was assumed to be the largest panel

**`companion/.../TapBridge.kt`, `gameDisplay()`.** DS Forever's own code, with no upstream to
report it to.

```kotlin
// the top screen is the Thor's largest panel: anything smaller is the wrong display
val largest = dm.displays.maxOfOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight } ?: 0L
if (w <= 0 || h <= 0 || w.toLong() * h < largest) null else Point(w, h)
```

That rule was a stand-in for a real defence — the 2026-09-28 bug where display 0's size came back
as the *companion* screen's — and it is true on a Thor, whose display 0 (1920x1080) is bigger than
its 1240x1080 bottom screen.

It is false the other way round. On a Nova display 0 is the 1280x960 built-in panel and the add-on
is 1920x1080, so display 0 is never the largest and **every tap the app ever made was refused**:

```
W TapBridge: refused: no trusted display size
```

Nothing else in the app reports this. The companion simply does nothing, for ever, with no
message a player could act on.

**Fix.** Exclude the companion display's size, which is what the rule was reaching for.
`DsForeverScreen` tells `TapBridge` which display its own `Presentation` is on, and clears the
cached panel size when a display comes or goes (the add-on is hot-pluggable, and its display id
increases on every reconnect — 7, 8, 9, 10, 13, 17 over one evening).

`HomeLoginWatch.gameSize()` held a second copy of the same rule, so auto-login failed the same
way; it now defers to `TapBridge`, leaving one validated read for the whole app.

**Thor unchanged:** display 0 is still its largest panel, and the new check only ever excludes the
companion screen's size, which on a Thor is 1240x1080 and never equal to display 0's.

---

## Bug 2 — the container's shape was assumed to be 16:9

**`app/.../ui/screen/wow/WoWForeverScreen.kt`, `GpuProfile`.** Not an upstream bug — this fork
dropped a fix upstream already has. Do not report it there; see "Bug 2 is ours, not upstream's"
below for the evidence and what should happen instead.

```kotlin
GpuProfile(ADRENO_8XX_DRIVER, "noconform,sysmem", "1280x720",  "Adreno 8xx", ...)
GpuProfile(DEFAULT_DRIVER,    "noconform",        "1280x720",  "Adreno 650", ...)
GpuProfile(DEFAULT_DRIVER,    "noconform",        "1920x1080", "Adreno 740", ...)
```

Every profile names a 16:9 size, because every Thor is 16:9, and `containerConfig` puts it
straight into the container. **There is no 4:3 option anywhere in the project.**

So on a 4:3 panel the container is always the wrong shape, the game renders 16:9 into it, and the
aspect guard in `TapBridge.gamePoint` refuses every tap — correctly, because a tap arrives in the
game's own pixels and those cannot be mapped onto a differently shaped panel without landing
somewhere else entirely. The guard was right and the container was wrong:

```
I TapBridge: game display 0 = 1280x960 (physical mode, landscape)
W TapBridge: refused: display 1280x960 does not match the game's 1280x720
```

**Fix.** `detect()` keeps the profile's size whenever it already matches the panel's shape, and
otherwise takes `PluviaApp.getDefaultScreenSize()` — which already reads the display's aspect and
answers `1280x960` below 1.5, so no new policy is invented. The tolerance is 0.02, the same figure
`gamePoint` compares with, so the two agree by construction rather than by luck.

Nothing has to be recreated: an existing container gets `container.loadData(config)` at every
launch.

**Thor unchanged:** 1920x1080 and 1280x720 are both 16:9, every Thor panel is 16:9, so the
profile's size is always kept and `getDefaultScreenSize()` is never consulted.

---

## Bug 2 is ours, not upstream's

An earlier version of this file said bug 2 was "worth reporting" to the GameNative-derived
launchers, on the reasoning that a per-device profile list is the kind of thing that gets copied
along with its assumptions. That was an inference, it was never checked, and it is wrong.
`jaredgei/wow-forever-android` handles this already:

```
WoWForeverScreen.kt:  var resolution by remember { mutableStateOf(RenderResolution.current(context, gpu.screenSize)) }
                      val screenSize = fitScreenSizeToWindow(context, resolution.size)
FitScreenSizeTest.kt: assertEquals("1920x1080", fitScreenSize("1920x1080", 2400, 1350))
                      assertEquals("1104x834",  fitScreenSize("1280x720", 2448, 1848))
```

The second assertion is this exact bug, already covered: 2448x1848 is a ~4:3 window, and a 16:9
profile size of `1280x720` is refit to `1104x834` to match it. Upstream also exposes the choice
through a `RenderResolution` picker rather than deciding silently.

This tree has **none** of `fitScreenSizeToWindow`, `fitScreenSize` or `RenderResolution` — no hits
at all. So bug 2 is a fork regression: `WoWForeverScreen.kt` diverged and lost the fitting path,
and the `detect()` / `fitPanel()` fix above reinvents a narrower version of what upstream wrote and
tested.

**What should happen instead.** Port upstream's `RenderResolution` + `fitScreenSizeToWindow` back
in, which also gives the player a resolution choice this fork does not have. Keep one thing from
the fix above when doing it: upstream fits to the **window**, `fitPanel` fits to the **display**
the game will be on. Those are the same screen while the game renders in the launcher's own window
and different ones the moment anything puts it on another display, so the display-aware read
should survive the port. Until then `fitPanel` is measured and working, so this is a cleanup rather
than an outage.

Checked 2026-11-13 with `gh search code --repo jaredgei/wow-forever-android "screenSize"`. Worth
re-checking rather than trusting this paragraph: the fork is behind upstream by an unknown amount,
and this is exactly the kind of claim that goes stale.

---

## What this looked like once both were in

```
I GpuProfile$Companion: Container screen size 1920x1080 does not fit the panel: using 1280x960
I TapBridge: game display 0 = 1280x960 (physical mode, landscape)
W TapBridge: refused: display 1280x960 does not match the game's 1280x720   <- the first call only
I TapBridge: quiet pad: qact:18 waited 0 ms (held at queue false) keys off: off
I TapBridge: quiet pad: pnav:36 pnav:1 pnav:3 pnav:25 pnav:17 pnav:12 pnav:15 ...
I TapBridge: quiet pad: spells:7 ...
I TapBridge: quiet pad: ui:31 / ui:33 / uisw:1 ... uisw:34, qact:36, qact:19
```

The one refusal is the first call, before the add-on republished its pad at the new resolution.
After it, thirty seconds of real room actions — quest actions, party navigation, spells, UI
switches — and no refusal.

## How to check this on any device

1. `adb logcat -d -s TapBridge:V` in the world, after touching a companion control that acts on
   the game.
2. `game display N = WxH` must appear at all. Its absence, or `refused: no trusted display size`,
   is bug 1.
3. `refused: display WxH does not match the game's AxB` is bug 2: the container's shape is not the
   panel's.
4. `quiet pad: <cells>` at INFO is the success path.
