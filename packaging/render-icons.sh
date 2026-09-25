#!/bin/bash
# Regenerate the checked-in PNGs and index.theme from the SVG artwork after
# editing the logo. Requires Inkscape (preferred) or rsvg-convert; ordinary
# builds consume the generated files directly (AppStream compose needs raster
# icons — the 64x64 PNG is mandatory — and Flatpak exports require square art).
set -euo pipefail
ICON_ROOT="$(cd "$(dirname "$0")/.." && pwd)/gui-gtk/src/main/resources/icons/hicolor"
ICON_NAME=linux-device-manager

render() {
    local src="$1" dest="$2" size="$3"
    if command -v inkscape >/dev/null 2>&1; then
        inkscape "$src" --export-type=png \
            --export-width="$size" --export-height="$size" \
            --export-filename="$dest" >/dev/null
    elif command -v rsvg-convert >/dev/null 2>&1; then
        rsvg-convert -w "$size" -h "$size" -o "$dest" "$src"
    else
        echo "Need inkscape or rsvg-convert to render icons" >&2
        exit 1
    fi
}

for size in 16 24 32 48 64 128 256 512; do
    destination="$ICON_ROOT/${size}x${size}/apps"
    mkdir -p "$destination"
    # Per-size SVG overrides win; otherwise fall back to the scalable artwork.
    source_svg="$destination/$ICON_NAME.svg"
    if [[ ! -f "$source_svg" ]]; then
        source_svg="$ICON_ROOT/scalable/apps/$ICON_NAME.svg"
    fi
    render "$source_svg" "$destination/$ICON_NAME.png" "$size"
done

# Minimal index.theme so icon lookups resolve the staged hicolor tree.
cat > "$ICON_ROOT/index.theme" <<'EOF'
[Icon Theme]
Name=Hicolor
Comment=Fallback icon theme
Directories=scalable/apps,16x16/apps,24x24/apps,32x32/apps,48x48/apps,64x64/apps,128x128/apps,256x256/apps,512x512/apps

[scalable/apps]
Size=128
Type=Scalable
MinSize=1
MaxSize=512

[16x16/apps]
Size=16
Context=Applications
Type=Fixed

[24x24/apps]
Size=24
Context=Applications
Type=Fixed

[32x32/apps]
Size=32
Context=Applications
Type=Fixed

[48x48/apps]
Size=48
Context=Applications
Type=Fixed

[64x64/apps]
Size=64
Context=Applications
Type=Fixed

[128x128/apps]
Size=128
Context=Applications
Type=Fixed

[256x256/apps]
Size=256
Context=Applications
Type=Fixed

[512x512/apps]
Size=512
Context=Applications
Type=Fixed
EOF
echo "Icons rendered under $ICON_ROOT"
