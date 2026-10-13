#!/system/bin/sh
# THE COMPANION'S SELF-SETUP (Home, 2026-10-02; the user: "would it be possible to have the addon install and Config change as part
# of our apps install ... we can make it a prompt that happens if its missing and then it goes and fixes it", "close the game and
# reopen it after its all set up", "lets get that all added"). Shipped in the APK, run by the app as root:
#   su --mount-master -c "sh companion_setup.sh <mode> <gn|wf> <src dir>"
# check   : read-only; what this game lacks (machine lines below)
# close   : stops the game's app (only after the user's yes on the prompt) and waits until its game process is gone
# install : the game must be closed. Backs up its companion folders, Config.wtf and the game app's container config; copies the
#           add-ons from <src>/addons byte for byte, removes companion files the set no longer has, writes the set stamp, sets the
#           REQUIRED settings and the DEFAULTS (below); then checks the md5 of EVERY add-on file against <src>/manifest.txt. ANY
#           failure puts the whole backup back and prints FAIL.
# start   : starts the game on the top screen (display 0)
#
# THE SAME FOR EVERY USER (the user, 2026-10-02: "most people are going to be using this from a fresh set up standpoint and I dont want
# to just migrate stuff over, I want to set defaults and for this to set it up for people"). NOTHING is copied from another install.
# REQUIRED (always set):
#   - Config.wtf developerLog "1": the log channel the companion reads
#   - Config.wtf InputDeviceInterfaceStyle "1": the game's "Enable Gamepad UI", Forever's controller switch (a fresh install starts
#     with it OFF and every face button does nothing). With it on, the game's own gamepad code turns interact soft targeting on.
#   - the game APP's container config: Touchscreen mode ON, Shooter mode OFF (touchscreenMode true, shooterMode false): in trackpad /
#     shooter mode a tap clicks where the cursor is and no pad tap of the companion lands (measured in WoW Forever; the user: "if we
#     need touchscreen mode in order for our app to function correctly then yes add it"). Written through `cat >` so the file keeps
#     its owner and SELinux label.
# DEFAULTS (the user's picks; written only where the setting is ABSENT, so a change the player makes later is never fought):
#   - GamepadDisableTooltips "1": hide tooltips in gamepad mode
#   - GamePadTurnWithCamera: turn with camera (0 moving, 1 moving + combat, 3 moving + combat + casting = Blizzard's default, 2 always)
# NEVER touched: graphics, sound, the account and character folders, saved variables. Output: one fact per line, then END.
MODE="$1"; G="$2"; SRC="$3"
GN_ROOT=${WC_GN:-/data/data/app.gamenative/files/imagefs_shared/home/xuser-CUSTOM_GAME_178249507/games/WoW-Forever-beta/_classic_beta_}
WF_ROOT="${WC_WF:-/storage/emulated/0/WoW Forever/_classic_beta_}"
BKROOT=${WC_BK:-/sdcard/WowCompanion/setup}
T=${WC_T:-/data/local/tmp}
REQ_KEYS='developerLog InputDeviceInterfaceStyle'
DEFAULTS='GamepadDisableTooltips=1 GamePadTurnWithCamera=1'
STAMP=.companion_set

case "$G" in
gn) ROOT="$GN_ROOT"; PKG=${WC_PKG:-app.gamenative}
    CT=${WC_GN_CT:-/data/data/app.gamenative/files/imagefs_shared/home/xuser-CUSTOM_GAME_178249507/.container} ;;
wf) ROOT="$WF_ROOT"; PKG=${WC_PKG:-app.wowforever}
    CT=${WC_WF_CT:-/data/data/app.wowforever/files/imagefs_shared/home/xuser-wow_forever/.container} ;;
# the private fork "DS Forever Launcher" (2026-10-08): WoW Forever's code under its own package, the SAME game folder
dl) ROOT="$WF_ROOT"; PKG=${WC_PKG:-app.dsforever.launcher}
    CT=${WC_DL_CT:-/data/data/app.dsforever.launcher/files/imagefs_shared/home/xuser-wow_forever/.container} ;;
*) echo "FAIL usage unknown game"; echo END; exit 1 ;;
esac
A="$ROOT/Interface/AddOns"
C="$ROOT/WTF/Config.wtf"

