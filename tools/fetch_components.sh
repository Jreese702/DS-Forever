#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_DIR/app/src/main/assets/bundled_components"
RELEASE_URL="${COMPONENTS_URL:-https://github.com/jaredgei/wow-forever-android/releases/download/components-v1}"
FILES=(
    proton-11.0-90624-arm64ec.wcp
    dxvk-2.4.1-wow-aarch64-test.wcp
    turnip-wow-scheduler-test.zip
    turnip-V32-RP6sched.zip
)

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
    if [ -s "$DEST/$f" ]; then
        echo "have $f"
    elif [ -n "${1:-}" ] && [ -s "$1/$f" ]; then
        cp "$1/$f" "$DEST/$f"
        echo "copied $f"
    else
        if [ "$f" = "turnip-V32-RP6sched.zip" ]; then
            curl -fL --retry 3 -o "$DEST/$f.part" "https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip"
        else
            curl -fL --retry 3 -o "$DEST/$f.part" "$RELEASE_URL/$f"
        fi
        mv "$DEST/$f.part" "$DEST/$f"
        echo "downloaded $f"
    fi
done

(cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")

# DXVK 3.1.1 (the Adreno 740 profile's DXVK; tools/dxvk/build.sh builds it): optional, pinned in its own tools/components-dxvk.sha256.
# Without it the launcher uses the bundled 2.4.1 everywhere.
DXVK3=dxvk-3.1.1-wow-aarch64-diag2.wcp
DXVK3_URL="${DXVK3_URL:-https://github.com/RenaeTheRaven/DS-Forever/releases/download/v1.0.40-custom-thor/$DXVK3}"
if [ -s "$DEST/$DXVK3" ]; then
    echo "have $DXVK3"
elif [ -n "${1:-}" ] && [ -s "$1/$DXVK3" ]; then
    cp "$1/$DXVK3" "$DEST/$DXVK3"
    echo "copied $DXVK3"
else
    curl -fL --retry 3 -o "$DEST/$DXVK3.part" "$DXVK3_URL"
    mv "$DEST/$DXVK3.part" "$DEST/$DXVK3"
    echo "downloaded $DXVK3"
fi
(cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components-dxvk.sha256")
