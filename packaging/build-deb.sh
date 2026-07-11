#!/bin/sh
# build-deb.sh — assemble a .deb package for Linux Device Manager.
#
# Steps:
#   1. Maven build (JARs)
#   2. jlink trimmed JRE (java.base only — confirmed by jdeps)
#   3. Collect runtime classpath (app JARs + java-gi deps)
#   4. Assemble .deb directory layout
#   5. dpkg-deb --build
#
# Usage: packaging/build-deb.sh
# Output: packaging/dist/linux-device-manager_0.1.0-1_amd64.deb
set -eu

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
export JAVA_HOME

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="0.1.0"
PKG_VERSION="${VERSION}-1"
PKG_NAME="linux-device-manager"
INSTALL_DIR="/opt/${PKG_NAME}"
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

echo "=== 1/5 Maven build ==="
mvn -q install -DskipTests

echo "=== 2/5 jlink trimmed JRE ==="
JRE_DIR="$STAGING${INSTALL_DIR}/runtime"
# NOTE: jlink creates the output dir itself and errors if it pre-exists,
# so we do NOT mkdir -p "$JRE_DIR" here.
"$JAVA_HOME/bin/jlink" \
    --add-modules java.base \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --compress=zip-6 \
    --output "$JRE_DIR"

echo "=== 3/5 Collect runtime classpath ==="
LIB_DIR="$STAGING${INSTALL_DIR}/lib"
mkdir -p "$LIB_DIR"
# copy-dependencies correctly excludes test-scoped JARs (build-classpath does not)
# and copies ldm-core transitively; we only add the gui-gtk JAR ourselves.
mvn -q -pl gui-gtk dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory="$LIB_DIR"
cp gui-gtk/target/ldm-gui-gtk-*.jar "$LIB_DIR/"

echo "=== 4/5 Assemble .deb layout ==="
# DEBIAN control
mkdir -p "$STAGING/DEBIAN"
cp packaging/deb/DEBIAN/control "$STAGING/DEBIAN/"
cp packaging/deb/DEBIAN/postinst "$STAGING/DEBIAN/"
cp packaging/deb/DEBIAN/postrm "$STAGING/DEBIAN/"
chmod 0755 "$STAGING/DEBIAN/postinst" "$STAGING/DEBIAN/postrm"
# Patch control version from PKG_VERSION
sed -i "s/^Version: .*/Version: ${PKG_VERSION}/" "$STAGING/DEBIAN/control"

# Launcher script (/usr/bin/linux-device-manager)
LAUNCHER_DIR="$STAGING/usr/bin"
mkdir -p "$LAUNCHER_DIR"
cat > "$LAUNCHER_DIR/linux-device-manager" << 'LAUNCHER'
#!/bin/sh
exec /opt/linux-device-manager/runtime/bin/java \
    --enable-native-access=ALL-UNNAMED \
    -cp "/opt/linux-device-manager/lib/*" \
    org.ldm.gtk.LinuxDeviceManagerApp "$@"
LAUNCHER
chmod 0755 "$LAUNCHER_DIR/linux-device-manager"

# Helper (/usr/libexec/ldm-helper)
mkdir -p "$STAGING/usr/libexec"
cp helper/ldm-helper "$STAGING/usr/libexec/"
chmod 0755 "$STAGING/usr/libexec/ldm-helper"

# Polkit policy (/usr/share/polkit-1/actions/)
mkdir -p "$STAGING/usr/share/polkit-1/actions"
cp helper/org.ldm.policy "$STAGING/usr/share/polkit-1/actions/"

# .desktop entry (/usr/share/applications/)
mkdir -p "$STAGING/usr/share/applications"
cp packaging/desktop/linux-device-manager.desktop "$STAGING/usr/share/applications/"

# App icon (/usr/share/icons/hicolor/scalable/apps/)
mkdir -p "$STAGING/usr/share/icons/hicolor/scalable/apps"
cp packaging/desktop/linux-device-manager.svg \
   "$STAGING/usr/share/icons/hicolor/scalable/apps/"

echo "=== 5/5 dpkg-deb --build ==="
DIST_DIR="packaging/dist"
mkdir -p "$DIST_DIR"
DEB_FILE="$DIST_DIR/${PKG_NAME}_${PKG_VERSION}_amd64.deb"
dpkg-deb --build --root-owner-group "$STAGING" "$ROOT/$DEB_FILE"

echo ""
echo "Built: $DEB_FILE"
dpkg-deb --info "$DEB_FILE" | head -20
echo ""
echo "Contents (top-level):"
dpkg-deb --contents "$DEB_FILE" | head -30