uid_of() { dumpsys package "$PKG" 2>/dev/null | grep -m1 'userId=' | sed 's/.*userId=\([0-9]*\).*/\1/'; }
running() {
    u=$(uid_of); [ -z "$u" ] && return 1
    ps -A -o USER=,NAME= | grep -E "^u0_a$((u - 10000)) +(wine|wineserver|wine64)" >/dev/null
}
# GameNative's folder is that app's private data: what root writes there must belong to the app again
own() {
    [ "$G" = gn ] || return 0
    [ -e "$1" ] || return 0
    u=$(uid_of); [ -z "$u" ] && return 0
    chown -R "$u:$u" "$1" 2>/dev/null
    if [ -d "$1" ]; then
        find "$1" -type d -exec chmod 770 {} + 2>/dev/null; find "$1" -type f -exec chmod 644 {} + 2>/dev/null
    else chmod 644 "$1" 2>/dev/null; fi
    chcon -R u:object_r:app_data_file:s0 "$1" 2>/dev/null
    return 0
}
sums() { ( cd "$A" 2>/dev/null && for d in WowCompanion*; do [ -d "$d" ] && find "$d" -type f ! -name "$STAMP"; done | sort |
           while read -r f; do echo "$(md5sum "$f" | cut -c1-32)  $f"; done ); }
val() { sed -n "s/^SET $2 \"\(.*\)\"\r*\$/\1/p" "$1" 2>/dev/null | head -1; }      # the value of SET <key> in file $1
has() { grep -q "^SET $2 " "$1" 2>/dev/null; }                                         # file $1 sets key $2 at all
setkv() {                                                                               # file key value: replace or append
    grep -v "^SET $2 " "$1" > "$T/wcsetup_kv" 2>/dev/null
    # the game writes CRLF (a Windows client under Wine): a new line keeps the file's own ending
    if grep -q "$(printf '\r')" "$1" 2>/dev/null; then printf 'SET %s "%s"\r\n' "$2" "$3" >> "$T/wcsetup_kv"
    else printf 'SET %s "%s"\n' "$2" "$3" >> "$T/wcsetup_kv"; fi
    cat "$T/wcsetup_kv" > "$1"; rm -f "$T/wcsetup_kv"
}
# the game app's touch settings the companion's taps need: prints the keys that are wrong (nothing = right, or no container file)
touch_missing() {
    [ -f "$CT" ] || return 0
    grep -qE '"touchscreenMode" *: *true' "$CT" || echo touchscreenMode
    grep -qE '"shooterMode" *: *false' "$CT" || echo shooterMode
}
req_missing() { for k in $REQ_KEYS; do [ "$(val "$C" "$k")" = 1 ] || echo "$k"; done; }
def_missing() { for kv in $DEFAULTS; do has "$C" "${kv%%=*}" || echo "${kv%%=*}"; done; }

case "$MODE" in
check)
    if [ -d "$ROOT" ]; then echo "INSTALLED 1"; else echo "INSTALLED 0"; echo END; exit 0; fi
    if running; then echo "RUNNING 1"; else echo "RUNNING 0"; fi
    echo "STAMP $(cat "$A/WowCompanion/$STAMP" 2>/dev/null || echo none)"
    if [ -n "$SRC" ] && [ -f "$SRC/manifest.txt" ]; then
        miss=""
        for d in $(grep -v '^set ' "$SRC/manifest.txt" | cut -c35- | cut -d/ -f1 | sort -u); do [ -d "$A/$d" ] || miss="$miss $d"; done
        echo "MISSING$miss"
        grep -v '^set ' "$SRC/manifest.txt" > "$T/wcsetup_want.txt"
        sums > "$T/wcsetup_have.txt"          # two files: toybox diff does not read "-" (measured on the Thor)
        echo "DIFF $(diff "$T/wcsetup_have.txt" "$T/wcsetup_want.txt" 2>/dev/null | grep -c '^[-+<>][^-+]')"
        rm -f "$T/wcsetup_want.txt" "$T/wcsetup_have.txt"
    fi
    echo "CONFIG_MISSING $(req_missing | tr '\n' ' ')"
    echo "TOUCH_MISSING $(touch_missing | tr '\n' ' ')"
    echo "DEFAULTS_MISSING $(def_missing | tr '\n' ' ')"
    ;;
close)
    if ! running; then echo "CLOSED already"; echo END; exit 0; fi
    am force-stop "$PKG"
    n=0; while running && [ $n -lt 30 ]; do sleep 0.5; n=$((n + 1)); done
    if running; then echo "FAIL close the game is still running"; else echo "CLOSED"; fi
    ;;
