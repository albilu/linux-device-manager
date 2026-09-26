#!/bin/bash
# Unified package builder for Linux Device Manager (GTK4).
# Builds the app JARs + a trimmed jlink runtime, stages ONE install tree under
# packaging/stage, then assembles .deb / .rpm / .pkg.tar.zst / AppImage from
# that tree.
# Run inside the ldm-dev Docker image (or any Linux with JDK 25, maven,
# dpkg-deb, rpmbuild, bsdtar, zstd). Artifacts land in packaging/dist/.
set -euo pipefail

# Package metadata and the java-gi native bindings target Linux amd64.
if [[ "$(uname -s):$(uname -m)" != "Linux:x86_64" ]]; then
    echo "Packaging requires Linux amd64 (x86_64); use an amd64 Docker environment." >&2
    exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROJECT_VERSION="$(mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout)"
# Strip any -SNAPSHOT-style suffix: the package version is dotted-numeric.
BASE_VERSION="${PROJECT_VERSION%%-*}"
VERSION="${1:-$BASE_VERSION}"
if [[ ! "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]]; then
    echo "Invalid package version: expected numeric dotted version" >&2
    exit 2
fi
if [[ "$VERSION" != "$BASE_VERSION" ]]; then
    echo "Package version $VERSION must match Maven version $PROJECT_VERSION" >&2
    exit 2
fi
RELEASE=1
DEB_VERSION="${VERSION}-${RELEASE}"
APP_ID_DIR="linux-device-manager"
STAGE="$ROOT/packaging/stage"
DIST="$ROOT/packaging/dist"
INSTALL_DIR="/opt/${APP_ID_DIR}"
RUNTIME="$STAGE${INSTALL_DIR}/runtime"
APP_LIB="$STAGE${INSTALL_DIR}/lib"

log() { echo "[ldm-package] $*"; }

log "Building JARs (Maven $PROJECT_VERSION -> package $DEB_VERSION)..."
mvn -q install -DskipTests

log "Assembling application tree under $STAGE..."
rm -rf "$STAGE"
mkdir -p "$DIST" "$APP_LIB" \
    "$STAGE/usr/bin" \
    "$STAGE/usr/libexec" \
    "$STAGE/usr/share/polkit-1/actions" \
    "$STAGE/usr/share/applications" \
    "$STAGE/usr/share/metainfo" \
    "$STAGE/usr/share/appdata" \
    "$STAGE/usr/share/man/man1" \
    "$STAGE/usr/share/doc/${APP_ID_DIR}" \
    "$STAGE/usr/share/licenses/${APP_ID_DIR}" \
    "$STAGE/usr/share/icons/hicolor"

# copy-dependencies correctly excludes test-scoped JARs; we add the gui-gtk
# JAR ourselves (ldm-core comes transitively).
log "Collecting runtime classpath..."
mvn -q -pl gui-gtk dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory="$APP_LIB"
cp gui-gtk/target/ldm-gui-gtk-*.jar "$APP_LIB/"

log "Creating jlink trimmed JRE..."
"$JAVA_HOME/bin/jlink" \
    --add-modules java.base \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --compress=zip-6 \
    --output "$RUNTIME"
# Assert the bundled runtime covers every module the artifact needs.
runtime_modules=$("$RUNTIME/bin/java" --list-modules | cut -d@ -f1)
required_modules=$(jdeps --ignore-missing-deps --multi-release 25 \
    --print-module-deps "$APP_LIB"/ldm-gui-gtk-*.jar "$APP_LIB"/ldm-core-*.jar \
    | tr ',' ' ')
for module in $required_modules; do
    if ! grep -qx "$module" <<<"$runtime_modules"; then
        echo "Bundled runtime is missing required module: $module" >&2
        exit 1
    fi
done

# Launcher: prefer the bundled runtime, fall back to system java.
# LDM_APP_HOME is a test hook so the verifier can smoke-test the staged
# tree without installing; production uses /opt/linux-device-manager.
cat > "$STAGE/usr/bin/linux-device-manager" <<'EOF'
#!/bin/sh
# Linux Device Manager launcher: prefer the bundled runtime, fall back to system java
APP_HOME=${LDM_APP_HOME:-/opt/linux-device-manager}
if [ -x "$APP_HOME/runtime/bin/java" ]; then
    exec "$APP_HOME/runtime/bin/java" \
        --enable-native-access=ALL-UNNAMED \
        -cp "$APP_HOME/lib/*" \
        org.ldm.gtk.LinuxDeviceManagerApp "$@"
else
    exec java \
        --enable-native-access=ALL-UNNAMED \
        -cp "$APP_HOME/lib/*" \
        org.ldm.gtk.LinuxDeviceManagerApp "$@"
fi
EOF
chmod 755 "$STAGE/usr/bin/linux-device-manager"

# Privileged helper + polkit policy.
cp helper/ldm-helper "$STAGE/usr/libexec/"
chmod 755 "$STAGE/usr/libexec/ldm-helper"
cp helper/org.ldm.policy "$STAGE/usr/share/polkit-1/actions/"

# Desktop entry, metainfo, man page, copyright.
cp packaging/resources/linux-device-manager.desktop "$STAGE/usr/share/applications/"
cp packaging/resources/io.github.getldm.linux-device-manager.metainfo.xml "$STAGE/usr/share/metainfo/"
# Legacy appdata path with the same payload: old AppImage/appdir linters only
# recognize *.appdata.xml, while modern stores read metainfo/.
cp packaging/resources/io.github.getldm.linux-device-manager.metainfo.xml \
    "$STAGE/usr/share/appdata/linux-device-manager.appdata.xml"
cp packaging/resources/linux-device-manager.1 "$STAGE/usr/share/man/man1/"
cp packaging/resources/copyright "$STAGE/usr/share/doc/${APP_ID_DIR}/copyright"
cp packaging/resources/copyright "$STAGE/usr/share/licenses/${APP_ID_DIR}/copyright"

# Icons: svg + raster pngs + index.theme (AppStream needs raster icons).
cp -a gui-gtk/src/main/resources/icons/hicolor/. "$STAGE/usr/share/icons/hicolor/"

log "Stage complete:"
du -sh "$STAGE" "$RUNTIME"

# ---- .deb ----
build_deb() {
    log "Building .deb..."
    local debroot="$ROOT/packaging/stage-deb"
    rm -rf "$debroot"
    cp -r "$STAGE" "$debroot"
    # /usr/share/icons/hicolor/index.theme is owned by hicolor-icon-theme;
    # shipping our own copy makes the .deb uninstallable (dpkg conflict).
    # The hicolor theme already declares the standard size dirs, so our
    # icons resolve without it.
    rm -f "$debroot/usr/share/icons/hicolor/index.theme"
    mkdir -p "$debroot/DEBIAN"
    cp packaging/debian/control "$debroot/DEBIAN/control"
    cp packaging/debian/postinst "$debroot/DEBIAN/postinst"
    cp packaging/debian/prerm "$debroot/DEBIAN/prerm"
    cp packaging/debian/postrm "$debroot/DEBIAN/postrm"
    chmod 755 "$debroot/DEBIAN/postinst" "$debroot/DEBIAN/prerm" "$debroot/DEBIAN/postrm"
    sed -i "s/__VERSION__/${DEB_VERSION}/g" "$debroot/DEBIAN/control"
    # Package permissions must not depend on the builder's umask.
    chmod -R u=rwX,go=rX "$debroot"
    dpkg-deb --root-owner-group --build -Zxz "$debroot" \
        "$DIST/${APP_ID_DIR}_${DEB_VERSION}_amd64.deb"
    rm -rf "$debroot"
    log "Built ${APP_ID_DIR}_${DEB_VERSION}_amd64.deb"
}

# ---- .rpm ----
build_rpm() {
    log "Building .rpm..."
    command -v rpmbuild >/dev/null || { log "rpmbuild not found, skipping rpm"; return 0; }
    local rpmtop="$ROOT/packaging/rpmbuild"
    rm -rf "$rpmtop"
    mkdir -p "$rpmtop"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
    sed -e "s/__VERSION__/${VERSION}/g" packaging/rpm/linux-device-manager.spec \
        > "$rpmtop/SPECS/linux-device-manager.spec"
    (cd "$rpmtop" && rpmbuild --define "_topdir $rpmtop" --define "stage $STAGE" \
        --nodeps --nocheck -bb "$rpmtop/SPECS/linux-device-manager.spec")
    find "$rpmtop/RPMS" "$ROOT/rpmbuild/RPMS" -name "*.rpm" -exec mv {} "$DIST/" \; 2>/dev/null || true
    rm -rf "$rpmtop" "$ROOT/rpmbuild"
}

# ---- Arch .pkg.tar.zst (manual assembly — no Arch container for makepkg) ----
build_arch() {
    log "Building .pkg.tar.zst..."
    local archroot="$ROOT/packaging/archbuild"
    rm -rf "$archroot"
    mkdir -p "$archroot/pkg"
    cp -a "$STAGE/." "$archroot/pkg/"
    # Same hicolor-icon-theme ownership conflict as the .deb (pacman file
    # conflict); the installing system's index.theme covers our icons.
    rm -f "$archroot/pkg/usr/share/icons/hicolor/index.theme"
    chmod 755 "$archroot/pkg/usr/bin/linux-device-manager" "$archroot/pkg/usr/libexec/ldm-helper"

    local size
    size=$(du -sk "$archroot/pkg" | cut -f1)
    local builddate
    builddate=$(date +%s)
    cat > "$archroot/pkg/.PKGINFO" <<EOF
pkgname = ${APP_ID_DIR}
pkgbase = ${APP_ID_DIR}
pkgver = ${VERSION}-${RELEASE}
pkgdesc = View and manage hardware devices (bundled Java runtime)
url = https://github.com/getldm/linux-device-manager
arch = x86_64
license = GPL-3.0-or-later
depend = gtk4>=4.14.5
depend = polkit
depend = systemd
optdepend = pciutils: PCI device details via lspci
optdepend = usbutils: USB device details via lsusb
optdepend = kmod: driver details via modinfo
packager = Linux Device Manager <dev@example.com>
size = $((size * 1024))
builddate = ${builddate}
EOF
    cat > "$archroot/pkg/.BUILDINFO" <<EOF
format = 2
pkgname = ${APP_ID_DIR}
pkgbase = ${APP_ID_DIR}
pkgver = ${VERSION}-${RELEASE}
pkgarch = x86_64
pkgbuild_sha256sum = $(sha256sum packaging/arch/PKGBUILD | cut -d' ' -f1)
packager = Linux Device Manager <dev@example.com>
builddate = ${builddate}
builddir = /app
startdir = /app
buildtool = ldm-package-builder
buildtoolver = 1.0.0
buildenv = !distcc
buildenv = color
buildenv = !ccache
buildenv = check
buildenv = !sign
options = strip
options = docs
options = !libtool
options = !staticlibs
options = emptydirs
options = zipman
options = purge
options = !debug
options = lto
EOF
    (cd "$archroot/pkg" && LANG=C bsdtar -czf .MTREE --format=mtree \
        --uid 0 --gid 0 \
        --options='!all,use-set,type,uid,gid,mode,time,size,sha256,link' \
        .PKGINFO .BUILDINFO opt usr)
    (cd "$archroot/pkg" && tar -C "$archroot/pkg" \
        --owner=0 --group=0 --numeric-owner \
        --use-compress-program="zstd -19 -T0" \
        -cf "$DIST/${APP_ID_DIR}-${VERSION}-${RELEASE}-x86_64.pkg.tar.zst" \
        .PKGINFO .BUILDINFO .MTREE opt usr)
    rm -rf "$archroot"
    log "Built ${APP_ID_DIR}-${VERSION}-${RELEASE}-x86_64.pkg.tar.zst"
}

# ---- AppImage (reuses the stage; requires host GTK4 like the .deb) ----
build_appimage() {
    if [[ "${SKIP_APPIMAGE:-0}" == "1" ]]; then
        log "Skipping AppImage (SKIP_APPIMAGE=1)"
        return 0
    fi
    log "Building AppImage..."
    local appdir="$ROOT/packaging/appimage/LinuxDeviceManager.AppDir"
    local tool="$ROOT/packaging/appimage/appimagetool-x86_64.AppImage"
    rm -rf "$appdir"
    mkdir -p "$appdir"
    cp -a "$STAGE/." "$appdir/"
    cp packaging/appimage/AppRun "$appdir/AppRun"
    chmod 755 "$appdir/AppRun"
    # AppImage root entries: desktop file (bare Exec), icons (SVG + PNG for
    # thumbnails), .DirIcon.
    cp packaging/resources/linux-device-manager.desktop "$appdir/"
    cp gui-gtk/src/main/resources/icons/hicolor/scalable/apps/linux-device-manager.svg \
        "$appdir/linux-device-manager.svg"
    cp gui-gtk/src/main/resources/icons/hicolor/512x512/apps/linux-device-manager.png \
        "$appdir/linux-device-manager.png"
    cp gui-gtk/src/main/resources/icons/hicolor/scalable/apps/linux-device-manager.svg \
        "$appdir/.DirIcon"
    if [[ ! -x "$tool" ]]; then
        log "Downloading appimagetool..."
        curl -fL -o "$tool" \
            "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
        chmod +x "$tool"
    fi
    local tool_args=()
    if [[ ! -e /dev/fuse ]]; then
        # No FUSE in containers: run the tool in extract-and-run mode.
        tool_args+=(--appimage-extract-and-run)
    fi
    ARCH=x86_64 "$tool" "${tool_args[@]}" "$appdir" \
        "$DIST/LinuxDeviceManager-${VERSION}-x86_64.AppImage"
    rm -rf "$appdir"
    log "Built LinuxDeviceManager-${VERSION}-x86_64.AppImage"
}

build_deb
build_rpm
build_arch
build_appimage

log "Artifacts:"
ls -la "$DIST/"*.deb "$DIST/"*.rpm "$DIST/"*.pkg.tar.zst "$DIST/"*.AppImage 2>/dev/null || true
log "Done."
