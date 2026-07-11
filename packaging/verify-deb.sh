#!/bin/sh
# verify-deb.sh — structural and smoke verification of the built .deb.
# Does NOT install the package (no root required for structural checks).
# Optional: if DPKG_INSTALL=1 and running as root, installs + smoke-tests.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEB="$(ls packaging/dist/linux-device-manager_*.deb 2>/dev/null | head -1 || true)"
if [ -z "$DEB" ]; then
    echo "FAIL: no .deb found in packaging/dist/"
    echo "Run packaging/build-deb.sh first."
    exit 1
fi

echo "=== Verifying: $DEB ==="

# 1. Structural integrity
echo "--- dpkg-deb --contents ---"
dpkg-deb --contents "$DEB" >/dev/null
echo "OK: archive integrity"

# 2. Control metadata
echo "--- Control fields ---"
INFO="$(dpkg-deb --info "$DEB")"
echo "$INFO" | grep -q 'Package: linux-device-manager' && echo "OK: package name"
echo "$INFO" | grep -q 'Architecture: amd64' && echo "OK: architecture"
echo "$INFO" | grep -q 'Depends:.*libgtk-4-1' && echo "OK: gtk4 dependency"
echo "$INFO" | grep -q 'Depends:.*policykit-1' && echo "OK: polkit dependency"

# 3. Required files present
echo "--- Required files ---"
CONTENTS="$(dpkg-deb --contents "$DEB")"
check_file() {
    if echo "$CONTENTS" | grep -q "$1"; then
        echo "OK: $1"
    else
        echo "MISSING: $1"
        return 1
    fi
}
check_file 'opt/linux-device-manager/runtime/bin/java'
check_file 'usr/bin/linux-device-manager'
check_file 'usr/libexec/ldm-helper'
check_file 'usr/share/polkit-1/actions/org.ldm.policy'
check_file 'usr/share/applications/linux-device-manager.desktop'
check_file 'usr/share/icons/hicolor/scalable/apps/linux-device-manager.svg'

# 4. Helper is executable in the archive
echo "--- Helper permissions ---"
if echo "$CONTENTS" | grep 'usr/libexec/ldm-helper' | grep -q '^-rwx'; then
    echo "OK: helper is executable"
else
    echo "WARN: helper may not be executable in archive"
fi

# 5. Launcher is executable
echo "--- Launcher permissions ---"
if echo "$CONTENTS" | grep 'usr/bin/linux-device-manager' | grep -q '^-rwx'; then
    echo "OK: launcher is executable"
else
    echo "WARN: launcher may not be executable in archive"
fi

# 6. JAR files present
echo "--- JAR files ---"
JAR_COUNT=$(echo "$CONTENTS" | grep -c '\.jar$')
if [ "$JAR_COUNT" -ge 3 ]; then
    echo "OK: $JAR_COUNT JAR files (ldm-core + ldm-gui-gtk + java-gi deps + jrt-fs)"
else
    echo "WARN: only $JAR_COUNT JAR files found (expected >= 3)"
fi

# 7. postinst/postrm present and executable (live in the control archive, not data)
echo "--- Scripts ---"
CTRL="$(dpkg-deb --ctrl-tarfile "$DEB" | tar -tv 2>/dev/null)"
check_ctrl() {
    if echo "$CTRL" | grep -q "$1"; then
        echo "OK: $1"
    else
        echo "MISSING: $1"
        return 1
    fi
}
check_ctrl 'postinst'
check_ctrl 'postrm'
if echo "$CTRL" | grep 'postinst' | grep -q '^-rwx'; then
    echo "OK: postinst is executable"
else
    echo "WARN: postinst may not be executable"
fi
if echo "$CTRL" | grep 'postrm' | grep -q '^-rwx'; then
    echo "OK: postrm is executable"
else
    echo "WARN: postrm may not be executable"
fi

# 8. No test JARs leaked
echo "--- Test JAR leak check ---"
if echo "$CONTENTS" | grep -qE 'junit|opentest|apiguardian'; then
    echo "FAIL: test JARs found in package"
    exit 1
else
    echo "OK: no test JARs"
fi

# 9. No stray cp.txt
echo "--- Stray file check ---"
if echo "$CONTENTS" | grep -q 'cp\.txt'; then
    echo "FAIL: cp.txt leaked into package"
    exit 1
else
    echo "OK: no cp.txt leak"
fi

echo ""
echo "=== Verification complete ==="

# Optional install + smoke test
if [ "${DPKG_INSTALL:-0}" = "1" ] && [ "$(id -u)" = "0" ]; then
    echo ""
    echo "=== Installing package (DPKG_INSTALL=1) ==="
    dpkg -i "$DEB"
    echo "=== Smoke test (timeout 10s) ==="
    timeout 10 xvfb-run -a /usr/bin/linux-device-manager > /tmp/ldm-smoke.log 2>&1
    EXIT_CODE=$?
    grep -vi "libEGL\|DRI3\|Picked up" /tmp/ldm-smoke.log || true
    rm -f /tmp/ldm-smoke.log
    if [ "$EXIT_CODE" = "124" ]; then
        echo "OK: app launched and ran until timeout (exit 124)"
    else
        echo "WARN: unexpected exit code $EXIT_CODE (124 = timeout = OK)"
    fi
    echo "=== Uninstalling ==="
    dpkg -r linux-device-manager
fi