install)
    running && { echo "FAIL install the game is still running"; echo END; exit 2; }
    [ -f "$SRC/manifest.txt" ] && [ -d "$SRC/addons" ] || { echo "FAIL install no add-on set in the app"; echo END; exit 2; }
    [ -f "$C" ] || { echo "FAIL install no Config.wtf (start the game once first)"; echo END; exit 2; }
    B="$BKROOT/$G-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$B/addons" || { echo "FAIL backup cannot write $B"; echo END; exit 3; }
    cp "$C" "$B/Config.wtf" || { echo "FAIL backup Config.wtf"; echo END; exit 3; }
    for d in "$A"/WowCompanion*; do [ -d "$d" ] && { cp -R "$d" "$B/addons/" || { echo "FAIL backup add-ons"; echo END; exit 3; }; }; done
    if [ -f "$CT" ]; then cp "$CT" "$B/container" || { echo "FAIL backup the game app's touch settings"; echo END; exit 3; }; fi
    echo "BACKUP $B"
    restore() {
        for d in "$A"/WowCompanion*; do [ -d "$d" ] && rm -rf "$d"; done
        for d in "$B"/addons/WowCompanion*; do [ -d "$d" ] && cp -R "$d" "$A/"; done
        cat "$B/Config.wtf" > "$C"
        [ -f "$B/container" ] && cat "$B/container" > "$CT"
        for d in "$A"/WowCompanion*; do [ -d "$d" ] && own "$d"; done
        own "$C"
        echo "RESTORED $B"
    }
    mkdir -p "$A"
    ok=1
    for d in "$SRC"/addons/WowCompanion*; do
        [ -d "$d" ] || continue
        cp -R "$d" "$A/" || { ok=0; echo "FAIL install copy $(basename "$d")"; break; }
    done
    if [ $ok = 1 ]; then
        # what the set no longer has goes (inside WowCompanion* folders only)
        cut -c35- "$SRC/manifest.txt" | grep -v '^$' > "$B/keep.txt"
        ( cd "$A" && for d in WowCompanion*; do [ -d "$d" ] && find "$d" -type f ! -name "$STAMP"; done ) | while read -r f; do
            grep -qxF "$f" "$B/keep.txt" || rm -f "$A/$f"
        done
        for d in "$A"/WowCompanion*; do [ -d "$d" ] && own "$d"; done
        grep -v '^set ' "$SRC/manifest.txt" > "$B/want.txt"
        sums > "$B/have.txt"
        if ! cmp -s "$B/have.txt" "$B/want.txt"; then ok=0; echo "FAIL install the copied files do not match the set"; fi
    fi
    if [ $ok = 1 ]; then
        n_req=0; for k in $(req_missing); do setkv "$C" "$k" 1; n_req=$((n_req + 1)); done
        n_def=0; for kv in $DEFAULTS; do has "$C" "${kv%%=*}" || { setkv "$C" "${kv%%=*}" "${kv#*=}"; n_def=$((n_def + 1)); }; done
        own "$C"
        n_touch=0
        if [ -n "$(touch_missing)" ]; then
            sed -e 's/"touchscreenMode" *: *false/"touchscreenMode":true/' -e 's/"shooterMode" *: *true/"shooterMode":false/' "$CT" > "$T/wcsetup_ct" &&
                cat "$T/wcsetup_ct" > "$CT" && n_touch=1 || { ok=0; echo "FAIL install the game app's touch settings"; }
            rm -f "$T/wcsetup_ct"
            [ -n "$(touch_missing)" ] && { ok=0; echo "FAIL install Touchscreen mode did not take ($(touch_missing | tr '\n' ' '))"; }
        fi
        [ -n "$(req_missing)" ] && { ok=0; echo "FAIL install Config.wtf lacks $(req_missing | tr '\n' ' ')"; }
        [ -n "$(def_missing)" ] && { ok=0; echo "FAIL install the default settings did not take"; }
    fi
    if [ $ok = 1 ]; then
        sed -n 's/^set //p' "$SRC/manifest.txt" | head -1 > "$A/WowCompanion/$STAMP"; own "$A/WowCompanion"
        echo "INSTALLED $(grep -c . "$B/want.txt") files, set $(cat "$A/WowCompanion/$STAMP"); required $n_req, touch mode $n_touch, defaults $n_def"
    else
        restore
    fi
    ;;
start)
    if [ "$G" = wf ] || [ "$G" = dl ]; then
        # the app's own launcher entry, asked of Android (not a hard-coded activity name; a WoW Forever update may rename it)
        comp=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tail -1)
        case "$comp" in */*) ;; *) comp="$PKG/app.gamenative.MainActivityAliasDefault" ;; esac
        am start --display 0 -n "$comp" >/dev/null 2>&1
    else
        # GameNative's own pinned shortcut for the WoW custom game (dumpsys shortcut: app_id 178249507, CUSTOM_GAME)
        am start --display 0 -a app.gamenative.LAUNCH_GAME -f 0x14000000 -n app.gamenative/.MainActivity \
            --ei app_id 178249507 --es game_source CUSTOM_GAME >/dev/null 2>&1
    fi
    echo "STARTED $?"
    ;;
*) echo "FAIL usage check|close|install|start" ;;
esac
echo END
