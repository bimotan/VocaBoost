#!/usr/bin/env bash
# Renders the app icon from packaging/icons/vocaboost.svg: the window icons in src/main/resources/icons
# (16 to 512 px), the Windows .ico and the macOS .icns that the packaging scripts pass to jpackage, and a
# 1024 px PNG. Run it after changing the SVG. Needs rsvg-convert (librsvg), ImageMagick's convert and
# png2icns (icnsutils).
#
# Usage: packaging/icons/make-icons.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
resources=$(cd "$here/../.." && pwd)/src/main/resources/icons
svg=$here/vocaboost.svg
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for size in 16 24 32 48 64 128 256 512 1024; do
    rsvg-convert --width "$size" --height "$size" "$svg" --output "$work/$size.png"
done
mkdir -p "$resources"
for size in 16 32 48 64 128 256 512; do
    cp "$work/$size.png" "$resources/vocaboost-$size.png"
done
cp "$work/1024.png" "$here/vocaboost-1024.png"
convert "$work"/{16,24,32,48,64,128,256}.png "$here/vocaboost.ico"
png2icns "$here/vocaboost.icns" "$work"/{16,32,48,128,256,512,1024}.png >/dev/null
echo "Rendered $svg to $resources and $here"
