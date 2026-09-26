#!/bin/bash
# Validate the artifacts that will be attached to a release. Run in ldm-dev
# (or any Linux amd64 host with dpkg-deb, rpm, bsdtar, zstd, xvfb).
# Fails fast; also enforces byte-identical payloads across deb/rpm/arch.
set -euo pipefail
PACKAGE_ROOT="$(cd "$(dirname "$0")" && pwd)"
DIST="$PACKAGE_ROOT/dist"
VERSION="${1:?Pass the package version}"
[[ "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]] || exit 2
DEB_VERSION="${VERSION}-1"
APP=linux-device-manager
CHECK_ROOT="$(mktemp -d)"
trap 'rm -rf "$CHECK_ROOT"' EXIT
DEB="$DIST/${APP}_${DEB_VERSION}_amd64.deb"
RPM="$DIST/${APP}-${DEB_VERSION}.x86_64.rpm"
ARCH="$DIST/${APP}-${DEB_VERSION}-x86_64.pkg.tar.zst"
APPIMAGE="$DIST/LinuxDeviceManager-${VERSION}-x86_64.AppImage"
for artifact in "$DEB" "$RPM" "$ARCH"; do test -s "$artifact"; done

# ---- .deb fields ----
[[ "$(dpkg-deb -f "$DEB" Package)" == "$APP" ]]
[[ "$(dpkg-deb -f "$DEB" Version)" == "$DEB_VERSION" ]]
[[ "$(dpkg-deb -f "$DEB" Architecture)" == amd64 ]]
DEPENDS="$(dpkg-deb -f "$DEB" Depends)"
grep -Eq '(^|,)[[:space:]]*libgtk-4-1 \(>= 4\.14\.5\)([[:space:]]*,|[[:space:]]*$)' <<<"$DEPENDS"
for dependency in policykit-1 udev; do
    grep -Eq "(^|,)[[:space:]]*$dependency([[:space:]]*\\([^)]*\\))?[[:space:]]*(,|$)" <<<"$DEPENDS"
done
echo "OK: deb control fields"

# ---- .rpm fields + ownership ----
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{VERSION}-%{RELEASE}' "$RPM")" == "$DEB_VERSION" ]]
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{ARCH}' "$RPM")" == x86_64 ]]
rpm --dbpath "$CHECK_ROOT/rpmdb" -K --nosignature "$RPM"
rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '[%{FILEUSERNAME}:%{FILEGROUPNAME}\n]' "$RPM" > "$CHECK_ROOT/rpm-owners"
if grep -vqx 'root:root' "$CHECK_ROOT/rpm-owners"; then
    echo 'RPM contains non-root payload ownership' >&2; exit 1
fi
echo "OK: rpm fields and ownership"

# ---- deb/arch payloads owned by uid/gid 0 ----
mkdir "$CHECK_ROOT/deb" "$CHECK_ROOT/rpm" "$CHECK_ROOT/arch"
dpkg-deb --fsys-tarfile "$DEB" > "$CHECK_ROOT/deb.tar"
zstd -dq "$ARCH" -o "$CHECK_ROOT/arch.tar"
python3 - "$CHECK_ROOT/deb.tar" "$CHECK_ROOT/arch.tar" <<'PY'
import sys, tarfile
for archive in sys.argv[1:]:
    with tarfile.open(archive) as package:
        assert all(m.uid == 0 and m.gid == 0 for m in package.getmembers()), archive
print('deb/arch payloads are root-owned')
PY

# ---- extract all three ----
tar -xf "$CHECK_ROOT/deb.tar" -C "$CHECK_ROOT/deb"
rpm2cpio "$RPM" 2>/dev/null | bsdtar -xf - -C "$CHECK_ROOT/rpm" || \
    bsdtar -xf "$RPM" -C "$CHECK_ROOT/rpm"
tar -xf "$CHECK_ROOT/arch.tar" -C "$CHECK_ROOT/arch"
grep -qx "pkgver = ${DEB_VERSION}" "$CHECK_ROOT/arch/.PKGINFO"
grep -qx 'arch = x86_64' "$CHECK_ROOT/arch/.PKGINFO"
test -s "$CHECK_ROOT/arch/.MTREE"
test -s "$CHECK_ROOT/arch/.BUILDINFO"
python3 - "$CHECK_ROOT/arch" <<'PYMTREE'
import gzip, hashlib, pathlib, shlex, sys
root = pathlib.Path(sys.argv[1])
checked = 0
for line in gzip.open(root / '.MTREE', 'rt'):
    if not line.startswith('./'):
        continue
    fields = shlex.split(line)
    digest = next((v.split('=', 1)[1] for v in fields[1:] if v.startswith('sha256digest=')), None)
    if digest:
        path = root / fields[0]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, str(path)
        checked += 1
assert checked > 0, 'MTREE contains no payload hashes'
print(f'Validated {checked} Arch MTREE hashes')
PYMTREE

# ---- per-format content + cross-format payload equality ----
for format in deb rpm arch; do
    root="$CHECK_ROOT/$format"
    test -x "$root/usr/bin/linux-device-manager"
    test -x "$root/usr/libexec/ldm-helper"
    test -s "$root/usr/share/polkit-1/actions/org.ldm.policy"
    test -s "$root/usr/share/applications/linux-device-manager.desktop"
    grep -qx 'Exec=linux-device-manager' "$root/usr/share/applications/linux-device-manager.desktop"
    grep -qx 'Icon=linux-device-manager' "$root/usr/share/applications/linux-device-manager.desktop"
    grep -qx 'StartupWMClass=io.github.getldm.linux-device-manager' "$root/usr/share/applications/linux-device-manager.desktop"
    test -s "$root/usr/share/metainfo/io.github.getldm.linux-device-manager.metainfo.xml"
    test -s "$root/usr/share/appdata/linux-device-manager.appdata.xml"
    test -s "$root/usr/share/man/man1/linux-device-manager.1"
    test -s "$root/usr/share/doc/linux-device-manager/copyright"
    test -s "$root/usr/share/icons/hicolor/scalable/apps/linux-device-manager.svg"
    test -s "$root/usr/share/icons/hicolor/64x64/apps/linux-device-manager.png"
    # hicolor/index.theme is owned by hicolor-icon-theme and must NOT be
    # shipped (dpkg/pacman file conflict); the system theme's index declares
    # the standard size dirs our icons live in.
    test ! -e "$root/usr/share/icons/hicolor/index.theme"
    for artifact in ldm-core ldm-gui-gtk gtk glib gdkpixbuf harfbuzz pango jspecify cairo; do
        count=$(find "$root/opt/linux-device-manager/lib" -maxdepth 1 -name "$artifact-*.jar" | wc -l)
        [[ "$count" -eq 1 ]] || { echo "expected exactly one runtime JAR for $artifact in $format" >&2; exit 1; }
    done
    (cd "$root" && find opt usr -type f -print0 | sort -z | xargs -0 sha256sum) > "$CHECK_ROOT/$format.sha256"
done
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/rpm.sha256"
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/arch.sha256"
echo "OK: payloads byte-identical across deb/rpm/arch"

# ---- bundled runtime sanity (module list covers the artifact needs) ----
APP_ROOT="$CHECK_ROOT/deb/opt/linux-device-manager"
"$APP_ROOT/runtime/bin/java" --list-modules | grep -q '^java.base@'
required_modules=$(jdeps --ignore-missing-deps --multi-release 25 \
    --print-module-deps "$APP_ROOT"/lib/ldm-gui-gtk-*.jar "$APP_ROOT"/lib/ldm-core-*.jar \
    | tr ',' ' ')
for module in $required_modules; do
    "$APP_ROOT/runtime/bin/java" --list-modules | cut -d@ -f1 | grep -qx "$module" \
        || { echo "Bundled runtime is missing required module: $module" >&2; exit 1; }
done
echo "OK: bundled runtime covers jdeps module deps ($required_modules)"

# ---- bounded startup of the real launcher (staged tree via LDM_APP_HOME) ----
set +e
LDM_APP_HOME="$APP_ROOT" \
XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data" \
    xvfb-run -a timeout -k 10s 25s "$CHECK_ROOT/deb/usr/bin/linux-device-manager" \
    > "$CHECK_ROOT/launcher.log" 2>&1
launch_status=$?
set -e
cat "$CHECK_ROOT/launcher.log"
[[ "$launch_status" == 124 ]]
if grep -Eq 'NoClassDefFoundError|NoSuchMethodError|Startup failed' "$CHECK_ROOT/launcher.log"; then
    exit 1
fi
echo "OK: launcher ran until timeout (exit 124)"

# ---- AppImage: extraction + jar comparison (when built) ----
if [[ -s "$APPIMAGE" ]]; then
    mkdir "$CHECK_ROOT/appimage"
    (cd "$CHECK_ROOT/appimage" && "$APPIMAGE" --appimage-extract >/dev/null)
    diff -r "$APP_ROOT/lib" "$CHECK_ROOT/appimage/squashfs-root/opt/linux-device-manager/lib"
    test -x "$CHECK_ROOT/appimage/squashfs-root/AppRun"
    echo "OK: AppImage payload matches stage"
else
    echo "SKIP: AppImage not built (SKIP_APPIMAGE=1?)"
fi

# ---- Flatpak bundle presence (sandbox launch is opt-in via FLATPAK_SMOKE=1) ----
FLATPAK_BUNDLE="$DIST/io.github.getldm.linux-device-manager.flatpak"
if [[ -s "$FLATPAK_BUNDLE" ]]; then
    echo "OK: Flatpak bundle present"
    if [[ "${FLATPAK_SMOKE:-0}" == "1" ]]; then
        flatpak install --user -y --bundle "$FLATPAK_BUNDLE"
        xvfb-run -a timeout -k 10s 25s flatpak run io.github.getldm.linux-device-manager \
            > "$CHECK_ROOT/flatpak.log" 2>&1 || [[ "$?" == 124 ]]
        echo "OK: Flatpak sandbox launch ran until timeout"
    fi
else
    echo "SKIP: Flatpak bundle not built (see packaging/flatpak/)"
fi

echo 'All package formats and the bundled launcher passed'
