#!/bin/sh
# verify-deb.sh — structural and smoke verification of the built .deb.
# Does NOT install the package (no root required for structural checks).
# Optional: if DPKG_INSTALL=1 and running as root, installs + smoke-tests.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fail() { echo "FAIL: $*" >&2; exit 1; }
if [ "$#" -gt 1 ]; then fail "usage: verify-deb.sh [package.deb]"; fi
if [ "$#" -eq 1 ]; then
    DEB=$(readlink -f -- "$1") || fail "invalid package path: $1"
else
    cd "$ROOT"
    # Canonical artifacts land in packaging/dist/; legacy packaging/*.deb still accepted.
    matches=""
    for candidate in packaging/dist/linux-device-manager_*.deb packaging/linux-device-manager_*.deb; do
        [ -f "$candidate" ] || continue
        matches="$matches $candidate"
    done
    # shellcheck disable=SC2086
    set -- $matches
    [ "$#" -eq 1 ] || fail "specify one .deb explicitly (none or multiple found in packaging/dist/ and packaging/)"
    DEB="$1"
fi
[ -f "$DEB" ] || fail "no package found: $DEB"
echo "=== Verifying: $DEB ==="

# Check exact control fields, not strings anywhere in the human-readable archive summary.
[ "$(dpkg-deb -f "$DEB" Package)" = linux-device-manager ] || fail "incorrect package name"
[ "$(dpkg-deb -f "$DEB" Architecture)" = amd64 ] || fail "incorrect package architecture"
DEPENDS=$(dpkg-deb -f "$DEB" Depends)
printf '%s\n' "$DEPENDS" | grep -Eq '(^|,)[[:space:]]*libgtk-4-1 \(>= 4\.14\.5\)([[:space:]]*,|[[:space:]]*$)' \
    || fail "missing tested GTK 4.14.5 minimum for device action dialogs"
for dependency in policykit-1 udev; do
    printf '%s\n' "$DEPENDS" | grep -Eq "(^|,)[[:space:]]*$dependency([[:space:]]*\\([^)]*\\))?[[:space:]]*(,|$)" \
        || fail "missing required dependency: $dependency"
done

echo "OK: required control fields"
CONTENTS=$(dpkg-deb --contents "$DEB")
# Require exact, regular, root-owned files with ordinary read/execute permissions.
check_file() {
    file="$1"
    mode="$2"
    printf '%s\n' "$CONTENTS" | awk -v path="./$file" -v expected="$mode" '
        $NF == path && $1 == expected && $2 == "root/root" {found=1}
        END {exit !found}' || fail "missing or unsafe required file: $file ($mode, root/root)"
}
check_file opt/linux-device-manager/runtime/bin/java -rwxr-xr-x
check_file usr/bin/linux-device-manager -rwxr-xr-x
check_file usr/libexec/ldm-helper -rwxr-xr-x
check_file usr/share/polkit-1/actions/org.ldm.policy -rw-r--r--
check_file usr/share/applications/linux-device-manager.desktop -rw-r--r--
check_file usr/share/icons/hicolor/scalable/apps/linux-device-manager.svg -rw-r--r--
check_file opt/linux-device-manager/runtime/lib/modules -rw-r--r--

for artifact in ldm-core ldm-gui-gtk gtk glib gdkpixbuf harfbuzz pango jspecify cairo; do
    count=$(printf '%s\n' "$CONTENTS" | awk -v prefix="./opt/linux-device-manager/lib/$artifact-" '
        index($NF,prefix)==1 && $NF ~ /\.jar$/ {
            count++
            if ($1 != "-rw-r--r--" || $2 != "root/root") unsafe=1
        }
        END {print unsafe ? -1 : count+0}')
    [ "$count" -eq 1 ] || fail "expected exactly one runtime JAR for $artifact"
done

CTRL=$(dpkg-deb --ctrl-tarfile "$DEB" | tar -tv)
for script in postinst postrm; do
    printf '%s\n' "$CTRL" | awk -v name="./$script" '
        $NF == name && $1 == "-rwxr-xr-x" && $2 == "root/root" {found=1}
        END {exit !found}' || fail "missing or unsafe control script: $script"
done
if printf '%s\n' "$CONTENTS" | grep -qE 'junit|opentest|apiguardian|cp\.txt'; then
    fail "test dependency or stray classpath file in archive"
fi
echo "OK: required files, runtime JARs and permissions"
echo "=== Verification complete ==="

# Optional install + smoke test
if [ "${DPKG_INSTALL:-0}" = "1" ] && [ "$(id -u)" = "0" ]; then
    if dpkg-query -W -f='${db:Status-Status}' linux-device-manager 2>/dev/null | grep -qx installed; then
        echo "FAIL: use a disposable environment without an existing installation"
        exit 1
    fi
    SMOKE_LOG=$(mktemp /tmp/ldm-smoke.XXXXXX)
    cleanup_install() {
        smoke_exit=$?
        trap - EXIT HUP INT TERM
        echo "=== Uninstalling test package ==="
        dpkg -r linux-device-manager || smoke_exit=1
        rm -f "$SMOKE_LOG"
        exit "$smoke_exit"
    }
    trap cleanup_install EXIT
    trap 'exit 130' INT
    trap 'exit 143' HUP TERM
    echo ""
    echo "=== Installing package (DPKG_INSTALL=1) ==="
    dpkg -i "$DEB"
    echo "=== Smoke test (timeout 10s) ==="
    if timeout 10 xvfb-run -a /usr/bin/linux-device-manager > "$SMOKE_LOG" 2>&1; then
        EXIT_CODE=0
    else
        EXIT_CODE=$?
    fi
    grep -vi "libEGL\|DRI3\|Picked up" "$SMOKE_LOG" || true
    if [ "$EXIT_CODE" = "124" ]; then
        echo "OK: app launched and ran until timeout (exit 124)"
    else
        echo "FAIL: unexpected exit code $EXIT_CODE (124 = timeout = OK)"
        exit 1
    fi
fi
