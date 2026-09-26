#!/bin/bash
# Build the Flatpak bundle from packaging/stage (created by build-packages.sh
# or `make package`). Requires flatpak + flatpak-builder and the GNOME 48
# Platform/Sdk (installed on demand with --user).
#
# In Docker, flatpak-builder needs extended privileges: run the container with
# --privileged and pass --disable-rofiles-fuse (bwrap needs userns + mount
# control). In bare containers without a system bus, alias the session bus:
#   alias DBUS_SYSTEM_BUS_ADDRESS to a `dbus-launch` session bus.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FLATPAK_DIR="$ROOT/packaging/flatpak"
APP_ID=io.github.getldm.linux-device-manager
RUNTIME_VERSION=48

log() { echo "[ldm-flatpak] $*"; }

command -v flatpak >/dev/null || { echo "flatpak is required" >&2; exit 1; }
command -v flatpak-builder >/dev/null || { echo "flatpak-builder is required" >&2; exit 1; }

if [[ ! -d "$ROOT/packaging/stage/opt/linux-device-manager" ]]; then
    echo "packaging/stage is missing — run packaging/build-packages.sh (or make package) first" >&2
    exit 1
fi

# Mount a persistent flatpak user dir in CI to avoid re-downloading runtimes.
log "Ensuring GNOME $RUNTIME_VERSION runtime + SDK..."
flatpak install --user -y flathub org.gnome.Platform//"$RUNTIME_VERSION" \
    org.gnome.Sdk//"$RUNTIME_VERSION" 2>/dev/null || \
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo && \
flatpak install --user -y flathub org.gnome.Platform//"$RUNTIME_VERSION" \
    org.gnome.Sdk//"$RUNTIME_VERSION"

BUILD_DIR="$FLATPAK_DIR/.flatpak-builder"
REPO_DIR="$FLATPAK_DIR/repo"
EXTRA_ARGS=()
if [[ "$(id -u)" -ne 0 && -e /.dockerenv ]]; then
    EXTRA_ARGS+=(--disable-rofiles-fuse)
fi
log "Building $APP_ID..."
flatpak-builder --force-clean --user --install-deps-from=flathub \
    --repo="$REPO_DIR" "${EXTRA_ARGS[@]}" \
    "$BUILD_DIR" "$FLATPAK_DIR/$APP_ID.yml"

BUNDLE="$ROOT/packaging/dist/$APP_ID.flatpak"
log "Bundling $BUNDLE..."
flatpak build-bundle "$REPO_DIR" "$BUNDLE" "$APP_ID" stable
ls -la "$BUNDLE"
log "Done. Install with: flatpak install --user $BUNDLE"
